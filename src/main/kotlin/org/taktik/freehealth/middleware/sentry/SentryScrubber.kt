package org.taktik.freehealth.middleware.sentry

import io.sentry.SentryEvent

/**
 * Strips from an event everything that may identify a patient or a practitioner, or let someone act as one.
 *
 * Exception messages are the only free text that travels: MyCareNet names the SSIN in some faults
 * (INCORRECT_INSS_PHYSIOTHERAPIST_SAML…), the connector puts NIHII in others, and a keystore or token id
 * together with the passphrase is a signing credential, and an XSD validation message quotes the offending value. The request itself — URL, headers, body — never
 * leaves: `/mda/{ssin}` carries the SSIN in the path, `POST /sts/keystore` the PKCS#12.
 */
object SentryScrubber {
    // 85.07.30-033.28, 850730-033-28 … the formatted SSIN, before the bare-digit rule eats half of it
    private val formattedSsin = Regex("""\b\d{2}\.?\d{2}\.?\d{2}[-.]?\d{3}[-.]?\d{2}\b""")
    // SSIN and NIHII (11 digits), NIHII roots (8), KMEHR ids and yyyyMMddHHmmss dates: none is worth the risk
    private val digitRuns = Regex("""\d{8,}""")
    // XSD validation (ERROR_XML_INVALID, a 500) repeats the offending value between quotes: a name, an address…
    private val quoted = Regex("""'[^']*'|"[^"]*"""")
    private val uuids = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")

    fun scrub(text: String?): String? = text
        ?.replace(quoted, "'[value]'")
        ?.replace(uuids, "[uuid]")
        ?.replace(formattedSsin, "[digits]")
        ?.replace(digitRuns, "[digits]")

    fun scrub(event: SentryEvent): SentryEvent = event.apply {
        request = null
        user = null
        breadcrumbs = null
        extras = null
        message?.let { m ->
            m.message = scrub(m.message)
            m.formatted = scrub(m.formatted)
            m.params = null
        }
        exceptions?.forEach { it.value = scrub(it.value) }
    }
}
