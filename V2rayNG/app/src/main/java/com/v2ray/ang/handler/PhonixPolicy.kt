package com.v2ray.ang.handler

import com.v2ray.ang.dto.ByName
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.net.URI
import java.util.Locale

/**
 * Phonix: the rules of this build.
 *
 * 1. Only subscription links of [SUB_DOMAIN] (and its subdomains), over https, are accepted.
 * 2. Every such subscription is chained behind the built-in Aether MASQUE/H2 profile: the connection of each
 *    config of the subscription to its server is dialed through the Aether tunnel.
 */
object PhonixPolicy {

    const val SUB_DOMAIN = "gsmpasarguard.ir"
    const val AETHER_NAME = "Phonix-Aether"
    private const val AETHER_LINK = "aether://?protocol=masque&transport=h2#$AETHER_NAME"
    private const val TAG = "PhonixPolicy"

    /** Whether [url] is an https URL whose host is exactly [SUB_DOMAIN] or one of its subdomains. */
    fun isAllowedSubUrl(url: String?): Boolean {
        val value = url?.trim().orEmpty()
        if (value.isEmpty() || value.any { it.isWhitespace() }) return false
        return try {
            val uri = URI(Utils.fixIllegalUrl(value))
            if (!"https".equals(uri.scheme, ignoreCase = true)) return false
            val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
            host == SUB_DOMAIN || host.endsWith(".$SUB_DOMAIN")
        } catch (_: Exception) {
            false
        }
    }

    /**
     * The lines of [text], and of its decoded form, that are allowed subscription links, one per line;
     * null when there is none. Anything else a user pastes or scans is dropped.
     */
    fun allowedSubLines(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val candidates = sequenceOf(text, Utils.decode(text))
            .flatMap { it.lineSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter(::isAllowedSubUrl)
            .distinct()
            .toList()
        return candidates.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** Makes sure the Aether profile exists, once. Returns whether exactly one such profile is there. */
    fun ensureAetherProfile(): Boolean {
        return try {
            when (SettingsManager.findServerViaRemarks(AETHER_NAME) { it.configType == EConfigType.AETHER }) {
                is ByName.One<*> -> true
                ByName.Several -> true
                ByName.None -> AngConfigManager.importTrustedLink(AETHER_LINK)
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "ensureAetherProfile failed", e)
            false
        }
    }

    /** Puts [item], the stored subscription [guid], behind the Aether profile. */
    fun ensureChain(guid: String, item: SubscriptionItem) {
        try {
            if (!ensureAetherProfile()) return
            if (item.prevProfile?.trim() == AETHER_NAME && item.allowInsecureUrl == false) return
            item.prevProfile = AETHER_NAME
            item.allowInsecureUrl = false
            MmkvManager.tryEncodeSubscription(guid, item)
        } catch (e: Exception) {
            LogUtil.e(TAG, "ensureChain failed", e)
        }
    }

    /** Every stored subscription behind the Aether profile; cheap enough to run before each start. */
    fun ensureChainForAllSubs() {
        try {
            val subs = MmkvManager.decodeSubscriptions()
            if (subs.isEmpty()) return
            subs.forEach { ensureChain(it.guid, it.subscription) }
        } catch (e: Exception) {
            LogUtil.e(TAG, "ensureChainForAllSubs failed", e)
        }
    }

    /** After an update: with no usable selected server, selects the first one of the subscription. */
    fun ensureSelection(subId: String) {
        try {
            val selected = MmkvManager.getSelectServer()
            if (!selected.isNullOrEmpty() && MmkvManager.decodeServerConfig(selected) != null) return
            val first = MmkvManager.decodeServerList(subId).firstOrNull { guid ->
                MmkvManager.decodeServerConfig(guid)?.configType?.let { it != EConfigType.CUSTOM } == true
            } ?: return
            MmkvManager.setSelectServer(first)
        } catch (e: Exception) {
            LogUtil.e(TAG, "ensureSelection failed", e)
        }
    }
}
