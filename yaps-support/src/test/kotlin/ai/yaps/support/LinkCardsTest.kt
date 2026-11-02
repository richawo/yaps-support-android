package ai.yaps.support

import ai.yaps.support.internal.core.CardActionName
import ai.yaps.support.internal.core.CardActions
import ai.yaps.support.internal.core.DeviceUpdate
import ai.yaps.support.internal.core.Limits
import ai.yaps.support.internal.core.LinkOpener
import ai.yaps.support.internal.core.isAllowedLinkUrl
import ai.yaps.support.internal.core.isLinkAvailable
import ai.yaps.support.internal.net.ApiClient
import ai.yaps.support.internal.protocol.Card
import ai.yaps.support.internal.protocol.CardType
import ai.yaps.support.internal.protocol.LinkTarget
import ai.yaps.support.internal.protocol.Message
import ai.yaps.support.internal.protocol.PaywallWire
import ai.yaps.support.internal.protocol.ScreenWire
import ai.yaps.support.internal.protocol.WireJson
import ai.yaps.support.ui.CardPresentation
import ai.yaps.support.ui.cardPresentation
import ai.yaps.support.ui.linkify
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun fixture(name: String): Message = WireJson.decodeFromString(Message.serializer(), Fixtures.read(name))

private fun Message.withCard(change: Card.() -> Card): Message = copy(card = card!!.change())

private val SCREENS = setOf("edit_profile")

class LinkCardDecodingTest {

    @Test
    fun urlLinkDecodes() {
        val card = fixture("message-link-card.json").card!!
        assertEquals(CardType.LINK, card.type)
        assertEquals("Read the guide", card.buttonLabel)
        assertEquals("lnk_1", card.linkId)
        assertEquals(LinkTarget.URL, card.target)
        assertEquals("https://wakely.app/guide/silent-alarms", card.url)
        assertNull(card.screenId)
        assertTrue(card.available)
        assertNull(card.openedAt)
    }

    @Test
    fun screenAndSettingsLinksDecode() {
        val screen = fixture("message-link-card-screen.json").card!!
        assertEquals(LinkTarget.SCREEN, screen.target)
        assertEquals("edit_profile", screen.screenId)
        assertEquals("Edit profile", screen.buttonLabel)
        assertEquals("lnk_3", screen.linkId)
        assertNull(screen.url)
        val settings = fixture("message-link-card-settings.json").card!!
        assertEquals(LinkTarget.APP_SETTINGS, settings.target)
        assertEquals("Open settings", settings.buttonLabel)
        assertNull(settings.url)
        assertNull(settings.screenId)
    }

    @Test
    fun everyLinkFixtureRendersAsALinkCard() {
        for (name in listOf("message-link-card.json", "message-link-card-screen.json", "message-link-card-settings.json")) {
            assertEquals(name, CardPresentation.Link, cardPresentation(fixture(name)))
        }
    }

    @Test
    fun unknownTargetsAndMissingLabelsStayPlainText() {
        val m = fixture("message-link-card.json")
        assertEquals(CardPresentation.PlainText, cardPresentation(m.withCard { copy(target = "deep_link") }))
        assertEquals(CardPresentation.PlainText, cardPresentation(m.withCard { copy(target = null) }))
        assertEquals(CardPresentation.PlainText, cardPresentation(m.withCard { copy(buttonLabel = " ") }))
        assertEquals(CardPresentation.PlainText, cardPresentation(fixture("message-unknown-card.json")))
    }

    @Test
    fun unavailableLinksStillRenderAsACard() {
        val m = fixture("message-link-card-screen.json").withCard { copy(available = false) }
        assertEquals(CardPresentation.Link, cardPresentation(m))
    }

    @Test
    fun oldCardsHaveNoLinkFields() {
        val card = fixture("message-offer-card.json").card!!
        assertNull(card.linkId)
        assertNull(card.target)
        assertNull(card.screenId)
    }
}

class LinkAvailabilityTest {

    @Test
    fun urlLinksFollowTheServer() {
        val card = fixture("message-link-card.json").card!!
        assertTrue(isLinkAvailable(card, emptySet(), hasScreenHandler = false))
        assertFalse(isLinkAvailable(card.copy(available = false), emptySet(), hasScreenHandler = false))
    }

    @Test
    fun settingsLinksFollowTheServer() {
        val card = fixture("message-link-card-settings.json").card!!
        assertTrue(isLinkAvailable(card, emptySet(), hasScreenHandler = false))
        assertFalse(isLinkAvailable(card.copy(available = false), SCREENS, hasScreenHandler = true))
    }

    @Test
    fun screenLinksNeedTheServerTheLocalScreenAndAHandler() {
        val card = fixture("message-link-card-screen.json").card!!
        assertTrue(isLinkAvailable(card, SCREENS, hasScreenHandler = true))
        assertFalse(isLinkAvailable(card, SCREENS, hasScreenHandler = false))
        assertFalse(isLinkAvailable(card, setOf("settings"), hasScreenHandler = true))
        assertFalse(isLinkAvailable(card, emptySet(), hasScreenHandler = true))
        assertFalse(isLinkAvailable(card.copy(available = false), SCREENS, hasScreenHandler = true))
        assertFalse(isLinkAvailable(card.copy(screenId = null), SCREENS, hasScreenHandler = true))
    }

    @Test
    fun onlyHttpsMailtoTelAndSmsOpen() {
        for (url in listOf("https://wakely.app/guide", "HTTPS://wakely.app", "mailto:help@wakely.app", "tel:+441234567890", "sms:+441234567890", " https://wakely.app ")) {
            assertTrue(url, isAllowedLinkUrl(url))
        }
        for (url in listOf(
            "http://wakely.app", "javascript:alert(1)", "intent://scan/#Intent;scheme=zxing;end", "file:///sdcard/x",
            "content://x", "market://details?id=x", "wakely://profile", "https:", "https:/x", "mailto:", "//wakely.app", "", null,
        )) {
            assertFalse(url.toString(), isAllowedLinkUrl(url))
        }
    }

    @Test
    fun aRefusedSchemeIsUnavailableWhateverTheServerSays() {
        val card = fixture("message-link-card.json").card!!.copy(url = "javascript:alert(1)")
        assertFalse(isLinkAvailable(card, emptySet(), hasScreenHandler = false))
        assertFalse(isLinkAvailable(card.copy(url = null), emptySet(), hasScreenHandler = false))
    }
}

class LinkActionsTest {
    private val events = mutableListOf<SupportEvent>()
    private val receipts = mutableListOf<Pair<String, String>>()
    private val actions = CardActions(emit = { events += it }, receipt = { id, action -> receipts += id to action })
    private val opened = mutableListOf<String>()

    private fun opener(works: Boolean = true) = object : LinkOpener {
        override fun openUrl(url: String): Boolean = works.also { opened += "url:$url" }
        override fun openAppSettings(): Boolean = works.also { opened += "settings" }
        override fun openScreen(screenId: String): Boolean = works.also { opened += "screen:$screenId" }
    }

    @Test
    fun urlOpensEmitsAndSendsAnOpenReceipt() {
        assertTrue(actions.openLink(fixture("message-link-card.json"), emptySet(), false, opener()))
        assertEquals(listOf("url:https://wakely.app/guide/silent-alarms"), opened)
        assertEquals(listOf(SupportEvent.LinkOpened("msg_10", "lnk_1")), events)
        assertEquals(listOf("msg_10" to CardActionName.OPEN), receipts)
    }

    @Test
    fun settingsOpensTheAppsSettingsPage() {
        assertTrue(actions.openLink(fixture("message-link-card-settings.json"), emptySet(), false, opener()))
        assertEquals(listOf("settings"), opened)
        assertEquals(listOf(SupportEvent.LinkOpened("msg_12", "lnk_2")), events)
        assertEquals(listOf("msg_12" to CardActionName.OPEN), receipts)
    }

    @Test
    fun screenGoesToTheHost() {
        assertTrue(actions.openLink(fixture("message-link-card-screen.json"), SCREENS, true, opener()))
        assertEquals(listOf("screen:edit_profile"), opened)
        assertEquals(listOf(SupportEvent.LinkOpened("msg_11", "lnk_3")), events)
    }

    @Test
    fun aOneOffLinkHasNoLinkId() {
        val m = fixture("message-link-card.json").withCard { copy(linkId = null) }
        assertTrue(actions.openLink(m, emptySet(), false, opener()))
        assertEquals(listOf(SupportEvent.LinkOpened("msg_10", null)), events)
    }

    @Test
    fun unavailableOrUnopenableLinksRecordNothing() {
        assertFalse(actions.openLink(fixture("message-link-card-screen.json"), SCREENS, false, opener()))
        assertFalse(actions.openLink(fixture("message-link-card-screen.json"), emptySet(), true, opener()))
        assertFalse(actions.openLink(fixture("message-link-card.json").withCard { copy(url = "intent://evil") }, emptySet(), false, opener()))
        assertFalse(actions.openLink(fixture("message-link-card.json").withCard { copy(available = false) }, emptySet(), false, opener()))
        assertFalse(actions.openLink(fixture("message-text-link.json"), emptySet(), false, opener()))
        assertTrue(opened.isEmpty())
        assertFalse(actions.openLink(fixture("message-link-card.json"), emptySet(), false, opener(works = false)))
        assertTrue(events.isEmpty())
        assertTrue(receipts.isEmpty())
    }
}

class ScreensTest {

    @Test
    fun invalidIdsAndRepeatsAreDroppedAndTitlesCut() {
        val clean = Limits.sanitizeScreens(
            listOf(
                SupportScreen("edit_profile", "Edit profile"),
                SupportScreen("has space", "Bad"),
                SupportScreen("", "Empty"),
                SupportScreen("x".repeat(65), "Too long"),
                SupportScreen("edit_profile", "Again"),
                SupportScreen("restore.purchases-2", "R".repeat(80)),
                SupportScreen("settings", "  "),
            ),
        )
        assertEquals(listOf("edit_profile", "restore.purchases-2", "settings"), clean.map { it.id })
        assertEquals("Edit profile", clean[0].title)
        assertEquals(60, clean[1].title.length)
        assertEquals("settings", clean[2].title)
    }

    @Test
    fun atMostFiftyAreKept() {
        val clean = Limits.sanitizeScreens((1..60).map { SupportScreen("s$it", "Screen $it") })
        assertEquals(50, clean.size)
        assertEquals("s50", clean.last().id)
        assertTrue(Limits.isValidScreenId("a".repeat(64)))
    }
}

class DeviceUpdateTest {

    @Test
    fun listsSendTheWholeListsGiven() {
        val body = DeviceUpdate.lists(listOf(PaywallWire("yearly_offer", "Yearly 40% off")), listOf(ScreenWire("edit_profile", "Edit profile")))
        assertEquals(
            """{"paywalls":[{"id":"yearly_offer","title":"Yearly 40% off","products":[]}],"screens":[{"id":"edit_profile","title":"Edit profile"}]}""",
            body.toString(),
        )
        assertEquals("""{"screens":[]}""", DeviceUpdate.lists(null, emptyList()).toString())
        assertEquals("""{"paywalls":[]}""", DeviceUpdate.lists(emptyList(), null).toString())
    }

    @Test
    fun badgeIsSentOnlyWhenItDiffersFromTheServer() {
        // A new registration starts true, so only opting out (or back in) sends anything.
        assertNull(DeviceUpdate.badge(desired = true, synced = true))
        assertNull(DeviceUpdate.badge(desired = false, synced = false))
        assertEquals("""{"badgeEnabled":false}""", DeviceUpdate.badge(desired = false, synced = true).toString())
        assertEquals("""{"badgeEnabled":true}""", DeviceUpdate.badge(desired = true, synced = false).toString())
    }

    @Test
    fun bodiesGoOutAsPatchDevice() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val api = ApiClient(server.url("/").toString(), "app_123", "android/1.0.0", OkHttpClient()) { "t" }
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(MockResponse().setBody("{}"))
            api.updateDevice(DeviceUpdate.badge(desired = false, synced = true)!!)
            api.updateDevice(DeviceUpdate.lists(null, listOf(ScreenWire("edit_profile", "Edit profile"))))
            for (expected in listOf("""{"badgeEnabled":false}""", """{"screens":[{"id":"edit_profile","title":"Edit profile"}]}""")) {
                val req = server.takeRequest()
                assertEquals("PATCH", req.method)
                assertEquals("/v1/sdk/device", req.path)
                assertEquals("Bearer t", req.getHeader("Authorization"))
                assertEquals(expected, req.body.readUtf8())
            }
        } finally {
            server.shutdown()
        }
    }
}

class MessageTextLinksTest {

    private fun linkedUrls(text: String): List<String> =
        linkify(text, Color.Black).getLinkAnnotations(0, text.length).map { (it.item as LinkAnnotation.Url).url }

    @Test
    fun theUrlIsTappableWithoutTheTrailingCommaAndTheEmailIsNot() {
        val text = fixture("message-text-link.json").text
        assertEquals(listOf("https://wakely.app/help/alarms"), linkedUrls(text))
        val annotated = linkify(text, Color.Black)
        assertEquals(text, annotated.text)
        val range = annotated.getLinkAnnotations(0, text.length).single()
        assertEquals(',', text[range.end])
        assertTrue(text.contains("help@wakely.app"))
    }

    @Test
    fun trailingPunctuationStaysOutOfTheLink() {
        assertEquals(listOf("https://wakely.app/a"), linkedUrls("See https://wakely.app/a."))
        assertEquals(listOf("https://wakely.app/a"), linkedUrls("(https://wakely.app/a)"))
        assertEquals(emptyList<String>(), linkedUrls("Email help@wakely.app or call us"))
    }
}
