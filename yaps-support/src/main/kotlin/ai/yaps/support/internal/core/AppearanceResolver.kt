package ai.yaps.support.internal.core

import ai.yaps.support.SupportAppearance
import ai.yaps.support.SupportColor
import ai.yaps.support.SupportColorScheme
import ai.yaps.support.SupportCornerStyle
import ai.yaps.support.SupportFontStyle
import ai.yaps.support.internal.protocol.Appearance
import ai.yaps.support.internal.protocol.ColorPair

/** Fully resolved colours for one theme variant, as ARGB ints. */
internal data class Palette(
    val accent: Int,
    val onAccent: Int,
    val background: Int,
    val incomingBackground: Int,
    val incomingForeground: Int,
)

/** The appearance the chat renders, after applying remote > local > (dynamic) > default. */
internal data class ResolvedAppearance(
    val light: Palette,
    val dark: Palette,
    /** `null` means "use the SDK's localized built-in string". */
    val title: String?,
    val welcomeTitle: String?,
    val welcomeText: String?,
    val colorScheme: SupportColorScheme,
    val fontStyle: SupportFontStyle,
    val cornerStyle: SupportCornerStyle,
    val showCloseButton: Boolean,
    val welcomeIconUrl: String?,
    val welcomeIconRes: Int?,
    val fadeOlderMessages: Boolean,
) {
    fun palette(dark: Boolean): Palette = if (dark) this.dark else light
}

internal object AppearanceResolver {

    val DefaultLight = Palette(
        accent = argb(0xFF1F6FEB),
        onAccent = argb(0xFFFFFFFF),
        background = argb(0xFFFFFFFF),
        incomingBackground = argb(0xFFF1F2F4),
        incomingForeground = argb(0xFF111113),
    )

    val DefaultDark = Palette(
        accent = argb(0xFF5B9DFF),
        onAccent = argb(0xFF00142E),
        background = argb(0xFF0B0B0D),
        incomingBackground = argb(0xFF1D1E22),
        incomingForeground = argb(0xFFEDEDEF),
    )

    /**
     * @param remote the cached remote appearance, or `null` when there is none or
     *   [ai.yaps.support.YapsSupport.usesRemoteAppearance] is off.
     * @param dynamicLight / [dynamicDark] Material You palettes, used in place of the SDK defaults
     *   when the host opted in with [SupportAppearance.useDynamicColor].
     */
    fun resolve(
        remote: Appearance?,
        local: SupportAppearance,
        language: String,
        dynamicLight: Palette? = null,
        dynamicDark: Palette? = null,
    ): ResolvedAppearance {
        val baseLight = if (local.useDynamicColor && dynamicLight != null) dynamicLight else DefaultLight
        val baseDark = if (local.useDynamicColor && dynamicDark != null) dynamicDark else DefaultDark
        val c = remote?.colors

        fun pick(remotePair: ColorPair?, localColor: SupportColor?, dark: Boolean, default: Int): Int {
            val remoteValue = parseHex(if (dark) remotePair?.dark else remotePair?.light)
            return remoteValue ?: localColor?.let { if (dark) it.dark else it.light } ?: default
        }

        fun palette(dark: Boolean, base: Palette) = Palette(
            accent = pick(c?.accent, local.accent, dark, base.accent),
            onAccent = pick(c?.onAccent, local.onAccent, dark, base.onAccent),
            background = pick(c?.background, local.background, dark, base.background),
            incomingBackground = pick(c?.incomingBackground, local.incomingBackground, dark, base.incomingBackground),
            incomingForeground = pick(c?.incomingForeground, local.incomingForeground, dark, base.incomingForeground),
        )

        return ResolvedAppearance(
            light = palette(false, baseLight),
            dark = palette(true, baseDark),
            title = localized(remote?.title, language) ?: local.title,
            welcomeTitle = localized(remote?.welcomeTitle, language) ?: local.welcomeTitle,
            welcomeText = localized(remote?.welcomeText, language) ?: local.welcomeText,
            colorScheme = enumOrNull<SupportColorScheme>(remote?.colorScheme) ?: local.colorScheme ?: SupportColorScheme.SYSTEM,
            fontStyle = enumOrNull<SupportFontStyle>(remote?.fontStyle) ?: local.fontStyle ?: SupportFontStyle.DEFAULT,
            cornerStyle = enumOrNull<SupportCornerStyle>(remote?.cornerStyle) ?: local.cornerStyle ?: SupportCornerStyle.ROUND,
            showCloseButton = remote?.showCloseButton ?: local.showCloseButton ?: true,
            welcomeIconUrl = remote?.welcomeIconUrl?.takeIf { it.isNotBlank() },
            welcomeIconRes = local.welcomeIcon,
            fadeOlderMessages = remote?.fadeOlderMessages ?: local.fadeOlderMessages ?: false,
        )
    }

    /** PROTOCOL §5: text maps are keyed by language code; fall back to `en`. */
    fun localized(map: Map<String, String>?, language: String): String? {
        if (map.isNullOrEmpty()) return null
        val primary = TranslationRule.primary(language)
        val exact = map.entries.firstOrNull { it.key.equals(language, ignoreCase = true) }?.value
            ?: map.entries.firstOrNull { TranslationRule.primary(it.key) == primary }?.value
        return (exact ?: map["en"])?.takeIf { it.isNotBlank() }
    }

    /** `#RGB`, `#RRGGBB` or `#AARRGGBB` → ARGB. Anything else → `null` (falls through to local/default). */
    fun parseHex(value: String?): Int? {
        val hex = value?.trim()?.removePrefix("#") ?: return null
        if (hex.isEmpty() || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return when (hex.length) {
            3 -> (0xFF000000 or hex.map { "$it$it" }.joinToString("").toLong(16)).toInt()
            6 -> (0xFF000000 or hex.toLong(16)).toInt()
            8 -> hex.toLong(16).toInt()
            else -> null
        }
    }

    private inline fun <reified E : Enum<E>> enumOrNull(value: String?): E? =
        value?.let { v -> enumValues<E>().firstOrNull { it.name.equals(v, ignoreCase = true) } }

    private fun argb(value: Long): Int = value.toInt()
}
