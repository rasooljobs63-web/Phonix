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
 * 2. A built-in Aether MASQUE/H2 profile is made for them, with its WARP keys and endpoint, and every server of the
 *    subscription is chained behind it: a connection goes through the Aether tunnel first, then out through the
 *    server with the lowest ping.
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

    /** The servers of the allowed subscriptions, as (guid, profile), that can be the hop after the Aether tunnel. */
    fun serverCandidates(): List<Pair<String, ProfileItem>> =
        allowedSubs()
            .flatMap { sub -> MmkvManager.decodeServerList(sub.guid) }
            .mapNotNull { guid -> MmkvManager.decodeServerConfig(guid)?.let { guid to it } }
            .filter { (_, profile) -> AetherExit.takesAsNode(profile) }

    /** Smaller first: measured delays, then untested, then the ones whose test failed. */
    fun rank(delayMillis: Long): Long = when {
        delayMillis > 0L -> delayMillis
        delayMillis == 0L -> 100_000L
        else -> 1_000_000L
    }

    fun delayOf(guid: String): Long = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L

    /** A server of the list on the screen: its key, its name, and its delay (0 untested, below 0 failed). */
    data class ServerRow(val guid: String, val name: String, val delayMillis: Long)

    /** The servers of the subscription, in the order of the panel, for the list on the screen. */
    fun serverRows(): List<ServerRow> =
        serverCandidates().map { (guid, profile) -> ServerRow(guid, profile.remarks.trim(), delayOf(guid)) }

    private const val PREF_MANUAL_SERVER = "phonix_manual_server"

    /**
     * The key of the server the person chose by hand, null while the lowest delay decides. It is kept by name: the keys
     * change with every update of the subscription, the names do not.
     */
    fun manualServerGuid(): String? {
        val name = MmkvManager.decodeSettingsString(PREF_MANUAL_SERVER)?.takeIf { it.isNotEmpty() } ?: return null
        return serverCandidates().firstOrNull { (_, profile) -> profile.remarks.trim() == name }?.first
    }

    /** Takes the choice of a server by hand: its name, or null to give the choice back to the lowest delay. */
    fun setManualServerName(name: String?) {
        MmkvManager.encodeSettings(PREF_MANUAL_SERVER, name.orEmpty())
    }

    /** Selects the server the person chose by hand, else the one with the lowest delay. */
    fun applySelection() {
        (manualServerGuid() ?: bestServer())?.let { MmkvManager.setSelectServer(it) }
    }

    /** The key of the server with the lowest delay among the candidates, those keyed in [exclude] left out. */
    fun bestServer(exclude: Set<String> = emptySet()): String? =
        serverCandidates()
            .filter { (guid, _) -> guid !in exclude }
            .minByOrNull { (guid, _) -> rank(delayOf(guid)) }
            ?.first

    /**
     * Selects the server with the lowest delay when the one selected is none of the subscription's: nothing, or the
     * Aether profile itself, which on its own goes nowhere past the tunnel.
     */
    fun ensureServerSelected() {
        val candidates = serverCandidates()
        if (candidates.isEmpty()) return
        val selected = MmkvManager.getSelectServer()
        val manual = manualServerGuid()
        if (manual != null) {
            if (selected != manual) MmkvManager.setSelectServer(manual)
            return
        }
        if (selected != null && candidates.any { it.first == selected }) return
        bestServer()?.let { MmkvManager.setSelectServer(it) }
    }

    /**
     * Gives every server of the allowed subscriptions a name no other profile has, so that none takes the name of the
     * Aether profile, which the subscriptions name as their entry: a panel can name several servers alike. A name taken
     * gets a number after it.
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

    private fun store(guid: String, profile: ProfileItem) {
        try {
            MmkvManager.encodeServerConfig(guid, profile)
        } catch (e: Exception) {
            LogUtil.e(TAG, "the profile was not stored", e)
        }
    }

    /**
     * Sets [item], the stored subscription [guid], as this build wants it. A subscription of the allowed domain is on,
     * named, asked with the User-Agent that gets plain links, and behind the Aether profile, its entry proxy: every
     * server of it dials through the tunnel. Any other, the default group included, is behind nothing: a chain with
     * two Aether profiles does not start.
     */
    fun ensureChain(guid: String, item: SubscriptionItem) {
        try {
            val allowed = isAllowedSubUrl(item.url)
            val entry = if (allowed) findAetherProfile()?.second?.remarks?.trim().takeUnless { it.isNullOrEmpty() } else null
            var changed = false
            if (item.prevProfile?.trim().orEmpty() != entry.orEmpty()) { item.prevProfile = entry; changed = true }
            if (!item.nextProfile.isNullOrBlank()) { item.nextProfile = null; changed = true }
            if (item.allowInsecureUrl) { item.allowInsecureUrl = false; changed = true }
            if (allowed) {
                if (item.userAgent != SUB_USER_AGENT) { item.userAgent = SUB_USER_AGENT; changed = true }
                if (!item.enabled) { item.enabled = true; changed = true }
                if (item.remarks.isBlank()) { item.remarks = "Phonix"; changed = true }
            }
            if (changed) MmkvManager.tryEncodeSubscription(guid, item)
        } catch (e: Exception) {
            LogUtil.e(TAG, "ensureChain failed", e)
        }
    }

    /** Every stored subscription as this build wants it; cheap enough to run before each start. */
    fun ensureChainForAllSubs() {
        try {
            MmkvManager.decodeSubscriptions().forEach { ensureChain(it.guid, it.subscription) }
        } catch (e: Exception) {
            LogUtil.e(TAG, "ensureChainForAllSubs failed", e)
        }
    }
}
