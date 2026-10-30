package ai.yaps.support.internal.core

import ai.yaps.support.internal.outbox.OutboxEntry
import ai.yaps.support.internal.protocol.Message
import ai.yaps.support.internal.protocol.SdkConfig

/** What the connection banner shows (mirrors Reception's states, plus the polling fallback). */
internal enum class ConnectionStatus {
    /** Nothing to show: unused chat, or connected without a prior interruption. */
    HIDDEN,
    OFFLINE,
    CONNECTING,

    /** Shown briefly after recovering from an interruption. */
    CONNECTED,

    /** The socket is refused, so the chat polls `messages?after=` instead. */
    PAUSED,
}

internal enum class Restriction { NONE, BLOCKED, VERIFICATION_REQUIRED, DISABLED }

internal data class ChatState(
    val messages: List<Message> = emptyList(),
    val pending: List<OutboxEntry> = emptyList(),
    val hasMore: Boolean = false,
    val loadingEarlier: Boolean = false,
    val loaded: Boolean = false,
    val connection: ConnectionStatus = ConnectionStatus.HIDDEN,
    val agentTyping: Boolean = false,
    val agentTypingName: String? = null,
    /** Agents have read customer messages up to this seq (read ticks). */
    val agentReadSeq: Long = 0,
    val restriction: Restriction = Restriction.NONE,
    val config: SdkConfig? = null,
    val uploadProgress: Map<String, Float> = emptyMap(),
    val customerLanguage: String = "en",
)
