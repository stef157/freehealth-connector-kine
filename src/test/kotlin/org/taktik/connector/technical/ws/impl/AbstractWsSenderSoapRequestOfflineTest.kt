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

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.taktik.connector.technical.ws.domain.GenericRequest
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress

/**
 * The request a `GenericResponse` carries — offline, against a loopback HTTP server, no eHealth call.
 *
 * `GenericResponse.asObject` hands `getRequest()` to every `SoapConversationLogger`, and the services write it to
 * `mycarenetConversation.soapRequest`. `call()` built its response without the request (dropped in 2ecfc2748,
 * 2020, and not restored by the HttpClient rewrite 13e096754), so `soapRequest` came back null on every route that
 * reads it — the MDA routes of 712cfcbfa included. The check is on the bytes the server received, not on the
 * presence of a message: the request must be the envelope that left, not a rebuilt copy.
 */
class AbstractWsSenderSoapRequestOfflineTest {
    private lateinit var server: HttpServer
    private var received: ByteArray = ByteArray(0)

    private val reply = """<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">""" +
        """<soapenv:Body><pong xmlns="urn:test:soaprequest"/></soapenv:Body></soapenv:Envelope>"""

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ws") { exchange ->
            received = exchange.requestBody.readBytes()
            val body = reply.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/xml; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
    }

    @Test
    fun theResponseCarriesTheEnvelopeThatWasSent() {
        val request = GenericRequest()
            .setEndpoint("http://127.0.0.1:${server.address.port}/ws")
            .setPayload("""<ping xmlns="urn:test:soaprequest">sentinel-4b1d</ping>""")

        val response = object : AbstractWsSender() {}.call(request)

        assertThat(response.request).isNotNull
        val sent = ByteArrayOutputStream().also { response.request.writeTo(it) }.toByteArray()
        assertThat(String(sent, Charsets.UTF_8)).contains("sentinel-4b1d")
        assertThat(sent).isEqualTo(received)
        assertThat(String(received, Charsets.UTF_8)).contains("sentinel-4b1d")
    }
}
