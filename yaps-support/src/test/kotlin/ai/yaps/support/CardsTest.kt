package ai.yaps.support

import ai.yaps.support.internal.core.AppPromoState
import ai.yaps.support.internal.core.CardActionName
import ai.yaps.support.internal.core.CardActions
import ai.yaps.support.internal.core.CodeState
import ai.yaps.support.internal.core.PlayStoreLink
import ai.yaps.support.internal.core.RedeemHint
import ai.yaps.support.internal.core.UrlLauncher
import ai.yaps.support.internal.core.appPromoState
import ai.yaps.support.internal.core.codeCard
import ai.yaps.support.internal.core.openFirst
import ai.yaps.support.internal.core.redeemHint
import ai.yaps.support.internal.core.showsOwnCopyFeedback
import ai.yaps.support.internal.core.spokenCode
import ai.yaps.support.internal.protocol.Card
import ai.yaps.support.internal.protocol.Message
import ai.yaps.support.internal.protocol.WireJson
import ai.yaps.support.ui.CardPresentation
import ai.yaps.support.ui.cardPresentation
import ai.yaps.support.ui.parseIsoMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun fixture(name: String): Message = WireJson.decodeFromString(Message.serializer(), Fixtures.read(name))

/** As the server sends it to an Android device: the store link is resolved to `market://`. */
private fun androidAppPromo(card: Card.() -> Card = { this }): Message {
    val m = fixture("message-app-card.json")
    return m.copy(card = m.card!!.copy(url = "market://details?id=com.yaps.stacked").card())
}

private fun androidCode(card: Card.() -> Card = { this }): Message {
    val m = fixture("message-code-card.json")
    return m.copy(card = m.card!!.copy(platform = "android", url = "https://play.google.com/redeem?code=STUDENT20").card())
}

private val BEFORE_EXPIRY = parseIsoMillis("2026-10-30T09:50:00.000Z")!!
private val AFTER_EXPIRY = parseIsoMillis("2027-01-01T00:00:00.000Z")!!

class AppPromoStateTest {

    @Test
    fun fixtureIsAnAvailablePromo() {
        val m = fixture("message-app-card.json")
        assertEquals(AppPromoState.Available, appPromoState(m.card!!))
        assertEquals(CardPresentation.AppPromo, cardPresentation(m))
    }

    @Test
    fun installedShowsTheCardWithoutAButtonEvenWhenUnavailable() {
        val installed = androidAppPromo { copy(installed = true) }.card!!
        assertEquals(AppPromoState.Installed, appPromoState(installed))
        assertEquals(AppPromoState.Installed, appPromoState(installed.copy(available = false, url = null)))
        assertEquals(CardPresentation.AppPromo, cardPresentation(androidAppPromo { copy(installed = true, available = false) }))
    }

    @Test
    fun unavailableAndNotInstalledRendersAsText() {
        val m = androidAppPromo { copy(available = false, url = null) }
        assertEquals(AppPromoState.Text, appPromoState(m.card!!))
        assertEquals(CardPresentation.PlainText, cardPresentation(m))
    }

    @Test
    fun availableWithoutALinkRendersAsText() {
        assertEquals(AppPromoState.Text, appPromoState(androidAppPromo { copy(url = "  ") }.card!!))
    }
}

class CodeCardStateTest {

    @Test
    fun availableCodeIsRedeemable() {
        val model = codeCard(androidCode().card!!, BEFORE_EXPIRY)!!
        assertEquals(CodeState.Redeemable, model.state)
        assertEquals("STUDENT20", model.code)
        assertEquals("https://play.google.com/redeem?code=STUDENT20", model.redeemUrl)
        assertNull(model.hint)
        assertEquals(parseIsoMillis("2026-12-31T23:59:59.000Z"), model.expiresAtMillis)
        assertEquals(CardPresentation.Code, cardPresentation(androidCode()))
    }

    @Test
    fun otherPlatformFixtureCopiesWithAnIphoneHint() {
        val m = fixture("message-code-card-other-platform.json")
        val model = codeCard(m.card!!, BEFORE_EXPIRY)!!
        assertEquals(CodeState.CopyOnly, model.state)
        assertNull(model.redeemUrl)
        assertEquals(RedeemHint.IPHONE, model.hint)
        assertEquals(CardPresentation.Code, cardPresentation(m))
    }

    @Test
    fun hintsFollowThePlatform() {
        assertEquals(RedeemHint.IPHONE, redeemHint("ios"))
        assertEquals(RedeemHint.ANDROID, redeemHint("android"))
        assertEquals(RedeemHint.WEB, redeemHint("WEB"))
        assertNull(redeemHint("any"))
        assertNull(redeemHint(null))
        assertNull(redeemHint("watchos"))
        val web = fixture("message-code-card-other-platform.json").card!!.copy(platform = "web")
        assertEquals(RedeemHint.WEB, codeCard(web, BEFORE_EXPIRY)!!.hint)
    }

    @Test
    fun availableWithoutAUrlCopiesWithoutAHint() {
        val model = codeCard(androidCode { copy(url = null) }.card!!, BEFORE_EXPIRY)!!
        assertEquals(CodeState.CopyOnly, model.state)
        assertNull(model.hint)
    }

    @Test
    fun pastExpiryIsExpiredWhateverTheAvailability() {
        assertEquals(CodeState.Expired, codeCard(androidCode().card!!, AFTER_EXPIRY)!!.state)
        val unavailable = fixture("message-code-card-other-platform.json").card!!.copy(expiresAt = "2026-10-01T00:00:00Z")
        val model = codeCard(unavailable, BEFORE_EXPIRY)!!
        assertEquals(CodeState.Expired, model.state)
        assertNull(model.redeemUrl)
        assertNull(model.hint)
        // Exactly at expiresAt counts as expired.
        assertEquals(CodeState.Expired, codeCard(androidCode().card!!, parseIsoMillis("2026-12-31T23:59:59.000Z")!!)!!.state)
    }

    @Test
    fun noExpiryOrAnUnparseableOneNeverExpires() {
        assertEquals(CodeState.Redeemable, codeCard(androidCode { copy(expiresAt = null) }.card!!, AFTER_EXPIRY)!!.state)
        assertEquals(CodeState.Redeemable, codeCard(androidCode { copy(expiresAt = "soon") }.card!!, AFTER_EXPIRY)!!.state)
    }

    @Test
    fun aCodeCardWithoutACodeRendersAsText() {
        val m = androidCode { copy(code = " ") }
        assertNull(codeCard(m.card!!, BEFORE_EXPIRY))
        assertEquals(CardPresentation.PlainText, cardPresentation(m))
    }

    @Test
    fun talkBackSpellsTheCode() {
        assertEquals("S T U D E N T 2 0", spokenCode("STUDENT20"))
        assertEquals("A B - 1 2", spokenCode(" AB-12 "))
    }

    @Test
    fun ownCopyFeedbackOnlyBeforeAndroid13() {
        assertTrue(showsOwnCopyFeedback(32))
        assertFalse(showsOwnCopyFeedback(33))
        assertFalse(showsOwnCopyFeedback(36))
    }
}

class PlayStoreLinkTest {

    @Test
    fun marketLinkFallsBackToTheHttpsListing() {
        assertEquals(
            listOf("market://details?id=com.yaps.stacked", "https://play.google.com/store/apps/details?id=com.yaps.stacked"),
            PlayStoreLink.candidates("market://details?id=com.yaps.stacked"),
        )
    }

    @Test
    fun noStoreAppOpensTheHttpsListing() {
        val tried = mutableListOf<String>()
        val noPlayStore = UrlLauncher { url -> tried += url; url.startsWith("https://") }
        val opened = openFirst(PlayStoreLink.candidates("market://details?id=com.yaps.stacked&referrer=support"), noPlayStore)
        assertEquals("https://play.google.com/store/apps/details?id=com.yaps.stacked&referrer=support", opened)
        assertEquals(2, tried.size)
    }

    @Test
    fun storeAppHandlesMarketFirst() {
        val tried = mutableListOf<String>()
        val opened = openFirst(PlayStoreLink.candidates("market://details?id=com.a"), UrlLauncher { tried += it; true })
        assertEquals("market://details?id=com.a", opened)
        assertEquals(listOf("market://details?id=com.a"), tried)
    }

    @Test
    fun nothingHandlesEither() {
        assertNull(openFirst(PlayStoreLink.candidates("market://details?id=com.a"), UrlLauncher { false }))
        assertEquals(emptyList<String>(), PlayStoreLink.candidates(" "))
    }

    @Test
    fun otherLinksAreTriedAsIs() {
        assertEquals(listOf("https://apps.apple.com/app/id1"), PlayStoreLink.candidates("https://apps.apple.com/app/id1"))
    }
}

class CardActionsTest {
    private val events = mutableListOf<SupportEvent>()
    private val receipts = mutableListOf<Pair<String, String>>()
    private var now = BEFORE_EXPIRY
    private val actions = CardActions(emit = { events += it }, receipt = { id, action -> receipts += id to action }, now = { now })

    @Test
    fun getItOpensTheListingEmitsAndSendsAnOpenReceipt() {
        val opened = mutableListOf<String>()
        assertTrue(actions.openAppPromo(androidAppPromo(), UrlLauncher { opened += it; true }))
        assertEquals(listOf("market://details?id=com.yaps.stacked"), opened)
        assertEquals(listOf(SupportEvent.AppPromoOpened("msg_7", "app_2")), events)
        assertEquals(listOf("msg_7" to CardActionName.OPEN), receipts)
    }

    @Test
    fun getItWithoutPlayStoreUsesTheWebListing() {
        val opened = mutableListOf<String>()
        assertTrue(actions.openAppPromo(androidAppPromo(), UrlLauncher { url -> url.startsWith("https://").also { if (it) opened += url } }))
        assertEquals(listOf("https://play.google.com/store/apps/details?id=com.yaps.stacked"), opened)
        assertEquals(1, events.size)
    }

    @Test
    fun aPromoNothingCanOpenRecordsNothing() {
        assertFalse(actions.openAppPromo(androidAppPromo(), UrlLauncher { false }))
        assertFalse(actions.openAppPromo(androidAppPromo { copy(installed = true) }, UrlLauncher { true }))
        assertFalse(actions.openAppPromo(androidAppPromo { copy(available = false) }, UrlLauncher { true }))
        assertTrue(events.isEmpty())
        assertTrue(receipts.isEmpty())
    }

    @Test
    fun copyPutsTheCodeOnTheClipboardEmitsAndSendsACopyReceipt() {
        var clipboard: String? = null
        assertTrue(actions.copyCode(androidCode()) { clipboard = it; true })
        assertEquals("STUDENT20", clipboard)
        assertEquals(listOf(SupportEvent.CodeCopied("msg_8", "cmp_1")), events)
        assertEquals(listOf("msg_8" to CardActionName.COPY), receipts)
    }

    @Test
    fun copyWorksForAnotherPlatformsCode() {
        assertTrue(actions.copyCode(fixture("message-code-card-other-platform.json")) { true })
        assertEquals(listOf("msg_9" to CardActionName.COPY), receipts)
    }

    @Test
    fun failedOrExpiredCopiesRecordNothing() {
        assertFalse(actions.copyCode(androidCode()) { false })
        now = AFTER_EXPIRY
        var touched = false
        assertFalse(actions.copyCode(androidCode()) { touched = true; true })
        assertFalse(touched)
        assertTrue(events.isEmpty())
        assertTrue(receipts.isEmpty())
    }

    @Test
    fun redeemOpensThePlayRedeemPageEmitsAndSendsAnOpenReceipt() {
        val opened = mutableListOf<String>()
        assertTrue(actions.redeemCode(androidCode(), UrlLauncher { opened += it; true }))
        assertEquals(listOf("https://play.google.com/redeem?code=STUDENT20"), opened)
        assertEquals(listOf(SupportEvent.CodeOpened("msg_8", "cmp_1")), events)
        assertEquals(listOf("msg_8" to CardActionName.OPEN), receipts)
    }

    @Test
    fun redeemDoesNothingWithoutAUsableLink() {
        val launcher = UrlLauncher { fail() }
        assertFalse(actions.redeemCode(fixture("message-code-card-other-platform.json"), launcher))
        now = AFTER_EXPIRY
        assertFalse(actions.redeemCode(androidCode(), launcher))
        now = BEFORE_EXPIRY
        assertFalse(actions.redeemCode(androidCode(), UrlLauncher { false }))
        assertTrue(events.isEmpty())
        assertTrue(receipts.isEmpty())
    }

    private fun fail(): Boolean = throw AssertionError("should not launch")
}
