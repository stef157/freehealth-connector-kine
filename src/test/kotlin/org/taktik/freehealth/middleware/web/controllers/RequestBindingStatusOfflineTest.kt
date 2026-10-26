package org.taktik.freehealth.middleware.web.controllers

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.junit4.SpringRunner
import org.taktik.freehealth.middleware.MyTestsConfiguration
import java.net.URI
import java.util.UUID

/**
 * A request Spring cannot bind or route answers its own 4xx, no longer the 500 the catch-all handler gave it.
 * None of these reaches a controller, so none sends anything — which is what kine-data reads in a status below 500.
 * Offline: the failures happen before any keystore, token or network is needed.
 */
@RunWith(SpringRunner::class)
@Import(MyTestsConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestBindingStatusOfflineTest {
    @LocalServerPort
    private val port: Int = 0

    @Autowired
    private val restTemplate: TestRestTemplate? = null

    private fun headers(keystoreId: String = UUID.randomUUID().toString()) = HttpHeaders().apply {
        set("X-FHC-keystoreId", keystoreId)
        set("X-FHC-tokenId", UUID.randomUUID().toString())
        set("X-FHC-passPhrase", "not-a-real-passphrase")
        accept = listOf(MediaType.APPLICATION_JSON)
    }

    private fun call(method: HttpMethod, path: String, headers: HttpHeaders, body: String? = null) =
        restTemplate!!.exchange(URI.create("http://localhost:$port$path"), method, HttpEntity(body, headers), String::class.java)

    private val messages = "/efact/12345678901/fr?ssin=11111111111&firstName=Jean&lastName=Kine&limit=10"

    @Test
    fun missingAuthHeadersAre400() {
        val response = call(HttpMethod.GET, messages, HttpHeaders().apply { accept = listOf(MediaType.APPLICATION_JSON) })
        assertThat(response.statusCode.value()).isEqualTo(400)
        assertThat(response.body).contains("X-FHC-keystoreId")
    }

    @Test
    fun aMissingQueryParameterIs400() {
        val response = call(HttpMethod.GET, "/efact/12345678901/fr?firstName=Jean&lastName=Kine&limit=10", headers())
        assertThat(response.statusCode.value()).isEqualTo(400)
        assertThat(response.body).contains("ssin")
    }

    @Test
    fun aMalformedUuidHeaderIs400() {
        assertThat(call(HttpMethod.GET, messages, headers(keystoreId = "not-a-uuid")).statusCode.value()).isEqualTo(400)
    }

    @Test
    fun anUnsupportedMethodIs405() {
        assertThat(call(HttpMethod.DELETE, "/efact/flatcore", headers()).statusCode.value()).isEqualTo(405)
    }

    @Test
    fun anUnknownPathIs404() {
        assertThat(call(HttpMethod.GET, "/no/such/route", headers()).statusCode.value()).isEqualTo(404)
    }

    @Test
    fun anUnsatisfiableAcceptIs406() {
        // /efact/flat produces text/plain only
        val response = call(HttpMethod.POST, "/efact/flat", headers().apply { contentType = MediaType.APPLICATION_JSON }, "{}")
        assertThat(response.statusCode.value()).isEqualTo(406)
    }

    @Test
    fun anUnreadableBodyIs400() {
        val response = call(HttpMethod.POST, "/efact/flatcore", headers().apply { contentType = MediaType.APPLICATION_JSON }, "{not json")
        assertThat(response.statusCode.value()).isEqualTo(400)
    }
}
