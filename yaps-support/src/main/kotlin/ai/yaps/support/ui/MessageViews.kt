package ai.yaps.support.ui

import ai.yaps.support.R
import ai.yaps.support.SupportCornerStyle
import ai.yaps.support.SupportPaywall
import ai.yaps.support.internal.core.TranslationRule
import ai.yaps.support.internal.core.isLinkAvailable
import ai.yaps.support.internal.outbox.PendingAttachment
import ai.yaps.support.internal.protocol.Attachment
import ai.yaps.support.internal.protocol.AuthorType
import ai.yaps.support.internal.protocol.LinkTarget
import ai.yaps.support.internal.protocol.Message
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import java.io.File
import java.util.Locale

/** Everything a row needs from the screen, bundled so rows stay cheap to recompose. */
internal class BubbleEnv(
    val customerLanguage: String,
    val showNames: Boolean,
    val showPhotos: Boolean,
    val appName: String?,
    val availablePaywalls: Set<String>,
    val hasPaywallHandler: Boolean,
    val imageLoader: ImageLoader?,
    val onReview: (Message) -> Unit,
    val onOffer: (Message) -> Unit,
    val onOpenImage: (images: List<ViewerImage>, index: Int) -> Unit,
    val onOpenFile: (Attachment) -> Unit,
    val onRetry: (String) -> Unit,
    val onDiscard: (String) -> Unit,
    val paywall: (String) -> SupportPaywall? = { null },
    /** App promo "Get it". */
    val onAppPromo: (Message) -> Unit = {},
    /** Code card Copy; `true` once the code is on the clipboard. */
    val onCopyCode: (Message) -> Boolean = { false },
    /** Code card Redeem. */
    val onRedeemCode: (Message) -> Unit = {},
    /** IDs in `YapsSupport.screens` and whether `onScreen` is set, for screen link cards. */
    val screenIds: Set<String> = emptySet(),
    val hasScreenHandler: Boolean = false,
    /** Link card button. */
    val onLink: (Message) -> Unit = {},
    /** Rating card star, 1–5. */
    val onRate: (Message, Int) -> Unit = { _, _ -> },
)

internal data class ViewerImage(val url: String, val cacheKey: String?, val description: String?)

private val BubbleMaxWidth = 300.dp

// ---------------------------------------------------------------- day divider

@Composable
internal fun DayHeader(dayStartMillis: Long) {
    val context = LocalContext.current
    val label = remember(dayStartMillis) {
        val now = System.currentTimeMillis()
        val text = if (now - dayStartMillis < 2 * DateUtils.DAY_IN_MILLIS) {
            DateUtils.getRelativeTimeSpanString(dayStartMillis, now, DateUtils.DAY_IN_MILLIS).toString()
        } else {
            DateUtils.formatDateTime(context, dayStartMillis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL)
        }
        text.uppercase(Locale.getDefault())
    }
    Text(
        text = label,
        style = TypeTokens.Tag,
        color = LocalSupportColors.current.faintText,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp).semantics { heading() },
    )
}

// ---------------------------------------------------------------- rows

@Composable
internal fun ServerMessageRow(item: TimelineItem.Server, env: BubbleEnv, faded: Boolean, entrance: Entrance, delayMillis: Int) {
    val m = item.message
    val outgoing = m.author.type == AuthorType.CUSTOMER
    val context = LocalContext.current
    val text = TranslationRule.displayText(m, env.customerLanguage)
    val authorName = when {
        outgoing -> stringResource(R.string.yaps_support_you)
        m.author.type == AuthorType.SYSTEM -> env.appName ?: stringResource(R.string.yaps_support_team)
        env.showNames && !m.author.name.isNullOrBlank() -> m.author.name
        else -> stringResource(R.string.yaps_support_team)
    }
    val time = remember(item.millis) { if (item.millis > 0) DateFormat.getTimeFormat(context).format(item.millis) else "" }
    val presentation = cardPresentation(m)
    val images = m.attachments.filter { it.isImage }
    val files = m.attachments.filterNot { it.isImage }
    val viewerImages = images.map { ViewerImage(it.url, "att:${it.id}", it.filename) }
    val receipt = item.status?.let { receiptText(it, time) }
    val description = listOfNotNull(
        stringResource(R.string.yaps_support_message_from, authorName, text.ifBlank { stringResource(R.string.yaps_support_photo) }),
        time.takeIf { it.isNotEmpty() && receipt == null },
        receipt,
    ).joinToString(", ")

    RowFrame(
        outgoing = outgoing,
        firstInRun = item.firstInRun,
        lastInRun = item.lastInRun,
        modifier = Modifier.alpha(if (faded) 0.55f else 1f).entrance(if (outgoing && entrance == Entrance.Fly) Entrance.Fly else entrance, delayMillis),
        avatar = { if (!outgoing) Avatar(m, authorName, env) },
        name = if (!outgoing && item.firstInRun && (env.showNames || m.author.type == AuthorType.SYSTEM)) authorName else null,
        footer = item.status?.let { s -> { Receipt(s, time) } },
        description = description,
    ) {
        val hasText = text.isNotBlank()
        if (images.isNotEmpty()) {
            ImageStack(
                models = images.map { att ->
                    // Signed URLs rotate hourly; cache by attachment id so a refreshed URL is not a miss.
                    ImageRequest.Builder(context).data(att.thumbnailUrl ?: att.url)
                        .memoryCacheKey("att:${att.id}:thumb").diskCacheKey("att:${att.id}:thumb").build()
                },
                aspects = images.map { aspectOf(it.width, it.height) },
                outgoing = outgoing,
                firstInRun = item.firstInRun,
                lastInRun = item.lastInRun && !hasText && files.isEmpty() && presentation.isTextOnly(),
                env = env,
                onOpen = { index -> env.onOpenImage(viewerImages, index) },
            )
        }
        files.forEach { FileTile(it.filename ?: "file", it.size, onClick = { env.onOpenFile(it) }) }
        if (hasText) {
            Bubble(outgoing, item.firstInRun && images.isEmpty(), item.lastInRun && presentation.isTextOnly()) {
                SelectionContainer {
                    Text(
                        text = linkify(text, if (outgoing) LocalSupportColors.current.onAccent else LocalSupportColors.current.onIncoming),
                        style = TypeTokens.Bubble,
                        color = if (outgoing) LocalSupportColors.current.onAccent else LocalSupportColors.current.onIncoming,
                    )
                }
            }
        }
        when (presentation) {
            CardPresentation.Review -> ReviewCard(m, env)
            CardPresentation.Offer -> OfferCard(m, env)
            CardPresentation.AppPromo -> AppPromoCard(m, env)
            CardPresentation.Code -> CodeCardView(m, env)
            CardPresentation.Link -> LinkCard(m, env)
            CardPresentation.Rating -> RatingCard(m, env)
            CardPresentation.PlainText, CardPresentation.None -> Unit
        }
    }
}

private fun CardPresentation.isTextOnly() = this == CardPresentation.None || this == CardPresentation.PlainText

@Composable
internal fun PendingMessageRow(item: TimelineItem.Pending, env: BubbleEnv, entrance: Entrance) {
    val e = item.entry
    val context = LocalContext.current
    val failed = e.outgoingStatus() == OutgoingStatus.FAILED
    val images = e.attachments.filter { it.kind == "image" }
    val files = e.attachments.filterNot { it.kind == "image" }
    val statusLabel = stringResource(if (failed) R.string.yaps_support_status_failed else R.string.yaps_support_status_sending)
    val description = listOf(
        stringResource(R.string.yaps_support_message_from, stringResource(R.string.yaps_support_you), e.text.ifBlank { stringResource(R.string.yaps_support_photo) }),
        statusLabel,
    ).joinToString(", ")
    RowFrame(
        outgoing = true,
        firstInRun = item.firstInRun,
        lastInRun = item.lastInRun,
        modifier = Modifier.entrance(entrance),
        avatar = {},
        name = null,
        footer = {
            if (failed) FailedLine(onRetry = { env.onRetry(e.clientId) }, onDiscard = { env.onDiscard(e.clientId) })
            else SendingLine(item.progress)
        },
        description = description,
        liveRegion = !failed,
        dimmed = failed,
    ) {
        if (images.isNotEmpty()) {
            ImageStack(
                models = images.map { ImageRequest.Builder(context).data(File(it.localPath)).build() },
                aspects = images.map { aspectOf(it.width, it.height) },
                outgoing = true,
                firstInRun = item.firstInRun,
                lastInRun = item.lastInRun && e.text.isBlank() && files.isEmpty(),
                env = env,
                onOpen = null,
            )
        }
        files.forEach { FileTile(it.filename ?: "file", it.size, onClick = null) }
        if (e.text.isNotBlank()) {
            Bubble(outgoing = true, firstInRun = item.firstInRun && images.isEmpty(), lastInRun = item.lastInRun) {
                Text(e.text, style = TypeTokens.Bubble, color = LocalSupportColors.current.onAccent)
            }
        }
    }
}

/**
 * Row skeleton from the mobile-chat brief: 14dp sides; theirs get a 34dp avatar gutter (26dp avatar
 * on the last bubble of a group) and 34dp trailing room; yours 60dp leading room; groups 16dp apart,
 * 3dp between bubbles of a group.
 */
@Composable
private fun RowFrame(
    outgoing: Boolean,
    firstInRun: Boolean,
    lastInRun: Boolean,
    modifier: Modifier,
    avatar: @Composable () -> Unit,
    name: String?,
    footer: (@Composable () -> Unit)?,
    description: String,
    liveRegion: Boolean = false,
    dimmed: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = LocalSupportColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = Metrics.SidePadding + if (outgoing) Metrics.MineLeading - Metrics.SidePadding else 0.dp,
                end = Metrics.SidePadding + if (outgoing) 0.dp else Metrics.TheirsTrailing - Metrics.SidePadding,
                top = if (firstInRun) Metrics.GroupGap else Metrics.InGroupGap,
            ),
        horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
    ) {
        if (name != null) {
            Text(
                name,
                style = TypeTokens.Status.copy(fontWeight = FontWeight.SemiBold),
                color = colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = Metrics.AvatarGutter + 4.dp, bottom = 4.dp),
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            if (!outgoing) {
                Box(Modifier.width(Metrics.AvatarGutter)) { if (lastInRun) avatar() }
            }
            Column(
                horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(Metrics.InGroupGap),
                modifier = Modifier
                    .weight(1f, fill = false)
                    .alpha(if (dimmed) 0.6f else 1f)
                    .semantics(mergeDescendants = true) {
                        contentDescription = description
                        if (liveRegion) this.liveRegion = LiveRegionMode.Polite
                    },
            ) { content() }
        }
        if (footer != null) {
            Box(Modifier.padding(top = 5.dp, start = if (outgoing) 0.dp else Metrics.AvatarGutter, end = 2.dp)) { footer() }
        }
    }
}

// ---------------------------------------------------------------- bubble

/** Corners per the brief: 20, grouped side 7, the tail corner 10, plus the SVG tail on the last bubble. */
@Composable
private fun bubbleShape(outgoing: Boolean, firstInRun: Boolean, lastInRun: Boolean): RoundedCornerShape {
    val s = LocalSupportShapes.current
    return if (outgoing) {
        RoundedCornerShape(topStart = s.bubble, topEnd = if (firstInRun) s.bubble else s.tight, bottomEnd = if (lastInRun) s.tail else s.tight, bottomStart = s.bubble)
    } else {
        RoundedCornerShape(topStart = if (firstInRun) s.bubble else s.tight, topEnd = s.bubble, bottomEnd = s.bubble, bottomStart = if (lastInRun) s.tail else s.tight)
    }
}

@Composable
private fun Bubble(outgoing: Boolean, firstInRun: Boolean, lastInRun: Boolean, content: @Composable () -> Unit) {
    val colors = LocalSupportColors.current
    val appearance = LocalAppearance.current
    val shape = bubbleShape(outgoing, firstInRun, lastInRun)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val tailRight = outgoing != rtl
    val showTail = lastInRun && appearance?.cornerStyle != SupportCornerStyle.SQUARE
    val brush = if (outgoing) {
        Brush.verticalGradient(listOf(colors.accentTokens.top, colors.accentTokens.mid, colors.accentTokens.bottom))
    } else {
        Brush.verticalGradient(listOf(colors.incomingTop, colors.incoming))
    }
    val tailColor = if (outgoing) colors.accentTokens.bottom else colors.incoming
    Box(
        Modifier
            .widthIn(max = BubbleMaxWidth)
            .then(if (outgoing) Modifier.shadow(5.dp, shape, clip = false, ambientColor = colors.accentTokens.glow, spotColor = colors.accentTokens.glow) else Modifier)
            .drawBehind { if (showTail) drawTail(tailRight, tailColor) }
            .clip(shape)
            .background(brush)
            .then(if (!outgoing && !colors.isDark) Modifier.border(BorderStroke(0.5.dp, colors.divider), shape) else Modifier)
            .drawWithContent {
                drawContent()
                // Inset top edge: a lit rim a few percent brighter than the surface.
                drawLine(colors.highlight, Offset(size.height * 0.15f, 0.5f), Offset(size.width - size.height * 0.15f, 0.5f), strokeWidth = 1f)
            }
            .padding(start = 14.dp, end = 14.dp, top = 9.dp, bottom = 10.dp),
    ) { content() }
}

/** The brief's 17 × 21 tail (`M0 3h11c0 8 1.4 13.6 6 17.8-4.6.6-9.4-.4-12.6-3.2L0 17.6Z`), overhanging by 6dp. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTail(onRight: Boolean, color: Color) {
    val u = density // path is in dp units
    val w = Metrics.TailWidth.value
    val left = if (onRight) size.width - (w - Metrics.TailOverhang.value) * u else -Metrics.TailOverhang.value * u
    val top = size.height - Metrics.TailHeight.value * u
    fun x(v: Float) = left + (if (onRight) v else w - v) * u
    fun y(v: Float) = top + v * u
    val path = Path().apply {
        moveTo(x(0f), y(3f))
        lineTo(x(11f), y(3f))
        cubicTo(x(11f), y(11f), x(12.4f), y(16.6f), x(17f), y(20.8f))
        cubicTo(x(12.4f), y(21.4f), x(7.6f), y(20.4f), x(4.4f), y(17.6f))
        lineTo(x(0f), y(17.6f))
        close()
    }
    drawPath(path, color)
}

// ---------------------------------------------------------------- avatar

@Composable
private fun Avatar(m: Message, name: String, env: BubbleEnv) {
    val colors = LocalSupportColors.current
    val url = m.author.avatarUrl
    // Neutral graphite disc: hue lives only in the accent.
    val disc = Modifier.size(Metrics.Avatar).clip(CircleShape).background(colors.onIncoming.copy(alpha = 0.09f))
    if (env.showPhotos && !url.isNullOrBlank() && env.imageLoader != null) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(url).memoryCacheKey("avatar:${m.author.id ?: url}").build(),
            imageLoader = env.imageLoader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = disc,
        )
    } else {
        Box(disc, contentAlignment = Alignment.Center) {
            Text(
                text = name.trim().firstOrNull()?.uppercase() ?: "·",
                style = TypeTokens.Meta.copy(fontWeight = FontWeight.SemiBold),
                color = colors.secondaryText,
            )
        }
    }
}

// ---------------------------------------------------------------- media

private fun aspectOf(w: Int?, h: Int?): Float = if (w != null && h != null && w > 0 && h > 0) w.toFloat() / h else 0.75f

/** One photo at its own aspect; several fan out like a hand of cards (−7°, 0°, 7°). */
@Composable
private fun ImageStack(
    models: List<ImageRequest>,
    aspects: List<Float>,
    outgoing: Boolean,
    firstInRun: Boolean,
    lastInRun: Boolean,
    env: BubbleEnv,
    onOpen: ((Int) -> Unit)?,
) {
    val colors = LocalSupportColors.current
    val context = LocalContext.current
    val loader = env.imageLoader ?: coil3.SingletonImageLoader.get(context)
    val openLabel = stringResource(R.string.yaps_support_open_photo)
    val photo = stringResource(R.string.yaps_support_photo)
    val interaction = remember { MutableInteractionSource() }
    val clickable = if (onOpen != null) {
        Modifier.clickable(interaction, indication = null, role = Role.Image, onClickLabel = openLabel) { onOpen(0) }
    } else Modifier
    if (models.size == 1) {
        AsyncImage(
            model = models[0],
            imageLoader = loader,
            contentDescription = photo,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .squish(interaction, 0.97f)
                .widthIn(max = 240.dp)
                .fillMaxWidth()
                .aspectRatio(aspects[0].coerceIn(0.6f, 1.8f))
                .clip(bubbleShape(outgoing, firstInRun, lastInRun))
                .background(colors.raised)
                .then(clickable),
        )
        return
    }
    val shown = models.take(3)
    val angles = if (shown.size == 2) listOf(-5f, 5f) else listOf(-7f, 0f, 7f)
    val radius = LocalSupportShapes.current.card - 4.dp
    Box(
        Modifier
            .squish(interaction, 0.97f)
            .width(232.dp)
            .height(196.dp)
            .then(clickable)
            .semantics(mergeDescendants = true) { contentDescription = "$photo × ${models.size}" },
        contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        shown.forEachIndexed { i, model ->
            val shift = (i - (shown.size - 1) / 2f) * 46f
            AsyncImage(
                model = model,
                imageLoader = loader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .offset(x = (shift + if (outgoing) -46f else 46f).dp)
                    .size(width = 128.dp, height = 168.dp)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        rotationZ = angles[i]
                    }
                    .shadow(6.dp, RoundedCornerShape(radius), clip = false, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
                    .clip(RoundedCornerShape(radius))
                    .border(BorderStroke(2.dp, colors.background), RoundedCornerShape(radius))
                    .background(colors.raised),
            )
        }
        if (models.size > 3) {
            Text(
                "+${models.size - 3}",
                style = TypeTokens.Meta.copy(fontWeight = FontWeight.SemiBold),
                color = colors.background,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .clip(CircleShape)
                    .background(colors.onIncoming.copy(alpha = 0.85f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun FileTile(name: String, size: Long, onClick: (() -> Unit)?) {
    val colors = LocalSupportColors.current
    val context = LocalContext.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .squish(interaction, 0.97f)
            .widthIn(min = 200.dp, max = BubbleMaxWidth)
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(LocalSupportShapes.current.card - 4.dp))
            .background(Brush.verticalGradient(listOf(colors.cardTop, colors.cardBottom)))
            .border(BorderStroke(0.5.dp, colors.divider), RoundedCornerShape(LocalSupportShapes.current.card - 4.dp))
            .then(if (onClick != null) Modifier.clickable(interaction, indication = null, role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(colors.onIncoming.copy(alpha = 0.07f)), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.yaps_support_ic_file), contentDescription = null, tint = colors.secondaryText, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(middleEllipsis(name, 26), style = TypeTokens.TileName, color = colors.onIncoming, maxLines = 1)
            Text(
                "${name.substringAfterLast('.', "").uppercase(Locale.ROOT)} · ${Formatter.formatShortFileSize(context, size)}",
                style = TypeTokens.Tag,
                color = colors.secondaryText,
            )
        }
    }
}

internal fun middleEllipsis(name: String, max: Int): String {
    if (name.length <= max) return name
    val keep = max - 1
    return name.take(keep - keep / 2) + "…" + name.takeLast(keep / 2)
}

// ---------------------------------------------------------------- cards

@Composable
internal fun CardSurface(tag: String, trailingTag: String?, content: @Composable () -> Unit) {
    val colors = LocalSupportColors.current
    val radius = LocalSupportShapes.current.card
    val shape = RoundedCornerShape(radius)
    Column(
        Modifier
            .widthIn(min = 260.dp, max = BubbleMaxWidth)
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(colors.cardTop, colors.cardBottom)))
            .drawBehind {
                // A soft light pooled in the top-end corner: the card's one source of light.
                drawCircle(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = if (colors.isDark) 0.07f else 0.5f), Color.Transparent), center = Offset(size.width, 0f), radius = size.width * 0.7f),
                    radius = size.width * 0.7f,
                    center = Offset(size.width, 0f),
                )
            }
            .border(BorderStroke(0.5.dp, colors.divider), shape)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tag.uppercase(Locale.getDefault()), style = TypeTokens.Tag, color = colors.secondaryText, modifier = Modifier.weight(1f))
            if (trailingTag != null) Text(trailingTag, style = TypeTokens.Tag, color = colors.faintText)
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/**
 * A card's button: filled with neutral ink, or [outlined] for a secondary action beside a filled one.
 * [leading] draws before the label (a check after Copy, say); [onClickLabel] is what TalkBack says
 * the double tap does.
 */
@Composable
internal fun CardAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    outlined: Boolean = false,
    onClickLabel: String? = null,
    topPadding: Dp = 14.dp,
    leading: (@Composable (ink: Color) -> Unit)? = null,
) {
    val colors = LocalSupportColors.current
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    // Neutral ink, not the accent: the accent stays on your bubbles and the send button.
    val fill = when {
        !enabled -> colors.onIncoming.copy(alpha = 0.08f)
        outlined -> Color.Transparent
        else -> colors.onIncoming
    }
    val ink = when {
        !enabled -> colors.faintText
        outlined -> colors.onIncoming
        else -> inkOn(colors.onIncoming)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .padding(top = topPadding)
            .squish(interaction, 0.97f)
            .fillMaxWidth()
            .heightIn(min = Metrics.Control)
            .clip(shape)
            .background(fill)
            .then(if (outlined && enabled) Modifier.border(BorderStroke(1.dp, colors.onIncoming.copy(alpha = 0.28f)), shape) else Modifier)
            .clickable(interaction, indication = androidx.compose.material3.ripple(color = ink), enabled = enabled, role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (leading != null) {
                leading(ink)
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = TypeTokens.TileName.copy(fontSize = TypeTokens.Bubble.fontSize), color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun OfferCard(m: Message, env: BubbleEnv) {
    val colors = LocalSupportColors.current
    val card = m.card ?: return
    val available = isOfferAvailable(card.available, card.paywallId, env.availablePaywalls, env.hasPaywallHandler)
    val paywall = card.paywallId?.let(env.paywall)
    val product = paywall?.products?.firstOrNull { !it.price.isNullOrBlank() }
    CardSurface(tag = stringResource(R.string.yaps_support_card_offer), trailingTag = null) {
        Column(Modifier.alpha(if (available) 1f else 0.55f)) {
            val title = card.title?.takeIf { it.isNotBlank() } ?: paywall?.title
            if (!title.isNullOrBlank()) {
                Text(title, style = TypeTokens.TrayTitle, color = colors.onIncoming)
            }
            if (product != null) {
                // The title carries the period ("Yearly 40% off"); the figure is the price the user will pay.
                Text(product.price.orEmpty(), style = TypeTokens.Figure, color = colors.onIncoming, modifier = Modifier.padding(top = 6.dp))
            }
        }
        CardAction(
            label = if (available) card.buttonLabel?.takeIf { it.isNotBlank() } ?: stringResource(R.string.yaps_support_offer_button)
            else stringResource(R.string.yaps_support_offer_unavailable),
            enabled = available,
            onClick = { env.onOffer(m) },
        )
    }
}

/**
 * PROTOCOL §5 link card: the message text is drawn as a bubble above, so the card is just its button,
 * with an icon for where it goes. Unavailable links keep the button, disabled, with the offer card's
 * "No longer available".
 */
@Composable
private fun LinkCard(m: Message, env: BubbleEnv) {
    val card = m.card ?: return
    val available = isLinkAvailable(card, env.screenIds, env.hasScreenHandler)
    val icon = when (card.target) {
        LinkTarget.APP_SETTINGS -> Icons.Filled.Settings
        LinkTarget.SCREEN -> Icons.AutoMirrored.Filled.KeyboardArrowRight
        else -> when (card.url?.trim()?.substringBefore(':')?.lowercase()) {
            "mailto" -> Icons.Filled.Email
            "tel" -> Icons.Filled.Call
            "sms" -> Icons.AutoMirrored.Filled.Send
            else -> Icons.AutoMirrored.Filled.ArrowForward
        }
    }
    CardAction(
        label = if (available) card.buttonLabel.orEmpty() else stringResource(R.string.yaps_support_offer_unavailable),
        enabled = available,
        onClick = { env.onLink(m) },
        modifier = Modifier.widthIn(min = 260.dp, max = BubbleMaxWidth),
        topPadding = 0.dp,
        leading = if (available) { ink -> Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp)) } else null,
    )
}

/** Rating card: five stars under the "How did we do?" bubble, then the thanks note. */
@Composable
private fun RatingCard(m: Message, env: BubbleEnv) {
    val colors = LocalSupportColors.current
    val score = m.card?.rating?.takeIf { it in 1..5 } ?: 0
    CardSurface(tag = stringResource(R.string.yaps_support_card_rating), trailingTag = env.appName?.uppercase(Locale.getDefault())) {
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.Center) {
            for (n in 1..5) {
                val label = stringResource(R.string.yaps_support_rating_stars, n)
                Box(
                    Modifier
                        .size(44.dp)
                        .selectable(selected = n == score, role = Role.RadioButton, onClick = { env.onRate(m, n) })
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint = if (n <= score) colors.accent else colors.faintText,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
        }
        if (score > 0) {
            Text(
                stringResource(R.string.yaps_support_rating_thanks),
                style = TypeTokens.TrayTitle,
                color = colors.onIncoming,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun ReviewCard(m: Message, env: BubbleEnv) {
    val colors = LocalSupportColors.current
    val card = m.card ?: return
    CardSurface(tag = stringResource(R.string.yaps_support_card_review), trailingTag = env.appName?.uppercase(Locale.getDefault())) {
        card.title?.takeIf { it.isNotBlank() }?.let { Text(it, style = TypeTokens.TrayTitle, color = colors.onIncoming) }
        CardAction(
            label = card.buttonLabel?.takeIf { it.isNotBlank() } ?: stringResource(R.string.yaps_support_review_button),
            enabled = true,
            onClick = { env.onReview(m) },
        )
    }
}

// ---------------------------------------------------------------- receipts

@Composable
private fun receiptText(status: OutgoingStatus, time: String): String = when (status) {
    OutgoingStatus.READ -> stringResource(R.string.yaps_support_read_at, time)
    OutgoingStatus.SENT -> stringResource(R.string.yaps_support_sent_at, time)
    OutgoingStatus.SENDING -> stringResource(R.string.yaps_support_status_sending)
    OutgoingStatus.FAILED -> stringResource(R.string.yaps_support_status_failed)
}

@Composable
private fun Receipt(status: OutgoingStatus, time: String) {
    val colors = LocalSupportColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(receiptText(status, time).trim(), style = TypeTokens.Meta, color = colors.secondaryText)
        Spacer(Modifier.width(4.dp))
        when (status) {
            OutgoingStatus.READ -> Icon(painterResource(R.drawable.yaps_support_ic_done_all), null, tint = colors.secondaryText, modifier = Modifier.size(14.dp))
            OutgoingStatus.SENT -> Icon(Icons.Filled.Done, null, tint = colors.faintText, modifier = Modifier.size(13.dp))
            else -> Unit
        }
    }
}

@Composable
private fun SendingLine(progress: Float?) {
    val colors = LocalSupportColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (progress != null && progress < 1f) {
            CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = colors.onIncoming, trackColor = colors.divider)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.yaps_support_uploading, (progress * 100).toInt()), style = TypeTokens.Meta, color = colors.secondaryText)
        } else {
            Text(stringResource(R.string.yaps_support_status_sending), style = TypeTokens.Meta, color = colors.faintText)
        }
    }
}

@Composable
private fun FailedLine(onRetry: () -> Unit, onDiscard: () -> Unit) {
    val colors = LocalSupportColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(colors.onIncoming), contentAlignment = Alignment.Center) {
            Text("!", style = TypeTokens.Meta.copy(fontWeight = FontWeight.Bold), color = colors.background)
        }
        Text(stringResource(R.string.yaps_support_status_failed), style = TypeTokens.Meta.copy(fontWeight = FontWeight.SemiBold), color = colors.error)
        Pill(stringResource(R.string.yaps_support_retry), strong = true, onClick = onRetry)
        Pill(stringResource(R.string.yaps_support_delete), strong = false, onClick = onDiscard)
    }
}

/** 32dp visual pill inside a 44dp touch target. */
@Composable
internal fun Pill(label: String, strong: Boolean, onClick: () -> Unit) {
    val colors = LocalSupportColors.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = Metrics.Control)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { role = Role.Button },
    ) {
        Text(
            label,
            style = TypeTokens.Meta.copy(fontWeight = FontWeight.SemiBold),
            color = if (strong) colors.onIncoming else colors.secondaryText,
            modifier = Modifier
                .squish(interaction, 0.92f)
                .clip(CircleShape)
                .border(BorderStroke(if (strong) 1.dp else 0.5.dp, if (strong) colors.onIncoming.copy(alpha = 0.5f) else colors.divider), CircleShape)
                .padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

// ---------------------------------------------------------------- typing

@Composable
internal fun TypingIndicator(name: String?) {
    val colors = LocalSupportColors.current
    val reduced = LocalReducedMotion.current
    val label = if (name.isNullOrBlank()) stringResource(R.string.yaps_support_typing_generic) else stringResource(R.string.yaps_support_typing, name)
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        modifier = Modifier
            .padding(start = Metrics.SidePadding + Metrics.AvatarGutter, top = Metrics.GroupGap)
            .entrance(Entrance.Pop)
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 10.dp))
            .background(Brush.verticalGradient(listOf(colors.incomingTop, colors.incoming)))
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(3) { i ->
            // Three dots breathing in sequence: the view's single ambient motion.
            val alpha by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = if (reduced) 0.3f else 1f,
                animationSpec = infiniteRepeatable(tween(durationMillis = 560, delayMillis = i * 180, easing = EaseOutBrief), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(Modifier.size(7.dp).alpha(alpha).clip(CircleShape).background(colors.onIncoming.copy(alpha = 0.7f)))
        }
    }
}

// ---------------------------------------------------------------- text

private val urlPattern = Regex("""(https?://[^\s<>"']+[^\s<>"'.,;:!?)\]])""")

/** Plain text with tappable http(s) links; no Markdown, no HTML. */
internal fun linkify(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (match in urlPattern.findAll(text)) {
        append(text.substring(last, match.range.first))
        pushLink(LinkAnnotation.Url(match.value, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))))
        append(match.value)
        pop()
        last = match.range.last + 1
    }
    append(text.substring(last))
}
