package ai.yaps.support.ui

import ai.yaps.support.SupportColorScheme
import ai.yaps.support.SupportCornerStyle
import ai.yaps.support.SupportFontStyle
import ai.yaps.support.internal.core.Palette
import ai.yaps.support.internal.core.ResolvedAppearance
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import ai.yaps.support.internal.core.AppearanceResolver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.text.TextUtilsCompat
import java.util.Locale

/** Chat-specific colours and shapes on top of MaterialTheme. */
@Immutable
internal data class SupportColors(
    val accent: Color,
    val onAccent: Color,
    val background: Color,
    val incoming: Color,
    val onIncoming: Color,
    val secondaryText: Color,
    val faintText: Color,
    val divider: Color,
    /** Failures and limits stay neutral and explicit (icons, reasons, Retry), never a red tint. */
    val error: Color,
    /** Raised surface a few percent off the background: composer field, tray tiles. */
    val raised: Color,
    /** Incoming bubble gradient: a lighter top stop over the host's incoming colour. */
    val incomingTop: Color,
    val cardTop: Color,
    val cardBottom: Color,
    /** Inset top-edge highlight on bubbles and cards. */
    val highlight: Color,
    val accentTokens: AccentTokens,
    val isDark: Boolean,
)

@Immutable
internal data class SupportShapes(val bubble: Dp, val tight: Dp, val tail: Dp, val card: Dp)

internal val LocalSupportColors = staticCompositionLocalOf {
    AppearanceResolver.DefaultLight.toColors(false)
}
internal val LocalSupportShapes = staticCompositionLocalOf { shapesFor(SupportCornerStyle.ROUND) }
internal val LocalAppearance = staticCompositionLocalOf<ResolvedAppearance?> { null }

internal fun Palette.toColors(dark: Boolean): SupportColors {
    val fg = Color(incomingForeground)
    val bg = Color(background)
    val incoming = Color(incomingBackground)
    val lift = if (dark) Color.White else Color.Black
    return SupportColors(
        accent = Color(accent),
        onAccent = Color(onAccent),
        background = bg,
        incoming = incoming,
        onIncoming = fg,
        // 70% / 60% of the ink keep muted and faint text at WCAG AA (≥ 4.5:1) on the default light and dark backgrounds.
        secondaryText = fg.copy(alpha = 0.70f),
        faintText = fg.copy(alpha = 0.60f),
        divider = fg.copy(alpha = if (dark) 0.10f else 0.09f),
        error = fg,
        raised = lerp(bg, incoming, 0.7f),
        incomingTop = lerp(incoming, lift, if (dark) 0.04f else 0.015f),
        cardTop = lerp(incoming, lift, if (dark) 0.02f else 0.0f),
        cardBottom = lerp(incoming, if (dark) Color.Black else Color.White, if (dark) 0.22f else 0.35f),
        highlight = Color.White.copy(alpha = if (dark) 0.07f else 0.65f),
        accentTokens = accentTokens(Color(accent)),
        isDark = dark,
    )
}

internal fun shapesFor(style: SupportCornerStyle): SupportShapes = when (style) {
    // ROUND is the brief: bubble 20, grouped side 7, tail corner 10, card 20.
    SupportCornerStyle.ROUND -> SupportShapes(bubble = 20.dp, tight = 7.dp, tail = 10.dp, card = 20.dp)
    SupportCornerStyle.SOFT -> SupportShapes(bubble = 12.dp, tight = 5.dp, tail = 6.dp, card = 12.dp)
    SupportCornerStyle.SQUARE -> SupportShapes(bubble = 4.dp, tight = 2.dp, tail = 2.dp, card = 4.dp)
}

internal fun fontFamilyFor(style: SupportFontStyle): FontFamily = when (style) {
    SupportFontStyle.DEFAULT -> FontFamily.Default
    // Android ships no rounded system face; Google Sans Rounded is not public. Fall back to the default sans.
    SupportFontStyle.ROUNDED -> FontFamily.SansSerif
    SupportFontStyle.SERIF -> FontFamily.Serif
    SupportFontStyle.MONO -> FontFamily.Monospace
}

/** Material You palettes for [ai.yaps.support.SupportAppearance.useDynamicColor] (Android 12+). */
internal fun dynamicPalettes(context: Context): Pair<Palette, Palette>? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val light = dynamicLightColorScheme(context)
    val dark = dynamicDarkColorScheme(context)
    fun p(s: androidx.compose.material3.ColorScheme) = Palette(
        accent = s.primary.toArgb(),
        onAccent = s.onPrimary.toArgb(),
        background = s.surface.toArgb(),
        incomingBackground = s.surfaceContainerHigh.toArgb(),
        incomingForeground = s.onSurface.toArgb(),
    )
    return p(light) to p(dark)
}

@Composable
internal fun isDarkFor(scheme: SupportColorScheme): Boolean = when (scheme) {
    SupportColorScheme.SYSTEM -> isSystemInDarkTheme()
    SupportColorScheme.LIGHT -> false
    SupportColorScheme.DARK -> true
}

@Composable
internal fun SupportTheme(appearance: ResolvedAppearance, dark: Boolean, content: @Composable () -> Unit) {
    val palette = appearance.palette(dark)
    val colors = remember(palette, dark) { palette.toColors(dark) }
    val scheme = remember(colors) {
        val base = if (dark) darkColorScheme() else lightColorScheme()
        base.copy(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.background,
            onBackground = colors.onIncoming,
            surface = colors.background,
            onSurface = colors.onIncoming,
            surfaceVariant = colors.incoming,
            onSurfaceVariant = colors.secondaryText,
            surfaceContainer = colors.incoming,
            surfaceContainerHigh = colors.incoming,
            surfaceContainerHighest = colors.incoming,
            outline = colors.divider,
            outlineVariant = colors.divider,
            error = colors.error,
        )
    }
    val family = fontFamilyFor(appearance.fontStyle)
    val typography = remember(family) {
        val t = Typography()
        fun TextStyle.f() = copy(fontFamily = family)
        t.copy(
            titleLarge = t.titleLarge.f(), titleMedium = t.titleMedium.f(), titleSmall = t.titleSmall.f(),
            headlineSmall = t.headlineSmall.f(),
            bodyLarge = t.bodyLarge.f(), bodyMedium = t.bodyMedium.f(), bodySmall = t.bodySmall.f(),
            labelLarge = t.labelLarge.f(), labelMedium = t.labelMedium.f(), labelSmall = t.labelSmall.f(),
        )
    }
    val reduced = rememberReducedMotion()
    CompositionLocalProvider(
        LocalReducedMotion provides reduced,
        LocalSupportColors provides colors,
        LocalSupportShapes provides shapesFor(appearance.cornerStyle),
        LocalAppearance provides appearance,
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

/**
 * Applies [ai.yaps.support.YapsSupport.languageOverride] to SDK strings and layout direction
 * without touching the host's configuration.
 */
@Composable
internal fun LocalizedContent(languageTag: String?, content: @Composable () -> Unit) {
    if (languageTag.isNullOrBlank()) {
        content()
        return
    }
    val base = LocalContext.current
    val baseConfig = LocalConfiguration.current
    val locale = remember(languageTag) { Locale.forLanguageTag(languageTag) }
    val (context, config) = remember(base, baseConfig, locale) {
        val c = Configuration(baseConfig).apply { setLocale(locale) }
        base.createConfigurationContext(c) to c
    }
    val direction = if (TextUtilsCompat.getLayoutDirectionFromLocale(locale) == android.view.View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(
        LocalContext provides LocaleContextWrapper(base, context),
        LocalConfiguration provides config,
        LocalLayoutDirection provides direction,
        content = content,
    )
}

/** Resources from the localized context, everything else (activity, launchers) from the real one. */
private class LocaleContextWrapper(base: Context, private val localized: Context) : android.content.ContextWrapper(base) {
    override fun getResources(): android.content.res.Resources = localized.resources
}
