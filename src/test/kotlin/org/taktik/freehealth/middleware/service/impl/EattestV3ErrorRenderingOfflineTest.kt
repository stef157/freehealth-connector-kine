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

import be.fgov.ehealth.messageservices.mycarenet.core.v1.RequestType
import be.fgov.ehealth.messageservices.mycarenet.core.v1.SendTransactionRequest
import be.fgov.ehealth.standards.kmehr.mycarenet.cd.v1.CDERRORMYCARENET
import be.fgov.ehealth.standards.kmehr.mycarenet.cd.v1.CDERRORMYCARENETschemes
import be.fgov.ehealth.standards.kmehr.mycarenet.cd.v1.CDTRANSACTION
import be.fgov.ehealth.standards.kmehr.mycarenet.cd.v1.CDTRANSACTIONschemes
import be.fgov.ehealth.standards.kmehr.mycarenet.dt.v1.TextType
import be.fgov.ehealth.standards.kmehr.mycarenet.id.v1.IDHCPARTY
import be.fgov.ehealth.standards.kmehr.mycarenet.id.v1.IDHCPARTYschemes
import be.fgov.ehealth.standards.kmehr.mycarenet.id.v1.IDKMEHR
import be.fgov.ehealth.standards.kmehr.mycarenet.id.v1.IDKMEHRschemes
import be.fgov.ehealth.standards.kmehr.mycarenet.schema.v1.AuthorType
import be.fgov.ehealth.standards.kmehr.mycarenet.schema.v1.ErrorMyCarenetType
import be.fgov.ehealth.standards.kmehr.mycarenet.schema.v1.FolderType
import be.fgov.ehealth.standards.kmehr.mycarenet.schema.v1.HcpartyType
import be.fgov.ehealth.standards.kmehr.mycarenet.schema.v1.Kmehrmessage
import be.fgov.ehealth.standards.kmehr.mycarenet.schema.v1.TransactionType
import org.taktik.connector.business.recipeprojects.core.utils.MarshallerHelper
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

    /**
     * The catalogue is a set of templates, not a scratchpad.
     *
     * `eAttestErrors` is read once into the singleton `@Service`, so rendering an error by writing `value` on the
     * catalogue entry itself published one caller's offending node — here the request id, elsewhere an SSIN — into
     * the entry every other caller reads. Two renderings of the same (path, code) pair must not see each other.
     */
    @Test
    fun renderingAnErrorDoesNotWriteIntoTheSharedCatalogue() {
        fun renderOnce(requestId: String) = service.errorsOf(
            listOf(
                acknowledgeError(
                    code = "111",
                    scheme = CDERRORMYCARENETschemes.CD_ERROR,
                    url = "/SendTransactionRequest/request/id"
                )
            ),
            request(requestId = requestId)
        ).single()

        val first = renderOnce("11111111111.20260907120000")
        assertThat(first.msgFr)
            .describedAs("uid 1 of the catalogue, i.e. the matched branch and not the fallback")
            .isEqualTo("Le format de l'identification de la requête n'est pas complété ou est erroné")
        assertThat(first.value).isEqualTo("11111111111.20260907120000")

        val second = renderOnce("22222222222.20260907130000")

        assertThat(second.value).isEqualTo("22222222222.20260907130000")
        assertThat(first.value)
            .describedAs("the first caller's node, after a second caller rendered the same catalogue entry")
            .isEqualTo("11111111111.20260907120000")
        assertThat(first).isNotSameAs(second)
    }

    /**
     * The 27 catalogue entries carrying a `regex` are the "missing element" errors — the commonest kind. They
     * are reachable only if the parent resolves: the entry's `path` stops on a `transaction[…]` or an
     * `item[…]` and the `regex` carries the absent child's name, which is why the truncation lines up.
     *
     * Here uid 52 / code 100, `regex = not.+patientpaid`: the amount charged to the patient is missing from
     * the CGA transaction. And `value` must stay empty — the parent's `textContent` would be everything the
     * transaction holds, which is neither the offending value nor ours to hand back.
     */
    @Test
    fun aMissingElementResolvesItsParentAndCarriesNoValue() {
        val request = SendTransactionRequest().apply {
            kmehrmessage = Kmehrmessage().apply {
                folders.add(FolderType().apply {
                    transactions.add(TransactionType().apply {
                        cds.add(CDTRANSACTION().apply {
                            s = CDTRANSACTIONschemes.CD_TRANSACTION_MYCARENET
                            sv = "1.0"
                            value = "cga"
                        })
                        author = AuthorType().apply {
                            hcparties.add(HcpartyType().apply {
                                ids.add(IDHCPARTY().apply {
                                    s = IDHCPARTYschemes.ID_HCPARTY
                                    sv = "1.0"
                                    value = "00000000000"
                                })
                            })
                        }
                    })
                })
            }
        }
        val marshalled = MarshallerHelper(SendTransactionRequest::class.java, SendTransactionRequest::class.java)
            .toXMLByteArray(request)

        val error = service.errorsOf(
            listOf(
                acknowledgeError(
                    code = "100",
                    scheme = CDERRORMYCARENETschemes.CD_ERROR,
                    url = "/SendTransactionRequest/kmehrmessage/folder/transaction/not(*:patientpaid)"
                )
            ),
            marshalled
        ).single()

        assertThat(error.uid).describedAs("the regex entry of the catalogue was reached").isEqualTo("52")
        assertThat(error.msgFr).isEqualTo("Le  montant porté en compte au patient est manquant")
        assertThat(error.path).isEqualTo("/SendTransactionRequest/kmehrmessage/folder/transaction[cga]")
        assertThat(error.value)
            .describedAs("the parent's whole text content is not the offending value")
            .isNull()
    }

    /** No error, or no acknowledged error list at all, is not an error. */
    @Test
    fun anAcknowledgementWithoutErrorsRendersNone() {
        assertThat(service.errorsOf(null, request())).isEmpty()
        assertThat(service.errorsOf(listOf(), request())).isEmpty()
    }
    /**
     * The catalogue is consulted on the request the call sites really send.
     *
     * The inverse of what `5cb8800f3` pinned. `MarshallerHelper(SendTransactionRequest…)` marshals the
     * request qualified — root `ns5:SendTransactionRequest` under `messageservices/protocol/v1`, its children
     * under the default `messageservices/core/v1` — while the catalogue's paths are unprefixed, so the
     * unprefixed name tests matched nothing and every error fell to "xpath invalide", losing `uid`, `path`,
     * `value` and the catalogue's 158 messages. Resolution now goes through `namespaceAgnostic`.
     */
    @Test
    fun theCatalogueIsConsultedOnARealMarshalledRequest() {
        val request = SendTransactionRequest().apply {
            this.request = RequestType().apply {
                id = IDKMEHR().apply {
                    s = IDKMEHRschemes.ID_KMEHR
                    sv = "1.0"
                    value = "00000000000.20260907120000"
                }
            }
        }
        val marshalled = MarshallerHelper(SendTransactionRequest::class.java, SendTransactionRequest::class.java)
            .toXMLByteArray(request)

        assertThat(String(marshalled))
            .describedAs("the request the call sites send is namespace-qualified")
            .contains("""xmlns="http://www.ehealth.fgov.be/messageservices/core/v1"""")

        // uid 1 of the catalogue: path /SendTransactionRequest/request/id, code 111 — the pair this holds.
        val error = service.errorsOf(
            listOf(
                acknowledgeError(
                    code = "111",
                    scheme = CDERRORMYCARENETschemes.CD_ERROR,
                    url = "/SendTransactionRequest/request/id"
                )
            ),
            marshalled
        ).single()

        assertThat(error.uid).describedAs("the catalogue entry was reached").isEqualTo("1")
        assertThat(error.code).isEqualTo("111")
        assertThat(error.msgFr)
            .isEqualTo("Le format de l'identification de la requête n'est pas complété ou est erroné")
        assertThat(error.locFr).isEqualTo("Id de la requête")
        assertThat(error.path).isEqualTo("/SendTransactionRequest/request/id")
        assertThat(error.value)
            .describedAs("the offending node of our own request")
            .isEqualTo("00000000000.20260907120000")
    }

    /**
     * A node deep inside the kmehr message, where the predicates `nodeDescr` rebuilds have to line up with
     * the catalogue's own — half the value of the fix, and never exercised until now.
     *
     * The entry is uid 51, code 156,
     * `/SendTransactionRequest/kmehrmessage/folder/transaction[cga]/author/hcparty/id`: the author of the CGA
     * transaction disagreeing with the author of the request. That is the very error CLAUDE.md's smoke of
     * 31/08 reports on the author NIHII.
     */
    @Test
    fun aDeepKmehrNodeMatchesItsCataloguePredicates() {
        val request = SendTransactionRequest().apply {
            kmehrmessage = Kmehrmessage().apply {
                folders.add(FolderType().apply {
                    transactions.add(TransactionType().apply {
                        cds.add(CDTRANSACTION().apply {
                            s = CDTRANSACTIONschemes.CD_TRANSACTION_MYCARENET
                            sv = "1.0"
                            value = "cga"
                        })
                        author = AuthorType().apply {
                            hcparties.add(HcpartyType().apply {
                                ids.add(IDHCPARTY().apply {
                                    s = IDHCPARTYschemes.ID_HCPARTY
                                    sv = "1.0"
                                    value = "00000000000"
                                })
                            })
                        }
                    })
                })
            }
        }
        val marshalled = MarshallerHelper(SendTransactionRequest::class.java, SendTransactionRequest::class.java)
            .toXMLByteArray(request)

        val error = service.errorsOf(
            listOf(
                acknowledgeError(
                    code = "156",
                    scheme = CDERRORMYCARENETschemes.CD_ERROR,
                    url = "/SendTransactionRequest/kmehrmessage/folder/transaction/author/hcparty/id"
                )
            ),
            marshalled
        ).single()

        assertThat(error.uid)
            .describedAs("nodeDescr must rebuild transaction[cga] to meet the catalogue")
            .isEqualTo("51")
        assertThat(error.path)
            .isEqualTo("/SendTransactionRequest/kmehrmessage/folder/transaction[cga]/author/hcparty/id")
        assertThat(error.value).isEqualTo("00000000000")
        assertThat(error.msgFr).contains("incohérente avec celle de l'auteur de la requête")
    }
}
