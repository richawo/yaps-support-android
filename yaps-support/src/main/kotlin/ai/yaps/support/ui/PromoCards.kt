package ai.yaps.support.ui

import ai.yaps.support.R
import ai.yaps.support.internal.core.AppPromoState
import ai.yaps.support.internal.core.CodeState
import ai.yaps.support.internal.core.RedeemHint
import ai.yaps.support.internal.core.appPromoState
import ai.yaps.support.internal.core.codeCard
import ai.yaps.support.internal.core.showsOwnCopyFeedback
import ai.yaps.support.internal.core.spokenCode
import ai.yaps.support.internal.protocol.Message
import android.os.Build
import android.text.format.DateUtils
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.delay

private val CardBody = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, letterSpacing = (-0.1).sp)
private val CodeText = TextStyle(
    fontSize = 21.sp,
    lineHeight = 26.sp,
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = 2.5.sp,
    textAlign = TextAlign.Center,
    // A code reads left to right in every locale; RTL only mirrors the card around it.
    textDirection = TextDirection.Ltr,
)

/** App icons are squircles on most launchers; 23% corners on a square is the closest plain shape. */
private val AppIconShape = RoundedCornerShape(percent = 23)
private val AppIconSize = 56.dp

// ---------------------------------------------------------------- app promo

@Composable
internal fun AppPromoCard(m: Message, env: BubbleEnv) {
    val colors = LocalSupportColors.current
    val card = m.card ?: return
    val state = appPromoState(card)
    if (state == AppPromoState.Text) return
    CardSurface(tag = stringResource(R.string.yaps_support_card_app), trailingTag = null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(card.iconUrl, card.title, env)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                card.title?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = TypeTokens.TrayTitle, color = colors.onIncoming, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                card.body?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = CardBody, color = colors.secondaryText, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        when (state) {
            AppPromoState.Available -> CardAction(
                label = card.buttonLabel?.takeIf { it.isNotBlank() } ?: stringResource(R.string.yaps_support_app_button),
                enabled = true,
                onClick = { env.onAppPromo(m) },
            )
            AppPromoState.Installed -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 14.dp).fillMaxWidth(),
            ) {
                Box(
                    Modifier.size(18.dp).clip(CircleShape).background(colors.onIncoming.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Done, contentDescription = null, tint = colors.onIncoming, modifier = Modifier.size(12.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.yaps_support_app_installed), style = TypeTokens.Status.copy(fontWeight = FontWeight.SemiBold), color = colors.secondaryText)
            }
            AppPromoState.Text -> Unit
        }
    }
}

/** The icon over a monogram placeholder, which stays if the icon fails to load. */
@Composable
private fun AppIcon(url: String?, title: String?, env: BubbleEnv) {
    val colors = LocalSupportColors.current
    val context = LocalContext.current
    var loaded by remember(url) { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(AppIconSize)
            .shadow(3.dp, AppIconShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f))
            .clip(AppIconShape)
            .background(colors.raised)
            .border(BorderStroke(0.5.dp, colors.divider), AppIconShape),
    ) {
        if (!loaded) {
            Text(
                text = title?.trim()?.firstOrNull()?.uppercase() ?: "·",
                style = TypeTokens.TrayTitle,
                color = colors.secondaryText,
            )
        }
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(url).memoryCacheKey("icon:$url").diskCacheKey("icon:$url").build(),
                imageLoader = env.imageLoader ?: SingletonImageLoader.get(context),
                contentDescription = null, // the title next to it names the app
                contentScale = ContentScale.Crop,
                onSuccess = { loaded = true },
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

// ---------------------------------------------------------------- code

@Composable
internal fun CodeCardView(m: Message, env: BubbleEnv) {
    val colors = LocalSupportColors.current
    val context = LocalContext.current
    val view = LocalView.current
    val card = m.card ?: return

    // Re-evaluate at expiresAt so a card on screen turns expired without a refresh.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val model = remember(card, now) { codeCard(card, now) } ?: return
    LaunchedEffect(model.expiresAtMillis, model.state) {
        val expiry = model.expiresAtMillis ?: return@LaunchedEffect
        val wait = expiry - System.currentTimeMillis()
        if (model.state != CodeState.Expired && wait in 1..DateUtils.DAY_IN_MILLIS) {
            delay(wait + 50)
            now = System.currentTimeMillis()
        }
    }

    // Our own "Copied" state only where the system shows none (before Android 13).
    var copiedTick by remember { mutableIntStateOf(0) }
    var showCopied by remember { mutableStateOf(false) }
    LaunchedEffect(copiedTick) {
        if (copiedTick == 0) return@LaunchedEffect
        showCopied = true
        delay(1_600)
        showCopied = false
    }
    val copy = {
        if (env.onCopyCode(m)) {
            view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP)
            if (showsOwnCopyFeedback(Build.VERSION.SDK_INT)) copiedTick++
        }
    }

    val expired = model.state == CodeState.Expired
    CardSurface(tag = stringResource(R.string.yaps_support_card_code), trailingTag = null) {
        card.title?.takeIf { it.isNotBlank() }?.let { Text(it, style = TypeTokens.TrayTitle, color = colors.onIncoming) }
        card.body?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = CardBody, color = colors.secondaryText, modifier = Modifier.padding(top = 2.dp))
        }

        val spoken = stringResource(R.string.yaps_support_code_spoken, spokenCode(model.code))
        val expiredLabel = stringResource(R.string.yaps_support_code_expired)
        CodeBox(
            code = model.code,
            expired = expired,
            description = if (expired) "$spoken, $expiredLabel" else spoken,
        )

        val expiry = model.expiresAtMillis
        if (!expired && expiry != null) {
            val date = remember(expiry) {
                DateUtils.formatDateTime(context, expiry, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH)
            }
            Text(
                stringResource(R.string.yaps_support_code_expires, date),
                style = TypeTokens.Meta,
                color = colors.faintText,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }

        when (model.state) {
            CodeState.Expired -> Text(
                expiredLabel,
                style = TypeTokens.Status.copy(fontWeight = FontWeight.SemiBold),
                color = colors.secondaryText,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            CodeState.Redeemable -> {
                val redeem = card.buttonLabel?.takeIf { it.isNotBlank() } ?: stringResource(R.string.yaps_support_code_redeem)
                // Side by side normally; stacked when large text would squeeze both labels.
                if (LocalDensity.current.fontScale >= 1.5f) {
                    Column {
                        CopyButton(showCopied, outlined = true, onClick = copy)
                        CardAction(label = redeem, enabled = true, onClick = { env.onRedeemCode(m) })
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CopyButton(showCopied, outlined = true, onClick = copy, modifier = Modifier.weight(1f))
                        CardAction(label = redeem, enabled = true, onClick = { env.onRedeemCode(m) }, modifier = Modifier.weight(1f))
                    }
                }
            }
            CodeState.CopyOnly -> {
                CopyButton(showCopied, outlined = false, onClick = copy)
                model.hint?.let { hint ->
                    Text(
                        stringResource(hint.label()),
                        style = TypeTokens.Status,
                        color = colors.secondaryText,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                }
            }
        }
    }
}

private fun RedeemHint.label(): Int = when (this) {
    RedeemHint.IPHONE -> R.string.yaps_support_code_redeem_ios
    RedeemHint.ANDROID -> R.string.yaps_support_code_redeem_android
    RedeemHint.WEB -> R.string.yaps_support_code_redeem_web
}

/** Monospaced, selectable, inside a dashed coupon outline. TalkBack reads it spelled out. */
@Composable
private fun CodeBox(code: String, expired: Boolean, description: String) {
    val colors = LocalSupportColors.current
    val density = LocalDensity.current
    val shape = RoundedCornerShape(12.dp)
    val dash = remember(density) { with(density) { PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) } }
    val outline = colors.onIncoming.copy(alpha = if (expired) 0.16f else 0.32f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(colors.background.copy(alpha = if (colors.isDark) 0.55f else 0.75f))
            .drawBehind {
                val stroke = 1.2.dp.toPx()
                drawRoundRect(
                    color = outline,
                    topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(12.dp.toPx() - stroke / 2),
                    style = Stroke(width = stroke, pathEffect = dash),
                )
            }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        SelectionContainer {
            Text(
                code,
                style = CodeText.copy(textDecoration = if (expired) TextDecoration.LineThrough else null),
                color = if (expired) colors.faintText else colors.onIncoming,
            )
        }
    }
}

@Composable
private fun CopyButton(copied: Boolean, outlined: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val copyLabel = stringResource(R.string.yaps_support_code_copy)
    val copiedLabel = stringResource(R.string.yaps_support_code_copied)
    Box(modifier.semantics { if (copied) liveRegion = LiveRegionMode.Polite }) {
        AnimatedContent(
            targetState = copied,
            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
            label = "copied",
        ) { done ->
            CardAction(
                label = if (done) copiedLabel else copyLabel,
                enabled = true,
                outlined = outlined,
                onClickLabel = stringResource(R.string.yaps_support_code_copy_label),
                onClick = onClick,
                leading = if (done) { ink -> Icon(Icons.Filled.Done, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp)) } else null,
            )
        }
    }
}
