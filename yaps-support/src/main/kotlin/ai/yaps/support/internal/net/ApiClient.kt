package ai.yaps.support.internal.net

import ai.yaps.support.internal.protocol.ApiErrorBody
import ai.yaps.support.internal.protocol.Appearance
import ai.yaps.support.internal.protocol.Attachment
import ai.yaps.support.internal.protocol.ConversationResponse
import ai.yaps.support.internal.protocol.DeviceUpdateResponse
import ai.yaps.support.internal.protocol.Message
import ai.yaps.support.internal.protocol.MessagesResponse
import ai.yaps.support.internal.protocol.RegisterRequest
import ai.yaps.support.internal.protocol.RegisterResponse
import ai.yaps.support.internal.protocol.ReviewPromptActionResponse
import ai.yaps.support.internal.protocol.ReviewPromptResponse
import ai.yaps.support.internal.protocol.SendMessageRequest
import ai.yaps.support.internal.protocol.SendMessageResponse
import ai.yaps.support.internal.protocol.TrackRequest
import ai.yaps.support.internal.protocol.TrackResponse
import ai.yaps.support.internal.protocol.UnreadResponse
import ai.yaps.support.internal.protocol.UploadRequest
import ai.yaps.support.internal.protocol.UploadResponse
import ai.yaps.support.internal.protocol.UploadedAttachmentResponse
import ai.yaps.support.internal.protocol.WireJson
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A non-2xx response. [code] is the PROTOCOL §1 error code; clients branch on it, never on the message. */
internal class ApiException(
    val status: Int,
    val code: String,
    message: String,
    val retryAfterSeconds: Double? = null,
) : IOException("HTTP $status $code: $message") {
    /** 5xx and 429 are worth retrying; other 4xx are final for this request. */
    val isRetryable: Boolean get() = status >= 500 || status == 429 || status == 408
}

internal sealed interface AppearanceResult {
    data class Fresh(val appearance: Appearance, val etag: String?) : AppearanceResult
    data object NotModified : AppearanceResult
}

/** REST client for PROTOCOL §3. Every call is main-safe (OkHttp's async dispatcher does the I/O). */
internal class ApiClient(
    baseUrl: String,
    private val appId: String,
    private val sdkHeader: String,
    private val http: OkHttpClient,
    private val deviceToken: () -> String?,
) {
    private val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()
    private val jsonType: MediaType = "application/json; charset=utf-8".toMediaType()

    /** PROTOCOL §4: the token rides in the query because browsers cannot set socket headers. OkHttp maps http(s) to ws(s). */
    fun webSocketUrl(token: String): String =
        base.newBuilder().addPathSegments("v1/sdk/ws").addQueryParameter("token", token).build().toString()

    suspend fun register(body: RegisterRequest): RegisterResponse =
        call(post("v1/sdk/register", WireJson.encodeToString(RegisterRequest.serializer(), body), auth = false), RegisterResponse.serializer())

    /** PATCH /v1/sdk/device with only the changed fields (a `null` push removes the token). */
    suspend fun updateDevice(fields: JsonObject): DeviceUpdateResponse =
        call(request("v1/sdk/device").patch(fields.toString().toRequestBody(jsonType)).build(), DeviceUpdateResponse.serializer())

    suspend fun logout(token: String) {
        val req = Request.Builder().url(url("v1/sdk/logout")).headers(token).post(EMPTY_JSON.toRequestBody(jsonType)).build()
        callUnit(req)
    }

    suspend fun deleteDevice() = callUnit(request("v1/sdk/device").delete().build())

    suspend fun conversation(): ConversationResponse =
        call(request("v1/sdk/conversation").get().build(), ConversationResponse.serializer())

    suspend fun messagesBefore(seq: Long, limit: Int = 50): MessagesResponse =
        call(request(url("v1/sdk/messages") { addQueryParameter("before", seq.toString()); addQueryParameter("limit", limit.toString()) }).get().build(), MessagesResponse.serializer())

    suspend fun messagesAfter(seq: Long): MessagesResponse =
        call(request(url("v1/sdk/messages") { addQueryParameter("after", seq.toString()) }).get().build(), MessagesResponse.serializer())

    suspend fun sendMessage(body: SendMessageRequest): Message =
        call(post("v1/sdk/messages", WireJson.encodeToString(SendMessageRequest.serializer(), body)), SendMessageResponse.serializer()).message

    suspend fun read(seq: Long) = callUnit(post("v1/sdk/read", buildJsonObject { put("seq", seq) }.toString()))

    suspend fun unread(): UnreadResponse = call(request("v1/sdk/unread").get().build(), UnreadResponse.serializer())

    suspend fun typing(on: Boolean) = callUnit(post("v1/sdk/typing", buildJsonObject { put("typing", on) }.toString()))

    /** Card action receipt (PROTOCOL §3.6): `open` for a card tap, `copy` for a code card's Copy. */
    suspend fun action(messageId: String, action: String = "open") =
        callUnit(post("v1/sdk/actions/$messageId", buildJsonObject { put("action", action) }.toString()))

    /** A rating card's score (1–5), PROTOCOL §3.6. */
    suspend fun rate(messageId: String, score: Int) =
        callUnit(post("v1/sdk/actions/$messageId", buildJsonObject { put("action", "rate"); put("score", score) }.toString()))

    suspend fun track(name: String, opensChat: Boolean): TrackResponse =
        call(post("v1/sdk/events", WireJson.encodeToString(TrackRequest.serializer(), TrackRequest(name, opensChat))), TrackResponse.serializer())

    suspend fun reviewPrompt(): ReviewPromptResponse = call(request("v1/sdk/review-prompt").get().build(), ReviewPromptResponse.serializer())

    suspend fun reviewPromptAction(action: String, milestone: Int): ReviewPromptActionResponse =
        call(
            post("v1/sdk/review-prompt", buildJsonObject { put("action", action); put("milestone", milestone) }.toString()),
            ReviewPromptActionResponse.serializer(),
        )

    suspend fun createUpload(body: UploadRequest): UploadResponse =
        call(post("v1/sdk/uploads", WireJson.encodeToString(UploadRequest.serializer(), body)), UploadResponse.serializer())

    /** PUT the raw bytes to the signed URL (no bearer: the signature is the credential). */
    suspend fun putUpload(uploadUrl: String, file: File, contentType: String, onProgress: (Float) -> Unit): Attachment {
        val body = ProgressRequestBody(file, contentType.toMediaType(), onProgress)
        val req = Request.Builder().url(uploadUrl).header("X-Support-SDK", sdkHeader).put(body).build()
        return call(req, UploadedAttachmentResponse.serializer()).attachment
    }

    /** Public: X-Support-App only, honours ETag (PROTOCOL §3.8). */
    suspend fun appearance(etag: String?): AppearanceResult {
        val builder = Request.Builder().url(url("v1/sdk/appearance"))
            .header("X-Support-App", appId)
            .header("X-Support-SDK", sdkHeader)
            .get()
        if (!etag.isNullOrBlank()) builder.header("If-None-Match", etag)
        http.newCall(builder.build()).await().use { response ->
            if (response.code == 304) return AppearanceResult.NotModified
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw error(response.code, text)
            return AppearanceResult.Fresh(WireJson.decodeFromString(Appearance.serializer(), text), response.header("ETag"))
        }
    }

    // ---- plumbing ----

    private fun url(path: String, build: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        base.newBuilder().addPathSegments(path).apply(build).build()

    private fun request(path: String): Request.Builder = request(url(path))

    private fun request(url: HttpUrl): Request.Builder = Request.Builder().url(url).headers(deviceToken())

    private fun Request.Builder.headers(token: String?): Request.Builder = apply {
        header("X-Support-App", appId)
        header("X-Support-SDK", sdkHeader)
        header("Accept", "application/json")
        if (token != null) header("Authorization", "Bearer $token")
    }

    private fun post(path: String, json: String, auth: Boolean = true): Request {
        val b = if (auth) request(path) else Request.Builder().url(url(path)).headers(null)
        return b.post(json.toRequestBody(jsonType)).build()
    }

    private suspend fun <T> call(request: Request, serializer: KSerializer<T>): T =
        http.newCall(request).await().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw error(response.code, text)
            WireJson.decodeFromString(serializer, text)
        }

    private suspend fun callUnit(request: Request) {
        http.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw error(response.code, response.body?.string().orEmpty())
        }
    }

    private fun error(status: Int, body: String): ApiException {
        val detail = runCatching { WireJson.decodeFromString(ApiErrorBody.serializer(), body).error }.getOrNull()
        val fallback = when {
            status == 401 -> "unauthorized"
            status == 404 -> "not_found"
            status == 429 -> "rate_limited"
            status >= 500 -> "server_error"
            else -> "invalid_request"
        }
        return ApiException(status, detail?.code ?: fallback, detail?.message.orEmpty(), detail?.retryAfter)
    }

    private companion object {
        const val EMPTY_JSON = "{}"
    }
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}

/** Streams a file and reports 0..1 progress (PROTOCOL §3.7 "upload with progress"). */
private class ProgressRequestBody(
    private val file: File,
    private val type: MediaType,
    private val onProgress: (Float) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType = type
    override fun contentLength(): Long = file.length()
    override fun writeTo(sink: BufferedSink) {
        val total = contentLength().coerceAtLeast(1)
        var written = 0L
        file.source().use { source ->
            val buffer = okio.Buffer()
            while (true) {
                val read = source.read(buffer, 16 * 1024)
                if (read == -1L) break
                sink.write(buffer, read)
                written += read
                onProgress((written.toFloat() / total).coerceIn(0f, 1f))
            }
        }
    }
}
