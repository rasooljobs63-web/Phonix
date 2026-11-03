package com.v2ray.ang.handler

import android.os.Build
import android.provider.Settings
import com.v2ray.ang.AngApplication
import com.v2ray.ang.core.AetherExit
import com.v2ray.ang.enums.AetherFingerprint
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Phonix: the rules of this build.
 *
 * 1. Only subscription links of [SUB_DOMAIN] (and its subdomains), over https, are accepted.
 * 2. A built-in Aether MASQUE/H2 profile is made for them, with its WARP keys and endpoint, and every server of the
 *    subscription is chained behind it: a connection goes through the Aether tunnel first, then out through a
 *    server of the subscription, picked at random unless the person chose one.
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

    private const val PREF_DEVICE_ID = "phonix_device_id"

    /**
     * The identity of this device for the panel's device limit: 32 hex digits derived from the Android ID, so that
     * the same device keeps it through a reinstall of the same build, and the raw ID never leaves the phone. It
     * changes with the key the app is signed with, and with a factory reset.
     */
    fun deviceId(): String {
        MmkvManager.decodeSettingsString(PREF_DEVICE_ID)?.takeIf { it.length == 32 }?.let { return it }
        val androidId = try {
            Settings.Secure.getString(AngApplication.application.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) {
            null
        }
        // 9774d56d682e549c is the one value some broken builds give every device
        val seed = androidId?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" } ?: UUID.randomUUID().toString()
        val id = MessageDigest.getInstance("SHA-256").digest("phonix:$seed".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
        MmkvManager.encodeSettings(PREF_DEVICE_ID, id)
        return id
    }

    /** The headers the subscription requests carry, as the JSON the subscription keeps them in. */
    fun deviceHeadersJson(): String {
        fun ascii(value: String) = value.filter { it.code in 32..126 }.trim().take(64).ifEmpty { "unknown" }
        return JSONObject(
            mapOf(
                "x-hwid" to deviceId(),
                "x-device-os" to "Android",
                "x-ver-os" to ascii(Build.VERSION.RELEASE.orEmpty()),
                "x-device-model" to ascii(Build.MODEL.orEmpty()),
            )
        ).toString()
    }

    /** Why the last request for a subscription failed, null when it did not. */
    @Volatile
    var lastFetchFailure: String? = null
        private set

    fun noteFetchFailure(error: Exception?) {
        lastFetchFailure = error?.message
    }

    /** Whether the panel refused the last request for the 403 of a device limit reached. */
    fun deviceLimitReached(): Boolean = lastFetchFailure?.contains("status code 403") == true

    /**
     * After an import: when the panel turned this device away, the subscription just added, which has no server, is
     * removed again, so that the key screen stays. True when one was removed.
     */
    fun rejectIfDeviceLimit(): Boolean {
        if (!deviceLimitReached()) return false
        var removed = false
        allowedSubs()
            .filter { MmkvManager.decodeServerList(it.guid).isEmpty() }
            .forEach { removed = MmkvManager.tryRemoveSubscription(it.guid) || removed }
        lastFetchFailure = null
        return removed
    }

    /** A way of reaching the tunnel: the TLS fingerprint of the core, and the final mask of its connection, if any. */
    data class Variant(val fingerprint: AetherFingerprint, val finalMask: String?)

    // The two final masks that split the TLS hello of a TCP connection, the ones of the list in the editor
    private const val MASK_TLSHELLO =
        """{"tcp":[{"type":"fragment","settings":{"packets":"tlshello","lengths":["6","98","1"],"delays":["0"],"maxSplit":"0"}},{"type":"fragment","settings":{"packets":"1-1","lengths":["114","1"],"delays":["1"],"maxSplit":"11"}}]}"""
    private const val MASK_TLSHELLO_0_LEN =
        """{"tcp":[{"type":"fragment","settings":{"packets":"tlshello","lengths":["0","104","1"],"delays":["0"],"maxSplit":"0"}},{"type":"fragment","settings":{"packets":"1-1","lengths":["114","1"],"delays":["1"],"maxSplit":"11"}}]}"""

    /**
     * The ways of reaching the tunnel, in the order they are tried when one does not connect: the fingerprints of
     * Chrome, Firefox and Chrome 120 with no final mask, then the same three with each of the final masks.
     */
    val variants: List<Variant> = listOf(null, MASK_TLSHELLO, MASK_TLSHELLO_0_LEN).flatMap { mask ->
        listOf(AetherFingerprint.CHROME, AetherFingerprint.FIREFOX, AetherFingerprint.CHROME_120)
            .map { Variant(it, mask) }
    }

    private const val PREF_VARIANT = "phonix_variant"

    /** The index in [variants] of the way the Aether profile is set to. */
    fun variantIndex(): Int =
        MmkvManager.decodeSettingsString(PREF_VARIANT)?.toIntOrNull()?.takeIf { it in variants.indices } ?: 0

    /** Sets the Aether profile to the way [index] of [variants], and remembers it. */
    fun applyVariant(index: Int) {
        val at = index.coerceIn(variants.indices)
        val variant = variants[at]
        MmkvManager.encodeSettings(PREF_VARIANT, at.toString())
        val (guid, profile) = findAetherProfile() ?: return
        if (profile.aetherFingerprint == variant.fingerprint.type && profile.finalMask == variant.finalMask) return
        profile.aetherFingerprint = variant.fingerprint.type
        profile.finalMask = variant.finalMask
        store(guid, profile)
    }

    /** Moves to the next way, the first one after the last; null when there is no Aether profile. */
    fun advanceVariant(): Int? {
        if (findAetherProfile() == null) return null
        val next = (variantIndex() + 1) % variants.size
        applyVariant(next)
        return next
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

    /** A server of the list on the screen: its key and its name. */
    data class ServerRow(val guid: String, val name: String)

    /** The servers of the subscription, in the order of the panel, for the list on the screen. */
    fun serverRows(): List<ServerRow> =
        serverCandidates().map { (guid, profile) -> ServerRow(guid, profile.remarks.trim()) }

    private const val PREF_MANUAL_SERVER = "phonix_manual_server"

    /**
     * The key of the server the person chose by hand, null while a server at random decides. It is kept by name: the
     * keys change with every update of the subscription, the names do not.
     */
    fun manualServerGuid(): String? {
        val name = MmkvManager.decodeSettingsString(PREF_MANUAL_SERVER)?.takeIf { it.isNotEmpty() } ?: return null
        return serverCandidates().firstOrNull { (_, profile) -> profile.remarks.trim() == name }?.first
    }

    /** Takes the choice of a server by hand: its name, or null to give the choice back to the random pick. */
    fun setManualServerName(name: String?) {
        MmkvManager.encodeSettings(PREF_MANUAL_SERVER, name.orEmpty())
    }

    /** Selects the server the person chose by hand, else one at random. */
    fun applySelection() {
        (manualServerGuid() ?: randomServer())?.let { MmkvManager.setSelectServer(it) }
    }

    /** A server at random among the candidates, those keyed in [exclude] left out. */
    fun randomServer(exclude: Set<String> = emptySet()): String? =
        serverCandidates().filter { (guid, _) -> guid !in exclude }.randomOrNull()?.first

    /**
     * Selects a server at random when the one selected is none of the subscription's: nothing, or the Aether profile
     * itself, which on its own goes nowhere past the tunnel.
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
        randomServer()?.let { MmkvManager.setSelectServer(it) }
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
                val headers = deviceHeadersJson()
                if (item.requestHeaders != headers) { item.requestHeaders = headers; changed = true }
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
