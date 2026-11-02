package ai.yaps.support

import ai.yaps.support.internal.core.CardActionName
import ai.yaps.support.internal.net.ApiClient
import ai.yaps.support.internal.net.ApiException
import ai.yaps.support.internal.net.AppearanceResult
import ai.yaps.support.internal.protocol.DeviceInfo
import ai.yaps.support.internal.protocol.RegisterRequest
import ai.yaps.support.internal.protocol.SendMessageRequest
import ai.yaps.support.ui.parseIsoMillis
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ApiClientTest {
    private val server = MockWebServer()
    private var token: String? = null
    private lateinit var api: ApiClient

    @Before
    fun setUp() {
        server.start()
        api = ApiClient(server.url("/").toString(), "app_123", "android/1.0.0", OkHttpClient()) { token }
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun registerSendsAppAndSdkHeadersWithoutBearer() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody(Fixtures.read("register-response.json")))
        val r = api.register(RegisterRequest("android", DeviceInfo(language = "es")))
        assertEquals("dt_reg_8f2k_example", r.deviceToken)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/sdk/register", req.path)
        assertEquals("app_123", req.getHeader("X-Support-App"))
        assertEquals("android/1.0.0", req.getHeader("X-Support-SDK"))
        assertNull(req.getHeader("Authorization"))
        assertTrue(req.body.readUtf8().contains("\"platform\":\"android\""))
    }

    @Test
    fun authedCallsCarryTheDeviceToken() = runBlocking {
        token = "dt_reg_x_secret"
        server.enqueue(MockResponse().setBody(Fixtures.read("conversation-response.json")))
        val r = api.conversation()
        assertEquals(2, r.messages.size)
        assertEquals("Bearer dt_reg_x_secret", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun sendOmitsEmptyFields() = runBlocking {
        token = "t"
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"message":${Fixtures.read("message-text.json")}}"""))
        api.sendMessage(SendMessageRequest("01JD8Z6Q0000000000000000AA", "hi", null))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"clientId\":\"01JD8Z6Q0000000000000000AA\""))
        assertFalse(body.contains("attachments"))
    }

    @Test
    fun errorsDecodeToProtocolCodes() = runBlocking {
        token = "t"
        server.enqueue(MockResponse().setResponseCode(429).setBody(Fixtures.read("error-rate-limited.json")))
        try {
            api.unread()
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(429, e.status)
            assertEquals("rate_limited", e.code)
            assertEquals(5.0, e.retryAfterSeconds!!, 0.0)
            assertTrue(e.isRetryable)
        }
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":"blocked","message":"x"}}"""))
        val blocked = runCatching { api.unread() }.exceptionOrNull() as ApiException
        assertEquals("blocked", blocked.code)
        assertFalse(blocked.isRetryable)
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"))
        val gateway = runCatching { api.unread() }.exceptionOrNull() as ApiException
        assertEquals("server_error", gateway.code)
        assertTrue(gateway.isRetryable)
    }

    @Test
    fun cardActionReceiptsPostOpenAndCopy() = runBlocking {
        token = "t"
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        api.action("msg_8", CardActionName.COPY)
        api.action("msg_8", CardActionName.OPEN)
        api.action("msg_7")
        for ((messageId, action) in listOf("msg_8" to "copy", "msg_8" to "open", "msg_7" to "open")) {
            val req = server.takeRequest()
            assertEquals("POST", req.method)
            assertEquals("/v1/sdk/actions/$messageId", req.path)
            assertEquals("""{"action":"$action"}""", req.body.readUtf8())
            assertEquals("Bearer t", req.getHeader("Authorization"))
        }
    }

    @Test
    fun appearanceHonoursEtag() = runBlocking {
        server.enqueue(MockResponse().setBody(Fixtures.read("appearance.json")).setHeader("ETag", "W/\"a1\""))
        val fresh = api.appearance(null) as AppearanceResult.Fresh
        assertEquals("W/\"a1\"", fresh.etag)
        assertNull(server.takeRequest().getHeader("If-None-Match"))
        server.enqueue(MockResponse().setResponseCode(304))
        assertEquals(AppearanceResult.NotModified, api.appearance("W/\"a1\""))
        assertEquals("W/\"a1\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun socketUrlCarriesTheTokenInTheQuery() {
        val url = api.webSocketUrl("dt_reg_x_secret")
        assertTrue(url, url.contains("/v1/sdk/ws?token=dt_reg_x_secret"))
    }

    @Test
    fun isoTimestampsParse() {
        assertEquals(1793353260000L, parseIsoMillis("2026-10-30T09:41:00.000Z"))
        assertEquals(1793353260000L, parseIsoMillis("2026-10-30T10:41:00+01:00"))
        assertNull(parseIsoMillis("yesterday"))
    }

    @Test
    fun reviewPromptReadsAndSendsActions() = runBlocking {
        token = "dt_reg_x_secret"
        server.enqueue(
            MockResponse().setBody(
                """{"prompt":{"milestone":100,"count":104,"title":"Enjoying Yaps?","body":"A quick review helps.","buttonLabel":"Leave a rating","url":"market://details?id=ai.yaps.app","shown":false}}""",
            ),
        )
        val due = api.reviewPrompt().prompt!!
        assertEquals(100, due.milestone)
        assertEquals("market://details?id=ai.yaps.app", due.url)
        assertFalse(due.shown)
        assertEquals("/v1/sdk/review-prompt", server.takeRequest().path)

        server.enqueue(MockResponse().setBody("""{"claimed":true}"""))
        assertEquals(true, api.reviewPromptAction("shown", 100).claimed)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("""{"action":"shown","milestone":100}""", req.body.readUtf8())

        server.enqueue(MockResponse().setBody("""{"prompt":null}"""))
        assertNull(api.reviewPrompt().prompt)
    }

    @Test
    fun ratingSendsTheScoreWithTheCardAction() = runBlocking {
        token = "dt_reg_x_secret"
        server.enqueue(MockResponse().setResponseCode(204))
        api.rate("msg_r", 4)
        val req = server.takeRequest()
        assertEquals("/v1/sdk/actions/msg_r", req.path)
        assertEquals("""{"action":"rate","score":4}""", req.body.readUtf8())
    }
}
