package ai.yaps.support.internal.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire types for protocol v1 (protocol/PROTOCOL.md is the source of truth).
 *
 * Decoding rules that keep old SDKs working against newer servers (PROTOCOL §9):
 * - unknown fields are ignored ([WireJson] has `ignoreUnknownKeys`);
 * - enum-like fields (`kind`, `author.type`, `card.type`, …) are plain strings, so a new value
 *   never fails decoding; the UI decides what to do with values it does not know;
 * - everything the server may omit has a default.
 */
internal val WireJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
    isLenient = true
}

@Serializable
internal data class ApiErrorBody(val error: ApiErrorDetail? = null)

@Serializable
internal data class ApiErrorDetail(
    val code: String = "server_error",
    val message: String = "",
    val retryAfter: Double? = null,
)

@Serializable
internal data class DeviceInfo(
    val model: String? = null,
    val osVersion: String? = null,
    val appVersion: String? = null,
    val appBuild: String? = null,
    val bundleId: String? = null,
    val locale: String? = null,
    val language: String? = null,
    val timeZone: String? = null,
)

@Serializable
internal data class IdentityInput(
    val userId: String? = null,
    val name: String? = null,
    val email: String? = null,
    val token: String? = null,
)

@Serializable
internal data class PushInput(
    val token: String,
    val provider: String,
    val environment: String? = null,
)

@Serializable
internal data class PaywallProductWire(
    val id: String,
    val price: String? = null,
    val period: String? = null,
    val trial: String? = null,
)

@Serializable
internal data class PaywallWire(
    val id: String,
    val title: String,
    val products: List<PaywallProductWire> = emptyList(),
)

@Serializable
internal data class Author(
    val type: String = AuthorType.SYSTEM,
    val id: String? = null,
    val name: String? = null,
    val avatarUrl: String? = null,
)

internal object AuthorType {
    const val CUSTOMER = "customer"
    const val AGENT = "agent"
    const val SYSTEM = "system"
}

internal object MessageKind {
    const val TEXT = "text"
    const val CARD = "card"
    const val EVENT = "event"
}

internal object CardType {
    const val REVIEW = "review"
    const val OFFER = "offer"
    const val APP = "app"
    const val CODE = "code"
}

/** Where a code card's code can be redeemed (`card.platform`). Plain strings, like every enum-like field. */
internal object CodePlatform {
    const val IOS = "ios"
    const val ANDROID = "android"
    const val WEB = "web"
    const val ANY = "any"
}

/**
 * PROTOCOL §5 "Card". The server resolves `url` for this device before sending: `market://` for
 * review and app promo cards, `https://play.google.com/redeem?code=…` for Play codes.
 */
@Serializable
internal data class Card(
    val type: String = "",
    val title: String? = null,
    /** Short supporting line (app pitch, campaign description). */
    val body: String? = null,
    val buttonLabel: String? = null,
    val url: String? = null,
    val paywallId: String? = null,
    /** App promo: the promoted app. */
    val promotedAppId: String? = null,
    val appStoreId: String? = null,
    val iconUrl: String? = null,
    /** App promo: this customer already uses the promoted app. */
    val installed: Boolean = false,
    /** Code card. */
    val code: String? = null,
    val campaignId: String? = null,
    val platform: String? = null,
    val expiresAt: String? = null,
    val available: Boolean = true,
    val openedAt: String? = null,
)

@Serializable
internal data class Attachment(
    val id: String,
    val kind: String = "file",
    val contentType: String = "application/octet-stream",
    val url: String = "",
    val thumbnailUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val size: Long = 0,
    val filename: String? = null,
) {
    val isImage: Boolean get() = kind == "image"
}

@Serializable
internal data class Translation(val language: String = "", val text: String = "")

@Serializable
internal data class Message(
    val id: String,
    val conversationId: String = "",
    val seq: Long = 0,
    val clientId: String? = null,
    val author: Author = Author(),
    val kind: String = MessageKind.TEXT,
    val text: String = "",
    val language: String? = null,
    val translation: Translation? = null,
    val attachments: List<Attachment> = emptyList(),
    val card: Card? = null,
    val createdAt: String = "",
    val status: String? = null,
) {
    val isFromCustomer: Boolean get() = author.type == AuthorType.CUSTOMER
}

@Serializable
internal data class SdkConversation(
    val id: String,
    val status: String = "open",
    val lastSeq: Long = 0,
    val customerReadSeq: Long = 0,
    val agentReadSeq: Long = 0,
    val unread: Int = 0,
    val createdAt: String? = null,
)

@Serializable
internal data class ColorPair(val light: String? = null, val dark: String? = null)

@Serializable
internal data class AppearanceColors(
    val accent: ColorPair? = null,
    val onAccent: ColorPair? = null,
    val background: ColorPair? = null,
    val incomingBackground: ColorPair? = null,
    val incomingForeground: ColorPair? = null,
)

@Serializable
internal data class Appearance(
    val colors: AppearanceColors? = null,
    val title: Map<String, String>? = null,
    val welcomeTitle: Map<String, String>? = null,
    val welcomeText: Map<String, String>? = null,
    val colorScheme: String? = null,
    val fontStyle: String? = null,
    val cornerStyle: String? = null,
    val showCloseButton: Boolean? = null,
    val welcomeIconUrl: String? = null,
    val fadeOlderMessages: Boolean? = null,
    val updatedAt: String? = null,
)

@Serializable
internal data class TranslationConfig(val enabled: Boolean = false, val customerLanguage: String? = null)

@Serializable
internal data class TeamDisplay(val showNames: Boolean = false, val showPhotos: Boolean = false)

@Serializable
internal data class UploadsConfig(val images: Boolean = true, val files: Boolean = true)

@Serializable
internal data class SdkConfig(
    val appId: String = "",
    val appName: String = "",
    val verifiedOnly: Boolean = false,
    val blocked: Boolean = false,
    val trackedEvents: List<String> = emptyList(),
    val translation: TranslationConfig = TranslationConfig(),
    val teamDisplay: TeamDisplay = TeamDisplay(),
    val uploads: UploadsConfig = UploadsConfig(),
    val pollIntervalSeconds: Int = 60,
    val appearanceEtag: String? = null,
)

@Serializable
internal data class SdkCustomer(
    val id: String = "",
    val verified: Boolean = false,
    val userId: String? = null,
    val name: String? = null,
    val email: String? = null,
)

@Serializable
internal data class RegisterRequest(
    val platform: String,
    val device: DeviceInfo,
    val identity: IdentityInput? = null,
    val metadata: Map<String, String>? = null,
    val push: PushInput? = null,
)

@Serializable
internal data class RegisterResponse(
    val deviceToken: String,
    val registrationId: String = "",
    val customer: SdkCustomer = SdkCustomer(),
    val config: SdkConfig = SdkConfig(),
)

@Serializable
internal data class DeviceUpdateResponse(
    val customer: SdkCustomer = SdkCustomer(),
    val config: SdkConfig = SdkConfig(),
)

@Serializable
internal data class ConversationResponse(
    val conversation: SdkConversation? = null,
    val messages: List<Message> = emptyList(),
    val hasMore: Boolean = false,
)

@Serializable
internal data class MessagesResponse(
    val messages: List<Message> = emptyList(),
    val hasMore: Boolean = false,
)

@Serializable
internal data class SendMessageRequest(
    val clientId: String,
    val text: String? = null,
    val attachments: List<String>? = null,
)

@Serializable
internal data class SendMessageResponse(val message: Message)

@Serializable
internal data class UnreadResponse(val count: Int = 0, val conversationId: String? = null)

@Serializable
internal data class UploadRequest(
    val contentType: String,
    val size: Long,
    val width: Int? = null,
    val height: Int? = null,
    val filename: String? = null,
)

@Serializable
internal data class UploadResponse(
    val uploadId: String,
    val uploadUrl: String,
    val expiresAt: String? = null,
)

@Serializable
internal data class UploadedAttachmentResponse(val attachment: Attachment)

@Serializable
internal data class TrackRequest(val name: String, val opensChat: Boolean)

@Serializable
internal data class TrackResponse(val openChat: Boolean = false)
