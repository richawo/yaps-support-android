package ai.yaps.support.ui

import ai.yaps.support.R
import ai.yaps.support.internal.core.Limits
import ai.yaps.support.internal.core.Restriction
import ai.yaps.support.internal.outbox.PendingAttachment
import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import java.io.File

internal class ComposerActions(
    val onTextChange: (String) -> Unit,
    val onSend: () -> Unit,
    val onPickPhotos: (() -> Unit)?,
    val onTakePhoto: (() -> Unit)?,
    val onPickFile: (() -> Unit)?,
    val onRemove: (PendingAttachment) -> Unit,
)

/**
 * Composer with the attachment tray (Design for AI `mobile-attachment-tray`): a 44dp "+" that turns
 * 135° into × while the tray is open, a field that grows to five lines on a spring, a 44dp send button
 * whose accent fill crossfades in only when there is something to send, and a hint line beneath.
 */
@Composable
internal fun Composer(
    text: String,
    staged: List<PendingAttachment>,
    preparing: Int,
    restriction: Restriction,
    trayOpen: Boolean,
    onTrayOpenChange: (Boolean) -> Unit,
    hint: String?,
    actions: ComposerActions,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSupportColors.current
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.background)
            .drawBehind { drawFadedHairline(colors.divider, top = true) },
    ) {
        if (restriction != Restriction.NONE) {
            RestrictionNotice(restriction)
            return@Column
        }
        val length = text.trim().length
        val tooLong = length > Limits.MESSAGE_TEXT
        val attachCount = staged.size + preparing
        val canSend = !tooLong && preparing == 0 && (text.isNotBlank() || staged.isNotEmpty())
        val hasSources = actions.onPickPhotos != null || actions.onTakePhoto != null || actions.onPickFile != null

        AnimatedVisibility(
            visible = trayOpen && hasSources,
            enter = expandVertically(tween(420, easing = EaseOutBrief), expandFrom = Alignment.Bottom) + fadeIn(tween(300)),
            exit = shrinkVertically(tween(340, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Bottom) + fadeOut(tween(200)),
        ) {
            AttachmentTray(staged, preparing, actions)
        }

        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp),
        ) {
            if (hasSources) {
                TrayToggle(open = trayOpen, onToggle = { onTrayOpenChange(!trayOpen) }, modifier = Modifier.entrance(Entrance.Rise, 60, 8.dp))
                Spacer(Modifier.width(8.dp))
            }
            Field(text, actions.onTextChange, Modifier.weight(1f).entrance(Entrance.Rise, 110, 8.dp))
            Spacer(Modifier.width(8.dp))
            SendButton(enabled = canSend, count = staged.size, onClick = actions.onSend, modifier = Modifier.entrance(Entrance.Rise, 160, 8.dp))
        }

        val line = when {
            tooLong -> stringResource(R.string.yaps_support_too_long, length, Limits.MESSAGE_TEXT)
            hint != null -> hint
            attachCount >= Limits.ATTACHMENTS -> stringResource(R.string.yaps_support_attachment_limit)
            else -> null
        }
        HintLine(line)
    }
}

@Composable
private fun RestrictionNotice(restriction: Restriction) {
    val colors = LocalSupportColors.current
    val notice = when (restriction) {
        Restriction.BLOCKED -> stringResource(R.string.yaps_support_blocked)
        Restriction.VERIFICATION_REQUIRED -> stringResource(R.string.yaps_support_verification_required)
        else -> stringResource(R.string.yaps_support_unavailable)
    }
    Text(
        text = notice,
        style = TypeTokens.Bubble.copy(fontSize = TypeTokens.Status.fontSize * 1.2f),
        color = colors.onIncoming,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(LocalSupportShapes.current.card))
            .background(colors.raised)
            .border(BorderStroke(0.5.dp, colors.divider), RoundedCornerShape(LocalSupportShapes.current.card))
            .padding(horizontal = 18.dp, vertical = 16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun TrayToggle(open: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    val colors = LocalSupportColors.current
    val interaction = remember { MutableInteractionSource() }
    val reduced = LocalReducedMotion.current
    val angle by animateFloatAsState(if (open) 135f else 0f, if (reduced) tween(0) else spring(dampingRatio = 0.8f, stiffness = 500f), label = "plus")
    val label = stringResource(if (open) R.string.yaps_support_close else R.string.yaps_support_attach)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .squish(interaction, 0.9f)
            .size(Metrics.Control)
            .clip(CircleShape)
            .background(colors.raised)
            .border(BorderStroke(0.5.dp, colors.divider), CircleShape)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onToggle)
            .semantics {
                contentDescription = label
                stateDescription = if (open) "expanded" else "collapsed"
            },
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = colors.onIncoming, modifier = Modifier.size(22.dp).rotate(angle))
    }
}

@Composable
private fun Field(text: String, onChange: (String) -> Unit, modifier: Modifier) {
    val colors = LocalSupportColors.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border by animateColorAsState(if (focused) colors.onIncoming.copy(alpha = 0.30f) else colors.divider, tween(200), label = "fieldBorder")
    val hint = stringResource(R.string.yaps_support_composer_hint)
    BasicTextField(
        value = text,
        onValueChange = onChange,
        textStyle = TypeTokens.Bubble.copy(color = colors.onIncoming),
        cursorBrush = SolidColor(colors.onIncoming),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        interactionSource = interaction,
        maxLines = 5,
        modifier = modifier
            .heightIn(min = Metrics.Control)
            .animateContentSize(spring(dampingRatio = 0.89f, stiffness = 500f))
            .semantics { contentDescription = hint },
        decorationBox = { inner ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(colors.raised)
                    .border(BorderStroke(1.dp, border), RoundedCornerShape(22.dp))
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) Text(hint, style = TypeTokens.Bubble, color = colors.faintText)
                inner()
            }
        },
    )
}

@Composable
private fun SendButton(enabled: Boolean, count: Int, onClick: () -> Unit, modifier: Modifier) {
    val colors = LocalSupportColors.current
    val interaction = remember { MutableInteractionSource() }
    val fill by animateColorAsState(if (enabled) colors.accent else colors.raised, tween(220), label = "sendFill")
    val ink by animateColorAsState(if (enabled) colors.onAccent else colors.faintText, tween(220), label = "sendInk")
    // The arrow sits 2dp low until there's something to send (chat-thread brief).
    val drop by animateDpAsState(if (enabled) 0.dp else 2.dp, spring(dampingRatio = 0.8f, stiffness = 500f), label = "sendDrop")
    Box(modifier.size(Metrics.Control)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .squish(interaction, 0.9f)
                .size(Metrics.Control)
                .clip(CircleShape)
                .background(fill)
                .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.yaps_support_send),
                tint = ink,
                modifier = Modifier.size(20.dp).offset(y = drop),
            )
        }
        if (count > 0) {
            Text(
                "$count",
                style = TypeTokens.Meta.copy(fontWeight = FontWeight.Bold),
                color = colors.onIncoming,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(colors.background)
                    .border(BorderStroke(1.dp, colors.divider), CircleShape)
                    .padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun HintLine(text: String?) {
    val colors = LocalSupportColors.current
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (fadeIn(tween(220)) + slideInVertically(tween(260, easing = EaseOutBrief)) { it / 3 })
                .togetherWith(fadeOut(tween(160)) + slideOutVertically(tween(200)) { -it / 3 })
        },
        label = "hint",
        modifier = Modifier.fillMaxWidth(),
    ) { line ->
        if (line == null) {
            Spacer(Modifier.height(8.dp))
        } else {
            Text(
                line,
                style = TypeTokens.Status,
                color = colors.onIncoming,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

// ---------------------------------------------------------------- tray

@Composable
private fun AttachmentTray(staged: List<PendingAttachment>, preparing: Int, actions: ComposerActions) {
    val colors = LocalSupportColors.current
    val context = LocalContext.current
    val count = staged.size + preparing
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .padding(start = 10.dp, end = 10.dp, top = 10.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(colors.cardTop, colors.cardBottom)))
            .border(BorderStroke(0.5.dp, colors.divider), shape)
            .drawBehind { drawLine(colors.highlight, Offset(24f, 0.5f), Offset(size.width - 24f, 0.5f), 1f) }
            .padding(14.dp),
    ) {
        Text(stringResource(R.string.yaps_support_attach_title), style = TypeTokens.TrayTitle, color = colors.onIncoming)
        Text(stringResource(R.string.yaps_support_attach_subtitle), style = TypeTokens.Status, color = colors.secondaryText)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            val full = count >= Limits.ATTACHMENTS
            actions.onPickPhotos?.let { SourceButton(R.drawable.yaps_support_ic_photo, stringResource(R.string.yaps_support_attach_photos), !full, it, Modifier.weight(1f)) }
            actions.onTakePhoto?.let { SourceButton(R.drawable.yaps_support_ic_camera, stringResource(R.string.yaps_support_attach_camera), !full, it, Modifier.weight(1f)) }
            actions.onPickFile?.let { SourceButton(R.drawable.yaps_support_ic_file, stringResource(R.string.yaps_support_attach_file), !full, it, Modifier.weight(1f)) }
        }
        if (count > 0) {
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                staged.forEach { att -> Tile(att, onRemove = { actions.onRemove(att) }) }
                repeat(preparing) { PreparingTile() }
            }
            Spacer(Modifier.height(14.dp))
            Meter(count, staged.sumOf { it.size }.let { Formatter.formatShortFileSize(context, it) })
        }
    }
}

@Composable
private fun SourceButton(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = LocalSupportColors.current
    val interaction = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .squish(interaction, 0.96f)
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.onIncoming.copy(alpha = if (enabled) 0.06f else 0.03f))
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = if (enabled) colors.onIncoming else colors.faintText, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = TypeTokens.Status.copy(fontWeight = FontWeight.Medium), color = if (enabled) colors.onIncoming else colors.faintText)
    }
}

@Composable
private fun Tile(att: PendingAttachment, onRemove: () -> Unit) {
    val colors = LocalSupportColors.current
    val context = LocalContext.current
    val name = att.filename.orEmpty()
    Column(
        Modifier
            .width(112.dp)
            .entrance(Entrance.Rise)
            .semantics(mergeDescendants = true) { contentDescription = "$name, ${Formatter.formatShortFileSize(context, att.size)}" },
    ) {
        Box(Modifier.size(width = 112.dp, height = 84.dp)) {
            if (att.kind == "image") {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(File(att.localPath)).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(colors.raised),
                )
            } else {
                Box(
                    Modifier.fillMaxWidth().fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(colors.onIncoming.copy(alpha = 0.06f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(name.substringAfterLast('.', "file").uppercase(), style = TypeTokens.Tag, color = colors.secondaryText)
                }
            }
            RemoveBadge(onRemove, Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(middleEllipsis(name, 16), style = TypeTokens.TileName, color = colors.onIncoming, maxLines = 1)
        Text(Formatter.formatShortFileSize(context, att.size), style = TypeTokens.Tag, color = colors.secondaryText)
    }
}

@Composable
private fun PreparingTile() {
    val colors = LocalSupportColors.current
    Box(
        Modifier.size(width = 112.dp, height = 84.dp).clip(RoundedCornerShape(14.dp)).background(colors.onIncoming.copy(alpha = 0.06f)),
        contentAlignment = Alignment.Center,
    ) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = colors.onIncoming, trackColor = colors.divider) }
}

@Composable
private fun RemoveBadge(onRemove: () -> Unit, modifier: Modifier) {
    val colors = LocalSupportColors.current
    val interaction = remember { MutableInteractionSource() }
    // 26dp badge inside a 44dp touch target.
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(Metrics.Control)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onRemove),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .squish(interaction, 0.88f)
                .size(26.dp)
                .clip(CircleShape)
                .background(colors.onIncoming)
                .border(BorderStroke(2.dp, colors.cardBottom), CircleShape),
        ) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.yaps_support_remove_attachment), tint = colors.background, modifier = Modifier.size(14.dp))
        }
    }
}

/** "2 of 5 files" over a 6dp track with one segment per file; segments grow as files land. */
@Composable
private fun Meter(count: Int, total: String) {
    val colors = LocalSupportColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.yaps_support_files_count, count, Limits.ATTACHMENTS), style = TypeTokens.Meta, color = colors.onIncoming, modifier = Modifier.weight(1f))
        Text(total, style = TypeTokens.Meta, color = colors.secondaryText)
    }
    Spacer(Modifier.height(6.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(colors.onIncoming.copy(alpha = 0.08f)),
    ) {
        repeat(Limits.ATTACHMENTS) { i ->
            val fill by animateFloatAsState(if (i < count) 1f else 0f, tween(420, easing = EaseOutBrief), label = "seg$i")
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fill).clip(CircleShape).background(colors.onIncoming.copy(alpha = 0.85f)))
            }
        }
    }
}

/** A hairline that fades out toward both ends (the briefs' header/composer edge). */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFadedHairline(color: Color, top: Boolean) {
    val y = if (top) 0f else size.height
    drawLine(
        Brush.horizontalGradient(listOf(Color.Transparent, color, color, Color.Transparent)),
        Offset(0f, y),
        Offset(size.width, y),
        strokeWidth = 1f,
    )
}
