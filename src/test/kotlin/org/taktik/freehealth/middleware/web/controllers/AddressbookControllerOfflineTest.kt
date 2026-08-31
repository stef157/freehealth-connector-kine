package org.taktik.freehealth.middleware.web.controllers

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
import org.springframework.test.context.junit4.SpringRunner
import org.taktik.freehealth.middleware.MyTestsConfiguration
import java.net.URI
import java.util.UUID

/**
 * Offline checks on /ab/search/hcp: criteria validation happens before any eHealth call, so no keystore, no token
 * and no network are needed. Also asserts that the endpoint is published in the OpenAPI descriptor.
 */
@RunWith(SpringRunner::class)
@Import(MyTestsConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AddressbookControllerOfflineTest {
    @LocalServerPort
    private val port: Int = 0

    @Autowired
    private val restTemplate: TestRestTemplate? = null

    private fun fakeAuthHeaders() = HttpHeaders().apply {
        set("X-FHC-keystoreId", UUID.randomUUID().toString())
        set("X-FHC-tokenId", UUID.randomUUID().toString())
        set("X-FHC-passPhrase", "not-a-real-passphrase")
    }

    // URI overload: the String overload treats the url as a template and would re-encode a literal % sequence
    private fun get(url: String) =
        restTemplate!!.exchange(URI.create(url), HttpMethod.GET, HttpEntity<Void>(fakeAuthHeaders()), String::class.java)

    @Test
    fun searchHcpIsExposedInSwagger() {
        // springdoc serves OpenAPI 3 at /v3/api-docs, where SpringFox served Swagger 2 at /v2/api-docs
        val apiDocs = restTemplate!!.getForObject("http://localhost:$port/v3/api-docs", String::class.java)
        val operation = ObjectMapper().readTree(apiDocs)
            .get("paths").get("/ab/search/hcp").get("get")
        Assertions.assertThat(operation.get("summary").asText()).isNotEmpty()
        Assertions.assertThat(operation.get("description").asText()).contains("mutually exclusive")

        val parameters = operation.get("parameters").toList()
        Assertions.assertThat(parameters.map { it.get("name").asText() }).containsExactlyInAnyOrder(
            "X-FHC-keystoreId", "X-FHC-tokenId", "X-FHC-passPhrase",
            "lastName", "firstName", "profession", "nihii", "ssin", "zipCode", "city", "email", "offset", "limit"
        )
        Assertions.assertThat(parameters).allMatch { it.get("description").asText().isNotEmpty() }
        // @Parameter also defaults required to false, so the auth headers must inherit it from @RequestHeader
        Assertions.assertThat(parameters.filter { it.get("in").asText() == "header" })
            .allMatch { it.get("required").asBoolean() }
        Assertions.assertThat(parameters.filter { it.get("in").asText() == "query" })
            .allMatch { !it.get("required").asBoolean() }
    }

    @Test
    fun zipCodeAndCityAreMutuallyExclusive() {
        val response = get("http://localhost:$port/ab/search/hcp?lastName=Steeman&zipCode=1000&city=Bruxelles")
        Assertions.assertThat(response.statusCode.value()).isEqualTo(400)
        Assertions.assertThat(response.body).contains("mutually exclusive")
    }

    @Test
    fun nihiiAndSsinAreMutuallyExclusive() {
        val response = get("http://localhost:$port/ab/search/hcp?nihii=10032669001&ssin=74010414733")
        Assertions.assertThat(response.statusCode.value()).isEqualTo(400)
        Assertions.assertThat(response.body).contains("mutually exclusive")
    }

    @Test
    fun theEmptyQueryIsRejected() {
        val response = get("http://localhost:$port/ab/search/hcp")
        Assertions.assertThat(response.statusCode.value()).isEqualTo(400)
        Assertions.assertThat(response.body).contains("At least one search criterion")
    }

    @Test
    fun blankCriteriaCountAsAbsent() {
        val response = get("http://localhost:$port/ab/search/hcp?lastName=%20%20")
        Assertions.assertThat(response.statusCode.value()).isEqualTo(400)
        Assertions.assertThat(response.body).contains("At least one search criterion")
    }
}
