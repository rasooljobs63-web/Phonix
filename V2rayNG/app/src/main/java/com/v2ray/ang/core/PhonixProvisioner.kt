package com.v2ray.ang.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherKeyKind
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.PhonixPolicy
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.service.RealPingWorkerService
import com.v2ray.ang.ui.server.AetherEditorRepository
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phonix: sets the Aether profile of this build up by itself once a subscription of the allowed domain is there:
 *
 * 1. the profile exists, MASQUE over HTTP/2 and dialing out on its own, named after the subscription, and every server
 *    of the subscription is chained behind it;
 * 2. the WARP keys are registered, when they are not there;
 * 3. an endpoint is scanned for, when the profile has none;
 * 4. the servers are tested through the tunnel, and the one with the lowest delay is selected.
 *
 * It runs in the background, one run at a time; a run asked for meanwhile follows it. A live Aether session is never
 * disturbed: the run waits for the next ask. [prepareStart] repeats the last step before each connection.
 */
object PhonixProvisioner {

    private const val TAG = "PhonixProvisioner"
    private const val PING_TIMEOUT_MS = 150_000L
    private const val START_PING_TIMEOUT_MS = 20_000L

    // Before a connection the servers are tested again only when the last test is older than this, and the test
    // stops as soon as one server has answered after EARLY_EXIT_AFTER_MS: the connection must not wait for the slow ones
    private const val FRESH_TEST_MS = 5 * 60_000L
    private const val EARLY_EXIT_AFTER_MS = 6_000L
    private const val PREF_LAST_TEST = "phonix_last_test"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val again = AtomicBoolean(false)
    private val refetched = AtomicBoolean(false)
    private val busy = Mutex()

    /** Where the first setup of a key is: the screen tells the person to wait while it is [PREPARING]. */
    enum class Setup { IDLE, PREPARING, READY, FAILED }

    private val setupFlow = MutableStateFlow(Setup.IDLE)
    val setupState: StateFlow<Setup> = setupFlow.asStateFlow()

    /** Forgets the state of the setup, as after a log out. */
    fun reset() {
        setupFlow.value = Setup.IDLE
        PhonixPolicy.setManualServerName(null)
    }

    /** Whether the profile, its endpoint and the servers are not all there yet: the setup is still the first one. */
    private fun needsSetup(): Boolean {
        if (PhonixPolicy.allowedSubs().isEmpty()) return false
        val aether = PhonixPolicy.findAetherProfile()?.second ?: return true
        if (aether.server.isNullOrBlank() || aether.serverPort.isNullOrBlank()) return true
        return PhonixPolicy.serverCandidates().isEmpty()
    }

    /** Asks for a run, in the background. */
    fun kick() {
        if (needsSetup()) setupFlow.value = Setup.PREPARING
        again.set(true)
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (again.getAndSet(false)) {
                    val first = needsSetup()
                    try {
                        val ready = busy.withLock { setup(AngApplication.application) }
                        if (first) setupFlow.value = if (ready) Setup.READY else Setup.FAILED
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LogUtil.e(AppConfig.TAG, "$TAG: setup failed", e)
                        if (first) setupFlow.value = Setup.FAILED
                    }
                }
            } finally {
                running.set(false)
            }
            // An ask that came between the last check and the flag going down
            if (again.get()) kick()
        }
    }

    /**
     * Before a connection: tests the servers through the tunnel, for a minute at most, and selects the one with the
     * lowest delay. Skipped while the setup runs, and when the profile has no endpoint to start with.
     */
    suspend fun prepareStart(context: Context) {
        PhonixPolicy.ensureChainForAllSubs()
        if (!busy.tryLock()) return
        try {
            val aether = PhonixPolicy.findAetherProfile()?.second
            val servers = PhonixPolicy.serverCandidates()
            if (aether == null || aether.server.isNullOrBlank() || servers.isEmpty()) {
                PhonixPolicy.ensureServerSelected()
                return
            }
            val guids = servers.map { it.first }
            val fresh = System.currentTimeMillis() - lastTestTime() < FRESH_TEST_MS && guids.any { PhonixPolicy.delayOf(it) > 0L }
            if (fresh) PhonixPolicy.applySelection()
            else testAndSelect(context, guids, START_PING_TIMEOUT_MS, earlyExit = true)
        } finally {
            busy.unlock()
        }
    }

    /** The outcomes of [testServers]. */
    enum class TestResult { DONE, BUSY, NOT_READY }

    /**
     * Tests the servers through the tunnel, for the list on the screen: the delay of each, and the lowest one selected
     * unless a server was chosen by hand. It does not run while a setup runs, nor while a connection is live.
     */
    suspend fun testServers(context: Context): TestResult {
        if (!busy.tryLock()) return TestResult.BUSY
        try {
            val aether = PhonixPolicy.findAetherProfile()?.second
            val servers = PhonixPolicy.serverCandidates()
            if (aether == null || aether.server.isNullOrBlank() || servers.isEmpty()) return TestResult.NOT_READY
            testAndSelect(context, servers.map { it.first }, PING_TIMEOUT_MS)
            return TestResult.DONE
        } finally {
            busy.unlock()
        }
    }

    /** The outcomes of [rescanEndpoint]. */
    enum class RescanResult { FOUND, NOT_FOUND, BUSY, SESSION_ACTIVE, NO_PROFILE, KEYS_MISSING }

    /**
     * "Get new IP": scans for a new endpoint of the MASQUE profile and stores it, whatever endpoint the profile has now.
     * It does not run while a setup runs, nor while an Aether session is live: a scan must not disturb its tunnel.
     */
    suspend fun rescanEndpoint(context: Context): RescanResult {
        if (!busy.tryLock()) return RescanResult.BUSY
        try {
            if (!PhonixPolicy.ensureAetherProfile()) return RescanResult.NO_PROFILE
            val (guid, _) = PhonixPolicy.findAetherProfile() ?: return RescanResult.NO_PROFILE
            if (AetherEditorRepository(context).activeSession() != null) return RescanResult.SESSION_ACTIVE
            val profile = MmkvManager.decodeServerConfig(guid) ?: return RescanResult.NO_PROFILE
            if (!ensureKeys(context, profile)) return RescanResult.KEYS_MISSING
            val result = AetherScanner.scan(context, profile) ?: return RescanResult.NOT_FOUND
            update(guid) {
                it.server = result.endpoint.host
                it.serverPort = result.endpoint.port.toString()
            }
            notifyUi(context)
            return RescanResult.FOUND
        } finally {
            busy.unlock()
        }
    }

    private suspend fun setup(context: Context): Boolean {
        val subs = PhonixPolicy.allowedSubs()
        if (subs.isEmpty()) return false

        // 1. The profile, named after the subscription, dialing out on its own, and the servers behind it
        if (!PhonixPolicy.ensureAetherProfile()) return false
        PhonixPolicy.ensureAetherName(subs.first())
        val (aetherGuid, _) = PhonixPolicy.findAetherProfile() ?: return false
        update(aetherGuid) { it.aetherExitNode = null }
        PhonixPolicy.uniquifyServerNames()
        PhonixPolicy.ensureChainForAllSubs()
        PhonixPolicy.ensureServerSelected()
        notifyUi(context)

        var servers = PhonixPolicy.serverCandidates()
        // Servers stored before the request name was set may be whole configurations: fetched again, once
        if (servers.isEmpty() && refetched.compareAndSet(false, true)) {
            toast(context, "Phonix: updating the subscription…")
            AngConfigManager.updateConfigViaSubAll()
            PhonixPolicy.uniquifyServerNames()
            PhonixPolicy.ensureChainForAllSubs()
            PhonixPolicy.ensureServerSelected()
            servers = PhonixPolicy.serverCandidates()
        }
        if (servers.isEmpty()) {
            val stored = subs.flatMap { sub -> MmkvManager.decodeServerList(sub.guid) }
                .mapNotNull { MmkvManager.decodeServerConfig(it) }
            toast(
                context,
                if (stored.isEmpty()) "Phonix: the subscription has no servers yet"
                else "Phonix: ${stored.size} servers in the subscription, none can be used (${stored.map { it.configType.name }.distinct().joinToString()})"
            )
            return false
        }

        // A live Aether session is not disturbed: its core, its keys and its profile stay as they are.
        if (AetherEditorRepository(context).activeSession() != null) return true

        // 2. The WARP keys, and 3. the endpoint
        val profile = MmkvManager.decodeServerConfig(aetherGuid) ?: return false
        val keysReady = ensureKeys(context, profile)
        var endpointReady = !profile.server.isNullOrBlank() && !profile.serverPort.isNullOrBlank()
        if (!endpointReady && keysReady) {
            toast(context, "Phonix: scanning for an endpoint…")
            AetherScanner.scan(context, profile)?.let { result ->
                update(aetherGuid) {
                    it.server = result.endpoint.host
                    it.serverPort = result.endpoint.port.toString()
                }
                endpointReady = true
            }
        }
        if (!keysReady || !endpointReady) {
            toast(context, "Phonix: ${if (!keysReady) "WARP keys" else "endpoint"} missing, it is retried at the next start")
            notifyUi(context)
            return false
        }

        // 4. The servers, tested through the tunnel
        toast(context, "Phonix: testing the servers…")
        testAndSelect(context, servers.map { it.first }, PING_TIMEOUT_MS)
        toast(context, "Phonix: ready")
        notifyUi(context)
        return true
    }

    /** Tests the servers [guids] from scratch, within [timeoutMs], and selects the one with the lowest delay. */
    private suspend fun testAndSelect(context: Context, guids: List<String>, timeoutMs: Long, earlyExit: Boolean = false) {
        // A delay measured before says nothing of the connection now
        guids.forEach { MmkvManager.encodeServerTestDelayMillis(it, 0L) }
        pingAll(context, guids, timeoutMs, earlyExit)
        MmkvManager.encodeSettings(PREF_LAST_TEST, System.currentTimeMillis().toString())
        PhonixPolicy.applySelection()
    }

    private fun lastTestTime(): Long = MmkvManager.decodeSettingsString(PREF_LAST_TEST)?.toLongOrNull() ?: 0L

    private suspend fun pingAll(context: Context, guids: List<String>, timeoutMs: Long, earlyExit: Boolean = false) {
        val done = CompletableDeferred<Unit>()
        val worker = RealPingWorkerService(context, guids) { event ->
            when (event) {
                is RealPingEvent.Result -> MmkvManager.encodeServerTestDelayMillis(event.guid, event.delayMillis)
                is RealPingEvent.Finish -> done.complete(Unit)
                is RealPingEvent.Progress -> Unit
            }
        }
        worker.start()
        try {
            val began = SystemClock.elapsedRealtime()
            while (!done.isCompleted) {
                val elapsed = SystemClock.elapsedRealtime() - began
                if (elapsed >= timeoutMs) break
                if (earlyExit && elapsed >= EARLY_EXIT_AFTER_MS && guids.any { PhonixPolicy.delayOf(it) > 0L }) break
                kotlinx.coroutines.delay(250L)
            }
        } finally {
            // Also when the caller is cancelled: a test left running would go on in the background
            if (!done.isCompleted) worker.cancel()
        }
    }

    /** Registers the WARP keys [profile] needs when they are missing. */
    private suspend fun ensureKeys(context: Context, profile: ProfileItem): Boolean {
        val needed = AetherIdentityManager.filesNeededBy(AetherCoreManager.buildArguments(profile, 0, scan = true))
        if (AetherIdentityManager.missing(context, needed).isEmpty()) return true
        toast(context, "Phonix: getting the WARP keys…")
        val settings = AetherKeysSettings(kind = AetherKeyKind.MASQUE)
        val arguments = AetherKeys.runArguments(settings)
        val kind = AetherKeys.kindOf(arguments) ?: return false
        AetherIdentityManager.renew(context, kind, arguments, settings.exit) { }
        return AetherIdentityManager.missing(context, needed).isEmpty()
    }

    /** Changes the stored profile [guid] as it is now. */
    private fun update(guid: String, change: (ProfileItem) -> Unit) {
        val profile = MmkvManager.decodeServerConfig(guid) ?: return
        change(profile)
        try {
            MmkvManager.encodeServerConfig(guid, profile)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "$TAG: the profile was not stored", e)
        }
    }

    private fun notifyUi(context: Context) {
        MmkvManager.decodeSubscriptions().forEach {
            MessageHelper.sendMsg2UI(context, AppConfig.MSG_SERVERS_CHANGED, it.guid)
        }
    }

    private fun toast(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }
}
