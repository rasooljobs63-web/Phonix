package com.v2ray.ang.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.StringRes
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherFingerprint
import com.v2ray.ang.enums.AetherKeyKind
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.PhonixPolicy
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.ui.server.AetherEditorRepository
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * 4. a server of the subscription is selected, at random unless the person chose one.
 *
 * It runs in the background, one run at a time; a run asked for meanwhile follows it. A live Aether session is never
 * disturbed: the run waits for the next ask. [prepareStart] repeats the last step before each connection.
 */
object PhonixProvisioner {

    private const val TAG = "PhonixProvisioner"

    // How long a scan for an endpoint may go on with one way into the tunnel: the first gets the time a scan needs,
    // the others only as long as it takes to see that they do not connect
    private const val FIRST_SCAN_MS = 3 * 60_000L
    private const val NEXT_SCAN_MS = 90_000L

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
        PhonixPolicy.applyVariant(0)
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
     * Before a connection: the server the person chose by hand is selected, else one at random, so that each
     * connection goes out through another server. Nothing is tested, so nothing waits.
     */
    suspend fun prepareStart(context: Context) {
        PhonixPolicy.ensureChainForAllSubs()
        PhonixPolicy.applySelection()
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
        // Dialing out on its own, by the way into the tunnel that worked last, also for a profile an earlier build made
        update(aetherGuid) { it.aetherExitNode = null }
        PhonixPolicy.applyVariant(PhonixPolicy.variantIndex())
        PhonixPolicy.uniquifyServerNames()
        PhonixPolicy.ensureChainForAllSubs()
        PhonixPolicy.ensureServerSelected()
        notifyUi(context)

        var servers = PhonixPolicy.serverCandidates()
        // Servers stored before the request name was set may be whole configurations: fetched again, once
        if (servers.isEmpty() && refetched.compareAndSet(false, true)) {
            toast(context, R.string.ui_phonix_updating)
            AngConfigManager.updateConfigViaSubAll()
            PhonixPolicy.uniquifyServerNames()
            PhonixPolicy.ensureChainForAllSubs()
            PhonixPolicy.ensureServerSelected()
            servers = PhonixPolicy.serverCandidates()
        }
        if (servers.isEmpty()) {
            val stored = subs.flatMap { sub -> MmkvManager.decodeServerList(sub.guid) }
                .mapNotNull { MmkvManager.decodeServerConfig(it) }
            if (stored.isEmpty()) toast(context, R.string.ui_phonix_no_servers)
            else toast(context, R.string.ui_phonix_servers_unusable, stored.size, stored.map { it.configType.name }.distinct().joinToString())
            return false
        }

        // A live Aether session is not disturbed: its core, its keys and its profile stay as they are.
        if (AetherEditorRepository(context).activeSession() != null) return true

        // 2. The WARP keys, and 3. the endpoint. When one way into the tunnel gets neither, the next is tried: the
        // fingerprints of Chrome, Firefox and Chrome 120 in turn, then the same with each final mask
        var keysReady = false
        var endpointReady = false
        var index = PhonixPolicy.variantIndex()
        for (attempt in PhonixPolicy.variants.indices) {
            PhonixPolicy.applyVariant(index)
            val profile = MmkvManager.decodeServerConfig(aetherGuid) ?: return false
            keysReady = ensureKeys(context, profile)
            endpointReady = !profile.server.isNullOrBlank() && !profile.serverPort.isNullOrBlank()
            if (keysReady && !endpointReady) {
                toast(context, R.string.ui_phonix_scanning)
                val result = withTimeoutOrNull(if (attempt == 0) FIRST_SCAN_MS else NEXT_SCAN_MS) {
                    AetherScanner.scan(context, profile)
                }
                if (result != null) {
                    update(aetherGuid) {
                        it.server = result.endpoint.host
                        it.serverPort = result.endpoint.port.toString()
                    }
                    endpointReady = true
                }
            }
            if (keysReady && endpointReady) break
            index = (index + 1) % PhonixPolicy.variants.size
            toast(context, R.string.ui_phonix_variant_next)
        }
        if (!keysReady || !endpointReady) {
            // The next start goes on from the next way
            PhonixPolicy.applyVariant(index)
            toast(context, if (!keysReady) R.string.ui_phonix_missing_keys else R.string.ui_phonix_missing_endpoint)
            notifyUi(context)
            return false
        }

        // 4. A server is selected, the one chosen by hand or one at random
        PhonixPolicy.applySelection()
        toast(context, R.string.ui_phonix_ready)
        notifyUi(context)
        return true
    }

    /** Registers the WARP keys [profile] needs when they are missing. */
    private suspend fun ensureKeys(context: Context, profile: ProfileItem): Boolean {
        val needed = AetherIdentityManager.filesNeededBy(AetherCoreManager.buildArguments(profile, 0, scan = true))
        if (AetherIdentityManager.missing(context, needed).isEmpty()) return true
        toast(context, R.string.ui_phonix_getting_keys)
        val settings = AetherKeysSettings(
            kind = AetherKeyKind.MASQUE,
            fingerprint = AetherFingerprint.fromString(profile.aetherFingerprint),
            finalMask = profile.finalMask.orEmpty(),
        )
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

    /** A message in the language of the app, shown from any thread. */
    private fun toast(context: Context, @StringRes id: Int, vararg args: Any) {
        val text = AppLocaleManager.localizedContext(context).getString(id, *args)
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }
}
