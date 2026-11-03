package org.taktik.freehealth.middleware.sentry

import io.sentry.Sentry

/**
 * Failures that no longer reach [org.taktik.freehealth.middleware.web.ExceptionHandlers] because the call
 * answers 200 anyway. They go through [SentryScrubber] like everything else.
 */
object SentryReporter {
    /**
     * An async message that could not be decoded: it comes back as a `DECODING_ERROR` entry among the others,
     * so without this the failure would only sit in the container log.
     */
    fun asyncDecodingFailure(channel: String, stage: String, exception: Exception) {
        if (!Sentry.isEnabled()) return
        Sentry.withScope { scope ->
            scope.setTag("channel", channel)
            scope.setTag("stage", stage)
            Sentry.captureException(exception)
        }
    }

    /**
     * An eHealthBox message the list could not read. It comes back as an `ErrorMessage` carrying its id, so the
     * list answers 200; without this the failure would only sit in the container log.
     */
    fun ehboxMessageUnreadable(exception: Exception) {
        if (!Sentry.isEnabled()) return
        Sentry.withScope { scope ->
            scope.setTag("channel", "ehbox-list")
            Sentry.captureException(exception)
        }
    }
}
