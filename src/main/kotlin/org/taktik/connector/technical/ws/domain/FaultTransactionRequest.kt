package org.taktik.connector.technical.ws.domain

/**
 * Runs [call] and, if it ends in a SOAP fault from eHealth, attaches the business request [transactionRequest] to it
 * before letting it go — the piece the success path records in `mycarenetConversation.transactionRequest`.
 *
 * Measured on the dev on 09/11/2026 (eAgreement `consultList`, SOA-01002): with [SoapConversationFaultException] the
 * error body carried both envelopes, but `transactionRequest` stayed null, because only the service that built the
 * request knows it, and it set it on the success path alone.
 *
 * - The fault is looked for in the whole cause chain, as `ExceptionDto.conversationOf` does: between the call and
 *   `GenericResponse`, a proxy or a connector exception may wrap it.
 * - The SAME throwable is rethrown, so the status, the handlers and the message do not move.
 * - [transactionRequest] is computed only on a fault, and a failure to compute it never hides the fault.
 * - Pass the very expression of the success path, so the piece does not depend on the outcome.
 * - [businessRequest] is the clear content where [transactionRequest] carries it encrypted (eAgreement's FHIR
 *   Bundle, `mycarenetConversation.businessRequest`); the other domains leave it out. Same rules, independently:
 *   one piece failing to compute does not drop the other.
 */
inline fun <T> withTransactionRequest(
    transactionRequest: () -> String?,
    businessRequest: () -> String? = { null },
    call: () -> T
): T =
    try {
        call()
    } catch (e: Throwable) {
        soapConversationFaultIn(e)?.let { fault ->
            runCatching(transactionRequest).getOrNull()?.let { fault.attachTransactionRequest(it) }
            runCatching(businessRequest).getOrNull()?.let { fault.attachBusinessRequest(it) }
        }
        throw e
    }

/** The first [SoapConversationFaultException] in the cause chain of [e], if any. */
fun soapConversationFaultIn(e: Throwable): SoapConversationFaultException? {
    val seen = mutableSetOf<Throwable>()
    var current: Throwable? = e
    while (current != null && seen.add(current)) {
        if (current is SoapConversationFaultException) {
            return current
        }
        current = current.cause
    }
    return null
}
