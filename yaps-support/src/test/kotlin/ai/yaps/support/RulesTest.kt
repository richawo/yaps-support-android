package ai.yaps.support

import ai.yaps.support.internal.core.Backoff
import ai.yaps.support.internal.core.Limits
import ai.yaps.support.internal.core.PushRecognizer
import ai.yaps.support.internal.core.ReviewLink
import ai.yaps.support.internal.core.TranslationRule
import ai.yaps.support.internal.core.Ulid
import ai.yaps.support.internal.protocol.Message
import ai.yaps.support.internal.protocol.Translation
import ai.yaps.support.internal.protocol.WireJson
import ai.yaps.support.ui.isOfferAvailable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TranslationRuleTest {
    private val translated = WireJson.decodeFromString(Message.serializer(), Fixtures.read("message-agent-translated.json"))

    @Test
    fun showsTranslationWhenItMatchesTheCustomerLanguage() {
        assertEquals("¡Sí! Ajustes → Apariencia → Oscuro.", TranslationRule.displayText(translated, "es"))
    }

    @Test
    fun showsOriginalOtherwise() {
        assertEquals("Yes! Settings → Appearance → Dark.", TranslationRule.displayText(translated, "en"))
        assertEquals("Yes! Settings → Appearance → Dark.", TranslationRule.displayText(translated, "fr"))
        assertEquals("Yes! Settings → Appearance → Dark.", TranslationRule.displayText(translated, null))
    }

    @Test
    fun comparesPrimarySubtagsCaseInsensitively() {
        assertEquals(translated.translation!!.text, TranslationRule.displayText(translated, "es-MX"))
        assertEquals(translated.translation!!.text, TranslationRule.displayText(translated, "ES_es"))
    }

    @Test
    fun legacyAndModernLanguageCodesMatch() {
        val hebrew = translated.copy(translation = Translation("he", "כן"))
        assertEquals("כן", TranslationRule.displayText(hebrew, "iw"))
        val indonesian = translated.copy(translation = Translation("in", "Ya"))
        assertEquals("Ya", TranslationRule.displayText(indonesian, "id-ID"))
    }

    @Test
    fun noTranslationOrBlankTranslationShowsOriginal() {
        val plain = WireJson.decodeFromString(Message.serializer(), Fixtures.read("message-text.json"))
        assertEquals(plain.text, TranslationRule.displayText(plain, "es"))
        val blank = translated.copy(translation = Translation("es", "  "))
        assertEquals(translated.text, TranslationRule.displayText(blank, "es"))
    }
}

class PushRecognitionTest {
    private fun flatten(json: String): Map<String, String> =
        WireJson.parseToJsonElement(json).jsonObject.mapValues { (_, v) -> runCatching { v.jsonPrimitive.content }.getOrDefault(v.toString()) }

    @Test
    fun fcmFixtureIsASupportPush() {
        val data = flatten(Fixtures.read("push-fcm-data.json"))
        assertTrue(PushRecognizer.isSupportPush(data))
        assertTrue(YapsSupport.isSupportPush(data))
        assertEquals("cnv_1", PushRecognizer.conversationId(data))
        assertEquals("msg_9", PushRecognizer.messageId(data))
        assertEquals(1, PushRecognizer.badge(data))
    }

    @Test
    fun foreignPayloadsAreNotOurs() {
        assertFalse(YapsSupport.isSupportPush(mapOf("type" to "promo", "title" to "Sale", "body" to "50% off")))
        assertFalse(YapsSupport.isSupportPush(emptyMap()))
        assertFalse(YapsSupport.isSupportPush(mapOf("support" to "0")))
        assertFalse(YapsSupport.isSupportPush(mapOf("support" to "true")))
        assertFalse(YapsSupport.isSupportPush(null))
    }

    @Test
    fun apnsFixtureCarriesTheSupportKeyButIsNotAnFcmDataPush() {
        // On Android only FCM data maps arrive; the APNs shape has an object under "support", never "1".
        val apns = flatten(Fixtures.read("push-apns.json"))
        assertTrue(apns.containsKey("support"))
        assertFalse(YapsSupport.isSupportPush(apns))
    }

    @Test
    fun handlePushReturnsFalseForForeignPayloadsSoHostsCanChain() {
        assertFalse(YapsSupport.handlePush(FakeContext.none, mapOf("from" to "marketing")))
    }
}

class ReviewLinkTest {
    @Test
    fun marketUrlFallsBackToHttps() {
        assertEquals(
            listOf("market://details?id=com.clucky.app", "https://play.google.com/store/apps/details?id=com.clucky.app"),
            ReviewLink.candidates("market://details?id=com.clucky.app", "ignored"),
        )
    }

    @Test
    fun marketUrlKeepsExtraQueryParameters() {
        assertEquals(
            "https://play.google.com/store/apps/details?id=com.a&showAllReviews=true",
            ReviewLink.candidates("market://details?id=com.a&showAllReviews=true", "x")[1],
        )
    }

    @Test
    fun playWebUrlTriesTheStoreAppFirst() {
        assertEquals(
            listOf("market://details?id=com.a", "https://play.google.com/store/apps/details?id=com.a"),
            ReviewLink.candidates("https://play.google.com/store/apps/details?id=com.a", "x"),
        )
    }

    @Test
    fun missingUrlUsesTheHostPackage() {
        assertEquals(
            listOf("market://details?id=com.host", "https://play.google.com/store/apps/details?id=com.host"),
            ReviewLink.candidates(null, "com.host"),
        )
        assertEquals(ReviewLink.candidates(null, "com.host"), ReviewLink.candidates("  ", "com.host"))
    }

    @Test
    fun otherUrlsOpenAsIs() {
        val url = "https://apps.apple.com/app/id6450000000?action=write-review"
        assertEquals(listOf(url), ReviewLink.candidates(url, "x"))
    }
}

class OfferAvailabilityTest {
    @Test
    fun offerNeedsServerAvailabilityAHandlerAndAnAvailablePaywall() {
        assertTrue(isOfferAvailable(true, "yearly_offer", setOf("yearly_offer"), hasHandler = true))
        assertFalse(isOfferAvailable(false, "yearly_offer", setOf("yearly_offer"), hasHandler = true))
        assertFalse(isOfferAvailable(true, "yearly_offer", emptySet(), hasHandler = true))
        assertFalse(isOfferAvailable(true, "yearly_offer", setOf("yearly_offer"), hasHandler = false))
        assertFalse(isOfferAvailable(true, null, setOf("yearly_offer"), hasHandler = true))
    }
}

class BackoffAndIdsTest {
    @Test
    fun socketBackoffFollowsTheProtocolScheduleWithinJitter() {
        val b = Backoff(baseMillis = 1_000, capMillis = 30_000, jitter = 0.2, random = Random(7))
        val expected = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)
        expected.forEachIndexed { i, base ->
            val d = b.delayFor(i + 1)
            assertTrue("attempt ${i + 1}: $d not within 20% of $base", d in (base * 0.8).toLong()..(base * 1.2).toLong())
        }
    }

    @Test
    fun zeroJitterIsExact() {
        val b = Backoff(baseMillis = 1_000, capMillis = 60_000, jitter = 0.0)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), (1..8).map { b.delayFor(it) })
    }

    @Test
    fun ulidsAreValidSortableAndUnique() {
        val a = Ulid.generate(1_700_000_000_000)
        val b = Ulid.generate(1_700_000_000_001)
        assertTrue(Ulid.isValid(a))
        assertTrue(a.substring(0, 10) < b.substring(0, 10))
        assertEquals(1_000, (1..1_000).map { Ulid.generate() }.toSet().size)
    }

    @Test
    fun limitsCountUtf16CodeUnits() {
        assertFalse(Limits.textTooLong("a".repeat(4_000)))
        assertTrue(Limits.textTooLong("a".repeat(4_001)))
        assertTrue(Limits.textTooLong("🐓".repeat(2_001))) // each rooster is two UTF-16 units
        assertFalse(Limits.textTooLong("  " + "a".repeat(4_000) + "  ")) // trimmed first
    }

    @Test
    fun eventNamesAndMetadataAreValidated() {
        assertTrue(Limits.isValidEventName("paywall_declined"))
        assertFalse(Limits.isValidEventName("has space"))
        assertFalse(Limits.isValidEventName(""))
        assertEquals(mapOf("plan" to "pro"), Limits.sanitizeMetadata(mapOf("plan" to "pro", "bad key" to "x")))
    }
}
