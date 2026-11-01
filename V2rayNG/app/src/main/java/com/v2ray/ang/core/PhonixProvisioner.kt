package com.v2ray.ang.core

import android.content.Context
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
    private const val START_PING_TIMEOUT_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val again = AtomicBoolean(false)
    private val refetched = AtomicBoolean(false)
    private val busy = Mutex()

    /** Asks for a run, in the background. */
    fun kick() {
        again.set(true)
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (again.getAndSet(false)) {
                    try {
                        busy.withLock { setup(AngApplication.application) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LogUtil.e(AppConfig.TAG, "$TAG: setup failed", e)
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
            testAndSelect(context, servers.map { it.first }, START_PING_TIMEOUT_MS)
        } finally {
            busy.unlock()
        }
    }

    private suspend fun setup(context: Context) {
        val subs = PhonixPolicy.allowedSubs()
        if (subs.isEmpty()) return

        // 1. The profile, named after the subscription, dialing out on its own, and the servers behind it
        if (!PhonixPolicy.ensureAetherProfile()) return
        PhonixPolicy.ensureAetherName(subs.first())
        val (aetherGuid, _) = PhonixPolicy.findAetherProfile() ?: return
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
            return
        }

        // A live Aether session is not disturbed: its core, its keys and its profile stay as they are.
        if (AetherEditorRepository(context).activeSession() != null) return

        // 2. The WARP keys, and 3. the endpoint
        val profile = MmkvManager.decodeServerConfig(aetherGuid) ?: return
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
            return
        }

        // 4. The servers, tested through the tunnel
        toast(context, "Phonix: testing the servers…")
        testAndSelect(context, servers.map { it.first }, PING_TIMEOUT_MS)
        toast(context, "Phonix: ready")
        notifyUi(context)
    }

    /** Tests the servers [guids] from scratch, within [timeoutMs], and selects the one with the lowest delay. */
    private suspend fun testAndSelect(context: Context, guids: List<String>, timeoutMs: Long) {
        // A delay measured before says nothing of the connection now
        guids.forEach { MmkvManager.encodeServerTestDelayMillis(it, 0L) }
        pingAll(context, guids, timeoutMs)
        PhonixPolicy.bestServer()?.let { MmkvManager.setSelectServer(it) }
    }

    private suspend fun pingAll(context: Context, guids: List<String>, timeoutMs: Long) {
        val done = CompletableDeferred<Unit>()
        val worker = RealPingWorkerService(context, guids) { event ->
            when (event) {
                is RealPingEvent.Result -> MmkvManager.encodeServerTestDelayMillis(event.guid, event.delayMillis)
                is RealPingEvent.Finish -> done.complete(Unit)
                is RealPingEvent.Progress -> Unit
            }
        }
        worker.start()
        if (withTimeoutOrNull(timeoutMs) { done.await() } == null) worker.cancel()
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
