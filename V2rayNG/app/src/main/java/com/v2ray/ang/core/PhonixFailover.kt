package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
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
 * The caller supplies the probe, true while the connection works or the probe cannot judge, and the restart.
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

    @Synchronized
    fun start(probe: suspend () -> Boolean, restart: () -> Unit) {
        job?.cancel()
        job = scope.launch {
            delay(FIRST_CHECK_DELAY_MS)
            var fails = 0
            while (isActive) {
                val ok = try {
                    probe()
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "PhonixFailover: probe failed", e)
                    true
                }
                if (ok) {
                    fails = 0
                    synchronized(tried) { tried.clear() }
                } else {
                    fails++
                    if (fails >= FAILS_TO_SWITCH) {
                        if (switchServer()) {
                            restart()
                            return@launch
                        }
                        fails = 0
                    }
                }
                delay(if (ok) CHECK_INTERVAL_MS else RETRY_INTERVAL_MS)
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
            PhonixPolicy.randomServer(exclude = tried.toSet()) ?: run { tried.clear(); null }
        } ?: return false
        LogUtil.i(AppConfig.TAG, "PhonixFailover: switching to $next")
        MmkvManager.setSelectServer(next)
        return true
    }
}
