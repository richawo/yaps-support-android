package ai.yaps.support

import ai.yaps.support.internal.core.AppearanceResolver
import ai.yaps.support.internal.core.Limits
import ai.yaps.support.internal.core.Log
import ai.yaps.support.internal.core.ReviewLink
import ai.yaps.support.internal.core.SupportEngine
import ai.yaps.support.internal.core.UrlLauncher
import ai.yaps.support.internal.media.MediaPreparer
import ai.yaps.support.internal.media.PrepareResult
import ai.yaps.support.internal.outbox.PendingAttachment
import ai.yaps.support.internal.protocol.Attachment
import ai.yaps.support.internal.protocol.Message
import ai.yaps.support.internal.protocol.WireJson
import ai.yaps.support.ui.BubbleEnv
import ai.yaps.support.ui.ChatContent
import ai.yaps.support.ui.ComposerActions
import ai.yaps.support.ui.ImageViewer
import ai.yaps.support.ui.LocalizedContent
import ai.yaps.support.ui.SupportTheme
import ai.yaps.support.ui.ViewerImage
import ai.yaps.support.ui.dynamicPalettes
import ai.yaps.support.ui.isDarkFor
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The support chat as a Composable. Drop it into a sheet, a dialog or a full-screen destination.
 * It draws edge-to-edge and handles status bar, navigation bar, cutout and IME insets itself
 * (insets your layout already consumed are respected).
 *
 * @param onClose shows a close button (unless the inbox hides it) and handles Back with a
 *   predictive-back animation. Also called by [YapsSupport.closeChat]. Pass `null` to manage
 *   dismissal yourself.
 */
@Composable
public fun SupportChat(modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    SupportChatHost(modifier = modifier, onClose = onClose, interceptBack = true)
}

@Composable
internal fun SupportChatHost(modifier: Modifier, onClose: (() -> Unit)?, interceptBack: Boolean) {
    val engine = YapsSupport.engine
    LocalizedContent(YapsSupport.languageOverride) {
        if (engine == null) {
            NotConfigured(modifier)
        } else {
            EngineChat(engine, modifier, onClose, interceptBack)
        }
    }
}

@Composable
private fun EngineChat(engine: SupportEngine, modifier: Modifier, onClose: (() -> Unit)?, interceptBack: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Remote appearance is snapshotted once per open, so a publish never restyles a chat mid-conversation.
    val appearance = remember(engine) {
        val dynamic = if (YapsSupport.appearance.useDynamicColor) dynamicPalettes(context) else null
        AppearanceResolver.resolve(
            remote = if (YapsSupport.usesRemoteAppearance) engine.remoteAppearance else null,
            local = YapsSupport.appearance,
            language = engine.customerLanguage(),
            dynamicLight = dynamic?.first,
            dynamicDark = dynamic?.second,
        )
    }
    val dark = isDarkFor(appearance.colorScheme)

    DisposableEffect(engine) {
        engine.onChatMounted()
        onDispose { engine.onChatUnmounted() }
    }
    LifecycleStartEffect(engine) {
        engine.onChatVisible()
        onStopOrDispose { engine.onChatHidden() }
    }
    val currentOnClose = onClose
    LaunchedEffect(engine) {
        engine.closeRequests.drop(1).collect { currentOnClose?.invoke() }
    }

    val state by engine.state.collectAsStateWithLifecycle()
    val availablePaywalls by engine.availablePaywallIds.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf(engine.loadDraft()) }
    val staged = rememberSaveable(saver = attachmentListSaver) { mutableStateListOf<PendingAttachment>() }
    var preparing by remember { mutableIntStateOf(0) }
    var viewer by remember { mutableStateOf<Pair<List<ViewerImage>, Int>?>(null) }
    var cameraFile by rememberSaveable { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    var trayOpen by rememberSaveable { mutableStateOf(false) }
    // Limits and failures are said where they happen, in the hint line under the composer, then clear.
    fun say(message: String) {
        hint = message
        scope.launch {
            delay(4_000)
            if (hint == message) hint = null
        }
    }

    val msgUnreadable = stringResource(R.string.yaps_support_image_unreadable)
    val msgTooLarge = stringResource(R.string.yaps_support_file_too_large)
    val msgUnsupported = stringResource(R.string.yaps_support_file_unsupported)
    val msgLimit = stringResource(R.string.yaps_support_attachment_limit)

    fun stage(uris: List<Uri>, image: Boolean, cleanup: File? = null) {
        val room = Limits.ATTACHMENTS - staged.size - preparing
        if (uris.size > room) say(msgLimit)
        val accepted = uris.take(room.coerceAtLeast(0))
        if (accepted.isEmpty()) return
        preparing += accepted.size
        scope.launch {
            for (uri in accepted) {
                val result = withContext(Dispatchers.IO) {
                    if (image) MediaPreparer.prepareImage(context, uri, engine.attachmentsDir)
                    else MediaPreparer.prepareFile(context, uri, engine.attachmentsDir)
                }
                preparing--
                when (result) {
                    is PrepareResult.Ok -> staged += result.attachment
                    PrepareResult.Unreadable -> say(msgUnreadable)
                    PrepareResult.TooLarge -> say(msgTooLarge)
                    PrepareResult.UnsupportedType -> say(msgUnsupported)
                }
            }
            cleanup?.let { withContext(Dispatchers.IO) { it.delete() } }
        }
    }

    // System Photo Picker: no storage permission on any API level (backported via Play services).
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(Limits.ATTACHMENTS)) { uris ->
        stage(uris, image = true)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val path = cameraFile
        cameraFile = null
        if (path != null) {
            val file = File(path)
            if (saved && file.length() > 0) stage(listOf(Uri.fromFile(file)), image = true, cleanup = file) else file.delete()
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) stage(listOf(uri), image = false)
    }

    val uploads = state.config?.uploads
    val composer = ComposerActions(
        onTextChange = {
            draft = it
            engine.onComposerChanged(it)
        },
        onSend = {
            if (engine.send(draft, staged.toList())) {
                draft = ""
                staged.clear()
                trayOpen = false
                hint = null
            }
        },
        onPickPhotos = if (uploads?.images != false) {
            { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        } else null,
        onTakePhoto = if (uploads?.images != false && hasCamera(context)) {
            {
                runCatching {
                    val (uri, file) = MediaPreparer.newCameraUri(context)
                    cameraFile = file.absolutePath
                    camera.launch(uri)
                }.onFailure { Log.w("Camera unavailable", it) }
            }
        } else null,
        onPickFile = if (uploads?.files != false) {
            { filePicker.launch(FILE_TYPES) }
        } else null,
        onRemove = {
            staged.remove(it)
            engine.discardStaged(it)
        },
    )

    val env = BubbleEnv(
        customerLanguage = engine.customerLanguage(),
        showNames = state.config?.teamDisplay?.showNames ?: true,
        showPhotos = state.config?.teamDisplay?.showPhotos ?: true,
        appName = state.config?.appName?.takeIf { it.isNotBlank() },
        availablePaywalls = availablePaywalls,
        hasPaywallHandler = YapsSupport.onPaywall != null,
        imageLoader = engine.imageLoader,
        onReview = { m -> openReview(context, m) },
        onOffer = { m -> m.card?.paywallId?.let { YapsSupport.openPaywall(m.id, it) } },
        onOpenImage = { images, index -> viewer = images to index },
        onOpenFile = { a -> openFile(context, a) },
        onRetry = engine::retry,
        onDiscard = engine::discard,
        paywall = { id -> YapsSupport.paywalls.firstOrNull { it.id == id } },
        onAppPromo = { m -> YapsSupport.cardActions.openAppPromo(m, contextLauncher(context)) },
        onCopyCode = { m -> YapsSupport.cardActions.copyCode(m) { code -> copyToClipboard(context, code) } },
        onRedeemCode = { m -> YapsSupport.cardActions.redeemCode(m, contextLauncher(context)) },
    )

    // Predictive back for embedded use: the chat shrinks with the gesture and closes on commit.
    var backProgress by remember { mutableFloatStateOf(0f) }
    if (interceptBack && onClose != null) {
        PredictiveBackHandler(enabled = viewer == null) { progress ->
            try {
                progress.collect { backProgress = it.progress }
                onClose()
            } catch (e: CancellationException) {
                backProgress = 0f
                throw e
            }
        }
    }
    BackHandler(enabled = viewer != null) { viewer = null }
    BackHandler(enabled = viewer == null && trayOpen) { trayOpen = false }

    SupportTheme(appearance, dark) {
        ChatContent(
            state = state,
            appearance = appearance,
            env = env,
            draft = draft,
            staged = staged,
            preparing = preparing,
            composer = composer,
            onLoadEarlier = engine::loadEarlier,
            onClose = onClose,
            hint = hint,
            trayOpen = trayOpen,
            onTrayOpenChange = { trayOpen = it },
            modifier = modifier.graphicsLayer {
                val s = 1f - 0.08f * backProgress
                scaleX = s
                scaleY = s
                alpha = 1f - 0.3f * backProgress
            },
        )
        viewer?.let { (images, index) -> ImageViewer(images, index, engine.imageLoader) { viewer = null } }
    }
}

@Composable
private fun NotConfigured(modifier: Modifier) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.yaps_support_unavailable),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(24.dp),
        )
    }
    LaunchedEffect(Unit) { Log.e("SupportChat shown before YapsSupport.configure()") }
}

private val FILE_TYPES = arrayOf(
    "application/pdf", "text/plain", "text/x-log", "application/zip", "application/x-zip-compressed", "application/octet-stream",
)

private val attachmentListSaver = listSaver<SnapshotStateList<PendingAttachment>, String>(
    save = { list -> list.map { WireJson.encodeToString(PendingAttachment.serializer(), it) } },
    restore = { saved -> mutableStateListOf<PendingAttachment>().apply { saved.forEach { add(WireJson.decodeFromString(PendingAttachment.serializer(), it)) } } },
)

private fun hasCamera(context: Context): Boolean =
    context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)

/** Review cards open the store listing (market:// first, then the Play web page). Never the In-App Review API. */
private fun openReview(context: Context, message: Message) {
    val candidates = ReviewLink.candidates(message.card?.url, context.packageName)
    for (url in candidates) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            YapsSupport.reviewOpened(message.id)
            return
        } catch (_: ActivityNotFoundException) {
            continue
        }
    }
    Log.w("No app can open the review link")
}

/** `ACTION_VIEW` for card links; `false` when no installed app handles the URL (e.g. no Play Store). */
private fun contextLauncher(context: Context) = UrlLauncher { url ->
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

private fun copyToClipboard(context: Context, code: String): Boolean = runCatching {
    val clipboard = ContextCompat.getSystemService(context, ClipboardManager::class.java) ?: return false
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.yaps_support_card_code), code))
    true
}.getOrElse {
    Log.w("Couldn't copy the code", it)
    false
}

private fun openFile(context: Context, attachment: Attachment) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(attachment.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Log.w("No app can open ${attachment.contentType}")
    }
}
