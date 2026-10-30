package ai.yaps.support.internal.core

import ai.yaps.support.internal.protocol.Message
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

    private val keyPattern = Regex("^[A-Za-z0-9_.-]{1,64}$")

    fun isValidEventName(name: String): Boolean = keyPattern.matches(name)
    fun isValidMetadataKey(key: String): Boolean = keyPattern.matches(key)
    fun isValidPaywallId(id: String): Boolean = keyPattern.matches(id)

    /** Lengths count UTF-16 code units, which is exactly Kotlin's `String.length`. */
    fun textTooLong(text: String): Boolean = text.trim().length > MESSAGE_TEXT

    fun sanitizeMetadata(metadata: Map<String, String>): Map<String, String> =
        metadata.entries
            .filter { isValidMetadataKey(it.key) }
            .take(30)
            .associate { it.key to it.value.take(512) }

    val allowedFileExtensions: Set<String> = setOf("pdf", "txt", "zip", "log")

    fun fileContentType(filename: String): String? = when (filename.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "txt", "log" -> "text/plain"
        "zip" -> "application/zip"
        else -> null
    }
}
