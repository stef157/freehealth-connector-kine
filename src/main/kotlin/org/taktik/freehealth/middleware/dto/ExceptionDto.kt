package org.taktik.freehealth.middleware.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.http.HttpStatus
import org.taktik.connector.technical.ws.domain.SoapConversationFaultException
import org.taktik.connector.technical.ws.domain.soapConversationFaultIn
import org.taktik.freehealth.middleware.dto.mycarenet.MycarenetConversation
import org.springframework.http.ResponseEntity
import java.util.*

@JsonIgnoreProperties(ignoreUnknown = true)
class ExceptionDto(
    val timestamp: Date,
    val status: Int,
    val error: String?,
    val message: String?,
    val path: String?,
    /**
     * The envelope that left and the fault that came back, when the error is a SOAP fault from eHealth
     * ([SoapConversationFaultException], found anywhere in the cause chain). Absent otherwise, so every other error
     * body keeps its shape. `transactionRequest` is there when the service attached it (`withTransactionRequest`), and
     * `businessRequest` — the content before encryption — where the domain has one (eAgreement's FHIR Bundle).
     * ⚠️ `soapRequest` carries the CIN licence in clear, as on a success, and eAgreement's `transactionRequest` too;
     * `businessRequest` carries patient data in clear.
     */
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val mycarenetConversation: MycarenetConversation? = null
) {
    companion object {
        private const val DEFAULT_EXCEPTION_MESSAGE = "unknown reason"

        /** The conversation of the first [SoapConversationFaultException] in the cause chain, if any. */
        fun conversationOf(exception: Throwable): MycarenetConversation? =
            soapConversationFaultIn(exception)?.let {
                MycarenetConversation(
                    transactionRequest = it.transactionRequest,
                    soapRequest = it.soapRequest,
                    soapResponse = it.soapResponse
                ).apply { businessRequest = it.businessRequest }
            }
    }

    constructor(status: HttpStatus, exception: Exception, path: String?) :
        this(
            Date(),
            status.value(),
            status.reasonPhrase,
            exception.message ?: DEFAULT_EXCEPTION_MESSAGE,
            path,
            conversationOf(exception)
        )

    fun toResponseEntity(): ResponseEntity<ExceptionDto> {
        val builder = ResponseEntity.status(status)
        if (status == HttpStatus.UNAUTHORIZED.value()) {
            builder.header("WWW-Authenticate", "Bearer")
        }
        return builder.body(this)
    }
}
