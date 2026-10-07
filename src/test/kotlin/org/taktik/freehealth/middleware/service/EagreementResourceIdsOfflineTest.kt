package org.taktik.freehealth.middleware.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.taktik.freehealth.middleware.service.impl.EagreementServiceUtilsImpl

/**
 * The resource ids of an eAgreement Bundle follow their entry's fullUrl, as in the official examples.
 * OA 100 rejected every claim-ask whose ids were `Claim1`, `Patient1`… with
 * MISSING_MESSAGEHEADER_DESTINATION_NAME (measured on acceptance, 2026-10-07).
 */
class EagreementResourceIdsOfflineTest {
    private val utils = EagreementServiceUtilsImpl()
    private val mapper = ObjectMapper()

    private fun bundle() = mapper.readTree(
        """{"Bundle":{"id":"Bundle1","entry":[
            {"fullUrl":"urn:uuid:11111111-1111-1111-1111-111111111111","resource":{"MessageHeader":{"id":"abc","focus":[{"reference":"urn:uuid:22222222-2222-2222-2222-222222222222"}]}}},
            {"fullUrl":"urn:uuid:22222222-2222-2222-2222-222222222222","resource":{"Claim":{"id":"Claim1"}}},
            {"fullUrl":"urn:uuid:33333333-3333-3333-3333-333333333333","resource":{"ServiceRequest":{"id":"ServiceRequest1","contained":[{"Binary":{"id":"annexSR1"}}]}}}
        ]}}"""
    ) as ObjectNode

    @Test
    fun everyResourceIdIsTheUuidOfItsFullUrl() {
        val bundle = bundle()
        utils.alignResourceIdsOnFullUrl(bundle, "99999999-9999-9999-9999-999999999999")

        val entries = bundle.get("Bundle").get("entry").toList()
        assertThat(entries).isNotEmpty
        entries.forEach { entry ->
            val id = entry.get("resource").elements().next().get("id").asText()
            assertThat("urn:uuid:$id").isEqualTo(entry.get("fullUrl").asText())
        }
        assertThat(bundle.get("Bundle").get("id").asText()).isEqualTo("99999999-9999-9999-9999-999999999999")
    }

    @Test
    fun aContainedResourceKeepsItsLocalId() {
        val bundle = bundle()
        utils.alignResourceIdsOnFullUrl(bundle, "99999999-9999-9999-9999-999999999999")

        val contained = bundle.get("Bundle").get("entry").get(2).get("resource").get("ServiceRequest").get("contained").get(0)
        assertThat(contained.get("Binary").get("id").asText()).isEqualTo("annexSR1")
    }

    @Test
    fun referencesAlreadyResolvedAreLeftAlone() {
        val bundle = bundle()
        utils.alignResourceIdsOnFullUrl(bundle, "99999999-9999-9999-9999-999999999999")

        val focus = bundle.get("Bundle").get("entry").get(0).get("resource").get("MessageHeader").get("focus").get(0)
        assertThat(focus.get("reference").asText()).isEqualTo("urn:uuid:22222222-2222-2222-2222-222222222222")
    }
}
