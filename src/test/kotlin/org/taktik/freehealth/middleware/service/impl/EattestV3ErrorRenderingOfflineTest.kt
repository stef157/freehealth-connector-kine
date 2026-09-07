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

package org.taktik.freehealth.middleware.service.impl

import be.fgov.ehealth.standards.kmehr.mycarenet.cd.v1.CDERRORMYCARENET
import be.fgov.ehealth.standards.kmehr.mycarenet.cd.v1.CDERRORMYCARENETschemes
import be.fgov.ehealth.standards.kmehr.mycarenet.dt.v1.TextType
import be.fgov.ehealth.standards.kmehr.mycarenet.schema.v1.ErrorMyCarenetType
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.junit4.SpringRunner
import org.taktik.freehealth.middleware.MyTestsConfiguration
import org.springframework.context.annotation.Import

/**
 * What an eAttest v3 acknowledgement's errors turn into, offline: no eHealth call, no certificate — the
 * acknowledgement is built by hand and rendered against a request built by hand.
 *
 * `ErrorMyCarenetType` codes its reason under one of the two — and only two — values of
 * `CDERRORMYCARENETschemes`: `CD-ERROR` for an error, `CD-REFUSAL-MYCARENET` for a refusal. Reading only the
 * first meant a refusal produced no `MycarenetError` at all, so the response came back `iscomplete = false`
 * with `errors = []`: a refusal with no motive, which is the whole diagnostic a caller gets on eAttest.
 *
 * The other half is the catalogue. `/be/errors/eAttestErrors.json` holds 158 (path, code) entries; a pair it
 * does not hold used to render as nothing, discarding the `description` the insurer itself sent.
 */
@RunWith(SpringRunner::class)
@Import(MyTestsConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EattestV3ErrorRenderingOfflineTest {
    @Autowired
    private val eattestV3Service: EattestV3ServiceImpl? = null

    private val service get() = eattestV3Service!!

    /**
     * A request the error urls can be resolved against. The catalogue's paths are unprefixed — `/SendTransactionRequest/…`
     * — so the fixture is unprefixed too, which is what makes the resolved branch reachable here.
     */
    private fun request(requestId: String = "00000000000.20260907120000") =
        """<SendTransactionRequest><request><id>$requestId</id><date>20260907</date></request></SendTransactionRequest>"""
            .toByteArray()

    private fun acknowledgeError(
        code: String,
        scheme: CDERRORMYCARENETschemes?,
        url: String?,
        description: String? = null
    ) = ErrorMyCarenetType().apply {
        cds.add(CDERRORMYCARENET().apply { this.s = scheme; this.value = code })
        this.url = url
        description?.let { this.description = TextType().apply { l = "fr"; value = it } }
    }

    /** The load-bearing half: a refusal must reach the caller at all. */
    @Test
    fun aRefusalIsNotDroppedOnTheFloor() {
        val errors = service.errorsOf(
            listOf(
                acknowledgeError(
                    code = "156",
                    scheme = CDERRORMYCARENETschemes.CD_REFUSAL_MYCARENET,
                    url = "/SendTransactionRequest/request/id",
                    description = "Le dispensateur n'est pas reconnu pour cette prestation"
                )
            ),
            request()
        )

        assertThat(errors)
            .describedAs("a CD-REFUSAL-MYCARENET reason used to yield an empty set, i.e. iscomplete=false with errors=[]")
            .isNotEmpty()
        assertThat(errors.map { it.code }).contains("156")
    }

    /**
     * A (path, code) pair the catalogue does not hold keeps the words the insurer sent, rather than rendering as
     * nothing. `value` still names the offending node of our own request.
     */
    @Test
    fun anErrorAbsentFromTheCatalogueKeepsTheInsurersOwnWords() {
        val errors = service.errorsOf(
            listOf(
                acknowledgeError(
                    code = "999",
                    scheme = CDERRORMYCARENETschemes.CD_ERROR,
                    url = "/SendTransactionRequest/request/id",
                    description = "Motif que le catalogue ne traduit pas"
                )
            ),
            request(requestId = "00000000000.20260907120000")
        )

        assertThat(errors).hasSize(1)
        val error = errors.single()
        assertThat(error.code).isEqualTo("999")
        assertThat(error.msgFr).isEqualTo("Motif que le catalogue ne traduit pas")
        assertThat(error.msgNl).isEqualTo("Motif que le catalogue ne traduit pas")
        assertThat(error.path).isEqualTo("/SendTransactionRequest/request/id")
        assertThat(error.value)
            .describedAs("the offending node of our own request, which the message never names")
            .isEqualTo("00000000000.20260907120000")
    }

    /** A `cd` whose scheme attribute is absent still carries a code, and it must not be lost either. */
    @Test
    fun aCodeWithNoSchemeStillSurfaces() {
        val errors = service.errorsOf(
            listOf(acknowledgeError(code = "111", scheme = null, url = "/SendTransactionRequest/request/id")),
            request()
        )

        assertThat(errors.map { it.code }).contains("111")
    }

    /** No error, or no acknowledged error list at all, is not an error. */
    @Test
    fun anAcknowledgementWithoutErrorsRendersNone() {
        assertThat(service.errorsOf(null, request())).isEmpty()
        assertThat(service.errorsOf(listOf(), request())).isEmpty()
    }
}
