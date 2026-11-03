package org.taktik.freehealth.middleware.web.controllers

import be.cin.nip.async.generic.Confirm
import be.cin.mycarenet.esb.common.v2.OrigineType
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions
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
import org.taktik.connector.business.genericasync.builders.impl.RequestObjectBuilderImpl
import org.taktik.freehealth.middleware.MyTestsConfiguration
import java.net.URI
import java.util.UUID

/**
 * PUT /efact/confirm/msgs/refs/{nihii} — messages confirmed by Reference (MsgRefValues, genericAsync v1.2), next to
 * the hash route that upstream PR #129 switched in place and that this fork keeps on hashes. Offline: no keystore,
 * no token, no network.
 */
@RunWith(SpringRunner::class)
@Import(MyTestsConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EfactConfirmMessagesByReferenceOfflineTest {
    @LocalServerPort
    private val port: Int = 0

    @Autowired
    private val restTemplate: TestRestTemplate? = null

    private fun put(path: String, body: List<String>) =
        restTemplate!!.exchange(
            URI.create("http://localhost:$port$path?ssin=11111111111&firstName=Test&lastName=Test"),
            HttpMethod.PUT,
            HttpEntity(body, HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                set("X-FHC-keystoreId", UUID.randomUUID().toString())
                set("X-FHC-tokenId", UUID.randomUUID().toString())
                set("X-FHC-passPhrase", "not-a-real-passphrase")
            }),
            String::class.java
        )

    @Test
    fun theRouteIsPublishedNextToTheHashRoute() {
        val paths = ObjectMapper().readTree(
            restTemplate!!.getForObject("http://localhost:$port/v3/api-docs", String::class.java)
        ).get("paths")
        Assertions.assertThat(paths.get("/efact/confirm/msgs/refs/{nihii}")?.get("put")).isNotNull
        Assertions.assertThat(paths.get("/efact/confirm/msgs/{nihii}")?.get("put")).isNotNull
    }

    @Test
    fun anEmptyListConfirmsNothingAndNeedsNoToken() {
        val response = put("/efact/confirm/msgs/refs/12345678901", listOf())
        Assertions.assertThat(response.statusCode.value()).isEqualTo(200)
        Assertions.assertThat(response.body).isEqualTo("true")
    }

    @Test
    fun aReferenceWithoutAValidSessionIsA401() {
        val response = put("/efact/confirm/msgs/refs/12345678901", listOf("173080583"))
        Assertions.assertThat(response.statusCode.value()).isEqualTo(401)
    }

    @Test
    fun theBuilderPutsMessageReferencesInMsgRefValuesAndNothingElse() {
        // The argument order the service relies on: messages first, tACKs second.
        val confirm: Confirm = RequestObjectBuilderImpl().buildConfirmRequestWithReference(
            OrigineType(), listOf("173080583"), listOf()
        )
        Assertions.assertThat(confirm.msgRefValues).containsExactly("173080583")
        Assertions.assertThat(confirm.tAckReferences).isEmpty()
        Assertions.assertThat(confirm.msgHashValues).isEmpty()
        Assertions.assertThat(confirm.tAckContents).isEmpty()
    }
}
