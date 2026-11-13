package org.taktik.freehealth.middleware.dto.mycarenet

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import org.eclipse.jetty.io.WriterOutputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.StringWriter

@JsonIgnoreProperties(ignoreUnknown = true)
class MycarenetConversation(
    soapRequest: String? = null,
    soapResponse: String? = null,
    var transactionRequest: String? = null,
    var transactionResponse: String? = null,
    var decryptedResponseContent: ArrayList<String>? = arrayListOf()
                           ) {

    /**
     * The business content as it was **before encryption** — the very bytes the service sealed, for a domain whose
     * `transactionRequest` carries them encrypted (eAgreement: the FHIR Bundle). Null where the domain has no distinct
     * clear piece, and then absent from the JSON, so the other domains keep their shape.
     *
     * Not `soapRequest`, which stays the envelope that left (medispring MS-15812 overwrote it with this piece).
     * ⚠️ Patient data in clear (SSIN, prescription PDF in base64); no CIN licence.
     */
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    var businessRequest: String? = null

    var soapRequest: String? = soapRequest
        get() = field ?: soapRequestBos?.toByteArray()?.toString(Charsets.UTF_8)
    var soapResponse: String? = soapResponse
        get() = field ?: soapResponseBos?.toByteArray()?.toString(Charsets.UTF_8)

    private var soapRequestBos:ByteArrayOutputStream? = null
    private var soapResponseBos:ByteArrayOutputStream? = null

    fun soapRequestOutputStream(): OutputStream {
        return soapRequestBos ?: ByteArrayOutputStream(10000).apply { this@MycarenetConversation.soapRequestBos = this }
    }

    fun soapResponseOutputStream(): OutputStream {
        return soapResponseBos ?: ByteArrayOutputStream(10000).apply { this@MycarenetConversation.soapResponseBos = this }
    }
}
