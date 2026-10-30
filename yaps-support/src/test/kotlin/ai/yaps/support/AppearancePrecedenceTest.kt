package ai.yaps.support

import ai.yaps.support.internal.core.AppearanceResolver
import ai.yaps.support.internal.core.Palette
import ai.yaps.support.internal.protocol.Appearance
import ai.yaps.support.internal.protocol.AppearanceColors
import ai.yaps.support.internal.protocol.ColorPair
import ai.yaps.support.internal.protocol.WireJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearancePrecedenceTest {

    private val remote = WireJson.decodeFromString(Appearance.serializer(), Fixtures.read("appearance.json"))
    private val local = SupportAppearance(
        accent = SupportColor(0xFF00AA00.toInt(), 0xFF00CC00.toInt()),
        background = SupportColor(0xFF101010.toInt()),
        title = "Local title",
        welcomeText = "Local welcome",
        cornerStyle = SupportCornerStyle.SQUARE,
        fadeOlderMessages = true,
        showCloseButton = false,
    )

    @Test
    fun remoteBeatsLocal() {
        val r = AppearanceResolver.resolve(remote, local, "en")
        assertEquals(0xFFFF7A45.toInt(), r.light.accent)
        assertEquals(0xFFFF8A5B.toInt(), r.dark.accent)
        assertEquals(0xFFFFFFFF.toInt(), r.light.background)
        assertEquals("Clucky support", r.title)
        assertEquals("We usually reply within a few hours.", r.welcomeText)
        assertEquals(SupportFontStyle.ROUNDED, r.fontStyle)
        assertEquals(SupportCornerStyle.ROUND, r.cornerStyle)
        assertEquals(false, r.fadeOlderMessages)
        assertEquals(true, r.showCloseButton)
    }

    @Test
    fun localBeatsDefaultWhenRemoteIsMissingAField() {
        val sparse = Appearance(colors = AppearanceColors(onAccent = ColorPair("#123456", "#654321")))
        val r = AppearanceResolver.resolve(sparse, local, "en")
        assertEquals(0xFF00AA00.toInt(), r.light.accent)
        assertEquals(0xFF00CC00.toInt(), r.dark.accent)
        assertEquals(0xFF101010.toInt(), r.dark.background) // single-colour SupportColor covers both
        assertEquals(0xFF123456.toInt(), r.light.onAccent)
        assertEquals("Local title", r.title)
        assertEquals(SupportCornerStyle.SQUARE, r.cornerStyle)
        assertEquals(true, r.fadeOlderMessages)
        assertEquals(false, r.showCloseButton)
    }

    @Test
    fun defaultsWhenNothingIsSet() {
        val r = AppearanceResolver.resolve(null, SupportAppearance(), "en")
        assertEquals(AppearanceResolver.DefaultLight, r.light)
        assertEquals(AppearanceResolver.DefaultDark, r.dark)
        assertNull("null means the localized built-in string", r.title)
        assertEquals(SupportColorScheme.SYSTEM, r.colorScheme)
        assertEquals(SupportFontStyle.DEFAULT, r.fontStyle)
        assertTrue(r.showCloseButton)
    }

    @Test
    fun localizedTextUsesTheCustomerLanguageThenEnglish() {
        assertEquals("Soporte de Clucky", AppearanceResolver.resolve(remote, local, "es").title)
        assertEquals("Soporte de Clucky", AppearanceResolver.resolve(remote, local, "es-MX").title)
        assertEquals("Clucky support", AppearanceResolver.resolve(remote, local, "de").title)
        assertEquals("How can we help?", AppearanceResolver.resolve(remote, local, "de").welcomeTitle)
    }

    @Test
    fun invalidRemoteValuesFallThrough() {
        val broken = Appearance(
            colors = AppearanceColors(accent = ColorPair("orange", "#GGGGGG")),
            cornerStyle = "blobby",
            colorScheme = "sepia",
        )
        val r = AppearanceResolver.resolve(broken, local, "en")
        assertEquals(0xFF00AA00.toInt(), r.light.accent)
        assertEquals(SupportCornerStyle.SQUARE, r.cornerStyle)
        assertEquals(SupportColorScheme.SYSTEM, r.colorScheme)
    }

    @Test
    fun dynamicColourOnlyReplacesDefaults() {
        val dyn = Palette(1, 2, 3, 4, 5)
        val optedIn = SupportAppearance(useDynamicColor = true)
        assertEquals(dyn, AppearanceResolver.resolve(null, optedIn, "en", dyn, dyn).light)
        // Remote colours still win over dynamic ones.
        assertEquals(0xFFFF7A45.toInt(), AppearanceResolver.resolve(remote, optedIn, "en", dyn, dyn).light.accent)
        // Not opted in: dynamic palette ignored.
        assertEquals(AppearanceResolver.DefaultLight, AppearanceResolver.resolve(null, SupportAppearance(), "en", dyn, dyn).light)
    }

    @Test
    fun hexParsing() {
        assertEquals(0xFFFF7A45.toInt(), AppearanceResolver.parseHex("#FF7A45"))
        assertEquals(0xFFFFAA00.toInt(), AppearanceResolver.parseHex("#FA0"))
        assertEquals(0x80FF0000.toInt(), AppearanceResolver.parseHex("#80FF0000"))
        assertNull(AppearanceResolver.parseHex("#12345"))
        assertNull(AppearanceResolver.parseHex(null))
    }
}
