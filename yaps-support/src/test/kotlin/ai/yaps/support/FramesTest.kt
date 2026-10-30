package ai.yaps.support

import ai.yaps.support.internal.protocol.FrameCodec
import ai.yaps.support.internal.protocol.ServerFrame
import ai.yaps.support.internal.protocol.WireJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FramesTest {

    private val lines = Fixtures.read("server-frames.jsonl").lines().filter { it.isNotBlank() }

    @Test
    fun everyKnownFrameDecodesAndUnknownFramesAreSkipped() {
        val frames = lines.map { FrameCodec.decode(it) }
        assertEquals(8, frames.size)
        assertEquals(ServerFrame.Welcome("cnv_1", 2), frames[0])
        assertEquals("msg_2", (frames[1] as ServerFrame.Msg).message.id)
        val ack = frames[2] as ServerFrame.Ack
        assertEquals("01JD8Z6Q0000000000000000AA", ack.cid)
        assertEquals("01JD8Z6Q0000000000000000AA", ack.message.clientId)
        assertEquals(ServerFrame.State(agentTyping = true, agentReadSeq = null, customerReadSeq = null), frames[3])
        assertEquals(ServerFrame.State(agentTyping = null, agentReadSeq = 1, customerReadSeq = null), frames[4])
        assertEquals(ServerFrame.Err("rate_limited", "01JD8Z6Q0000000000000000AC", null), frames[5])
        assertEquals(ServerFrame.Pong, frames[6])
        assertNull("unknown frame type must be skipped", frames[7])
    }

    @Test
    fun unknownFieldsInsideKnownFramesAreIgnored() {
        val frame = FrameCodec.decode(
            """{"t":"msg","extra":{"a":1},"m":{"id":"msg_7","seq":7,"author":{"type":"agent","mood":"happy"},"kind":"text","text":"hi","attachments":[],"createdAt":"2026-10-30T09:41:00.000Z","reactions":[]}}""",
        )
        assertEquals("msg_7", (frame as ServerFrame.Msg).message.id)
    }

    @Test
    fun unknownEnumValuesDoNotBreakDecoding() {
        val frame = FrameCodec.decode(
            """{"t":"upd","m":{"id":"msg_8","seq":8,"author":{"type":"bot"},"kind":"poll","text":"?","attachments":[{"id":"upl_9","kind":"video","contentType":"video/mp4","url":"https://x","size":1}],"createdAt":"x"}}""",
        ) as ServerFrame.Upd
        assertEquals("bot", frame.message.author.type)
        assertEquals("poll", frame.message.kind)
        assertEquals("video", frame.message.attachments.single().kind)
    }

    @Test
    fun malformedFramesAreSkippedNotThrown() {
        listOf("", "not json", "[]", "{}", """{"t":"msg"}""", """{"t":"ack","cid":"x"}""", """{"t":42}""").forEach {
            assertNull("frame '$it'", FrameCodec.decode(it))
        }
    }

    @Test
    fun clientFramesMatchTheProtocol() {
        fun obj(s: String) = WireJson.parseToJsonElement(s).jsonObject
        assertEquals("hello", obj(FrameCodec.hello(42))["t"]!!.jsonPrimitive.content)
        assertEquals("42", obj(FrameCodec.hello(42))["after"]!!.jsonPrimitive.content)
        val send = obj(FrameCodec.send("01ABC", "hi", listOf("upl_1")))
        assertEquals("send", send["t"]!!.jsonPrimitive.content)
        assertEquals("01ABC", send["cid"]!!.jsonPrimitive.content)
        assertTrue(send.containsKey("att"))
        val textOnly = obj(FrameCodec.send("01ABC", "hi", emptyList()))
        assertTrue(!textOnly.containsKey("att"))
        assertEquals("""{"t":"ping"}""", FrameCodec.ping())
        assertEquals("true", obj(FrameCodec.typing(true))["on"]!!.jsonPrimitive.content)
        assertEquals("9", obj(FrameCodec.read(9))["seq"]!!.jsonPrimitive.content)
    }
}
