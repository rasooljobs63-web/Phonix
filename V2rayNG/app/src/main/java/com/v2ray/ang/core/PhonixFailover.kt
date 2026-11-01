package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.enums.EConfigType
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
 * Phonix: watches the running connection and, when the exit server of the Aether profile stops working, gives the
 * profile the next server of the subscription as its exit-node (best known delay first) and has the caller restart.
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
                        if (switchExitNode()) {
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
     * Gives the selected Aether profile of this build the next server of its subscriptions as its exit-node.
     * False when the selected profile is not that one, or there is no server left to try.
     */
    private fun switchExitNode(): Boolean {
        val guid = MmkvManager.getSelectServer() ?: return false
        val profile = MmkvManager.decodeServerConfig(guid) ?: return false
        if (profile.configType != EConfigType.AETHER) return false
        val current = profile.aetherExitNode?.trim().orEmpty()
        val next = synchronized(tried) {
            if (current.isNotEmpty()) tried.add(current)
            PhonixPolicy.bestExitNode(exclude = tried.toSet()) ?: run { tried.clear(); null }
        } ?: return false
        LogUtil.i(AppConfig.TAG, "PhonixFailover: the exit-node becomes $next")
        profile.aetherExitNode = next
        return try {
            MmkvManager.encodeServerConfig(guid, profile)
            true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "PhonixFailover: the profile was not stored", e)
            false
        }
    }
}
