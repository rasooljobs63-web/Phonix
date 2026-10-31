package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Phonix: watches the running connection and, when the selected config of a subscription stops working,
 * switches to the next config of the same subscription that works (best known delay first).
 *
 * The caller supplies the probe, true while the connection works or the probe cannot judge, and the switch.
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
    fun start(probe: suspend () -> Boolean, switchTo: (String) -> Unit) {
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
                        val next = pickNext()
                        if (next != null) {
                            LogUtil.i(AppConfig.TAG, "PhonixFailover: switching to $next")
                            switchTo(next)
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

    /** The next config of the selected one's subscription, or null when there is none to try. */
    private fun pickNext(): String? {
        val current = MmkvManager.getSelectServer() ?: return null
        val currentProfile = MmkvManager.decodeServerConfig(current) ?: return null
        if (currentProfile.subscriptionId.isEmpty()) return null
        val next = synchronized(tried) {
            tried.add(current)
            MmkvManager.decodeServerList(currentProfile.subscriptionId)
                .asSequence()
                .filter { it !in tried }
                .mapNotNull { guid -> MmkvManager.decodeServerConfig(guid)?.let { guid to it } }
                .filter { (_, profile) -> !profile.configType.isComplexType() && LauncherManager.hasUsableServer(profile) }
                .sortedBy { (guid, _) -> rank(MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L) }
                .map { it.first }
                .firstOrNull()
                ?: run { tried.clear(); null }
        }
        return next
    }

    /** Smaller first: measured delays, then untested, then the ones whose test failed. */
    private fun rank(delayMillis: Long): Long = when {
        delayMillis > 0L -> delayMillis
        delayMillis == 0L -> 100_000L
        else -> 1_000_000L
    }
}
