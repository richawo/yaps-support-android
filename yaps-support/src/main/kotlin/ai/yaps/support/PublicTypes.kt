package ai.yaps.support

import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes

/** Push transport a token belongs to. Android hosts normally use [FCM]. */
public enum class PushProvider(internal val wire: String) {
    FCM("fcm"),

    /** Only for hosts that bridge APNs tokens (for example a Kotlin Multiplatform iOS target). */
    APNS("apns"),
}

/** How much the SDK writes to Logcat (tag `YapsSupport`). Tokens and message text are never logged. */
public enum class LogLevel { NONE, ERROR, WARNING, INFO, DEBUG }

/** A light and dark variant of one colour, as ARGB ints. */
public data class SupportColor(
    @param:ColorInt @get:ColorInt public val light: Int,
    @param:ColorInt @get:ColorInt public val dark: Int = light,
)

public enum class SupportColorScheme { SYSTEM, LIGHT, DARK }

public enum class SupportFontStyle { DEFAULT, ROUNDED, SERIF, MONO }

public enum class SupportCornerStyle { ROUND, SOFT, SQUARE }

/**
 * The chat's local look. Every property is optional; `null` means "use the SDK default".
 *
 * When [YapsSupport.usesRemoteAppearance] is on (the default), values published from the inbox win
 * over these, field by field: **remote > local > default**. Remote changes apply the next time the
 * chat opens, never mid-conversation.
 *
 * Java: `SupportAppearance a = new SupportAppearance(); a.setAccent(new SupportColor(0xFFFF7A45));`
 */
public data class SupportAppearance(
    public var accent: SupportColor? = null,
    public var onAccent: SupportColor? = null,
    public var background: SupportColor? = null,
    public var incomingBackground: SupportColor? = null,
    public var incomingForeground: SupportColor? = null,
    public var title: String? = null,
    public var welcomeTitle: String? = null,
    public var welcomeText: String? = null,
    public var colorScheme: SupportColorScheme? = null,
    public var fontStyle: SupportFontStyle? = null,
    public var cornerStyle: SupportCornerStyle? = null,
    public var showCloseButton: Boolean? = null,
    @DrawableRes public var welcomeIcon: Int? = null,
    public var fadeOlderMessages: Boolean? = null,
    /**
     * Opt in to Material You (Android 12+). Dynamic colours replace the SDK *defaults* only, so any
     * colour you set here or publish from the inbox still wins.
     */
    public var useDynamicColor: Boolean = false,
)

/** A product inside a [SupportPaywall], shown to agents so they can pick the right offer. */
public data class SupportPaywallProduct @JvmOverloads constructor(
    public val id: String,
    public val price: String? = null,
    /** ISO 8601 duration, e.g. `P1Y`. */
    public val period: String? = null,
    /** ISO 8601 duration of the free trial, e.g. `P1W`. */
    public val trial: String? = null,
)

/**
 * A paywall the host app can present. Agents send it as an offer card; tapping the card calls
 * [YapsSupport.onPaywall] with [id].
 */
public data class SupportPaywall @JvmOverloads constructor(
    public val id: String,
    public val title: String,
    public val products: List<SupportPaywallProduct> = emptyList(),
)

/** Things that happen in support, delivered on [YapsSupport.events]. */
public sealed class SupportEvent {
    public data object ChatOpened : SupportEvent()
    public data object ChatClosed : SupportEvent()
    public data class MessageSent(public val messageId: String) : SupportEvent()
    public data class MessageReceived(public val messageId: String) : SupportEvent()
    public data class ImageSent(public val messageId: String, public val count: Int) : SupportEvent()
    public data class ReviewOpened(public val messageId: String) : SupportEvent()
    public data class PaywallOpened(public val messageId: String, public val paywallId: String) : SupportEvent()

    /** An app promo card's button opened the Play listing. [appId] is the promoted app's Support Yaps App ID. */
    public data class AppPromoOpened(public val messageId: String, public val appId: String?) : SupportEvent()

    /** The user copied a code card's code. [campaignId] is the code campaign it came from. */
    public data class CodeCopied(public val messageId: String, public val campaignId: String?) : SupportEvent()

    /** The user tapped a code card's Redeem button (for Play codes, `play.google.com/redeem`). */
    public data class CodeOpened(public val messageId: String, public val campaignId: String?) : SupportEvent()
    public data object DataDeleted : SupportEvent()

    /** The identity JWT was rejected (`identity_token_invalid` / `identity_token_expired`). The chat keeps working unverified. */
    public data class IdentityRejected(public val code: String) : SupportEvent()
}

/** Java-friendly callback for [YapsSupport.addEventListener]. */
public fun interface SupportEventListener {
    public fun onEvent(event: SupportEvent)
}

/** Thrown by [YapsSupport.deleteData] and other calls that report failure. [code] is a PROTOCOL §1 error code. */
public class SupportException internal constructor(
    public val code: String,
    message: String,
    public val status: Int = 0,
    cause: Throwable? = null,
) : Exception(message, cause)
