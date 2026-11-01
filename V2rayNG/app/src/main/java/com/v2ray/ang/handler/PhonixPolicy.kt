package com.v2ray.ang.handler

import com.v2ray.ang.core.AetherExit
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionCache
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
 * 2. A built-in Aether MASQUE/H2 profile is made for them, with its WARP keys and endpoint, and the server of the
 *    subscription with the lowest ping is its exit-node: the core dials out through it.
 */
object PhonixPolicy {

    const val SUB_DOMAIN = "gsmpasarguard.ir"
    const val AETHER_NAME = "Phonix-Aether"

    /**
     * The User-Agent of the subscription requests. A panel of the Marzban family answers a client it takes for a recent
     * v2rayNG, which this app is by default, with whole Xray JSON configurations: they are stored as custom ones,
     * which cannot be an exit-node. A name it does not know gets the plain list of links.
     */
    const val SUB_USER_AGENT = "Phonix/1.0"
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

    /** The subscriptions of the allowed domain. */
    fun allowedSubs(): List<SubscriptionCache> =
        MmkvManager.decodeSubscriptions().filter { isAllowedSubUrl(it.subscription.url) }

    /** The Aether profile of this build and its key: the only Aether profile there is, null when there is none. */
    fun findAetherProfile(): Pair<String, ProfileItem>? {
        for (guid in MmkvManager.decodeAllServerList()) {
            val profile = MmkvManager.decodeServerConfig(guid) ?: continue
            if (profile.configType == EConfigType.AETHER) return guid to profile
        }
        return null
    }

    /** Makes sure the Aether profile exists, once. Returns whether it is there. */
    fun ensureAetherProfile(): Boolean {
        return try {
            findAetherProfile() != null || (AngConfigManager.importTrustedLink(AETHER_LINK) && findAetherProfile() != null)
        } catch (e: Exception) {
            LogUtil.e(TAG, "ensureAetherProfile failed", e)
            false
        }
    }

    /** The name of the Aether profile: the title the panel sends for the subscription, else the subscription's name. */
    fun aetherNameOf(sub: SubscriptionCache): String =
        sub.subscription.usageInfo?.title?.trim().takeUnless { it.isNullOrEmpty() }
            ?: sub.subscription.remarks.trim().ifEmpty { AETHER_NAME }

    /** Names the Aether profile after the subscription [sub]. */
    fun ensureAetherName(sub: SubscriptionCache) {
        val (guid, profile) = findAetherProfile() ?: return
        val name = aetherNameOf(sub)
        if (profile.remarks == name) return
        profile.remarks = name
        store(guid, profile)
    }

    /** The servers of the allowed subscriptions, as (guid, profile), that can be the exit-node and have a name of their own. */
    fun exitCandidates(): List<Pair<String, ProfileItem>> {
        val unique = AetherExit.nodes().filter { it.profiles == 1 }.map { it.name }.toSet()
        return allowedSubs()
            .flatMap { sub -> MmkvManager.decodeServerList(sub.guid) }
            .mapNotNull { guid -> MmkvManager.decodeServerConfig(guid)?.let { guid to it } }
            .filter { (_, profile) -> AetherExit.takesAsNode(profile) && profile.remarks.trim() in unique }
    }

    /** Smaller first: measured delays, then untested, then the ones whose test failed. */
    fun rank(delayMillis: Long): Long = when {
        delayMillis > 0L -> delayMillis
        delayMillis == 0L -> 100_000L
        else -> 1_000_000L
    }

    fun delayOf(guid: String): Long = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L

    /** The name of the server with the lowest delay among the candidates, those named in [exclude] left out. */
    fun bestExitNode(exclude: Set<String> = emptySet()): String? =
        exitCandidates()
            .filter { (_, profile) -> profile.remarks.trim() !in exclude }
            .minByOrNull { (guid, _) -> rank(delayOf(guid)) }
            ?.second?.remarks?.trim()

    /**
     * Gives every server of the allowed subscriptions a name no other profile has, as the exit-node is told by its
     * name: a panel can name several servers alike. A name taken gets a number after it.
     */
    fun uniquifyServerNames() {
        try {
            val subServers = allowedSubs().flatMap { sub -> MmkvManager.decodeServerList(sub.guid) }
            val inSubs = subServers.toSet()
            val taken = HashSet<String>()
            for (guid in MmkvManager.decodeAllServerList()) {
                if (guid in inSubs) continue
                MmkvManager.decodeServerConfig(guid)?.remarks?.trim()?.let { taken.add(it) }
            }
            for (guid in subServers) {
                val profile = MmkvManager.decodeServerConfig(guid) ?: continue
                val base = profile.remarks.trim().ifEmpty { "server" }
                var name = base
                var number = 2
                while (!taken.add(name)) name = "$base ($number)".also { number++ }
                if (profile.remarks != name) {
                    profile.remarks = name
                    store(guid, profile)
                }
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "uniquifyServerNames failed", e)
        }
    }

    /**
     * Gives the Aether profile a server of the subscription to dial out through when it has none, or none that is
     * there any more: it never dials out on its own. False when there is no such server, and it cannot be started.
     */
    fun ensureExitNode(): Boolean {
        val (guid, profile) = findAetherProfile() ?: return true
        val valid = exitCandidates().map { it.second.remarks.trim() }.toSet()
        if (profile.aetherExitNode?.trim().orEmpty() in valid) return true
        val best = bestExitNode() ?: return false
        profile.aetherExitNode = best
        store(guid, profile)
        return true
    }

    private fun store(guid: String, profile: ProfileItem) {
        try {
            MmkvManager.encodeServerConfig(guid, profile)
        } catch (e: Exception) {
            LogUtil.e(TAG, "the profile was not stored", e)
        }
    }

    /**
     * Keeps [item], the stored subscription [guid], out of a proxy chain: the subscription's servers are the
     * exit-nodes of the Aether profile, so none may dial through that profile in turn. Older builds put every
     * subscription behind it.
     */
    fun ensureChain(guid: String, item: SubscriptionItem) {
        try {
            if (item.prevProfile.isNullOrBlank() && item.nextProfile.isNullOrBlank() && !item.allowInsecureUrl &&
                item.userAgent == SUB_USER_AGENT
            ) return
            item.prevProfile = null
            item.nextProfile = null
            item.allowInsecureUrl = false
            item.userAgent = SUB_USER_AGENT
            MmkvManager.tryEncodeSubscription(guid, item)
        } catch (e: Exception) {
            LogUtil.e(TAG, "ensureChain failed", e)
        }
    }

    /** Every stored subscription out of a proxy chain; cheap enough to run before each start. */
    fun ensureChainForAllSubs() {
        try {
            MmkvManager.decodeSubscriptions().forEach { ensureChain(it.guid, it.subscription) }
        } catch (e: Exception) {
            LogUtil.e(TAG, "ensureChainForAllSubs failed", e)
        }
    }
}
