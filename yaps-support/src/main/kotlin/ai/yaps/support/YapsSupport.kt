package ai.yaps.support

import ai.yaps.support.internal.core.CardActions
import ai.yaps.support.internal.core.EngineHost
import ai.yaps.support.internal.core.Limits
import ai.yaps.support.internal.core.Log
import ai.yaps.support.internal.core.PushRecognizer
import ai.yaps.support.internal.core.SupportEngine
import ai.yaps.support.internal.protocol.IdentityInput
import ai.yaps.support.internal.protocol.PaywallProductWire
import ai.yaps.support.internal.protocol.PaywallWire
import ai.yaps.support.internal.push.SupportNotifications
import ai.yaps.support.internal.store.LocalStore
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.DrawableRes
import androidx.annotation.MainThread
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Support Yaps: in-app support chat.
 *
 * ```kotlin
 * // Application.onCreate
 * YapsSupport.configure(this, appId = "app_123")
 *
 * // After sign-in
 * YapsSupport.identify(userId = user.id, name = user.name, email = user.email)
 * YapsSupport.identify(token = jwtFromYourServer)  // optional: verified identity
 *
 * // Compose
 * SupportChat(onClose = { showSupport = false })
 * // Views
 * YapsSupport.openChat(context)
 * ```
 *
 * Every method is safe to call before [configure]; calls made earlier are applied once it runs
 * where that makes sense (identity, metadata, push token, paywalls), and ignored otherwise.
 */
public object YapsSupport {

    public const val DEFAULT_BASE_URL: String = "https://support.yaps.ai"

    /** SDK version, sent as `X-Support-SDK: android/<version>`. */
    @JvmStatic
    public val version: String = BuildConfig.SDK_VERSION

    private val main = Handler(Looper.getMainLooper())
    private val facadeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile internal var engine: SupportEngine? = null
        private set

    /** `true` once [configure] has run. */
    @JvmStatic
    public val isConfigured: Boolean get() = engine != null

    /** Logcat verbosity (tag `YapsSupport`). Device tokens, JWTs and message text are never logged. */
    @JvmStatic
    public var logLevel: LogLevel
        get() = Log.level
        set(value) {
            Log.level = value
        }

    /**
     * Sets up the SDK. Call once from `Application.onCreate`. Registers the device in the
     * background (no conversation is created until the first message) and fetches remote appearance.
     *
     * @param appId your public App ID (`app_…`) from the inbox.
     * @param baseUrl override for local development, e.g. `http://10.0.2.2:8787`.
     */
    @JvmStatic
    @JvmOverloads
    @MainThread
    public fun configure(context: Context, appId: String, baseUrl: String = DEFAULT_BASE_URL) {
        require(appId.isNotBlank()) { "YapsSupport.configure: appId must not be blank" }
        val existing = engine
        if (existing != null) {
            if (existing.appId == appId && existing.baseUrl == baseUrl) return
            Log.w("configure() called again with a different App ID or base URL; the new one wins")
        }
        val app = context.applicationContext
        SupportNotifications.ensureChannel(app)
        val created = SupportEngine(app, appId, baseUrl.trimEnd('/'), host)
        engine = created
        created.start()
        facadeScope.launch { created.unread.collect { _unreadCount.value = it } }
        facadeScope.launch { created.chatOpen.collect { _isChatOpen.value = it } }
        pendingIdentity?.let { created.identify(it) }
        pendingMetadata?.let { created.setMetadata(it) }
        pendingPush?.let { created.setPushToken(it.first, it.second) }
    }

    // ------------------------------------------------------------------ identity

    private var labels: IdentityInput? = null
    private var pendingIdentity: IdentityInput? = null
    private var pendingMetadata: Map<String, String>? = null
    private var pendingPush: Pair<String?, String>? = null

    /**
     * Attaches unverified labels to this device's support session. They help your team, but anyone
     * can claim any `userId`; use [identify] with a token to prove it.
     */
    @JvmStatic
    public fun identify(userId: String, name: String?, email: String?) {
        val input = IdentityInput(
            userId = userId.take(Limits.USER_ID),
            name = name?.take(Limits.NAME),
            email = email?.take(Limits.EMAIL),
            token = labels?.token,
        )
        labels = input
        applyIdentity(input)
    }

    /**
     * Verifies the user with an HS256 JWT minted by **your server** with the app's identity secret
     * (`ids_…`). Claims: `user_id` and `exp` (required), optional `name`, `email`, `nbf`. The token is
     * held in memory only. A verified user shares one conversation across devices and apps.
     * If the server rejects it you get [SupportEvent.IdentityRejected] and chat continues unverified.
     */
    @JvmStatic
    public fun identify(token: String) {
        if (token.length > Limits.IDENTITY_TOKEN) {
            Log.e("identify(token): JWT longer than ${Limits.IDENTITY_TOKEN} characters; ignored")
            return
        }
        val input = (labels ?: IdentityInput()).copy(token = token)
        labels = input
        applyIdentity(input)
    }

    private fun applyIdentity(input: IdentityInput) {
        val e = engine
        if (e == null) pendingIdentity = input else e.identify(input)
    }

    /** Replaces **all** metadata for this device (max 30 keys, `[A-Za-z0-9_.-]{1,64}`, values ≤ 512 chars). */
    @JvmStatic
    public fun setMetadata(metadata: Map<String, String>) {
        val clean = Limits.sanitizeMetadata(metadata)
        if (clean.size != metadata.size) Log.w("setMetadata: dropped invalid keys or entries beyond 30")
        val e = engine
        if (e == null) pendingMetadata = clean else e.setMetadata(clean)
    }

    /**
     * Ends this support session on this device: revokes the device token and clears local history,
     * drafts, the outbox and the unread count. Server history is kept. Call it when your user signs out.
     */
    @JvmStatic
    public fun logout() {
        labels = null
        pendingIdentity = null
        pendingMetadata = null
        engine?.logout()
    }

    /**
     * Deletes this device's registration and, if no other device belongs to the customer, their
     * conversations, messages and files. Then clears local state and emits [SupportEvent.DataDeleted].
     *
     * @throws SupportException when the server could not be reached or refused the request.
     */
    @JvmStatic
    public suspend fun deleteData() {
        labels = null
        pendingIdentity = null
        pendingMetadata = null
        val e = engine ?: return
        e.deleteData()
    }

    /** Java-friendly [deleteData]. [callback] runs on the main thread with `null` on success. */
    @JvmStatic
    public fun deleteDataAsync(callback: SupportCallback) {
        facadeScope.launch {
            val error = runCatching { deleteData() }.exceptionOrNull()
            callback.onComplete(error)
        }
    }

    // ------------------------------------------------------------------ UI

    /** Opens the chat as a full-screen [SupportActivity] (for View-based apps; Compose apps can use [SupportChat]). */
    @JvmStatic
    public fun openChat(context: Context) {
        val intent = SupportActivity.intent(context)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Closes any open chat: finishes [SupportActivity] and calls `onClose` of a visible [SupportChat]. */
    @JvmStatic
    public fun closeChat() {
        engine?.requestClose()
    }

    private val _unreadCount = MutableStateFlow(0)

    /** Agent replies the user has not seen. Updated from the server, from pushes, and cleared while the chat is open. */
    @JvmStatic
    public val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private val _isChatOpen = MutableStateFlow(false)

    /** Whether a chat ([SupportChat] or [SupportActivity]) is currently shown. */
    @JvmStatic
    public val isChatOpen: StateFlow<Boolean> = _isChatOpen.asStateFlow()

    /** The chat's local look. Remote values from the inbox win field by field when [usesRemoteAppearance] is on. */
    @JvmStatic
    public var appearance: SupportAppearance = SupportAppearance()

    /** Use the appearance published in the inbox (cached with ETag, applied on the next open). Default `true`. */
    @JvmStatic
    public var usesRemoteAppearance: Boolean = true

    /**
     * Language for the chat UI and translations (`"es"`, `"pt-BR"`, …), overriding the device and
     * per-app locale. `null` follows the system.
     */
    @JvmStatic
    public var languageOverride: String? = null
        set(value) {
            field = value?.takeIf { it.isNotBlank() }
            engine?.onLanguageChanged()
        }

    /** Small icon for support notifications. Should be a monochrome vector; defaults to a chat bubble. */
    @JvmStatic
    @DrawableRes
    public var notificationIcon: Int = R.drawable.yaps_support_ic_notification

    // ------------------------------------------------------------------ events

    private val _events = MutableSharedFlow<SupportEvent>(extraBufferCapacity = 64)

    /** Everything that happens in support. Hot flow: collect it for as long as you care. */
    @JvmStatic
    public val events: SharedFlow<SupportEvent> = _events.asSharedFlow()

    private val listeners = CopyOnWriteArrayList<SupportEventListener>()

    /** Java-friendly alternative to [events]. Listeners run on the main thread. */
    @JvmStatic
    public fun addEventListener(listener: SupportEventListener) {
        listeners.addIfAbsent(listener)
    }

    @JvmStatic
    public fun removeEventListener(listener: SupportEventListener) {
        listeners.remove(listener)
    }

    internal fun emit(event: SupportEvent) {
        _events.tryEmit(event)
        if (listeners.isNotEmpty()) main.post { listeners.forEach { it.onEvent(event) } }
    }

    // ------------------------------------------------------------------ paywalls

    /**
     * Paywalls your app can present. Agents pick one to send as an offer card; reported to the
     * server (max 20) and re-reported whenever you set this or call [refreshPaywalls].
     */
    @JvmStatic
    public var paywalls: List<SupportPaywall> = emptyList()
        set(value) {
            field = value.filter { Limits.isValidPaywallId(it.id) }.take(Limits.PAYWALLS)
            engine?.refreshPaywalls()
        }

    /** Called on the main thread when the user taps an offer card. Present your paywall for the given ID. */
    @JvmStatic
    public var onPaywall: ((paywallId: String) -> Unit)? = null

    /**
     * Optional check run before reporting paywalls: return `false` to hide one (for example the user
     * already owns it). Unavailable paywalls render as "No longer available" on existing cards.
     */
    @JvmStatic
    public var paywallAvailability: (suspend (paywallId: String) -> Boolean)? = null

    /** Re-evaluates [paywallAvailability] and reports the available list. Call after purchases. */
    @JvmStatic
    public fun refreshPaywalls() {
        engine?.refreshPaywalls()
    }

    // ------------------------------------------------------------------ automations

    /**
     * Reports an app event for automations (e.g. `"paywall_declined"`). Only names listed in the
     * app's tracked events are sent; others are dropped locally. With [opensChat] the server may ask
     * the SDK to open the chat (for an automation that greets the user).
     */
    @JvmStatic
    @JvmOverloads
    public fun track(name: String, opensChat: Boolean = false) {
        engine?.track(name, opensChat) ?: Log.w("track() before configure(); ignored")
    }

    // ------------------------------------------------------------------ push

    /**
     * Forwards the FCM registration token (from `FirebaseMessagingService.onNewToken` or
     * `FirebaseMessaging.getInstance().token`). Pass `null` to unregister this device from pushes.
     */
    @JvmStatic
    @JvmOverloads
    public fun setPushToken(token: String?, provider: PushProvider = PushProvider.FCM) {
        val e = engine
        if (e == null) pendingPush = token to provider.wire else e.setPushToken(token, provider.wire)
    }

    /** `true` when an FCM data payload belongs to Support Yaps (`support == "1"`). */
    @JvmStatic
    public fun isSupportPush(data: Map<String, String>?): Boolean = PushRecognizer.isSupportPush(data)

    /**
     * Handles a support push: updates [unreadCount] and shows a notification in the "Support"
     * channel, grouped by conversation, unless the chat is on screen. Returns `false` for payloads
     * that are not ours, so you can chain your own handling:
     *
     * ```kotlin
     * override fun onMessageReceived(message: RemoteMessage) {
     *     if (YapsSupport.handlePush(this, message.data)) return
     *     // your own pushes
     * }
     * ```
     */
    @JvmStatic
    public fun handlePush(context: Context, data: Map<String, String>): Boolean {
        if (!isSupportPush(data)) return false
        val app = context.applicationContext
        val e = engine ?: engineForBackgroundWork(app)
        val show = e?.onSupportPush(data) ?: true
        if (show) {
            main.post {
                SupportNotifications.show(app, data, notificationIcon, ContextCompat.getColor(app, R.color.yaps_support_notification_accent))
            }
        }
        return true
    }

    /**
     * Asks for `POST_NOTIFICATIONS` on Android 13+. The SDK never asks on its own: call this at a
     * moment that makes sense in your app (for example after the user sends their first message).
     * Returns `true` if notifications are already allowed (or the platform needs no runtime permission).
     */
    @JvmStatic
    @JvmOverloads
    public fun requestNotificationPermission(activity: Activity, requestCode: Int = NOTIFICATION_PERMISSION_REQUEST): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return true
        ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), requestCode)
        return false
    }

    /** Request code used by [requestNotificationPermission] when none is given. */
    public const val NOTIFICATION_PERMISSION_REQUEST: Int = 0x5A9

    // ------------------------------------------------------------------ internals

    /** Rebuilds the engine from the persisted App ID when a worker or push wakes a process before configure(). */
    internal fun engineForBackgroundWork(context: Context): SupportEngine? {
        engine?.let { return it }
        val (appId, baseUrl) = LocalStore(context.applicationContext).endpoint() ?: return null
        if (Looper.myLooper() == Looper.getMainLooper()) {
            configure(context, appId, baseUrl)
        } else {
            val latch = java.util.concurrent.CountDownLatch(1)
            main.post {
                runCatching { configure(context, appId, baseUrl) }
                latch.countDown()
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        }
        return engine
    }

    internal fun openPaywall(messageId: String, paywallId: String) {
        val handler = onPaywall ?: return
        main.post { handler(paywallId) }
        emit(SupportEvent.PaywallOpened(messageId, paywallId))
        engine?.recordCardAction(messageId)
    }

    internal fun reviewOpened(messageId: String) {
        emit(SupportEvent.ReviewOpened(messageId))
        engine?.recordCardAction(messageId)
    }

    /** App promo and code card taps: events on [events], receipts through the engine. */
    internal val cardActions: CardActions = CardActions(
        emit = { emit(it) },
        receipt = { messageId, action -> engine?.recordCardAction(messageId, action) },
    )

    private val host = object : EngineHost {
        override fun emit(event: SupportEvent) = this@YapsSupport.emit(event)
        override val languageOverride: String? get() = this@YapsSupport.languageOverride
        override val usesRemoteAppearance: Boolean get() = this@YapsSupport.usesRemoteAppearance
        override fun openChat(context: Context) = this@YapsSupport.openChat(context)
        override suspend fun availablePaywalls(): List<PaywallWire> {
            val check = paywallAvailability
            return paywalls
                .filter { p -> check == null || runCatching { check(p.id) }.getOrDefault(true) }
                .map { p ->
                    PaywallWire(
                        id = p.id,
                        title = p.title.take(60),
                        products = p.products.map { PaywallProductWire(it.id, it.price, it.period, it.trial) },
                    )
                }
        }
    }
}

/** Completion callback for Java callers of [YapsSupport.deleteDataAsync]. */
public fun interface SupportCallback {
    public fun onComplete(error: Throwable?)
}
