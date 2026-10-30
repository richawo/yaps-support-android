package ai.yaps.support.ui

import ai.yaps.support.SupportAppearance
import ai.yaps.support.internal.core.AppearanceResolver
import ai.yaps.support.internal.core.ChatState
import ai.yaps.support.internal.core.ConnectionStatus
import ai.yaps.support.internal.core.Restriction
import ai.yaps.support.internal.outbox.OutboxEntry
import ai.yaps.support.internal.outbox.OutboxState
import ai.yaps.support.internal.protocol.Author
import ai.yaps.support.internal.protocol.Card
import ai.yaps.support.internal.protocol.Message
import ai.yaps.support.internal.protocol.SdkConfig
import ai.yaps.support.internal.protocol.TeamDisplay
import ai.yaps.support.internal.protocol.Translation
import android.content.res.Configuration
import ai.yaps.support.SupportPaywall
import ai.yaps.support.SupportPaywallProduct
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview

/** Fake conversation used by previews (and handy for screenshot tests). */
internal object PreviewData {
    private const val DAY = "2026-10-30T"
    private val frederick = Author(type = "agent", id = "usr_3", name = "Frederick")

    val messages = listOf(
        Message("msg_1", "cnv_1", 1, "c1", Author("customer"), "text", "Hi! Is there a dark mode?", createdAt = "${DAY}09:41:00.000Z"),
        Message("msg_2", "cnv_1", 2, null, frederick, "text", "Yes! Settings → Appearance → Dark. It follows the system by default.", createdAt = "${DAY}09:42:10.000Z"),
        Message("msg_3", "cnv_1", 3, null, frederick, "text", "Anything else I can help with?", createdAt = "${DAY}09:42:30.000Z"),
        Message("msg_4", "cnv_1", 4, "c4", Author("customer"), "text", "Perfect, that worked. Do you have a yearly plan?", createdAt = "${DAY}09:44:00.000Z"),
        Message(
            "msg_5", "cnv_1", 5, null, frederick, "card", "Yearly is 40% off right now, want it?",
            translation = Translation("es", "El plan anual tiene un 40 % de descuento, ¿lo quieres?"),
            card = Card(type = "offer", title = "Yearly 40% off", buttonLabel = "View offer", paywallId = "yearly_offer"),
            createdAt = "${DAY}09:44:40.000Z",
        ),
        Message(
            "msg_6", "cnv_1", 6, null, frederick, "card", "Glad that's sorted! If the app is working for you, a quick rating helps a lot.",
            card = Card(type = "review", buttonLabel = "Rate on Google Play", url = "market://details?id=ai.yaps.app"),
            createdAt = "${DAY}09:46:00.000Z",
        ),
        Message("msg_7", "cnv_1", 7, "c7", Author("customer"), "text", "Will do, thanks Frederick! 🐓", createdAt = "${DAY}09:47:00.000Z"),
    )

    /** App promo and code cards in each state they can render in. */
    val promoMessages = listOf(
        Message(
            "msg_10", "cnv_1", 10, null, frederick, "card", "Since you're studying, you might like our flashcard app too.",
            card = Card(
                type = "app", title = "Stacked", body = "Flashcards that plan your revision for you.", buttonLabel = "Get it",
                url = "market://details?id=com.yaps.stacked", promotedAppId = "app_2",
            ),
            createdAt = "${DAY}09:47:00.000Z",
        ),
        Message(
            "msg_11", "cnv_1", 11, null, frederick, "card", "Here's 20% off yearly with your student discount.",
            card = Card(
                type = "code", title = "Student discount", body = "20% off yearly for students.", code = "STUDENT20",
                campaignId = "cmp_1", platform = "android", buttonLabel = "Redeem",
                url = "https://play.google.com/redeem?code=STUDENT20", expiresAt = "2099-12-31T23:59:59.000Z",
            ),
            createdAt = "${DAY}09:48:00.000Z",
        ),
        Message(
            "msg_12", "cnv_1", 12, null, frederick, "card", "This one's for the iPhone app.",
            card = Card(type = "code", title = "Launch week", code = "LAUNCH-IOS", platform = "ios", available = false),
            createdAt = "${DAY}09:49:00.000Z",
        ),
        Message(
            "msg_13", "cnv_1", 13, null, frederick, "card", "",
            card = Card(type = "code", title = "Summer sale", code = "SUMMER25", platform = "android", expiresAt = "2026-08-31T23:59:59.000Z"),
            createdAt = "${DAY}09:50:00.000Z",
        ),
        Message(
            "msg_14", "cnv_1", 14, null, frederick, "card", "",
            card = Card(type = "app", title = "Stacked", body = "Flashcards that plan your revision for you.", installed = true),
            createdAt = "${DAY}09:51:00.000Z",
        ),
    )

    val config = SdkConfig(appId = "app_123", appName = "Clucky", teamDisplay = TeamDisplay(showNames = true, showPhotos = false))

    val state = ChatState(messages = messages, loaded = true, agentReadSeq = 7, config = config, agentTyping = true, agentTypingName = "Frederick")

    val paywall = SupportPaywall("yearly_offer", "Yearly 40% off", listOf(SupportPaywallProduct("pro_yearly", "$29.99", "P1Y", "P1W")))

    val failed = OutboxEntry("c8", "Also, can I export my notes?", createdAt = 0, attempts = 5, state = OutboxState.FAILED, lastError = "network")
}

@Composable
private fun PreviewChat(
    state: ChatState,
    dark: Boolean,
    appearance: SupportAppearance = SupportAppearance(),
    hint: String? = null,
    trayOpen: Boolean = false,
) {
    val resolved = remember(appearance) { AppearanceResolver.resolve(null, appearance, "en") }
    SupportTheme(resolved, dark) {
        ChatContent(
            state = state,
            appearance = resolved,
            env = BubbleEnv(
                customerLanguage = "en", showNames = true, showPhotos = false, appName = "Clucky",
                availablePaywalls = setOf("yearly_offer"), hasPaywallHandler = true, imageLoader = null,
                onReview = {}, onOffer = {}, onOpenImage = { _, _ -> }, onOpenFile = {}, onRetry = {}, onDiscard = {},
                paywall = { id -> if (id == "yearly_offer") PreviewData.paywall else null },
            ),
            draft = "",
            staged = emptyList(),
            preparing = 0,
            composer = ComposerActions({}, {}, {}, {}, {}, {}),
            onLoadEarlier = {},
            onClose = {},
            hint = hint,
            trayOpen = trayOpen,
            onTrayOpenChange = {},
        )
    }
}

@Preview(name = "Chat · light", showBackground = true, widthDp = 380, heightDp = 800)
@Composable
private fun ChatLightPreview() = PreviewChat(PreviewData.state, dark = false)

@Preview(name = "Chat · dark", showBackground = true, widthDp = 380, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChatDarkPreview() = PreviewChat(PreviewData.state, dark = true)

@Preview(name = "Empty · welcome", showBackground = true, widthDp = 380, heightDp = 640)
@Composable
private fun EmptyPreview() = PreviewChat(ChatState(loaded = true), dark = false)

@Preview(name = "Failed send · offline", showBackground = true, widthDp = 380, heightDp = 640)
@Composable
private fun FailedPreview() = PreviewChat(
    PreviewData.state.copy(pending = listOf(PreviewData.failed), connection = ConnectionStatus.OFFLINE, agentTyping = false),
    dark = false,
)

@Preview(name = "Blocked", showBackground = true, widthDp = 380, heightDp = 520)
@Composable
private fun BlockedPreview() = PreviewChat(PreviewData.state.copy(restriction = Restriction.BLOCKED, agentTyping = false), dark = true)

@Preview(name = "Attachment tray", showBackground = true, widthDp = 380, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TrayPreview() = PreviewChat(PreviewData.state.copy(agentTyping = false), dark = true, trayOpen = true, hint = "You can attach up to 5 files.")

@Preview(name = "Promo cards · light", showBackground = true, widthDp = 380, heightDp = 1400)
@Composable
private fun PromoCardsLightPreview() = PreviewChat(ChatState(messages = PreviewData.promoMessages, loaded = true, config = PreviewData.config), dark = false)

@Preview(name = "Promo cards · dark", showBackground = true, widthDp = 380, heightDp = 1400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PromoCardsDarkPreview() = PreviewChat(ChatState(messages = PreviewData.promoMessages, loaded = true, config = PreviewData.config), dark = true)

@Preview(name = "Promo cards · RTL · large font", showBackground = true, widthDp = 380, heightDp = 1800, locale = "ar", fontScale = 1.5f)
@Composable
private fun PromoCardsRtlPreview() = PreviewChat(ChatState(messages = PreviewData.promoMessages, loaded = true, config = PreviewData.config), dark = false)

@Preview(name = "RTL · large font", showBackground = true, widthDp = 380, heightDp = 800, locale = "ar", fontScale = 1.5f)
@Composable
private fun RtlPreview() = PreviewChat(PreviewData.state.copy(agentTyping = false), dark = false)
