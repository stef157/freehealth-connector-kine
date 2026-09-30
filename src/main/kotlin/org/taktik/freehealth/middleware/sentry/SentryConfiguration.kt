package org.taktik.freehealth.middleware.sentry

import io.sentry.Sentry
import io.sentry.SentryOptions
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.taktik.freehealth.middleware.service.STSService

/**
 * Starts Sentry when `SENTRY_DSN` is set, and does nothing otherwise — the fork is public, so the DSN never
 * sits in the repository. Only what [org.taktik.freehealth.middleware.web.ExceptionHandlers] captures is
 * sent (server errors, 5xx), always through [SentryScrubber].
 */
@Configuration
class SentryConfiguration(
    private val stsService: STSService,
    @Value("\${sentry.dsn:}") private val dsn: String,
    @Value("\${sentry.environment:}") private val environment: String
) {
    private val log = LoggerFactory.getLogger(SentryConfiguration::class.java)

    @PostConstruct
    fun init() {
        if (dsn.isBlank()) {
            log.info("Sentry disabled: no SENTRY_DSN")
            return
        }
        val release = SentryConfiguration::class.java.`package`?.implementationVersion
        val env = environment.ifBlank { if (stsService.isAcceptance()) "acceptance" else "production" }
        Sentry.init { options: SentryOptions ->
            options.dsn = dsn
            options.environment = env
            options.release = release
            options.isSendDefaultPii = false
            options.maxBreadcrumbs = 0
            options.setTag("component", "fhc")
            options.beforeSend = SentryOptions.BeforeSendCallback { event, _ -> SentryScrubber.scrub(event) }
        }
        log.info("Sentry enabled: environment {}, release {}", env, release)
    }
}
