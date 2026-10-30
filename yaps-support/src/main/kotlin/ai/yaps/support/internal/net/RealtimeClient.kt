package ai.yaps.support.internal.net

import ai.yaps.support.internal.core.Backoff
import ai.yaps.support.internal.protocol.FrameCodec
import ai.yaps.support.internal.protocol.ServerFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * The chat socket (PROTOCOL §4). Opened only while the chat is on screen. Reconnects with jittered
 * backoff (1, 2, 4, 8, 16, 30 s ±20 %) and sends an application-level `ping` every 25 s.
 */
internal class RealtimeClient(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val listener: Listener,
    private val backoff: Backoff = Backoff(baseMillis = 1_000, capMillis = 30_000, jitter = 0.2),
) {
    interface Listener {
        /** Returns the socket URL (with device token), or `null` if the device is not registered yet. */
        suspend fun socketUrl(): String?
        fun onOpen(socket: RealtimeClient)
        fun onFrame(frame: ServerFrame)

        /** [consecutiveFailures] counts attempts since the last successful open. */
        fun onDisconnected(consecutiveFailures: Int, httpStatus: Int?)
    }

    @Volatile private var socket: WebSocket? = null
    @Volatile var isOpen: Boolean = false
        private set
    private var loop: Job? = null

    val isRunning: Boolean get() = loop?.isActive == true

    fun start() {
        if (isRunning) return
        loop = scope.launch {
            var failures = 0
            while (isActive) {
                val url = listener.socketUrl()
                if (url == null) {
                    delay(2_000)
                    continue
                }
                val outcome = connectOnce(url)
                failures = if (outcome.opened) 1 else failures + 1
                listener.onDisconnected(failures, outcome.httpStatus)
                if (!isActive) break
                delay(backoff.delayFor(failures))
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        socket?.close(1000, "chat closed")
        socket = null
        isOpen = false
    }

    /** Sends a client frame. Returns `false` when the socket is not open (caller falls back to REST). */
    fun send(text: String): Boolean = socket?.takeIf { isOpen }?.send(text) ?: false

    private data class Outcome(val opened: Boolean, val httpStatus: Int?)

    private suspend fun connectOnce(url: String): Outcome {
        val done = CompletableDeferred<Outcome>()
        var opened = false
        var pinger: Job? = null
        val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened = true
                isOpen = true
                listener.onOpen(this@RealtimeClient)
                pinger = scope.launch {
                    while (isActive) {
                        delay(PING_INTERVAL_MS)
                        webSocket.send(FrameCodec.ping())
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                FrameCodec.decode(text)?.let(listener::onFrame) // unknown frames are skipped
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.complete(Outcome(opened, null))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                done.complete(Outcome(opened, response?.code))
            }
        })
        socket = ws
        return try {
            done.await()
        } finally {
            pinger?.cancel()
            isOpen = false
            if (socket === ws) socket = null
            ws.cancel()
        }
    }

    private companion object {
        const val PING_INTERVAL_MS = 25_000L
    }
}
