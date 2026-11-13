package org.taktik.freehealth.middleware.web

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.taktik.freehealth.middleware.newrelic.NewRelicLogAppender
import org.taktik.freehealth.middleware.sentry.SentryScrubber
import java.util.UUID

/**
 * Ties every log line of a request to the caller's action: the client (kine-data, the apps) sends `X-Request-Id`,
 * the connector puts it in the MDC for the duration of the request and echoes it on the response. Absent or
 * unusable, a fresh UUID takes its place, and is echoed too, so the client can still quote it.
 *
 * The value travels to New Relic unscrubbed, as an attribute, so it is accepted only if it cannot carry an
 * identifier: a UUID, or up to 64 of `[A-Za-z0-9._-]` that [SentryScrubber] would leave untouched — an SSIN, a
 * NIHII or any run of eight digits is refused.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter : Filter {
    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val requestId = accept((request as? HttpServletRequest)?.getHeader(HEADER)) ?: UUID.randomUUID().toString()
        (response as? HttpServletResponse)?.setHeader(HEADER, requestId)
        MDC.put(NewRelicLogAppender.REQUEST_ID_MDC, requestId)
        try {
            chain.doFilter(request, response)
        } finally {
            MDC.remove(NewRelicLogAppender.REQUEST_ID_MDC)
        }
    }

    companion object {
        const val HEADER = "X-Request-Id"
        private val uuid = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
        private val token = Regex("""[A-Za-z0-9._-]{1,64}""")

        fun accept(header: String?): String? {
            val value = header?.trim() ?: return null
            return when {
                uuid.matches(value) -> value
                token.matches(value) && SentryScrubber.scrub(value) == value -> value
                else -> null
            }
        }
    }
}
