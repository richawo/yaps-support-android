package ai.yaps.support

import ai.yaps.support.internal.protocol.ApiErrorBody
import ai.yaps.support.internal.protocol.Appearance
import ai.yaps.support.internal.protocol.CardType
import ai.yaps.support.internal.protocol.CodePlatform
import ai.yaps.support.internal.protocol.ConversationResponse
import ai.yaps.support.internal.protocol.FrameCodec
import ai.yaps.support.internal.protocol.Message
import ai.yaps.support.internal.protocol.RegisterResponse
import ai.yaps.support.internal.protocol.WireJson
import ai.yaps.support.ui.CardPresentation
import ai.yaps.support.ui.cardPresentation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FixtureDecodingTest {

    private fun message(name: String): Message = WireJson.decodeFromString(Message.serializer(), Fixtures.read(name))

    @Test
    fun everyFixtureIsCoveredByADecoder() {
        val files = Fixtures.all()
        assertTrue("expected the shared fixtures, found ${files.map { it.name }}", files.size >= 13)
        for (file in files) {
            val text = file.readText()
            when {
                file.name.startsWith("message-") -> WireJson.decodeFromString(Message.serializer(), text)
                file.name == "appearance.json" -> WireJson.decodeFromString(Appearance.serializer(), text)
                file.name == "conversation-response.json" -> WireJson.decodeFromString(ConversationResponse.serializer(), text)
                file.name == "register-response.json" -> WireJson.decodeFromString(RegisterResponse.serializer(), text)
                file.name.startsWith("error-") -> assertNotNull(WireJson.decodeFromString(ApiErrorBody.serializer(), text).error)
                file.name.startsWith("push-") -> WireJson.parseToJsonElement(text).jsonObject
                file.name == "server-frames.jsonl" -> text.lines().filter { it.isNotBlank() }.forEach { FrameCodec.decode(it) }
                else -> fail("No decoder covers fixture ${file.name}; add one to this test")
            }
        }
    }

    @Test
    fun textMessageDecodes() {
        val m = message("message-text.json")
        assertEquals("msg_1", m.id)
        assertEquals(1L, m.seq)
        assertEquals("01JD8Z6Q0000000000000000AA", m.clientId)
        assertTrue(m.isFromCustomer)
        assertEquals("is there a dark mode?", m.text)
        assertNull(m.translation)
        assertNull(m.card)
    }

    @Test
    fun translatedMessageIgnoresUnknownFields() {
        val raw = Fixtures.read("message-agent-translated.json")
        assertTrue("fixture should carry futureField", raw.contains("futureField"))
        val m = WireJson.decodeFromString(Message.serializer(), raw)
        assertEquals("agent", m.author.type)
        assertEquals("Frederick", m.author.name)
        assertEquals("https://support.yaps.ai/v1/files/avatars/usr_3.png", m.author.avatarUrl)
        assertEquals("es", m.translation?.language)
        assertEquals("read", m.status)
    }

    @Test
    fun strictJsonWouldRejectTheFixtureSoTheLenientConfigMatters() {
        val strict = Json { ignoreUnknownKeys = false }
        val failed = runCatching { strict.decodeFromString(Message.serializer(), Fixtures.read("message-agent-translated.json")) }.isFailure
        assertTrue(failed)
    }

    @Test
    fun imageMessageDecodes() {
        val m = message("message-image.json")
        assertEquals("", m.text)
        val a = m.attachments.single()
        assertTrue(a.isImage)
        assertEquals("upl_1", a.id)
        assertEquals(1536, a.width)
        assertEquals(2048, a.height)
        assertEquals(183022L, a.size)
        assertNotNull(a.thumbnailUrl)
    }

    @Test
    fun offerAndReviewCardsDecode() {
        val offer = message("message-offer-card.json")
        assertEquals(CardType.OFFER, offer.card?.type)
        assertEquals("yearly_offer", offer.card?.paywallId)
        assertTrue(offer.card!!.available)

        val review = message("message-review-card.json")
        assertEquals(CardType.REVIEW, review.card?.type)
        assertEquals("Rate on the App Store", review.card?.buttonLabel)
        assertNull(review.card?.title)
    }

    @Test
    fun appPromoCardDecodes() {
        val m = message("message-app-card.json")
        assertEquals("card", m.kind)
        val card = m.card!!
        assertEquals(CardType.APP, card.type)
        assertEquals("Stacked", card.title)
        assertEquals("Flashcards that plan your revision for you.", card.body)
        assertEquals("Get it", card.buttonLabel)
        assertEquals("https://support.yaps.ai/v1/files/icons/app_2.png", card.iconUrl)
        assertEquals("app_2", card.promotedAppId)
        assertEquals("6450000001", card.appStoreId)
        assertEquals("https://apps.apple.com/app/id6450000001", card.url)
        assertFalse(card.installed)
        assertTrue(card.available)
        assertNull(card.openedAt)
        assertNull(card.code)
    }

    @Test
    fun codeCardDecodes() {
        val card = message("message-code-card.json").card!!
        assertEquals(CardType.CODE, card.type)
        assertEquals("Student discount", card.title)
        assertEquals("20% off yearly for students.", card.body)
        assertEquals("STUDENT20", card.code)
        assertEquals("cmp_1", card.campaignId)
        assertEquals(CodePlatform.IOS, card.platform)
        assertEquals("Redeem", card.buttonLabel)
        assertEquals("https://apps.apple.com/redeem?ctx=offercodes&id=6450000000&code=STUDENT20", card.url)
        assertEquals("2026-12-31T23:59:59.000Z", card.expiresAt)
        assertTrue(card.available)
    }

    @Test
    fun codeCardForAnotherPlatformDecodesWithoutUrlBodyOrExpiry() {
        val card = message("message-code-card-other-platform.json").card!!
        assertEquals(CardType.CODE, card.type)
        assertEquals("STUDENT20", card.code)
        assertEquals(CodePlatform.IOS, card.platform)
        assertFalse(card.available)
        assertNull(card.url)
        assertNull(card.body)
        assertNull(card.expiresAt)
    }

    @Test
    fun oldCardsDefaultTheNewFields() {
        val card = message("message-review-card.json").card!!
        assertFalse(card.installed)
        assertNull(card.body)
        assertNull(card.code)
        assertNull(card.platform)
        assertNull(card.iconUrl)
    }

    @Test
    fun unknownCardTypeRendersAsPlainText() {
        val m = message("message-unknown-card.json")
        assertEquals("calendar", m.card?.type)
        assertEquals(CardPresentation.PlainText, cardPresentation(m))
        assertEquals(CardPresentation.Offer, cardPresentation(message("message-offer-card.json")))
        assertEquals(CardPresentation.Review, cardPresentation(message("message-review-card.json")))
        assertEquals(CardPresentation.None, cardPresentation(message("message-text.json")))
    }

    @Test
    fun conversationResponseDecodes() {
        val r = WireJson.decodeFromString(ConversationResponse.serializer(), Fixtures.read("conversation-response.json"))
        assertEquals("cnv_1", r.conversation?.id)
        assertEquals(1, r.conversation?.unread)
        assertEquals(listOf(1L, 2L), r.messages.map { it.seq })
        assertFalse(r.hasMore)
    }

    @Test
    fun registerResponseDecodes() {
        val r = WireJson.decodeFromString(RegisterResponse.serializer(), Fixtures.read("register-response.json"))
        assertEquals("dt_reg_8f2k_example", r.deviceToken)
        assertFalse(r.customer.verified)
        assertEquals("Wakely", r.config.appName)
        assertEquals(listOf("paywall_declined"), r.config.trackedEvents)
        assertEquals("es", r.config.translation.customerLanguage)
        assertEquals(60, r.config.pollIntervalSeconds)
        assertEquals("W/\"a1\"", r.config.appearanceEtag)
    }

    @Test
    fun errorDecodesWithRetryAfter() {
        val e = WireJson.decodeFromString(ApiErrorBody.serializer(), Fixtures.read("error-rate-limited.json")).error!!
        assertEquals("rate_limited", e.code)
        assertEquals(5.0, e.retryAfter!!, 0.0)
    }

    @Test
    fun appearanceDecodes() {
        val a = WireJson.decodeFromString(Appearance.serializer(), Fixtures.read("appearance.json"))
        assertEquals("#FF8A5B", a.colors?.accent?.dark)
        assertEquals("Soporte de Wakely", a.title?.get("es"))
        assertEquals("rounded", a.fontStyle)
        assertNull(a.welcomeIconUrl)
    }

    @Test
    fun pushFixturesAreJsonObjects() {
        val fcm: JsonObject = WireJson.parseToJsonElement(Fixtures.read("push-fcm-data.json")).jsonObject
        assertEquals("1", fcm["support"]?.jsonPrimitive?.content)
    }
}
