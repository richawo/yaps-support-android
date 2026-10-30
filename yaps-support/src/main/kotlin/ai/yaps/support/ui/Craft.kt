package ai.yaps.support.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/*
 * Craft tokens from the Design for AI briefs (registry/pro/mobile-chat, pro/mobile-attachment-tray,
 * free/chat-thread), translated to Compose. Spacing, radius and motion follow the briefs; colours
 * always come from the host's appearance.
 */

/** `cubic-bezier(.22, 1, .36, 1)`: the briefs' ease-out for every entrance. */
internal val EaseOutBrief = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

internal object Metrics {
    val SidePadding = 14.dp
    val AvatarGutter = 34.dp
    val Avatar = 26.dp
    val MineLeading = 60.dp
    val TheirsTrailing = 34.dp
    val GroupGap = 16.dp
    val InGroupGap = 3.dp
    val Control = 44.dp
    val TailWidth = 17.dp
    val TailHeight = 21.dp
    val TailOverhang = 6.dp
}

internal object TypeTokens {
    val Bubble = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp)
    val Name = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
    val Status = TextStyle(fontSize = 12.5.sp, lineHeight = 16.sp)
    val Meta = TextStyle(fontSize = 11.5.sp, lineHeight = 14.sp, fontFeatureSettings = "tnum")
    val Tag = TextStyle(fontSize = 10.5.sp, lineHeight = 14.sp, letterSpacing = 1.sp, fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum")
    val Figure = TextStyle(fontSize = 30.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, fontFeatureSettings = "tnum")
    val FigureUnit = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val TrayTitle = TextStyle(fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
    val TileName = TextStyle(fontSize = 13.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold)
}

/** Accent tokens derived from the single host accent, so one value rebrands the chat (mobile-chat brief). */
internal data class AccentTokens(val top: Color, val mid: Color, val bottom: Color, val glow: Color, val soft: Color)

internal fun accentTokens(accent: Color): AccentTokens = AccentTokens(
    top = lerp(accent, Color.White, 0.12f),
    mid = accent,
    bottom = lerp(accent, Color.Black, 0.10f),
    glow = accent.copy(alpha = 0.40f),
    soft = accent.copy(alpha = 0.12f),
)

/** Ink for text on [background], picked by luminance. */
internal fun inkOn(background: Color): Color = if (background.luminance() > 0.5f) Color(0xFF111113) else Color(0xFFF4F4F5)

/** Android's "Remove animations" (animator duration scale 0) is the platform's reduced-motion switch. */
internal val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
internal fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

internal enum class Entrance {
    /** Already on screen: no animation (rows scrolled back into view, history). */
    None,

    /** Rise 12dp from scale 0.97 and fade, 560ms ease-out. */
    Rise,

    /** A message you just sent flies up out of the composer and springs home. */
    Fly,

    /** Typing bubble: scale 0.8 → 1 from its bottom-start corner. */
    Pop,
}

/**
 * Applies a one-shot entrance. With reduced motion every entrance becomes a 150ms fade.
 */
@Composable
internal fun Modifier.entrance(kind: Entrance, delayMillis: Int = 0, rise: Dp = 12.dp): Modifier {
    if (kind == Entrance.None) return this
    val reduced = LocalReducedMotion.current
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (delayMillis > 0) delay(delayMillis.toLong())
        when {
            reduced -> progress.animateTo(1f, tween(150))
            kind == Entrance.Fly -> progress.animateTo(1f, spring(dampingRatio = 0.68f, stiffness = 240f))
            kind == Entrance.Pop -> progress.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 420f))
            else -> progress.animateTo(1f, tween(560, easing = EaseOutBrief))
        }
    }
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return graphicsLayer {
        val p = progress.value
        if (reduced) {
            alpha = p
            return@graphicsLayer
        }
        when (kind) {
            Entrance.Rise -> {
                alpha = p.coerceIn(0f, 1f)
                translationY = with(density) { rise.toPx() } * (1f - p)
                val s = 0.97f + 0.03f * p
                scaleX = s; scaleY = s
            }
            Entrance.Fly -> {
                // From 74dp lower and 18dp toward the composer's start, scale 0.86 at the bottom-end corner.
                transformOrigin = TransformOrigin(if (rtl) 0f else 1f, 1f)
                alpha = (p * 2.5f).coerceIn(0f, 1f)
                translationY = with(density) { 74.dp.toPx() } * (1f - p)
                translationX = with(density) { 18.dp.toPx() } * (1f - p) * if (rtl) 1f else -1f
                val s = 0.86f + 0.14f * p
                scaleX = s; scaleY = s
            }
            Entrance.Pop -> {
                transformOrigin = TransformOrigin(if (rtl) 1f else 0f, 1f)
                alpha = p.coerceIn(0f, 1f)
                val s = 0.8f + 0.2f * p
                scaleX = s; scaleY = s
            }
            Entrance.None -> Unit
        }
    }
}

/** Presses respond within a frame: spring to [pressed] and back (squish). */
@Composable
internal fun Modifier.squish(interaction: MutableInteractionSource, pressed: Float = 0.94f): Modifier {
    val isPressed by interaction.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val scale by animateFloatAsState(
        targetValue = if (isPressed && !reduced) pressed else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow * 2),
        label = "squish",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}
