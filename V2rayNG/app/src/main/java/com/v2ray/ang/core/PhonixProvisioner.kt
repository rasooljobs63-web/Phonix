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
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phonix: sets the Aether profile of this build up by itself once a subscription of the allowed domain is there:
 *
 * 1. the profile exists, MASQUE over HTTP/2, named after the subscription, and is the one selected;
 * 2. the servers of the subscription are tested, and the one with the lowest delay is its exit-node: the core never
 *    dials out on its own, the keys and the scan included;
 * 3. the WARP keys are registered, when they are not there;
 * 4. an endpoint is scanned for, when the profile has none.
 *
 * It runs in the background, one run at a time; a run asked for meanwhile follows it. A live Aether session is never
 * disturbed: the run waits for the next ask.
 */
object PhonixProvisioner {

    private const val TAG = "PhonixProvisioner"
    private const val PING_TIMEOUT_MS = 150_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val again = AtomicBoolean(false)
    private val refetched = AtomicBoolean(false)

    /** Asks for a run, in the background. */
    fun kick() {
        again.set(true)
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (again.getAndSet(false)) {
                    try {
                        setup(AngApplication.application)
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

    private suspend fun setup(context: Context) {
        PhonixPolicy.ensureChainForAllSubs()
        val subs = PhonixPolicy.allowedSubs()
        if (subs.isEmpty()) return

        // 1. The profile, named after the subscription, at once, and the one the start button connects
        if (!PhonixPolicy.ensureAetherProfile()) return
        PhonixPolicy.ensureAetherName(subs.first())
        val (aetherGuid, _) = PhonixPolicy.findAetherProfile() ?: return
        MmkvManager.setSelectServer(aetherGuid)
        notifyUi(context)

        // The servers are the exit-nodes, told by their names
        PhonixPolicy.uniquifyServerNames()
        var servers = PhonixPolicy.exitCandidates()
        // Servers stored before the request name was set may be whole configurations: fetched again, once
        if (servers.isEmpty() && refetched.compareAndSet(false, true)) {
            toast(context, "Phonix: updating the subscription…")
            AngConfigManager.updateConfigViaSubAll()
            PhonixPolicy.uniquifyServerNames()
            servers = PhonixPolicy.exitCandidates()
        }
        if (servers.isEmpty()) {
            val stored = subs.flatMap { sub -> MmkvManager.decodeServerList(sub.guid) }
                .mapNotNull { MmkvManager.decodeServerConfig(it) }
            toast(
                context,
                if (stored.isEmpty()) "Phonix: the subscription has no servers yet"
                else "Phonix: ${stored.size} servers in the subscription, none can be an exit (${stored.map { it.configType.name }.distinct().joinToString()})"
            )
            return
        }

        // A live Aether session is not disturbed: its core, its keys and its profile stay as they are.
        if (AetherEditorRepository(context).activeSession() != null) return

        // 2. The server with the lowest delay is the exit-node; the core never dials out on its own
        val untested = servers.filter { PhonixPolicy.delayOf(it.first) == 0L }.map { it.first }
        if (untested.isNotEmpty()) {
            toast(context, "Phonix: testing the servers…")
            pingAll(context, untested)
        }
        val node = PhonixPolicy.bestExitNode() ?: return
        update(aetherGuid) { it.aetherExitNode = node }

        // 3. The WARP keys, and 4. the endpoint, both through the exit-node
        val profile = MmkvManager.decodeServerConfig(aetherGuid) ?: return
        val keysReady = ensureKeys(context, profile, node)
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

        toast(
            context,
            if (keysReady && endpointReady) "Phonix: ready, exit server: $node"
            else "Phonix: exit server $node set; ${if (!keysReady) "WARP keys" else "endpoint"} missing, it is retried at the next start"
        )
        notifyUi(context)
    }

    private suspend fun pingAll(context: Context, guids: List<String>) {
        val done = CompletableDeferred<Unit>()
        val worker = RealPingWorkerService(context, guids) { event ->
            when (event) {
                is RealPingEvent.Result -> MmkvManager.encodeServerTestDelayMillis(event.guid, event.delayMillis)
                is RealPingEvent.Finish -> done.complete(Unit)
                is RealPingEvent.Progress -> Unit
            }
        }
        worker.start()
        if (withTimeoutOrNull(PING_TIMEOUT_MS) { done.await() } == null) worker.cancel()
    }

    /** Registers the WARP keys [profile] needs when they are missing, dialing out through [node] and never on its own. */
    private suspend fun ensureKeys(context: Context, profile: ProfileItem, node: String): Boolean {
        val needed = AetherIdentityManager.filesNeededBy(AetherCoreManager.buildArguments(profile, 0, scan = true))
        if (AetherIdentityManager.missing(context, needed).isEmpty()) return true
        toast(context, "Phonix: getting the WARP keys…")
        val settings = AetherKeysSettings(kind = AetherKeyKind.MASQUE, exitNode = node)
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
