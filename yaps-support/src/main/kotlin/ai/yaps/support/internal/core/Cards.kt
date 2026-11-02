package ai.yaps.support.internal.core

import ai.yaps.support.SupportEvent
import ai.yaps.support.internal.protocol.Card
import ai.yaps.support.internal.protocol.CardType
import ai.yaps.support.internal.protocol.CodePlatform
import ai.yaps.support.internal.protocol.LinkTarget
import ai.yaps.support.internal.protocol.Message
import ai.yaps.support.ui.parseIsoMillis

/*
 * PROTOCOL §5 app promo, code and link cards: what each one shows and what its taps do. Kept free of
 * Android types so the rules run as plain JVM tests; the Compose cards only draw the result.
 */

/** How an app promo card renders. */
internal enum class AppPromoState {
    /** Icon, title, body and the "Get it" button. */
    Available,

    /** The customer already uses the promoted app: "You already have it", no button. */
    Installed,

    /** No store link for this platform (or none at all): the message renders as plain text. */
    Text,
}

internal fun appPromoState(card: Card): AppPromoState = when {
    card.installed -> AppPromoState.Installed
    card.available && !card.url.isNullOrBlank() -> AppPromoState.Available
    else -> AppPromoState.Text
}

/** How a code card renders. */
internal enum class CodeState {
    /** Copy and Redeem. */
    Redeemable,

    /** Copy only; [CodeCard.hint] says where to redeem when the code belongs to another platform. */
    CopyOnly,

    /** Past `expiresAt`: struck-through code, "Expired", no buttons. */
    Expired,
}

/** "Redeem on iPhone" / "Redeem on Android" / "Redeem on the web". */
internal enum class RedeemHint { IPHONE, ANDROID, WEB }

internal data class CodeCard(
    val code: String,
    val state: CodeState,
    /** Set only when [state] is [CodeState.Redeemable]. */
    val redeemUrl: String?,
    val hint: RedeemHint?,
    val expiresAtMillis: Long?,
)

/** `null` when the card carries no code, so the message renders as plain text. */
internal fun codeCard(card: Card, nowMillis: Long): CodeCard? {
    val code = card.code?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val expiry = card.expiresAt?.let(::parseIsoMillis)
    if (expiry != null && expiry <= nowMillis) {
        return CodeCard(code, CodeState.Expired, redeemUrl = null, hint = null, expiresAtMillis = expiry)
    }
    val url = card.url?.trim()?.takeIf { it.isNotEmpty() }
    return if (card.available && url != null) {
        CodeCard(code, CodeState.Redeemable, redeemUrl = url, hint = null, expiresAtMillis = expiry)
    } else {
        // Unavailable means another platform redeems it (PROTOCOL §5); say which one.
        val hint = if (card.available) null else redeemHint(card.platform)
        CodeCard(code, CodeState.CopyOnly, redeemUrl = null, hint = hint, expiresAtMillis = expiry)
    }
}

internal fun redeemHint(platform: String?): RedeemHint? = when (platform?.trim()?.lowercase()) {
    CodePlatform.IOS -> RedeemHint.IPHONE
    CodePlatform.ANDROID -> RedeemHint.ANDROID
    CodePlatform.WEB -> RedeemHint.WEB
    else -> null
}

/** What TalkBack reads for a code: one character at a time, so "STUDENT20" is spelled, not pronounced. */
internal fun spokenCode(code: String): String =
    code.trim().filterNot { it.isWhitespace() }.toList().joinToString(" ")

/**
 * Android 13+ shows its own clipboard confirmation (and announces it), so the SDK only shows its
 * brief "Copied" state on older versions.
 */
internal fun showsOwnCopyFeedback(sdkInt: Int): Boolean = sdkInt < 33

/** Play listing links: `market://` first, then the https listing when no store app can open it. */
internal object PlayStoreLink {
    private const val MARKET_PREFIX = "market://details?"
    private const val WEB_PREFIX = "https://play.google.com/store/apps/details?"

    /** URLs to try in order. The first one an installed app can open wins. */
    fun candidates(url: String): List<String> {
        val trimmed = url.trim()
        return when {
            trimmed.isEmpty() -> emptyList()
            trimmed.startsWith(MARKET_PREFIX, ignoreCase = true) ->
                listOf(trimmed, WEB_PREFIX + trimmed.substring(MARKET_PREFIX.length))
            trimmed.startsWith("market://", ignoreCase = true) -> {
                val id = queryParam(trimmed, "id")
                if (id != null) listOf(trimmed, "${WEB_PREFIX}id=$id") else listOf(trimmed)
            }
            trimmed.startsWith(WEB_PREFIX, ignoreCase = true) ->
                listOf(MARKET_PREFIX + trimmed.substring(WEB_PREFIX.length), trimmed)
            else -> listOf(trimmed)
        }
    }

    fun listing(packageName: String): List<String> = candidates("${MARKET_PREFIX}id=$packageName")

    private fun queryParam(url: String, name: String): String? =
        url.substringAfter('?', "").split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == name }
            ?.get(1)
}

// ---------------------------------------------------------------- link cards

/** The only schemes a link card may open (PROTOCOL §5). Anything else is refused, whatever the server sent. */
internal val LINK_SCHEMES: Set<String> = setOf("https", "mailto", "tel", "sms")

internal fun isAllowedLinkUrl(url: String?): Boolean {
    val trimmed = url?.trim().orEmpty()
    val colon = trimmed.indexOf(':')
    if (colon <= 0) return false
    val scheme = trimmed.substring(0, colon).lowercase()
    // https needs a host; the others need something after the colon.
    return when (scheme) {
        "https" -> trimmed.length > colon + 3 && trimmed.startsWith("//", colon + 1)
        in LINK_SCHEMES -> trimmed.length > colon + 1
        else -> false
    }
}

/**
 * PROTOCOL §5: a link card's button works when the server says so and this device can open it: a
 * `url` with an allowed scheme, the app's settings page, or a screen that is in the host's current
 * [YapsSupport.screens][ai.yaps.support.YapsSupport.screens] list with an `onScreen` handler set
 * (mirroring how offer cards check paywalls and `onPaywall`).
 */
internal fun isLinkAvailable(card: Card, screenIds: Set<String>, hasScreenHandler: Boolean): Boolean {
    if (!card.available) return false
    return when (card.target) {
        LinkTarget.URL -> isAllowedLinkUrl(card.url)
        LinkTarget.APP_SETTINGS -> true
        LinkTarget.SCREEN -> hasScreenHandler && card.screenId != null && card.screenId in screenIds
        else -> false
    }
}

/** What a link card's tap does on this device. The SDK supplies the Android side; tests supply fakes. */
internal interface LinkOpener {
    /** `ACTION_VIEW`; `false` when no installed app handles it. */
    fun openUrl(url: String): Boolean

    /** `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` for this app's package. */
    fun openAppSettings(): Boolean

    /** Closes the chat if the SDK presented it, then calls the host's `onScreen`. */
    fun openScreen(screenId: String): Boolean
}

/** Opens a URL with an `ACTION_VIEW` intent; `false` when no installed app handles it. */
internal fun interface UrlLauncher {
    fun launch(url: String): Boolean
}

/** Tries [candidates] in order and returns the one that opened, or `null`. */
internal fun openFirst(candidates: List<String>, launcher: UrlLauncher): String? {
    for (url in candidates) if (launcher.launch(url)) return url
    return null
}

/** `action` values for `POST /v1/sdk/actions/:messageId` (PROTOCOL §3.6). */
internal object CardActionName {
    const val OPEN = "open"
    const val COPY = "copy"
}

/**
 * Taps on app promo, code and link cards: do the thing, then emit the [SupportEvent] and send the action
 * receipt. Nothing is emitted or recorded when the tap could not do anything.
 */
internal class CardActions(
    private val emit: (SupportEvent) -> Unit,
    private val receipt: (messageId: String, action: String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun openAppPromo(message: Message, launcher: UrlLauncher): Boolean {
        val card = message.card ?: return false
        if (appPromoState(card) != AppPromoState.Available) return false
        openFirst(PlayStoreLink.candidates(card.url.orEmpty()), launcher) ?: run {
            Log.w("No app can open the app promo link")
            return false
        }
        emit(SupportEvent.AppPromoOpened(message.id, card.promotedAppId))
        receipt(message.id, CardActionName.OPEN)
        return true
    }

    /** [copy] puts the code on the clipboard and reports whether it worked. Expired codes are not copied. */
    fun copyCode(message: Message, copy: (code: String) -> Boolean): Boolean {
        val card = message.card ?: return false
        val model = codeCard(card, now()) ?: return false
        if (model.state == CodeState.Expired) return false
        if (!copy(model.code)) return false
        emit(SupportEvent.CodeCopied(message.id, card.campaignId))
        receipt(message.id, CardActionName.COPY)
        return true
    }

    fun redeemCode(message: Message, launcher: UrlLauncher): Boolean {
        val card = message.card ?: return false
        val url = codeCard(card, now())?.takeIf { it.state == CodeState.Redeemable }?.redeemUrl ?: return false
        if (!launcher.launch(url)) {
            Log.w("No app can open the redeem link")
            return false
        }
        emit(SupportEvent.CodeOpened(message.id, card.campaignId))
        receipt(message.id, CardActionName.OPEN)
        return true
    }

    fun openLink(message: Message, screenIds: Set<String>, hasScreenHandler: Boolean, opener: LinkOpener): Boolean {
        val card = message.card ?: return false
        if (card.type != CardType.LINK || !isLinkAvailable(card, screenIds, hasScreenHandler)) return false
        val opened = when (card.target) {
            LinkTarget.URL -> opener.openUrl(card.url.orEmpty().trim())
            LinkTarget.APP_SETTINGS -> opener.openAppSettings()
            LinkTarget.SCREEN -> opener.openScreen(card.screenId.orEmpty())
            else -> false
        }
        if (!opened) {
            Log.w("No app can open the link card")
            return false
        }
        emit(SupportEvent.LinkOpened(message.id, card.linkId))
        receipt(message.id, CardActionName.OPEN)
        return true
    }
}
