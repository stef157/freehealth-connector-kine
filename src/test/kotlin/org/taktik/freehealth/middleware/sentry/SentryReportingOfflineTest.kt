package org.taktik.freehealth.middleware.sentry

import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import jakarta.xml.ws.soap.SOAPFaultException
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.servlet.HandlerMapping
import org.taktik.freehealth.middleware.exception.MissingTokenException
import org.taktik.freehealth.middleware.web.ExceptionHandlers
import org.taktik.freehealth.utils.AsyncPayloadDecodingException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What reaches Sentry, offline: the real scrubber runs in `beforeSend`, which then drops the event, so nothing
 * leaves the machine. Identifiers are synthetic, one per role.
 */
class SentryReportingOfflineTest {
    private val sent = CopyOnWriteArrayList<SentryEvent>()
    private val handlers = ExceptionHandlers()

    @Before
    fun startSentry() {
        Sentry.init { options: SentryOptions ->
            SentryConfiguration.configure(options, "https://public@localhost/1", "test", "0.0.0")
            val shipped = options.beforeSend!!
            options.beforeSend = SentryOptions.BeforeSendCallback { event, hint ->
                shipped.execute(event, hint)?.let { sent.add(it) }
                null
            }
        }
    }

    @After
    fun stopSentry() = Sentry.close()

    private fun request(path: String, pattern: String) = MockHttpServletRequest("POST", path).apply {
        servletPath = path
        addHeader("X-FHC-passPhrase", "secret")
        addHeader("X-FHC-keystoreId", "0b9e4c1e-3f7a-4d2b-9c8e-1a2b3c4d5e6f")
        setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern)
    }

    @Test
    fun aServerErrorIsSentOnceWithEveryIdentifierScrubbed() {
        val cause = IllegalStateException("INCORRECT_INSS_PHYSIOTHERAPIST_SAML for 11111111111 (22.22.22-222.22)")
        val exception = RuntimeException(
            "author 12345678901 / root 12345678, keystore 0b9e4c1e-3f7a-4d2b-9c8e-1a2b3c4d5e6f", cause)

        handlers.handleException(request("/mda/11111111111", "/mda/{ssin}"), exception)

        assertThat(sent).hasSize(1)
        val event = sent.single()
        val texts = event.exceptions!!.map { it.value }
        assertThat(texts).hasSize(2)
        texts.forEach { text ->
            assertThat(text).doesNotContain("11111111111", "22.22.22-222.22", "12345678901", "12345678",
                "0b9e4c1e")
        }
        assertThat(texts).contains("author [digits] / root [digits], keystore [uuid]",
            "INCORRECT_INSS_PHYSIOTHERAPIST_SAML for [digits] ([digits])")
        assertThat(event.request).isNull()
        assertThat(event.getTag("route")).isEqualTo("/mda/{ssin}")
        assertThat(event.getTag("status")).isEqualTo("500")
    }

    @Test
    fun aSoapFaultIsSentAsA502() {
        handlers.handleSoapFaultException(request("/eattestv3/send/11111111111", "/eattestv3/send/{patientSsin}"),
            mock(SOAPFaultException::class.java))

        assertThat(sent).hasSize(1)
        assertThat(sent.single().getTag("status")).isEqualTo("502")
    }

    @Test
    fun aValueQuotedByXsdValidationIsScrubbed() {
        handlers.handleException(request("/efact/flat", "/efact/flat"), RuntimeException(
            "XML could not be validated against XSD. cvc-pattern-valid: Value 'Dupont Marie' is not facet-valid"))

        assertThat(sent.single().exceptions!!.single().value)
            .isEqualTo("XML could not be validated against XSD. cvc-pattern-valid: Value '[value]' is not facet-valid")
        assertThat(sent.single().getTag("component")).isEqualTo("fhc")
    }

    @Test
    fun aBindingFailureAnswers500ButStaysOut() {
        val response = handlers.handleException(request("/mda/11111111111", "/mda/{ssin}"),
            org.springframework.web.bind.MissingRequestHeaderException("X-FHC-tokenId",
                org.springframework.core.MethodParameter(
                    SentryReportingOfflineTest::class.java.getDeclaredMethod("startSentry"), -1)))

        assertThat(response.statusCode.value()).isEqualTo(500)
        assertThat(sent).isEmpty()
    }

    @Test
    fun clientErrorsStayOut() {
        handlers.handleIllegalArgumentException(request("/efact/flat", "/efact/flat"),
            IllegalArgumentException("bad batch for 11111111111"))
        handlers.handleUnauthorizedException(request("/mda/11111111111", "/mda/{ssin}"),
            MissingTokenException("no token"))

        assertThat(sent).isEmpty()
    }

    @Test
    fun anAsyncMessageThatCannotBeDecodedIsSentAlthoughTheCallAnswers200() {
        SentryReporter.asyncDecodingFailure("mda-async", "unmarshal ResponseList", AsyncPayloadDecodingException(
            "unmarshal ResponseList", "3c 3f 78 6d 6c (120 bytes)", IllegalStateException("unexpected NameID 11111111111")))

        val event = sent.single()
        assertThat(event.getTag("channel")).isEqualTo("mda-async")
        assertThat(event.getTag("stage")).isEqualTo("unmarshal ResponseList")
        assertThat(event.getTag("component")).isEqualTo("fhc")
        assertThat(event.exceptions!!.map { it.value }).allSatisfy { assertThat(it).doesNotContain("11111111111") }
    }
}
