package org.taktik.freehealth.middleware.web.controllers

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.junit4.SpringRunner
import org.taktik.freehealth.middleware.MyTestsConfiguration
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/**
 * A claim-argue may travel without a prescription (57bfa3c45): free text or another annex answers a request for
 * information as well. The builder took it, but /argueAgreement still required prescriberNihii,
 * prescriberFirstName, prescriberLastName and prescriptionDate, so such an argue was a 400 before reaching it.
 *
 * Offline: with an unknown token the call stops on MissingTokenException (401) — after binding, before anything is
 * built or sent. A 401 therefore proves the request was bound; a 400 naming a parameter proves it was not.
 *
 * The JDK client, not TestRestTemplate: Jetty's client rejects a 401 that carries no WWW-Authenticate header
 * ("HTTP protocol violation"), which is what EagreementController answers.
 */
@RunWith(SpringRunner::class)
@Import(MyTestsConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EagreementArgueRouteOfflineTest {
    @LocalServerPort
    private val port: Int = 0

    private val client = HttpClient.newHttpClient()

    private val withoutPrescription = linkedMapOf(
        "hcpQuality" to "physiotherapist",
        "hcpNihii" to "12345678901",
        "hcpName" to "Kine Test",
        "hcpSsin" to "11111111111",
        "hcpFirstName" to "Kine",
        "hcpLastName" to "Test",
        "patientFirstName" to "Patient",
        "patientLastName" to "Test",
        "patientGender" to "male",
        "patientSsin" to "22222222222",
        "insuranceRef" to "12345678901234567890",
        "agreementType" to "physiotherapy-fa"
    )

    private val freeText = """[{"type":"info","data":"Argument du kine"}]"""

    private fun argue(params: Map<String, String>, body: String): HttpResponse<String> {
        val query = params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8)}" }
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/eagreement/argueAgreement?$query"))
            .header("X-FHC-keystoreId", UUID.randomUUID().toString())
            .header("X-FHC-tokenId", UUID.randomUUID().toString())
            .header("X-FHC-passPhrase", "not-a-real-passphrase")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun anArgueWithFreeTextAndNoPrescriptionIsBound() {
        val response = argue(withoutPrescription, freeText)

        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(401)
    }

    /** The contrast: a parameter the route still requires is refused by the binding, by name. */
    @Test
    fun anArgueWithoutInsuranceRefIsStillA400NamingIt() {
        val response = argue(withoutPrescription - "insuranceRef", freeText)

        assertThat(response.statusCode()).isEqualTo(400)
        assertThat(response.body()).contains("insuranceRef")
    }

    /** What the published contract says: the four prescription parameters are optional on argue, and only there. */
    @Test
    fun theContractPublishesThePrescriptionParametersAsOptionalOnArgueOnly() {
        val apiDocs = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/v3/api-docs")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        ).body()
        val paths = ObjectMapper().readTree(apiDocs).get("paths")
        fun required(path: String) = paths.get(path).get("post").get("parameters")
            .filter { it.get("in").asText() == "query" }
            .associate { it.get("name").asText() to it.get("required").asBoolean() }

        val argue = required("/eagreement/argueAgreement")
        for (name in listOf("prescriberNihii", "prescriberFirstName", "prescriberLastName", "prescriptionDate")) {
            assertThat(argue[name]).describedAs(name).isFalse
        }
        assertThat(argue["insuranceRef"]).isTrue

        val extend = required("/eagreement/extendAgreement")
        assertThat(extend.filterKeys { it.startsWith("prescri") }.values).isNotEmpty.allMatch { it }

        println("argueAgreement query parameters (name=required): $argue")
    }
}
