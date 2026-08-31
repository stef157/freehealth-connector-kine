/*
 *
 * Copyright (C) 2018 iCure SA
 *
 * This file is part of FreeHealthConnector.
 *
 * FreeHealthConnector is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation.
 *
 * FreeHealthConnector is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with FreeHealthConnector.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.taktik.freehealth.middleware.web.controllers

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.junit4.SpringRunner
import org.taktik.freehealth.middleware.MyTestsConfiguration
import org.taktik.freehealth.middleware.service.EattestV3Service
import kotlin.reflect.full.declaredFunctions

/**
 * The eattestv3 surface must be able to replay an InputReference — offline, read from the OpenAPI descriptor and
 * from the controller's own signature, with no eHealth call.
 *
 * Normative source: `FR-MPTI-EAT3-PPS Manuel procedure de test eAttestV3 Duplicata V1.0`, § 4.1.2, which states the
 * two halves of a duplicate as opposable requirements: *"InputReference SC2 = InputReference SC1"* and
 * *"Kmehr-ID SC2 = Kmehr-ID SC 1"*, together with `attemptNbr = 2` and *"Vous ne pouvez apporter aucune
 * modification au message Kmehr"*. The `CIN Messages definition eattest v3.xlsx` legend says the same in one line:
 * *"Duplicate Error — define the acceptable period for a duplicate request using the same inputref with an
 * increased attempt number value."*
 *
 * `attemptNbr` was already wired. The reference was not: `InputReference()` with no argument delegates to
 * `KmehrIdGenerator`, which returns the clock to the second, so two calls one second apart carry two references and
 * a resend can never be recognised as a duplicate — it is treated as one more attestation.
 *
 * The other half, the Kmehr-ID, is `<nihii>.<refDateTime:yyyyMMddHHmmss>`, so on the send routes it follows the
 * `date` query parameter. A duplicate therefore replays **both** parameters: eHealth checks their coherence and
 * answers *"La valeur de l'identification de la requete est incoherente avec celle du Web Service"* otherwise.
 */
@RunWith(SpringRunner::class)
@Import(MyTestsConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EattestV3InputReferenceOfflineTest {
    @LocalServerPort
    private val port: Int = 0

    @Autowired
    private val restTemplate: TestRestTemplate? = null

    private fun descriptor() =
        ObjectMapper().readTree(restTemplate!!.getForObject("http://localhost:$port/v3/api-docs", String::class.java))

    private fun parametersOf(path: String, verb: String) =
        descriptor().get("paths").get(path).get(verb).get("parameters").toList()
            .associateBy { it.get("name").asText() }

    private val routes = listOf(
        "/eattestv3/send/{patientSsin}" to "post",
        "/eattestv3/send/{patientSsin}/verbose" to "post",
        "/eattestv3/send/{patientSsin}" to "delete",
        "/eattestv3/send/{patientSsin}/verbose" to "delete"
    )

    /** All four routes — sending and cancelling, verbose and not — can carry the reference. */
    @Test
    fun everyEattestV3RouteCanCarryAnInputReference() {
        routes.forEach { (path, verb) ->
            val parameters = parametersOf(path, verb)
            assertThat(parameters.keys).describedAs("%s %s", verb, path).contains("inputReference")
            val inputReference = parameters.getValue("inputReference")
            assertThat(inputReference.get("in").asText()).isEqualTo("query")
            assertThat(inputReference.get("required").asBoolean())
                .describedAs("%s %s: the nominal path must not change", verb, path).isFalse()
            assertThat(inputReference.get("description").asText()).isNotEmpty()
        }
    }

    /**
     * The cancellation carries a `date`, and it must reach the message: the Kmehr-ID of a cancellation is built
     * from it, so a duplicate cancellation needs it just as a duplicate send does. The parameter was declared and
     * dropped, and its type resolved to the day rather than to the second.
     */
    @Test
    fun theCancellationRoutesCarryTheirDateToTheMessage() {
        listOf("/eattestv3/send/{patientSsin}", "/eattestv3/send/{patientSsin}/verbose").forEach { path ->
            assertThat(parametersOf(path, "delete").keys).describedAs(path).contains("date", "attemptNbr")
        }
        // referenceDate is the parameter the Kmehr-ID is built from. On the send side it is a 14 digit Long,
        // yyyyMMddHHmmss; the cancellation took an Int, so dateTime(Int) resolved it to the day and two
        // cancellations by the same provider on the same day would have shared their Kmehr-ID, which the manual
        // forbids ("numero unique en 14 positions").
        val cancel = EattestV3Service::class.declaredFunctions.single { it.name == "cancelAttest" }
        val referenceDate = cancel.parameters.single { it.name == "referenceDate" }
        assertThat(referenceDate.type.classifier)
            .describedAs("cancelAttest must take referenceDate to the second, like the send side")
            .isEqualTo(Long::class)
        assertThat(cancel.parameters.map { it.name }).contains("inputReference")
    }
}
