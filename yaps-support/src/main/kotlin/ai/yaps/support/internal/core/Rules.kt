package ai.yaps.support.internal.core

import ai.yaps.support.SupportScreen
import ai.yaps.support.internal.protocol.Message
import ai.yaps.support.internal.protocol.PaywallWire
import ai.yaps.support.internal.protocol.ScreenWire
import ai.yaps.support.internal.protocol.WireJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.min
import kotlin.random.Random

/** PROTOCOL §5 "Which text to show". */
internal object TranslationRule {

    /**
     * Shows `translation.text` when `translation.language` equals the customer's language, else
     * `text`. Languages compare by primary subtag, case-insensitively (`es-MX` matches `es`), and a
     * blank translation never replaces the original.
     */
    fun displayText(message: Message, customerLanguage: String?): String {
        val translation = message.translation ?: return message.text
        if (translation.text.isBlank() || customerLanguage.isNullOrBlank()) return message.text
        return if (primary(translation.language) == primary(customerLanguage)) translation.text else message.text
    }

    /** Primary language subtag, lowercased, with Java's legacy codes mapped to ISO 639-1 (`iw` → `he`). */
    fun primary(tag: String): String = when (val p = tag.trim().replace('_', '-').substringBefore('-').lowercase()) {
        "iw" -> "he"
        "in" -> "id"
        "ji" -> "yi"
        else -> p
    }
}

/** PROTOCOL §6.1: FCM data pushes are ours when `support == "1"`. */
internal object PushRecognizer {
    fun isSupportPush(data: Map<String, String>?): Boolean = data?.get("support") == "1"

    fun conversationId(data: Map<String, String>): String? = data["conversationId"]?.takeIf { it.isNotBlank() }
    fun messageId(data: Map<String, String>): String? = data["messageId"]?.takeIf { it.isNotBlank() }
    fun badge(data: Map<String, String>): Int? = data["badge"]?.trim()?.toIntOrNull()
}

/**
 * Review cards open the store listing, never the In-App Review API (Google forbids calling it from
 * a button because the quota may silently swallow the prompt). PROTOCOL §5: open `url`, which is
 * `market://details?id=<package>` on Android, falling back to the Play web listing when no store app
 * can handle it.
 */
internal object ReviewLink {
    /** URLs to try in order. The first one an installed app can open wins. Without a URL, this app's own listing. */
    fun candidates(url: String?, hostPackage: String): List<String> =
        if (url.isNullOrBlank()) PlayStoreLink.listing(hostPackage) else PlayStoreLink.candidates(url)
}

/**
 * Exponential backoff with ±[jitter] jitter: 1, 2, 4, 8, … seconds capped at [capMillis].
 * Used for socket reconnects (PROTOCOL §4: 1, 2, 4, 8, 16, 30 s) and outbox retries.
 */
internal class Backoff(
    private val baseMillis: Long = 1_000,
    private val capMillis: Long = 30_000,
    private val jitter: Double = 0.2,
    private val random: Random = Random.Default,
) {
    /** Delay before retry number [attempt] (1-based). */
    fun delayFor(attempt: Int): Long {
        val exp = min(attempt.coerceAtLeast(1) - 1, 30)
        val raw = min(capMillis.toDouble(), baseMillis.toDouble() * (1L shl exp))
        val factor = 1.0 + (random.nextDouble() * 2.0 - 1.0) * jitter
        return (raw * factor).toLong().coerceAtLeast(0)
    }
}

/** Crockford base32 ULIDs for message `clientId`s: sortable, URL-safe, 26 characters. */
internal object Ulid {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun generate(timeMillis: Long = System.currentTimeMillis(), random: Random = Random.Default): String {
        val chars = CharArray(26)
        var t = timeMillis
        for (i in 9 downTo 0) {
            chars[i] = ALPHABET[(t and 31).toInt()]
            t = t ushr 5
        }
        for (i in 10 until 26) chars[i] = ALPHABET[random.nextInt(32)]
        return String(chars)
    }

    fun isValid(value: String): Boolean = value.length == 26 && value.all { it in ALPHABET }
}

/** PROTOCOL §3.4 `PATCH /v1/sdk/device` bodies for the host's lists and settings. */
internal object DeviceUpdate {
    /** `paywalls` and/or `screens`; each one present replaces the device's whole list. */
    fun lists(paywalls: List<PaywallWire>?, screens: List<ScreenWire>?): JsonObject = buildJsonObject {
        paywalls?.let { put("paywalls", WireJson.encodeToJsonElement(ListSerializer(PaywallWire.serializer()), it)) }
        screens?.let { put("screens", WireJson.encodeToJsonElement(ListSerializer(ScreenWire.serializer()), it)) }
    }

    /** `badgeEnabled`, or `null` when the server already has [desired]. */
    fun badge(desired: Boolean, synced: Boolean): JsonObject? =
        if (desired == synced) null else buildJsonObject { put("badgeEnabled", desired) }
}

/** Decisions shared with iOS and the TypeScript core, kept pure so they can be unit tested. */
internal object SessionRules {
    /** `track(name, opensChat = true)` opens the chat only if the server answers within this. */
    const val TRACK_OPEN_WINDOW_MS = 5_000L

    /**
     * `identify(userId)` for someone other than the user this device represents starts a new
     * session, so the previous person's conversation is never relabelled.
     */
    fun isUserSwitch(knownUserId: String?, newUserId: String?): Boolean =
        !knownUserId.isNullOrEmpty() && !newUserId.isNullOrEmpty() && knownUserId != newUserId

    /** The server asked to open the chat, in time, while the app is in the foreground and the chat is closed. */
    fun shouldOpenChatAfterTrack(opensChat: Boolean, serverSaysOpen: Boolean, elapsedMs: Long, appForeground: Boolean, chatOpen: Boolean): Boolean =
        opensChat && serverSaysOpen && elapsedMs <= TRACK_OPEN_WINDOW_MS && appForeground && !chatOpen
}

/** PROTOCOL §3.4 `paywalls`: the ones the host's availability check allows. */
internal object PaywallRules {
    /** A check that throws counts as unavailable, as on iOS, React Native and the web. */
    suspend fun <T> available(paywalls: List<T>, id: (T) -> String, check: (suspend (String) -> Boolean)?): List<T> {
        if (check == null) return paywalls
        return paywalls.filter { p ->
            try {
                check(id(p))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("paywallAvailability threw for '${id(p)}'; it counts as unavailable", e)
                false
            }
        }
    }
}

/** PROTOCOL §2 validation helpers. */
internal object Limits {
    const val MESSAGE_TEXT = 4_000
    const val ATTACHMENTS = 5
    const val IMAGE_EDGE = 2_048
    const val IMAGE_BYTES = 1024 * 1024
    const val FILE_BYTES = 10L * 1024 * 1024
    const val HISTORY_CACHE = 200
    const val USER_ID = 255
    const val NAME = 200
    const val EMAIL = 254
    const val IDENTITY_TOKEN = 4_096
    const val PAYWALLS = 20
    const val SCREENS = 50
    const val SCREEN_TITLE = 60
    const val METADATA_ENTRIES = 30
    const val METADATA_VALUE = 512

    private val keyPattern = Regex("^[A-Za-z0-9_.-]{1,64}$")

    fun isValidEventName(name: String): Boolean = keyPattern.matches(name)
    fun isValidMetadataKey(key: String): Boolean = keyPattern.matches(key)
    fun isValidPaywallId(id: String): Boolean = keyPattern.matches(id)
    fun isValidScreenId(id: String): Boolean = keyPattern.matches(id)

    /** Drops invalid IDs and repeats (the first wins), keeps 50, and cuts titles to 60; a blank title becomes the ID. */
    fun sanitizeScreens(screens: List<SupportScreen>): List<SupportScreen> {
        val seen = HashSet<String>()
        return screens
            .filter { isValidScreenId(it.id) && seen.add(it.id) }
            .take(SCREENS)
            .map { it.copy(title = it.title.trim().ifEmpty { it.id }.take(SCREEN_TITLE)) }
    }

    /** Lengths count UTF-16 code units, which is exactly Kotlin's `String.length`. */
    fun textTooLong(text: String): Boolean = text.trim().length > MESSAGE_TEXT

    /**
     * Like iOS and the web: an invalid key or a value over 512 characters drops that entry (never
     * truncates it), and entries beyond 30 are dropped. Each drop is logged.
     */
    fun sanitizeMetadata(metadata: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((key, value) in metadata) {
            when {
                !isValidMetadataKey(key) -> Log.e("setMetadata: a key is invalid (1–64 characters of A–Z, a–z, 0–9, _ . -); it was left out")
                value.length > METADATA_VALUE -> Log.e("setMetadata: the value for '$key' is over $METADATA_VALUE characters; it was left out")
                out.size >= METADATA_ENTRIES -> {
                    Log.e("setMetadata: more than $METADATA_ENTRIES entries; extra keys were left out")
                    break
                }
                else -> out[key] = value
            }
        }
        return out
    }

    val allowedFileExtensions: Set<String> = setOf("pdf", "txt", "zip", "log")

    fun fileContentType(filename: String): String? = when (filename.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "txt", "log" -> "text/plain"
        "zip" -> "application/zip"
        else -> null
    }
}
