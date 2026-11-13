package org.taktik.freehealth.middleware.web

import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.taktik.freehealth.middleware.newrelic.NewRelicLogAppender

class RequestIdFilterOfflineTest {
    private fun run(header: String?): Pair<String?, String?> {
        val request = MockHttpServletRequest().apply { header?.let { addHeader(RequestIdFilter.HEADER, it) } }
        val response = MockHttpServletResponse()
        var seen: String? = null
        RequestIdFilter().doFilter(request, response, FilterChain { _, _ -> seen = MDC.get(NewRelicLogAppender.REQUEST_ID_MDC) })
        assertThat(MDC.get(NewRelicLogAppender.REQUEST_ID_MDC)).isNull()
        return seen to response.getHeader(RequestIdFilter.HEADER)
    }

    @Test
    fun theCallersIdIsKeptAndEchoed() {
        assertThat(run("123e4567-e89b-12d3-a456-426614174000")).isEqualTo("123e4567-e89b-12d3-a456-426614174000" to "123e4567-e89b-12d3-a456-426614174000")
        assertThat(run("kd-attest.42")).isEqualTo("kd-attest.42" to "kd-attest.42")
    }

    @Test
    fun withoutOneAFreshUuidIsUsedAndEchoed() {
        val (mdc, echoed) = run(null)
        assertThat(mdc).matches("[0-9a-f-]{36}").isEqualTo(echoed)
    }

    @Test
    fun anIdThatCouldCarryAnIdentifierIsReplaced() {
        listOf("12345678901", "patient-11111111111", "a b", "x".repeat(65), "'quoted'").forEach { bad ->
            val (mdc, echoed) = run(bad)
            assertThat(mdc).isNotEqualTo(bad).matches("[0-9a-f-]{36}").isEqualTo(echoed)
        }
    }
}
