package com.v2ray.ang.core

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.PhonixPolicy
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Phonix: watches the running connection and, when the server of the subscription it goes out through stops working,
 * selects another one, at random, and has the caller restart.
 *
 * When the Aether core itself does not come up, or every server has failed, the way into the tunnel is changed instead:
 * the next TLS fingerprint, then the next final mask, see [PhonixPolicy.variants].
 *
 * The caller supplies the probe, which says what it makes of the connection, and the restart.
 */
internal object PhonixFailover {

    private const val FIRST_CHECK_DELAY_MS = 45_000L
    private const val CHECK_INTERVAL_MS = 40_000L
    private const val RETRY_INTERVAL_MS = 10_000L
    private const val FAILS_TO_SWITCH = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** Configs already tried since the connection last worked. */
    private val tried = linkedSetOf<String>()

    /** What a probe makes of the connection. */
    enum class Probe {
        /** It works, or the probe cannot tell. */
        OK,

        /** The tunnel is up but the connection through it does not work: the server of the subscription is at fault. */
        SERVER_FAIL,

        /** The Aether core does not come up: the way into the tunnel is at fault, not the server. */
        TUNNEL_DOWN,
    }

    @Synchronized
    fun start(probe: suspend () -> Probe, restart: () -> Unit) {
        job?.cancel()
        job = scope.launch {
            delay(FIRST_CHECK_DELAY_MS)
            var serverFails = 0
            var tunnelFails = 0
            while (isActive) {
                val result = try {
                    probe()
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "PhonixFailover: probe failed", e)
                    Probe.OK
                }
                when (result) {
                    Probe.OK -> {
                        serverFails = 0
                        tunnelFails = 0
                        synchronized(tried) { tried.clear() }
                    }

                    Probe.SERVER_FAIL -> {
                        tunnelFails = 0
                        serverFails++
                        if (serverFails >= FAILS_TO_SWITCH) {
                            if (switchServer()) {
                                restart()
                                return@launch
                            }
                            serverFails = 0
                        }
                    }

                    Probe.TUNNEL_DOWN -> {
                        serverFails = 0
                        tunnelFails++
                        if (tunnelFails >= FAILS_TO_SWITCH) {
                            if (advanceVariant()) {
                                restart()
                                return@launch
                            }
                            tunnelFails = 0
                        }
                    }
                }
                delay(if (result == Probe.OK) CHECK_INTERVAL_MS else RETRY_INTERVAL_MS)
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Selects the next server of the subscription, one at random among those not tried since the
     * connection last worked. False when the selected one is none of the subscription's, or none is left to try.
     */
    private fun switchServer(): Boolean {
        // A server chosen by hand stays, whatever its delay
        if (PhonixPolicy.manualServerGuid() != null) return false
        val current = MmkvManager.getSelectServer() ?: return false
        if (PhonixPolicy.serverCandidates().none { it.first == current }) return false
        val next = synchronized(tried) {
            tried.add(current)
            PhonixPolicy.randomServer(exclude = tried.toSet())
        }
        if (next == null) {
            // Every server failed: the way into the tunnel is the likelier fault, so the next way is tried
            synchronized(tried) { tried.clear() }
            return advanceVariant()
        }
        LogUtil.i(AppConfig.TAG, "PhonixFailover: switching to $next")
        MmkvManager.setSelectServer(next)
        return true
    }

    /** Moves the Aether profile to the next way of reaching the tunnel, and says so. False when it has no profile. */
    private fun advanceVariant(): Boolean {
        val index = PhonixPolicy.advanceVariant() ?: return false
        LogUtil.i(AppConfig.TAG, "PhonixFailover: the way into the tunnel becomes number $index")
        val context = AngApplication.application
        val text = AppLocaleManager.localizedContext(context).getString(R.string.ui_phonix_variant_next)
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
        return true
    }
}
