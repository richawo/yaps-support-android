package ai.yaps.support.sample

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * An in-process stand-in for https://support.yaps.ai that speaks PROTOCOL.md v1 (REST, uploads,
 * appearance with ETag, and the chat WebSocket). It lets the sample exercise the real SDK end to
 * end with no account: send a message and "Frederick" types, then answers.
 */
class FakeBackend {
    private val server = MockWebServer()
    private val messages = CopyOnWriteArrayList<JSONObject>()
    private val seq = AtomicLong(0)
    private val uploads = ConcurrentHashMap<String, ByteArray>()
    private val uploadMeta = ConcurrentHashMap<String, JSONObject>()
    private val uploadIds = AtomicInteger(0)
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var agentReadSeq = 0L
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = route(request)
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        seed() // after start: attachment URLs embed the server's port

    }

    private fun now(offsetMinutes: Long = 0): String = iso.format(Date(System.currentTimeMillis() + offsetMinutes * 60_000))

    private fun seed() {
        val frederick = JSONObject().put("type", "agent").put("id", "usr_3").put("name", "Frederick")
        addMessage(customer(), "Hi! Is there a dark mode? My eyes are begging 🙏", at = now(-14))
        addMessage(frederick, "Yes! Settings → Appearance → Dark. It follows the system by default.", at = now(-13))
        addMessage(frederick, "Here's where to find it:", at = now(-13), attachments = JSONArray().put(demoImage()))
        addMessage(customer(), "Perfect, that worked. Do you have a yearly plan?", at = now(-9))
        addMessage(
            frederick, "Yearly is 40% off right now, want it?", at = now(-8), kind = "card",
            card = JSONObject().put("type", "offer").put("title", "Yearly 40% off").put("buttonLabel", "View offer")
                .put("paywallId", "yearly_offer").put("available", true),
        )
        addMessage(customer(), "I'm a student, is there a discount?", at = now(-7))
        // As the real server resolves them for an Android device: a Play redeem link, and a
        // market:// listing (the SDK falls back to the https listing without the Play Store).
        addMessage(
            frederick, "There is! Here's 20% off yearly with your student discount.", at = now(-6), kind = "card",
            card = JSONObject().put("type", "code").put("title", "Student discount").put("body", "20% off yearly for students.")
                .put("code", "STUDENT20").put("campaignId", "cmp_demo").put("platform", "android").put("buttonLabel", "Redeem")
                .put("url", "https://play.google.com/redeem?code=STUDENT20").put("expiresAt", now(60L * 24 * 60))
                .put("available", true),
        )
        addMessage(
            frederick, "And since you're studying, you might like our flashcard app too.", at = now(-5), kind = "card",
            card = JSONObject().put("type", "app").put("title", "Stacked").put("body", "Flashcards that plan your revision for you.")
                .put("buttonLabel", "Get it").put("iconUrl", "$baseUrl/v1/files/icons/${demoIcon()}").put("promotedAppId", "app_stacked")
                .put("url", "market://details?id=ai.yaps.stacked").put("installed", false).put("available", true),
        )
        addMessage(customer(), "Also, how do I change my email? And my alarm went silent this morning.", at = now(-4))
        // Link cards: a screen the app registered in SampleApp (opens through onScreen) and a web page.
        addMessage(
            frederick, "You can change your email from your profile.", at = now(-4), kind = "card",
            card = JSONObject().put("type", "link").put("buttonLabel", "Edit profile").put("linkId", "lnk_profile")
                .put("target", "screen").put("screenId", "edit_profile").put("available", true),
        )
        addMessage(
            frederick, "Our guide walks through why an alarm might stay silent.", at = now(-4), kind = "card",
            card = JSONObject().put("type", "link").put("buttonLabel", "Read the guide").put("linkId", "lnk_guide")
                .put("target", "url").put("url", "https://support.yaps.ai").put("available", true),
        )
        addMessage(customer(), "Will do, thanks Frederick! 🐓", at = now(-3))
        addMessage(
            frederick, "Glad that's sorted! If Wakely is working for you, a quick rating helps a lot.", at = now(-2), kind = "card",
            card = JSONObject().put("type", "review").put("buttonLabel", "Rate on Google Play")
                .put("url", "market://details?id=ai.yaps.support.sample").put("available", true),
        )
        agentReadSeq = seq.get()
    }

    private fun customer() = JSONObject().put("type", "customer")

    private fun addMessage(
        author: JSONObject,
        text: String,
        at: String = now(),
        kind: String = "text",
        card: JSONObject? = null,
        attachments: JSONArray = JSONArray(),
        clientId: String? = null,
    ): JSONObject {
        val s = seq.incrementAndGet()
        val m = JSONObject()
            .put("id", "msg_$s").put("conversationId", "cnv_demo").put("seq", s)
            .put("author", author).put("kind", kind).put("text", text)
            .put("attachments", attachments).put("createdAt", at)
            .put("futureField", "ignored by the SDK")
        if (card != null) m.put("card", card)
        if (clientId != null) m.put("clientId", clientId)
        messages += m
        return m
    }

    private fun demoImage(): JSONObject {
        val id = "upl_demo"
        if (!uploads.containsKey(id)) {
            val bmp = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint().apply { shader = LinearGradient(0f, 0f, 900f, 1200f, 0xFFFFE9DA.toInt(), 0xFFFF7A45.toInt(), Shader.TileMode.CLAMP) }
            c.drawRect(0f, 0f, 900f, 1200f, p)
            val card = Paint().apply { color = 0xE6FFFFFF.toInt(); isAntiAlias = true }
            c.drawRoundRect(90f, 300f, 810f, 900f, 48f, 48f, card)
            val text = Paint().apply { color = 0xFF111113.toInt(); textSize = 64f; isAntiAlias = true; isFakeBoldText = true }
            c.drawText("Appearance", 150f, 420f, text)
            val row = Paint().apply { color = 0xFF111113.toInt(); textSize = 48f; isAntiAlias = true }
            listOf("Light", "Dark  ✓", "System").forEachIndexed { i, label -> c.drawText(label, 150f, 560f + i * 110f, row) }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            uploads[id] = out.toByteArray()
        }
        return attachmentJson(id, "image", "image/jpeg", uploads[id]!!.size.toLong(), 900, 1200, "appearance.jpg")
    }

    /** A stand-in app icon for the promo card: a stack of cards on a gradient. Returns its file name. */
    private fun demoIcon(): String {
        val id = "stacked.png"
        if (!uploads.containsKey(id)) {
            val size = 192f
            val bmp = Bitmap.createBitmap(size.toInt(), size.toInt(), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val bg = Paint().apply { shader = LinearGradient(0f, 0f, size, size, 0xFF6C8CFF.toInt(), 0xFF3A4BD8.toInt(), Shader.TileMode.CLAMP) }
            c.drawRect(0f, 0f, size, size, bg)
            val back = Paint().apply { color = 0x66FFFFFF; isAntiAlias = true }
            c.drawRoundRect(58f, 40f, 150f, 128f, 14f, 14f, back)
            val front = Paint().apply { color = 0xFFFFFFFF.toInt(); isAntiAlias = true }
            c.drawRoundRect(42f, 62f, 134f, 150f, 14f, 14f, front)
            val line = Paint().apply { color = 0xFF3A4BD8.toInt(); isAntiAlias = true }
            c.drawRoundRect(58f, 92f, 118f, 100f, 4f, 4f, line)
            c.drawRoundRect(58f, 112f, 102f, 120f, 4f, 4f, line)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            uploads[id] = out.toByteArray()
        }
        return id
    }

    private fun attachmentJson(id: String, kind: String, type: String, size: Long, w: Int?, h: Int?, name: String?): JSONObject =
        JSONObject().put("id", id).put("kind", kind).put("contentType", type)
            .put("url", "$baseUrl/v1/files/$id?sig=demo").put("thumbnailUrl", "$baseUrl/v1/files/$id?w=400&sig=demo")
            .put("size", size).apply {
                if (w != null) put("width", w)
                if (h != null) put("height", h)
                if (name != null) put("filename", name)
            }

    private fun config() = JSONObject()
        .put("appId", "app_demo").put("appName", "Wakely").put("verifiedOnly", false).put("blocked", false)
        .put("trackedEvents", JSONArray().put("paywall_declined").put("onboarding_completed"))
        .put("translation", JSONObject().put("enabled", true))
        .put("teamDisplay", JSONObject().put("showNames", true).put("showPhotos", true))
        .put("uploads", JSONObject().put("images", true).put("files", true))
        .put("pollIntervalSeconds", 30).put("appearanceEtag", APPEARANCE_ETAG)

    private fun json(body: JSONObject, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body.toString())

    private fun route(r: RecordedRequest): MockResponse {
        val path = r.requestUrl?.encodedPath.orEmpty()
        val method = r.method.orEmpty()
        return when {
            method == "POST" && path == "/v1/sdk/register" -> json(
                JSONObject().put("deviceToken", "dt_reg_demo_secret").put("registrationId", "reg_demo")
                    .put("customer", JSONObject().put("id", "cus_demo").put("verified", false)).put("config", config()),
                201,
            )
            method == "GET" && path == "/v1/sdk/appearance" ->
                if (r.getHeader("If-None-Match") == APPEARANCE_ETAG) MockResponse().setResponseCode(304)
                else json(JSONObject(APPEARANCE)).setHeader("ETag", APPEARANCE_ETAG)
            method == "PATCH" && path == "/v1/sdk/device" -> json(
                JSONObject().put("customer", JSONObject().put("id", "cus_demo").put("verified", false)).put("config", config()),
            )
            method == "GET" && path == "/v1/sdk/conversation" -> json(
                JSONObject()
                    .put("conversation", JSONObject().put("id", "cnv_demo").put("status", "open").put("lastSeq", seq.get())
                        .put("customerReadSeq", seq.get()).put("agentReadSeq", agentReadSeq).put("unread", 0).put("createdAt", now(-14)))
                    .put("messages", JSONArray(messages.takeLast(50))).put("hasMore", messages.size > 50),
            )
            method == "GET" && path == "/v1/sdk/messages" -> {
                val after = r.requestUrl?.queryParameter("after")?.toLongOrNull()
                val before = r.requestUrl?.queryParameter("before")?.toLongOrNull()
                val list = when {
                    after != null -> messages.filter { it.getLong("seq") > after }
                    before != null -> messages.filter { it.getLong("seq") < before }.takeLast(50)
                    else -> messages.takeLast(50)
                }
                json(JSONObject().put("messages", JSONArray(list)).put("hasMore", false))
            }
            method == "POST" && path == "/v1/sdk/messages" -> {
                val body = JSONObject(r.body.readUtf8())
                json(JSONObject().put("message", receive(body.getString("clientId"), body.optString("text"), body.optJSONArray("attachments"))), 201)
            }
            method == "GET" && path == "/v1/sdk/unread" -> json(JSONObject().put("count", 0).put("conversationId", "cnv_demo"))
            method == "POST" && path == "/v1/sdk/events" -> json(JSONObject().put("openChat", false))
            method == "POST" && path == "/v1/sdk/uploads" -> {
                val body = JSONObject(r.body.readUtf8())
                val id = "upl_${uploadIds.incrementAndGet()}"
                uploadMeta[id] = body
                json(JSONObject().put("uploadId", id).put("uploadUrl", "$baseUrl/v1/uploads/$id?sig=demo").put("expiresAt", now(60)), 201)
            }
            method == "PUT" && path.startsWith("/v1/uploads/") -> {
                val id = path.substringAfterLast('/')
                val bytes = r.body.readByteArray()
                uploads[id] = bytes
                val meta = uploadMeta[id] ?: JSONObject()
                val type = meta.optString("contentType", "application/octet-stream")
                val kind = if (type.startsWith("image/")) "image" else "file"
                val w = meta.optInt("width").takeIf { it > 0 }
                val h = meta.optInt("height").takeIf { it > 0 }
                json(JSONObject().put("attachment", attachmentJson(id, kind, type, bytes.size.toLong(), w, h, meta.optString("filename").ifEmpty { null })))
            }
            method == "GET" && path.startsWith("/v1/files/") -> {
                val bytes = uploads[path.substringAfterLast('/')] ?: return MockResponse().setResponseCode(404)
                MockResponse().setHeader("Content-Type", "image/jpeg").setBody(okio.Buffer().write(bytes))
            }
            path == "/v1/sdk/ws" -> MockResponse().withWebSocketUpgrade(socketListener)
            method == "POST" || method == "DELETE" -> MockResponse().setResponseCode(204)
            else -> json(JSONObject().put("error", JSONObject().put("code", "not_found").put("message", path)), 404)
        }
    }

    /** Stores a customer message (deduplicated on clientId, like the real server) and schedules a reply. */
    private fun receive(clientId: String, text: String, attachmentIds: JSONArray?): JSONObject {
        messages.firstOrNull { it.optString("clientId") == clientId }?.let { return it }
        val atts = JSONArray()
        for (i in 0 until (attachmentIds?.length() ?: 0)) {
            val id = attachmentIds!!.getString(i)
            val meta = uploadMeta[id] ?: JSONObject()
            val type = meta.optString("contentType", "image/jpeg")
            atts.put(attachmentJson(id, if (type.startsWith("image/")) "image" else "file", type, (uploads[id]?.size ?: 0).toLong(),
                meta.optInt("width").takeIf { it > 0 }, meta.optInt("height").takeIf { it > 0 }, meta.optString("filename").ifEmpty { null }))
        }
        val m = addMessage(customer(), text, attachments = atts, clientId = clientId)
        scheduleReply(m.getLong("seq"), text, atts.length() > 0)
        return m
    }

    private fun scheduleReply(customerSeq: Long, text: String, hadAttachments: Boolean) {
        timer.schedule({
            agentReadSeq = customerSeq
            broadcast(JSONObject().put("t", "state").put("agentReadSeq", customerSeq))
            broadcast(JSONObject().put("t", "state").put("agentTyping", true))
        }, 900, TimeUnit.MILLISECONDS)
        timer.schedule({
            val reply = when {
                hadAttachments -> "Thanks for the screenshot, that helps a lot. Looking into it now."
                text.contains("?") -> "Good question! Let me check with the team and get back to you shortly."
                else -> "Got it, thanks! Anything else I can help with?"
            }
            val frederick = JSONObject().put("type", "agent").put("id", "usr_3").put("name", "Frederick")
            val m = addMessage(frederick, reply)
            broadcast(JSONObject().put("t", "state").put("agentTyping", false))
            broadcast(JSONObject().put("t", "msg").put("m", m))
        }, 3_200, TimeUnit.MILLISECONDS)
    }

    private fun broadcast(frame: JSONObject) {
        sockets.forEach { it.send(frame.toString()) }
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sockets += webSocket
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = JSONObject(text)
            when (frame.optString("t")) {
                "hello" -> {
                    val after = frame.optLong("after")
                    webSocket.send(JSONObject().put("t", "welcome").put("conversationId", "cnv_demo").put("lastSeq", seq.get()).toString())
                    messages.filter { it.getLong("seq") > after }.forEach { webSocket.send(JSONObject().put("t", "msg").put("m", it).toString()) }
                    webSocket.send(JSONObject().put("t", "state").put("agentReadSeq", agentReadSeq).toString())
                    webSocket.send("""{"t":"some_future_frame","x":1}""") // the SDK must ignore this
                }
                "send" -> {
                    val m = receive(frame.getString("cid"), frame.optString("text"), frame.optJSONArray("att"))
                    webSocket.send(JSONObject().put("t", "ack").put("cid", frame.getString("cid")).put("m", m).toString())
                }
                "ping" -> webSocket.send("""{"t":"pong"}""")
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            sockets -= webSocket
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            sockets -= webSocket
        }
    }

    private companion object {
        const val APPEARANCE_ETAG = "W/\"demo1\""
        val APPEARANCE = """
            {"colors":{"accent":{"light":"#FF6B35","dark":"#FF8A5B"},"onAccent":{"light":"#FFFFFF","dark":"#1A0A00"},
             "background":{"light":"#FFFFFF","dark":"#0B0B0D"},"incomingBackground":{"light":"#F2F2F4","dark":"#1C1C1F"},
             "incomingForeground":{"light":"#111113","dark":"#EDEDEF"}},
             "title":{"en":"Wakely support","es":"Soporte de Wakely"},"welcomeTitle":{"en":"How can we help?"},
             "welcomeText":{"en":"We usually reply within a few hours."},"colorScheme":"system","fontStyle":"default",
             "cornerStyle":"round","showCloseButton":true,"welcomeIconUrl":null,"fadeOlderMessages":false}
        """.trimIndent()
    }
}
