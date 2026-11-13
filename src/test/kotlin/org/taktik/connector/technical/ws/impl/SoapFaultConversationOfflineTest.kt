/*
 * Copyright (C) 2018 Taktik SA
 *
 * This file is part of iCureBackend.
 *
 * iCureBackend is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2 as published by
 * the Free Software Foundation.
 *
 * iCureBackend is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with iCureBackend.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.taktik.connector.technical.ws.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import jakarta.xml.ws.soap.SOAPFaultException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.springframework.http.HttpStatus
import org.taktik.connector.technical.ws.domain.GenericRequest
import org.taktik.connector.technical.ws.domain.SoapConversationFaultException
import org.taktik.connector.technical.ws.domain.withTransactionRequest
import org.taktik.freehealth.middleware.dto.ExceptionDto
import org.taktik.freehealth.middleware.dto.mycarenet.MycarenetConversation
import java.net.InetSocketAddress

/**
 * A SOAP fault keeps its conversation — offline, against a loopback HTTP server, no eHealth call.
 *
 * `GenericResponse.getSOAPException()` threw a bare `SOAPFaultException`, so a refused call reached the controller
 * with its fault string only: the envelope that left and the fault that came back were lost, and the client had
 * nothing to archive (measured 09/11/2026 on eAgreement `consultList`, SOA-01002: a 39-byte text body). The checks
 * are on the bytes the server received and on the fault it sent, not on the presence of two strings.
 */
class SoapFaultConversationOfflineTest {
    private lateinit var server: HttpServer
    private var received: ByteArray = ByteArray(0)

    private val fault = """<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">""" +
        """<soapenv:Body><soapenv:Fault><faultcode>soapenv:Server</faultcode>""" +
        """<faultstring>SOA-01002: Service call not authorized.</faultstring>""" +
        """<detail><marker xmlns="urn:test:soapfault">fault-sentinel-7c2e</marker></detail>""" +
        """</soapenv:Fault></soapenv:Body></soapenv:Envelope>"""

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ws") { exchange ->
            received = exchange.requestBody.readBytes()
            val body = fault.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/xml; charset=utf-8")
            exchange.sendResponseHeaders(500, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
    }

    private fun faultFromServer(): Throwable {
        val request = GenericRequest()
            .setEndpoint("http://127.0.0.1:${server.address.port}/ws")
            .setPayload("""<ping xmlns="urn:test:soapfault">request-sentinel-4b1d</ping>""")

        val response = object : AbstractWsSender() {}.call(request)

        return catchThrowable { response.getSOAPException() }
    }

    @Test
    fun theFaultCarriesTheEnvelopeThatLeftAndTheFaultThatCameBack() {
        val thrown = faultFromServer()

        // Still a SOAPFaultException: every existing catch keeps working.
        assertThat(thrown).isInstanceOf(SOAPFaultException::class.java)
        assertThat(thrown).isInstanceOf(SoapConversationFaultException::class.java)
        thrown as SoapConversationFaultException

        assertThat(thrown.soapRequest).contains("request-sentinel-4b1d")
        assertThat(thrown.soapRequest!!.toByteArray(Charsets.UTF_8)).isEqualTo(received)
        assertThat(thrown.soapResponse).contains("fault-sentinel-7c2e")
        assertThat(thrown.soapResponse).contains("SOA-01002: Service call not authorized.")
    }

    @Test
    fun theEnvelopesNeverReachTheMessage() {
        val thrown = faultFromServer()

        // The message reaches logs and Sentry; the request envelope carries the CIN licence.
        assertThat(thrown.message).isEqualTo("SOA-01002: Service call not authorized.")
        assertThat(thrown.toString()).doesNotContain("request-sentinel-4b1d").doesNotContain("fault-sentinel-7c2e")
    }

    @Test
    fun theErrorBodyCarriesTheConversationEvenWhenTheFaultIsWrapped() {
        // eFact rethrows a fault as IllegalStateException(e): the advice answers it from the cause chain.
        val wrapped = IllegalStateException(faultFromServer())

        val json = ObjectMapper().readTree(
            ObjectMapper().writeValueAsString(ExceptionDto(HttpStatus.BAD_GATEWAY, wrapped, "/efact/batch"))
        )

        assertThat(json["status"].asInt()).isEqualTo(502)
        assertThat(json["mycarenetConversation"]["soapRequest"].asText()).contains("request-sentinel-4b1d")
        assertThat(json["mycarenetConversation"]["soapResponse"].asText()).contains("fault-sentinel-7c2e")
    }

    /**
     * The business request the service built, attached on the way up even when the fault arrives wrapped — the piece
     * the dev's error body lacked on 09/11/2026 (`transactionRequest: null` beside both envelopes).
     */
    @Test
    fun theServiceAttachesItsBusinessRequestToAWrappedFault() {
        val thrown = catchThrowable {
            withTransactionRequest({ "<business>transaction-sentinel-9e3a</business>" }) {
                // A connector layer that wraps the fault, as a proxy or `TechnicalConnectorException` would.
                throw IllegalStateException(faultFromServer())
            }
        }

        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
        assertThat(thrown.toString()).doesNotContain("transaction-sentinel-9e3a")
        assertThat(thrown.cause!!.message).isEqualTo("SOA-01002: Service call not authorized.")
        assertThat(thrown.cause.toString()).doesNotContain("transaction-sentinel-9e3a")

        val json = ObjectMapper().readTree(
            ObjectMapper().writeValueAsString(ExceptionDto(HttpStatus.BAD_GATEWAY, thrown as Exception, "/eagreement/consultList"))
        )
        val conversation = json["mycarenetConversation"]
        assertThat(conversation["transactionRequest"].asText()).isEqualTo("<business>transaction-sentinel-9e3a</business>")
        assertThat(conversation["soapRequest"].asText()).contains("request-sentinel-4b1d")
        assertThat(conversation["soapResponse"].asText()).contains("fault-sentinel-7c2e")
    }

    /**
     * eAgreement's piece before encryption (the FHIR Bundle), which `transactionRequest` carries sealed. medispring's
     * MS-15812 put it in `soapRequest`; here it has its own field and `soapRequest` stays the envelope that left.
     */
    @Test
    fun theServiceAttachesItsContentBeforeEncryptionBesideTheTransactionRequest() {
        val thrown = catchThrowable {
            withTransactionRequest({ "<sealed>transaction-sentinel-9e3a</sealed>" }, { "<Bundle>business-sentinel-5f80</Bundle>" }) {
                throw IllegalStateException(faultFromServer())
            }
        }

        assertThat(thrown.toString()).doesNotContain("business-sentinel-5f80")
        assertThat(thrown.cause.toString()).doesNotContain("business-sentinel-5f80")

        val conversation = ObjectMapper().readTree(
            ObjectMapper().writeValueAsString(ExceptionDto(HttpStatus.BAD_GATEWAY, thrown as Exception, "/eagreement/askAgreement"))
        )["mycarenetConversation"]
        assertThat(conversation["businessRequest"].asText()).isEqualTo("<Bundle>business-sentinel-5f80</Bundle>")
        assertThat(conversation["transactionRequest"].asText()).isEqualTo("<sealed>transaction-sentinel-9e3a</sealed>")
        assertThat(conversation["soapRequest"].asText()).contains("request-sentinel-4b1d").doesNotContain("business-sentinel-5f80")
    }

    @Test
    fun oneMissingPieceNeverDropsTheOther() {
        val thrown = catchThrowable {
            withTransactionRequest({ throw IllegalArgumentException("marshalling failed") }, { "<Bundle/>" }) {
                faultFromServer().let { throw it }
            }
        } as SoapConversationFaultException

        assertThat(thrown.transactionRequest).isNull()
        assertThat(thrown.businessRequest).isEqualTo("<Bundle/>")
    }

    @Test
    fun aDomainWithoutAContentBeforeEncryptionKeepsItsShape() {
        val thrown = catchThrowable {
            withTransactionRequest({ "<business/>" }) { faultFromServer().let { throw it } }
        }
        val conversation = ObjectMapper().readTree(
            ObjectMapper().writeValueAsString(ExceptionDto(HttpStatus.BAD_GATEWAY, thrown as Exception, "/efact/batch"))
        )["mycarenetConversation"]

        assertThat(conversation.has("businessRequest")).isFalse()
        assertThat(ObjectMapper().readTree(ObjectMapper().writeValueAsString(MycarenetConversation())).has("businessRequest")).isFalse()
    }

    @Test
    fun aRequestThatCannotBeBuiltNeverHidesTheFault() {
        val thrown = catchThrowable {
            withTransactionRequest({ throw IllegalArgumentException("marshalling failed") }) { faultFromServer().let { throw it } }
        }

        assertThat(thrown).isInstanceOf(SoapConversationFaultException::class.java)
        assertThat((thrown as SoapConversationFaultException).transactionRequest).isNull()
        assertThat(thrown.soapRequest).contains("request-sentinel-4b1d")
    }

    @Test
    fun anErrorThatIsNotAFaultNeverBuildsTheRequest() {
        var built = false
        val thrown = catchThrowable {
            withTransactionRequest({ built = true; "<business/>" }) { throw IllegalStateException("boom") }
        }

        assertThat(thrown).hasMessage("boom")
        assertThat(built).isFalse()
    }

    @Test
    fun anErrorThatIsNotAFaultKeepsItsBodyShape() {
        val json = ObjectMapper().readTree(
            ObjectMapper().writeValueAsString(
                ExceptionDto(HttpStatus.INTERNAL_SERVER_ERROR, IllegalStateException("boom"), "/efact/batch")
            )
        )

        assertThat(json.has("mycarenetConversation")).isFalse()
        assertThat(json["message"].asText()).isEqualTo("boom")
    }
}
