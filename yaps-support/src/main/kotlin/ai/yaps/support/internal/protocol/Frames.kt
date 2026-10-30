package ai.yaps.support.internal.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

/** Server → client socket frames (PROTOCOL §4). */
internal sealed interface ServerFrame {
    data class Welcome(val conversationId: String?, val lastSeq: Long) : ServerFrame
    data class Msg(val message: Message) : ServerFrame
    data class Upd(val message: Message) : ServerFrame
    data class Ack(val cid: String, val message: Message) : ServerFrame
    data class State(
        val agentTyping: Boolean?,
        val agentReadSeq: Long?,
        val customerReadSeq: Long?,
    ) : ServerFrame
    data class Err(val code: String, val cid: String?, val message: String?) : ServerFrame
    data object Pong : ServerFrame
}

internal object FrameCodec {

    /**
     * Decodes one text frame. Returns `null` for anything this SDK does not understand: unknown
     * `t`, malformed JSON, or a known frame missing a required field. Callers simply skip nulls,
     * which is what PROTOCOL §9 requires ("clients must ignore unknown … frames").
     */
    fun decode(text: String): ServerFrame? = try {
        val obj = WireJson.parseToJsonElement(text).jsonObject
        when ((obj["t"] as? JsonPrimitive)?.content) {
            "welcome" -> ServerFrame.Welcome(
                conversationId = obj.string("conversationId"),
                lastSeq = obj.long("lastSeq") ?: 0L,
            )
            "msg" -> obj.message("m")?.let { ServerFrame.Msg(it) }
            "upd" -> obj.message("m")?.let { ServerFrame.Upd(it) }
            "ack" -> {
                val cid = obj.string("cid")
                val m = obj.message("m")
                if (cid != null && m != null) ServerFrame.Ack(cid, m) else null
            }
            "state" -> ServerFrame.State(
                agentTyping = (obj["agentTyping"] as? JsonPrimitive)?.booleanOrNull,
                agentReadSeq = obj.long("agentReadSeq"),
                customerReadSeq = obj.long("customerReadSeq"),
            )
            "err" -> ServerFrame.Err(
                code = obj.string("code") ?: "server_error",
                cid = obj.string("cid"),
                message = obj.string("message"),
            )
            "pong" -> ServerFrame.Pong
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    fun hello(after: Long): String = buildJsonObject {
        put("t", "hello")
        put("after", after)
    }.toString()

    fun send(cid: String, text: String?, attachmentIds: List<String>): String = buildJsonObject {
        put("t", "send")
        put("cid", cid)
        if (!text.isNullOrEmpty()) put("text", text)
        if (attachmentIds.isNotEmpty()) putJsonArray("att") { attachmentIds.forEach { add(it) } }
    }.toString()

    fun read(seq: Long): String = buildJsonObject {
        put("t", "read")
        put("seq", seq)
    }.toString()

    fun typing(on: Boolean): String = buildJsonObject {
        put("t", "typing")
        put("on", on)
    }.toString()

    fun ping(): String = """{"t":"ping"}"""

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.content.toDoubleOrNull()?.toLong() }

    private fun JsonObject.message(key: String): Message? =
        this[key]?.let { runCatching { WireJson.decodeFromJsonElement(Message.serializer(), it) }.getOrNull() }
}
