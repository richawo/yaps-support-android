package ai.yaps.support.internal.core

import ai.yaps.support.BuildConfig
import ai.yaps.support.ReviewPrompt
import ai.yaps.support.SupportEvent
import ai.yaps.support.SupportException
import ai.yaps.support.internal.net.ApiClient
import ai.yaps.support.internal.net.ApiException
import ai.yaps.support.internal.net.AppearanceResult
import ai.yaps.support.internal.net.RealtimeClient
import ai.yaps.support.internal.outbox.FileOutboxPersistence
import ai.yaps.support.internal.outbox.Outbox
import ai.yaps.support.internal.outbox.OutboxEntry
import ai.yaps.support.internal.outbox.OutboxWorker
import ai.yaps.support.internal.outbox.PendingAttachment
import ai.yaps.support.internal.protocol.CardType
import ai.yaps.support.internal.protocol.Appearance
import ai.yaps.support.internal.protocol.DeviceInfo
import ai.yaps.support.internal.protocol.FrameCodec
import ai.yaps.support.internal.protocol.IdentityInput
import ai.yaps.support.internal.protocol.Message
import ai.yaps.support.internal.protocol.MessageKind
import ai.yaps.support.internal.protocol.PaywallWire
import ai.yaps.support.internal.protocol.PushInput
import ai.yaps.support.internal.protocol.RegisterRequest
import ai.yaps.support.internal.protocol.ScreenWire
import ai.yaps.support.internal.protocol.SdkConfig
import ai.yaps.support.internal.protocol.SdkCustomer
import ai.yaps.support.internal.protocol.SendMessageRequest
import ai.yaps.support.internal.protocol.ServerFrame
import ai.yaps.support.internal.protocol.UploadRequest
import ai.yaps.support.internal.protocol.WireJson
import ai.yaps.support.internal.push.SupportNotifications
import ai.yaps.support.internal.store.CachedConversation
import ai.yaps.support.internal.store.LocalStore
import ai.yaps.support.internal.store.SecureTokenStore
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Everything the facade needs from the host's settings, read lazily so changes apply at once. */
internal interface EngineHost {
    fun emit(event: SupportEvent)
    val languageOverride: String?
    val usesRemoteAppearance: Boolean
    val updatesAppBadge: Boolean
    suspend fun availablePaywalls(): List<PaywallWire>
    fun screens(): List<ScreenWire>
    fun openChat(context: Context)
}

/**
 * The SDK's state machine: registration, identity, the conversation, the socket and polling
 * fallback, the outbox, unread count and remote appearance. One instance per configured App ID.
 */
internal class SupportEngine(
    context: Context,
    val appId: String,
    val baseUrl: String,
    private val host: EngineHost,
) {
    private val context: Context = context.applicationContext
    private val store = LocalStore(this.context)
    private val tokens = SecureTokenStore(this.context)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t -> Log.e("Unexpected error in support engine", t) },
    )
    private val main = Handler(Looper.getMainLooper())

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val api = ApiClient(baseUrl, appId, "android/${BuildConfig.SDK_VERSION}", http) { tokens.get() }
    val outbox = Outbox(FileOutboxPersistence(store.outboxFile))

    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(this.context)
            // Coil's own connectivity check forces cache-only (HTTP 504) when it misjudges the network
            // (emulators, captive portals, VPNs). Let OkHttp try and fail honestly instead.
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { http }, connectivityChecker = { coil3.network.ConnectivityChecker.ONLINE })) }
            .crossfade(true)
            .apply { if (Log.level == ai.yaps.support.LogLevel.DEBUG) logger(coil3.util.DebugLogger()) }
            .build()
    }

    private val cached: CachedConversation = store.loadHistory()
    private val _state = MutableStateFlow(
        ChatState(
            messages = cached.messages,
            hasMore = cached.hasMore,
            agentReadSeq = cached.conversation?.agentReadSeq ?: 0,
            config = store.config,
            restriction = restrictionFor(store.config, store.customerVerified),
        ),
    )
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val _unread = MutableStateFlow(store.unread)
    val unread: StateFlow<Int> = _unread.asStateFlow()

    private val _chatOpen = MutableStateFlow(false)
    val chatOpen: StateFlow<Boolean> = _chatOpen.asStateFlow()

    private val _availablePaywallIds = MutableStateFlow<Set<String>>(emptySet())
    val availablePaywallIds: StateFlow<Set<String>> = _availablePaywallIds.asStateFlow()

    /** IDs in the host's current `YapsSupport.screens`; link cards to other screens are unavailable. */
    private val _screenIds = MutableStateFlow<Set<String>>(emptySet())
    val screenIds: StateFlow<Set<String>> = _screenIds.asStateFlow()

    /** Bumps when a close was requested via YapsSupport.closeChat(). */
    private val _closeRequests = MutableStateFlow(0)
    val closeRequests: StateFlow<Int> = _closeRequests.asStateFlow()

    @Volatile var remoteAppearance: Appearance? = store.loadAppearance()
        private set

    @Volatile private var identity: IdentityInput? = null
    @Volatile private var metadata: Map<String, String>? = null
    @Volatile private var customerVerified = store.customerVerified
    @Volatile private var conversationId: String? = cached.conversation?.id
    @Volatile private var disabled = false
    @Volatile private var online = true
    @Volatile private var appForeground = false
    @Volatile private var lastReadSent = 0L
    @Volatile private var hadInterruption = false
    @Volatile private var typingOnSent = false
    @Volatile private var lastTypingAt = 0L

    private var mountedCount = 0 // main thread
    private var visibleCount = 0 // main thread
    private val visible: Boolean get() = visibleCount > 0

    private val registerMutex = Mutex()
    private val listsMutex = Mutex()
    private val badgeMutex = Mutex()
    private val ackWaiters = ConcurrentHashMap<String, CompletableDeferred<Result<Message>>>()
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private var flushJob: Job? = null
    private var pollJob: Job? = null
    private var typingOffJob: Job? = null
    private var agentTypingJob: Job? = null
    private var draftJob: Job? = null
    private var bannerJob: Job? = null

    private val realtime = RealtimeClient(http, scope, object : RealtimeClient.Listener {
        override suspend fun socketUrl(): String? = tokens.get()?.let(api::webSocketUrl)
        override fun onOpen(socket: RealtimeClient) = onSocketOpen(socket)
        override fun onFrame(frame: ServerFrame) = handleFrame(frame)
        override fun onDisconnected(consecutiveFailures: Int, httpStatus: Int?) = onSocketDown(consecutiveFailures, httpStatus)
    })

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        store.saveEndpoint(appId, baseUrl)
        _screenIds.value = host.screens().mapTo(HashSet()) { it.id }
        scope.launch { outbox.entries.collect { entries -> _state.update { it.copy(pending = entries) } } }
        scope.launch { cleanOrphanedFiles() }
        watchNetwork()
        main.post {
            runCatching { ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver) }
        }
        scope.launch {
            refreshAppearance()
            if (tokens.get() == null) runCatching { ensureRegistered() } else refreshUnread()
            syncLists()
            syncBadgePreference()
        }
    }

    private val processObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            appForeground = true
            ensureFlushLoop()
            scope.launch {
                if (!_chatOpen.value) {
                    refreshUnread()
                    refreshAppearance()
                }
            }
            if (visible) markRead()
        }

        override fun onStop(owner: LifecycleOwner) {
            appForeground = false
            if (outbox.hasPending) OutboxWorker.enqueue(context)
        }
    }

    /** The chat UI entered composition (drives isChatOpen and the open/close events). */
    fun onChatMounted() {
        mountedCount++
        if (mountedCount == 1) {
            _chatOpen.value = true
            host.emit(SupportEvent.ChatOpened)
            SupportNotifications.cancel(context, null)
        }
    }

    fun onChatUnmounted() {
        mountedCount = (mountedCount - 1).coerceAtLeast(0)
        if (mountedCount == 0) {
            _chatOpen.value = false
            host.emit(SupportEvent.ChatClosed)
            scope.launch {
                refreshUnread()
                refreshAppearance() // applied on the *next* open (PROTOCOL §3.8)
            }
        }
    }

    /** The chat is on screen and the app is started: open the socket (PROTOCOL §4). */
    fun onChatVisible() {
        visibleCount++
        if (visibleCount != 1) return
        appForeground = true
        SupportNotifications.cancel(context, null)
        if (tokens.get() != null) setConnection(if (online) ConnectionStatus.CONNECTING else ConnectionStatus.OFFLINE)
        scope.launch { loadConversation() }
        realtime.start()
        ensureFlushLoop()
    }

    fun onChatHidden() {
        visibleCount = (visibleCount - 1).coerceAtLeast(0)
        if (visibleCount != 0) return
        realtime.stop()
        pollJob?.cancel()
        sendTyping(false)
        hadInterruption = false
        setConnection(ConnectionStatus.HIDDEN)
        _state.update { it.copy(agentTyping = false) }
        failAckWaiters()
    }

    fun requestClose() {
        _closeRequests.value++
    }

    // ------------------------------------------------------------------ registration & identity

    private suspend fun ensureRegistered(): String {
        tokens.get()?.let { return it }
        if (disabled) throw ApiException(404, "unknown_app", "SDK disabled")
        return registerMutex.withLock {
            tokens.get()?.let { return@withLock it }
            val response = try {
                api.register(registerRequest(identity))
            } catch (e: ApiException) {
                if (e.code == "identity_token_invalid" || e.code == "identity_token_expired") {
                    host.emit(SupportEvent.IdentityRejected(e.code))
                    identity = identity?.copy(token = null)
                    api.register(registerRequest(identity))
                } else {
                    handleError(e)
                    throw e
                }
            }
            tokens.set(response.deviceToken)
            store.badgeEnabled = true // the server's default for a new registration
            Log.i("Registered device ${response.registrationId}")
            applyCustomer(response.customer, response.config)
            scope.launch {
                syncLists()
                syncBadgePreference()
            }
            response.deviceToken
        }
    }

    private fun registerRequest(identity: IdentityInput?) = RegisterRequest(
        platform = "android",
        device = deviceInfo(),
        identity = identity,
        metadata = metadata?.takeIf { it.isNotEmpty() },
        push = store.pushToken?.let { PushInput(it.first, it.second) },
    )

    /** Runs [block] with a device token; on `401 unauthorized` drops the token, registers again and retries once. */
    private suspend fun <T> authed(block: suspend () -> T): T {
        ensureRegistered()
        return try {
            block()
        } catch (e: ApiException) {
            if (e.status == 401 && e.code == "unauthorized") {
                Log.w("Device token rejected; registering again")
                dropRegistration()
                ensureRegistered()
                block()
            } else {
                handleError(e)
                throw e
            }
        }
    }

    private fun handleError(e: ApiException) {
        when (e.code) {
            "unknown_app" -> if (!disabled) {
                disabled = true
                Log.e("Unknown App ID '$appId'. Check YapsSupport.configure(). Support is disabled.")
                _state.update { it.copy(restriction = Restriction.DISABLED) }
            }
            "blocked" -> _state.update { it.copy(restriction = Restriction.BLOCKED) }
            "verification_required" -> _state.update { it.copy(restriction = Restriction.VERIFICATION_REQUIRED) }
            "identity_token_invalid", "identity_token_expired" -> host.emit(SupportEvent.IdentityRejected(e.code))
            "identity_mismatch" -> {
                Log.w("Identity belongs to a different user; starting a fresh support session")
                val keep = identity
                resetLocal()
                identity = keep
                scope.launch { runCatching { ensureRegistered() } }
            }
        }
    }

    private fun applyCustomer(customer: SdkCustomer, config: SdkConfig) {
        customerVerified = customer.verified
        store.customerVerified = customer.verified
        store.customerUserId = customer.userId
        store.config = config
        _state.update { it.copy(config = config, restriction = if (disabled) Restriction.DISABLED else restrictionFor(config, customer.verified)) }
        if (config.appearanceEtag != null && config.appearanceEtag != store.appearanceEtag) {
            scope.launch { refreshAppearance() }
        }
    }

    private fun restrictionFor(config: SdkConfig?, verified: Boolean): Restriction = when {
        config == null -> Restriction.NONE
        config.blocked -> Restriction.BLOCKED
        config.verifiedOnly && !verified -> Restriction.VERIFICATION_REQUIRED
        else -> Restriction.NONE
    }

    /** Server no longer accepts the token: new registration, new (empty) conversation unless verified. */
    private fun dropRegistration() {
        tokens.clear()
        conversationId = null
        lastReadSent = 0
        store.saveHistory(CachedConversation())
        _state.update { it.copy(messages = emptyList(), hasMore = false, agentReadSeq = 0) }
    }

    fun identify(input: IdentityInput) {
        if (SessionRules.isUserSwitch(store.customerUserId ?: identity?.userId, input.userId)) {
            // Like iOS and the web: the previous user's session ends here, on the server too.
            Log.i("Switching users; clearing the previous user's local support session")
            val previous = tokens.get()
            resetLocal()
            metadata = null
            identity = input
            if (previous != null) scope.launch { runCatching { api.logout(previous) } }
            scope.launch { runCatching { ensureRegistered() } }
            return
        }
        identity = input
        if (input.token != null && _state.value.restriction == Restriction.VERIFICATION_REQUIRED) {
            // Clear the notice optimistically; the server has the final word on the next response.
            _state.update { it.copy(restriction = Restriction.NONE) }
        }
        val json = WireJson.encodeToJsonElement(IdentityInput.serializer(), input)
        patchDevice(buildJsonObject { put("identity", json) })
    }

    fun setMetadata(values: Map<String, String>) {
        metadata = values
        patchDevice(buildJsonObject { put("metadata", WireJson.encodeToJsonElement(MapSerializer(String.serializer(), String.serializer()), values)) })
    }

    fun setPushToken(token: String?, provider: String) {
        val previous = store.pushToken
        store.pushToken = token?.let { it to provider }
        if (previous == store.pushToken && tokens.get() != null) return
        patchDevice(buildJsonObject {
            if (token == null) put("push", JsonNull)
            else put("push", WireJson.encodeToJsonElement(PushInput.serializer(), PushInput(token, provider)))
        })
    }

    fun onLanguageChanged() {
        patchDevice(buildJsonObject { put("device", WireJson.encodeToJsonElement(DeviceInfo.serializer(), deviceInfo())) })
    }

    private fun patchDevice(fields: JsonObject) {
        scope.launch {
            try {
                if (tokens.get() == null) {
                    ensureRegistered() // registration already carries identity, metadata and push
                    return@launch
                }
                val response = authed { api.updateDevice(fields) }
                applyCustomer(response.customer, response.config)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("Device update failed", e)
            }
        }
    }

    fun badgePreferenceChanged() {
        scope.launch { syncBadgePreference() }
    }

    /**
     * PROTOCOL §3.4 `badgeEnabled`: sent when `YapsSupport.updatesAppBadge` differs from what the
     * server has for this registration, so `false` goes right after register and on every change.
     */
    private suspend fun syncBadgePreference() = badgeMutex.withLock {
        val desired = host.updatesAppBadge
        val body = DeviceUpdate.badge(desired, synced = store.badgeEnabled) ?: return@withLock
        if (tokens.get() == null || disabled) return@withLock
        try {
            val response = authed { api.updateDevice(body) }
            store.badgeEnabled = desired
            applyCustomer(response.customer, response.config)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("Reporting the badge preference failed", e)
        }
    }

    fun refreshPaywalls() {
        scope.launch { syncLists(paywalls = true, screens = false) }
    }

    fun refreshScreens() {
        _screenIds.value = host.screens().mapTo(HashSet()) { it.id }
        scope.launch { syncLists(paywalls = false, screens = true) }
    }

    /**
     * PROTOCOL §3.4: `paywalls` and `screens` replace this device's lists. Both go after register
     * (and on start); each one again whenever the host changes it.
     */
    private suspend fun syncLists(paywalls: Boolean = true, screens: Boolean = true) = listsMutex.withLock {
        val available = if (paywalls) runCatching { host.availablePaywalls() }.getOrDefault(emptyList()).take(20) else null
        val screenList = if (screens) host.screens() else null
        available?.let { list -> _availablePaywallIds.value = list.map { it.id }.toSet() }
        screenList?.let { list -> _screenIds.value = list.map { it.id }.toSet() }
        if (tokens.get() == null || disabled) return@withLock
        try {
            val response = authed { api.updateDevice(DeviceUpdate.lists(available, screenList)) }
            applyCustomer(response.customer, response.config)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("Reporting paywalls and screens failed", e)
        }
    }

    // ------------------------------------------------------------------ conversation

    private val lastSeq: Long get() = _state.value.messages.maxOfOrNull { it.seq } ?: 0L

    suspend fun loadConversation() {
        if (tokens.get() == null) {
            _state.update { it.copy(loaded = true) }
            return
        }
        try {
            val response = authed { api.conversation() }
            conversationId = response.conversation?.id ?: conversationId
            val visibleMessages = response.messages.filter { it.kind != MessageKind.EVENT }
            _state.update {
                it.copy(
                    messages = visibleMessages.sortedBy { m -> m.seq },
                    hasMore = response.hasMore,
                    loaded = true,
                    agentReadSeq = response.conversation?.agentReadSeq ?: it.agentReadSeq,
                )
            }
            reconcileOutbox(visibleMessages)
            persistHistory(response.conversation)
            if (!_chatOpen.value) response.conversation?.let { setUnread(it.unread) }
            markRead()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("Loading the conversation failed", e)
            _state.update { it.copy(loaded = true) }
        }
    }

    fun loadEarlier() {
        val s = _state.value
        if (!s.hasMore || s.loadingEarlier) return
        val before = s.messages.minOfOrNull { it.seq } ?: return
        _state.update { it.copy(loadingEarlier = true) }
        scope.launch {
            try {
                val response = authed { api.messagesBefore(before) }
                upsert(response.messages, notify = false)
                _state.update { it.copy(hasMore = response.hasMore) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("Loading earlier messages failed", e)
            } finally {
                _state.update { it.copy(loadingEarlier = false) }
            }
        }
    }

    private suspend fun catchUp() {
        if (tokens.get() == null) return
        if (lastSeq == 0L) {
            loadConversation()
            return
        }
        try {
            val response = authed { api.messagesAfter(lastSeq) }
            upsert(response.messages, notify = true)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.d("Catch-up poll failed")
        }
    }

    private fun upsert(incoming: List<Message>, notify: Boolean) {
        val visibleMessages = incoming.filter { it.kind != MessageKind.EVENT }
        if (visibleMessages.isEmpty()) return
        var added: List<Message> = emptyList()
        _state.update { s ->
            val byId = LinkedHashMap<String, Message>(s.messages.size + visibleMessages.size)
            s.messages.forEach { byId[it.id] = it }
            added = visibleMessages.filter { it.id !in byId }
            visibleMessages.forEach { byId[it.id] = it }
            val typingCleared = added.any { !it.isFromCustomer }
            s.copy(
                messages = byId.values.sortedBy { it.seq },
                agentTyping = if (typingCleared) false else s.agentTyping,
            )
        }
        visibleMessages.firstOrNull { it.conversationId.isNotEmpty() }?.let { conversationId = it.conversationId }
        reconcileOutbox(visibleMessages)
        if (notify) added.filter { !it.isFromCustomer }.forEach { host.emit(SupportEvent.MessageReceived(it.id)) }
        persistHistory(null)
        markRead()
    }

    private fun reconcileOutbox(messages: List<Message>) {
        outbox.reconcile(messages.mapNotNull { it.clientId }).forEach(::deleteFiles)
    }

    private fun persistHistory(conversation: ai.yaps.support.internal.protocol.SdkConversation?) {
        val s = _state.value
        val existing = conversation ?: store.loadHistory().conversation
        store.saveHistory(CachedConversation(existing, s.messages, s.hasMore))
    }

    private fun markRead() {
        if (!visible || !appForeground) return
        val seq = _state.value.messages.filter { !it.isFromCustomer }.maxOfOrNull { it.seq } ?: run {
            setUnread(0)
            return
        }
        setUnread(0)
        if (seq <= lastReadSent) return
        lastReadSent = seq
        if (!realtime.send(FrameCodec.read(seq))) {
            scope.launch { runCatching { authed { api.read(seq) } } }
        }
    }

    private fun setUnread(count: Int) {
        val value = count.coerceAtLeast(0)
        if (_unread.value != value) {
            _unread.value = value
            store.unread = value
        }
    }

    suspend fun refreshUnread() {
        if (tokens.get() == null || disabled) return
        try {
            val response = authed { api.unread() }
            if (!_chatOpen.value) setUnread(response.count)
            response.conversationId?.let { conversationId = it }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.d("Unread refresh failed")
        }
    }

    // ------------------------------------------------------------------ socket

    private fun onSocketOpen(socket: RealtimeClient) {
        socket.send(FrameCodec.hello(lastSeq))
        pollJob?.cancel()
        if (hadInterruption) {
            setConnection(ConnectionStatus.CONNECTED)
            bannerJob?.cancel()
            bannerJob = scope.launch {
                delay(2_000)
                if (_state.value.connection == ConnectionStatus.CONNECTED) setConnection(ConnectionStatus.HIDDEN)
            }
        } else {
            setConnection(ConnectionStatus.HIDDEN)
        }
        hadInterruption = false
        lastReadSent = 0 // re-send read state on the fresh connection
        markRead()
        kickFlush()
    }

    private fun onSocketDown(failures: Int, httpStatus: Int?) {
        failAckWaiters()
        if (!visible) return
        hadInterruption = true
        if (httpStatus == 401) {
            scope.launch {
                dropRegistration()
                runCatching { ensureRegistered() }
            }
        }
        if (!online) {
            setConnection(ConnectionStatus.OFFLINE)
            return
        }
        if (failures >= 3) {
            setConnection(ConnectionStatus.PAUSED)
            startPolling()
        } else {
            setConnection(ConnectionStatus.CONNECTING)
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive && visible && !realtime.isOpen) {
                catchUp()
                delay((_state.value.config?.pollIntervalSeconds ?: 60).coerceIn(5, 600) * 1_000L)
            }
        }
    }

    private fun handleFrame(frame: ServerFrame) {
        when (frame) {
            is ServerFrame.Welcome -> frame.conversationId?.let { conversationId = it }
            is ServerFrame.Msg -> upsert(listOf(frame.message), notify = true)
            is ServerFrame.Upd -> upsert(listOf(frame.message), notify = false)
            is ServerFrame.Ack -> {
                ackWaiters.remove(frame.cid)?.complete(Result.success(frame.message))
                    ?: onDelivered(frame.cid, frame.message)
            }
            is ServerFrame.State -> {
                frame.agentTyping?.let { typing ->
                    val name = _state.value.messages.lastOrNull { !it.isFromCustomer }?.author?.name
                    _state.update { it.copy(agentTyping = typing, agentTypingName = name) }
                    agentTypingJob?.cancel()
                    if (typing) agentTypingJob = scope.launch {
                        delay(8_000)
                        _state.update { it.copy(agentTyping = false) }
                    }
                }
                frame.agentReadSeq?.let { seq -> _state.update { it.copy(agentReadSeq = maxOf(it.agentReadSeq, seq)) } }
            }
            is ServerFrame.Err -> {
                val error = ApiException(statusFor(frame.code), frame.code, frame.message.orEmpty())
                val cid = frame.cid
                if (cid != null) {
                    val waiter = ackWaiters.remove(cid)
                    if (waiter != null) waiter.complete(Result.failure(error))
                    else if (outbox.get(cid) != null) {
                        handleError(error)
                        outbox.recordFailure(cid, frame.code, error.isRetryable)
                    }
                } else {
                    handleError(error)
                }
            }
            ServerFrame.Pong -> Unit
        }
    }

    private fun statusFor(code: String): Int = when (code) {
        "invalid_request" -> 400
        "unauthorized", "identity_token_invalid", "identity_token_expired" -> 401
        "identity_mismatch", "verification_required", "blocked", "forbidden" -> 403
        "not_found", "unknown_app" -> 404
        "conflict" -> 409
        "too_large" -> 413
        "rate_limited" -> 429
        else -> 500
    }

    private fun failAckWaiters() {
        val waiters = ackWaiters.values.toList()
        ackWaiters.clear()
        waiters.forEach { it.complete(Result.failure(IOException("socket closed"))) }
    }

    private fun setConnection(status: ConnectionStatus) {
        _state.update { if (it.connection == status) it else it.copy(connection = status) }
    }

    // ------------------------------------------------------------------ composer

    fun loadDraft(): String = store.loadDraft()

    fun onComposerChanged(text: String) {
        draftJob?.cancel()
        draftJob = scope.launch {
            delay(300)
            store.saveDraft(text)
        }
        if (text.isBlank()) {
            sendTyping(false)
            return
        }
        val now = System.currentTimeMillis()
        if (!typingOnSent || now - lastTypingAt >= 3_000) sendTyping(true)
        typingOffJob?.cancel()
        typingOffJob = scope.launch {
            delay(5_000)
            sendTyping(false)
        }
    }

    private fun sendTyping(on: Boolean) {
        // Only for an existing conversation that allows sending; typing never creates a conversation.
        if (tokens.get() == null || conversationId == null || _state.value.restriction != Restriction.NONE) return
        if (!on && !typingOnSent) return
        typingOnSent = on
        lastTypingAt = System.currentTimeMillis()
        if (!realtime.send(FrameCodec.typing(on)) && pollJob?.isActive == true) {
            scope.launch { runCatching { authed { api.typing(on) } } }
        }
    }

    /** Stores the draft synchronously (used on send, so a restored draft never resurrects sent text). */
    fun clearDraft() {
        draftJob?.cancel()
        scope.launch { store.saveDraft("") }
    }

    val attachmentsDir: File get() = store.outboxFilesDir

    fun send(text: String, attachments: List<PendingAttachment>): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return false
        if (Limits.textTooLong(trimmed) || attachments.size > Limits.ATTACHMENTS) return false
        if (_state.value.restriction != Restriction.NONE) return false
        val entry = OutboxEntry(clientId = Ulid.generate(), text = trimmed, attachments = attachments, createdAt = System.currentTimeMillis())
        if (!outbox.enqueue(entry)) return false
        typingOffJob?.cancel()
        sendTyping(false)
        clearDraft()
        ensureFlushLoop()
        kickFlush()
        return true
    }

    fun retry(clientId: String) {
        if (outbox.retry(clientId)) {
            ensureFlushLoop()
            kickFlush()
        }
    }

    fun discard(clientId: String) {
        outbox.remove(clientId)?.let(::deleteFiles)
    }

    fun discardStaged(attachment: PendingAttachment) {
        runCatching { File(attachment.localPath).delete() }
    }

    // ------------------------------------------------------------------ outbox

    private fun kickFlush() {
        kick.trySend(Unit)
    }

    private fun ensureFlushLoop() {
        if (flushJob?.isActive == true) return
        if (outbox.entries.value.isEmpty() && !visible) return
        flushJob = scope.launch {
            while (isActive) {
                if (online && !disabled) {
                    for (entry in outbox.due()) process(entry)
                }
                val wake = outbox.nextWakeAt()
                if (wake == null && !visible && !appForeground) break
                val wait = if (wake == null) 60_000L else (wake - System.currentTimeMillis()).coerceIn(0, 60_000)
                if (wait > 0) withTimeoutOrNull(wait) { kick.receive() }
            }
        }
    }

    /** WorkManager entry point: deliver what is due, waiting out short backoffs. Returns true when nothing retryable is left. */
    suspend fun flushForWorker(): Boolean {
        val deadline = System.currentTimeMillis() + 8 * 60_000L
        while (System.currentTimeMillis() < deadline) {
            for (entry in outbox.due()) process(entry)
            val wake = outbox.nextWakeAt() ?: return true
            val wait = wake - System.currentTimeMillis()
            if (wait > 60_000) return false
            if (wait > 0) delay(wait)
        }
        return !outbox.hasPending
    }

    private suspend fun process(entry: OutboxEntry) {
        val cid = entry.clientId
        if (!outbox.markSending(cid)) return
        try {
            val uploadIds = uploadAll(cid)
            val current = outbox.get(cid) ?: return
            val message = deliver(cid, current.text, uploadIds)
            onDelivered(cid, message)
        } catch (e: CancellationException) {
            outbox.release(cid)
            throw e
        } catch (e: ApiException) {
            val retryAfter = e.retryAfterSeconds?.let { (it * 1_000).toLong() }
            outbox.recordFailure(cid, e.code, e.isRetryable, retryAfter)
            if (e.code == "unknown_app" || e.code == "blocked" || e.code == "verification_required") handleError(e)
        } catch (e: MissingFileException) {
            outbox.recordFailure(cid, "file_missing", retryable = false)
        } catch (e: Exception) {
            Log.d("Send attempt failed: ${e.javaClass.simpleName}")
            outbox.recordFailure(cid, "network", retryable = true)
        } finally {
            _state.update { if (cid in it.uploadProgress) it.copy(uploadProgress = it.uploadProgress - cid) else it }
        }
    }

    private class MissingFileException : IOException("attachment file missing")

    private suspend fun uploadAll(cid: String): List<String> {
        val entry = outbox.get(cid) ?: return emptyList()
        if (entry.attachments.isEmpty()) return emptyList()
        val total = entry.attachments.sumOf { it.size }.coerceAtLeast(1).toFloat()
        var done = entry.attachments.filter { it.uploadId != null }.sumOf { it.size }.toFloat()
        val ids = ArrayList<String>(entry.attachments.size)
        entry.attachments.forEachIndexed { index, attachment ->
            attachment.uploadId?.let {
                ids += it
                return@forEachIndexed
            }
            val file = File(attachment.localPath)
            if (!file.exists()) throw MissingFileException()
            val ticket = authed {
                api.createUpload(UploadRequest(attachment.contentType, file.length(), attachment.width, attachment.height, attachment.filename))
            }
            api.putUpload(ticket.uploadUrl, file, attachment.contentType) { fraction ->
                val progress = ((done + fraction * attachment.size) / total).coerceIn(0f, 1f)
                _state.update { it.copy(uploadProgress = it.uploadProgress + (cid to progress)) }
            }
            done += attachment.size
            outbox.markUploaded(cid, index, ticket.uploadId)
            ids += ticket.uploadId
        }
        return ids
    }

    /** Sends over the socket when it is open (awaiting `ack`), otherwise over REST. Both dedupe on clientId. */
    private suspend fun deliver(cid: String, text: String, uploadIds: List<String>): Message {
        ensureRegistered()
        if (realtime.isOpen) {
            val waiter = CompletableDeferred<Result<Message>>()
            ackWaiters[cid] = waiter
            if (realtime.send(FrameCodec.send(cid, text, uploadIds))) {
                val result = withTimeoutOrNull(15_000) { waiter.await() }
                ackWaiters.remove(cid)
                if (result != null) {
                    result.exceptionOrNull()?.let { if (it is ApiException) throw it }
                    result.getOrNull()?.let { return it }
                }
            } else {
                ackWaiters.remove(cid)
            }
        }
        return authed { api.sendMessage(SendMessageRequest(cid, text.ifEmpty { null }, uploadIds.ifEmpty { null })) }
    }

    private fun onDelivered(cid: String, message: Message) {
        val removed = outbox.complete(cid)
        upsert(listOf(message), notify = false)
        removed?.let(::deleteFiles)
        if (removed != null) {
            host.emit(SupportEvent.MessageSent(message.id))
            val images = message.attachments.count { it.isImage }
            if (images > 0) host.emit(SupportEvent.ImageSent(message.id, images))
        }
    }

    private fun deleteFiles(entry: OutboxEntry) {
        entry.attachments.forEach { runCatching { File(it.localPath).delete() } }
    }

    private fun cleanOrphanedFiles() {
        val referenced = outbox.entries.value.flatMap { e -> e.attachments.map { it.localPath } }.toHashSet()
        store.outboxFilesDir.listFiles()?.forEach { if (it.absolutePath !in referenced) it.delete() }
    }

    // ------------------------------------------------------------------ cards, events, push

    /** A star on a rating card: shows the score at once and sends it. Tapping again changes it. */
    fun rateCard(messageId: String, score: Int) {
        if (score !in 1..5) return
        val message = _state.value.messages.firstOrNull { it.id == messageId } ?: return
        val card = message.card?.takeIf { it.type == CardType.RATING } ?: return
        upsert(listOf(message.copy(card = card.copy(rating = score))), notify = false)
        scope.launch { runCatching { authed { api.rate(messageId, score) } } }
    }

    /** Fire-and-forget card action receipt: [CardActionName.OPEN] or [CardActionName.COPY]. */
    fun recordCardAction(messageId: String, action: String = CardActionName.OPEN) {
        scope.launch { runCatching { authed { api.action(messageId, action) } } }
    }

    fun track(name: String, opensChat: Boolean) {
        if (!Limits.isValidEventName(name)) {
            Log.w("track(): invalid event name")
            return
        }
        val started = System.currentTimeMillis()
        scope.launch {
            try {
                ensureRegistered()
                val tracked = _state.value.config?.trackedEvents.orEmpty()
                if (name !in tracked) {
                    Log.d("track(): '$name' is not in config.trackedEvents; not sent")
                    return@launch
                }
                val response = authed { api.track(name, opensChat) }
                // Like iOS: a late answer would open the chat over whatever the user moved on to.
                val elapsed = System.currentTimeMillis() - started
                if (SessionRules.shouldOpenChatAfterTrack(opensChat, response.openChat, elapsed, appForeground, _chatOpen.value)) {
                    withContext(Dispatchers.Main) { host.openChat(context) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("track() failed", e)
            }
        }
    }

    // ------------------------------------------------------------------ review prompt

    suspend fun reviewPrompt(): ReviewPrompt? = try {
        authed { api.reviewPrompt() }.prompt?.let {
            ReviewPrompt(it.milestone, it.count, it.title, it.body, it.buttonLabel, it.url, it.shown)
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.w("reviewPrompt() failed", e)
        null
    }

    /** Records a review prompt action; for `shown`, whether this device claimed it. */
    suspend fun reviewPromptAction(action: String, milestone: Int): Boolean = try {
        authed { api.reviewPromptAction(action, milestone) }.claimed ?: true
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.w("review prompt $action failed", e)
        false
    }

    /** Returns true when a notification should be shown (the chat is not on screen). */
    fun onSupportPush(data: Map<String, String>): Boolean {
        val showing = visible && appForeground
        if (showing) {
            scope.launch { if (!realtime.isOpen) catchUp() }
        } else {
            val badge = PushRecognizer.badge(data)
            if (badge != null) setUnread(badge) else scope.launch { refreshUnread() }
        }
        return !showing
    }

    // ------------------------------------------------------------------ appearance

    suspend fun refreshAppearance() {
        if (!host.usesRemoteAppearance || disabled) return
        try {
            when (val result = api.appearance(store.appearanceEtag.takeIf { remoteAppearance != null })) {
                is AppearanceResult.Fresh -> {
                    remoteAppearance = result.appearance
                    store.saveAppearance(result.appearance, result.etag)
                }
                AppearanceResult.NotModified -> Unit
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is ApiException && e.code == "unknown_app") handleError(e)
            Log.d("Appearance refresh failed")
        }
    }

    // ------------------------------------------------------------------ logout & delete

    fun logout() {
        val token = tokens.get()
        resetLocal()
        identity = null
        metadata = null
        if (token != null) scope.launch { runCatching { api.logout(token) } }
    }

    suspend fun deleteData() {
        if (tokens.get() != null) {
            try {
                api.deleteDevice()
            } catch (e: ApiException) {
                // Already gone server-side counts as deleted.
                if (e.status != 401 && e.status != 404) throw SupportException(e.code, e.message.orEmpty(), e.status, e)
            } catch (e: IOException) {
                throw SupportException("network", "Could not reach Support Yaps", 0, e)
            }
        }
        resetLocal()
        identity = null
        metadata = null
        store.pushToken = null
        host.emit(SupportEvent.DataDeleted)
    }

    private fun resetLocal() {
        tokens.clear()
        outbox.clear().forEach(::deleteFiles)
        store.clearSession()
        conversationId = null
        lastReadSent = 0
        customerVerified = false
        setUnread(0)
        failAckWaiters()
        OutboxWorker.cancel(context)
        SupportNotifications.cancel(context, null)
        _state.update {
            ChatState(loaded = true, connection = it.connection, restriction = if (disabled) Restriction.DISABLED else Restriction.NONE)
        }
    }

    // ------------------------------------------------------------------ device

    fun customerLanguage(): String =
        host.languageOverride?.takeIf { it.isNotBlank() }
            ?: _state.value.config?.translation?.customerLanguage?.takeIf { it.isNotBlank() }
            ?: deviceLanguage()

    private fun deviceLanguage(): String = Locale.getDefault().toLanguageTag().substringBefore('-').ifBlank { "en" }

    private fun deviceInfo(): DeviceInfo {
        val pm = context.packageManager
        val info = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
        val build = info?.let {
            if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toString() else @Suppress("DEPRECATION") it.versionCode.toString()
        }
        val locale = Locale.getDefault()
        return DeviceInfo(
            model = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" ").ifBlank { null },
            osVersion = Build.VERSION.RELEASE,
            appVersion = info?.versionName,
            appBuild = build,
            bundleId = context.packageName,
            locale = locale.toString(),
            language = host.languageOverride?.takeIf { it.isNotBlank() } ?: deviceLanguage(),
            timeZone = TimeZone.getDefault().id,
        )
    }

    private fun watchNetwork() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        // Start optimistic and only trust explicit callbacks: capability probes give false negatives
        // (emulators, some VPNs and OEM builds), and a wrong "offline" would stall every send.
        online = true
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    online = true
                    if (visible && _state.value.connection == ConnectionStatus.OFFLINE) setConnection(ConnectionStatus.CONNECTING)
                    kickFlush()
                }

                override fun onLost(network: Network) {
                    online = false
                    if (visible && tokens.get() != null) setConnection(ConnectionStatus.OFFLINE)
                }
            })
        }
    }

    val hasDeviceToken: Boolean get() = tokens.get() != null
}
