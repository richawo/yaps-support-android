package ai.yaps.support.ui

import ai.yaps.support.internal.core.AppPromoState
import ai.yaps.support.internal.core.appPromoState
import ai.yaps.support.internal.protocol.CardType
import ai.yaps.support.internal.protocol.Message

/** How a message's card renders. Unknown card types render as plain text (PROTOCOL §9). */
internal enum class CardPresentation { None, Review, Offer, AppPromo, Code, PlainText }

internal fun cardPresentation(message: Message): CardPresentation {
    val card = message.card ?: return CardPresentation.None
    return when (card.type) {
        // A review card without a URL still works: the SDK opens this app's Play listing.
        CardType.REVIEW -> CardPresentation.Review
        CardType.OFFER -> if (card.paywallId.isNullOrBlank()) CardPresentation.PlainText else CardPresentation.Offer
        // No store link for this platform and not installed: just the message text.
        CardType.APP -> if (appPromoState(card) == AppPromoState.Text) CardPresentation.PlainText else CardPresentation.AppPromo
        // Expired and other-platform codes still render as a card; only a missing code falls back.
        CardType.CODE -> if (card.code.isNullOrBlank()) CardPresentation.PlainText else CardPresentation.Code
        else -> CardPresentation.PlainText
    }
}

/**
 * PROTOCOL §5: an offer card is unavailable when the server says so, when its paywall is not in
 * the device's current available list, or when the host has no `onPaywall` handler to present it.
 */
internal fun isOfferAvailable(
    serverAvailable: Boolean,
    paywallId: String?,
    availablePaywallIds: Set<String>,
    hasHandler: Boolean,
): Boolean = serverAvailable && hasHandler && paywallId != null && paywallId in availablePaywallIds
