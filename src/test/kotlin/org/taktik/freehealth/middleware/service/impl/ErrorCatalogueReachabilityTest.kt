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

import be.fgov.ehealth.medicalagreement.core.v1.Kmehrrequest
import be.fgov.ehealth.messageservices.core.v1.RetrieveTransactionRequest
import be.fgov.ehealth.standards.kmehr.schema.v1.AuthorType
import be.fgov.ehealth.standards.kmehr.schema.v1.FolderType
import be.fgov.ehealth.standards.kmehr.schema.v1.HcpartyType
import be.fgov.ehealth.standards.kmehr.schema.v1.Kmehrmessage
import be.fgov.ehealth.standards.kmehr.schema.v1.TransactionType
import be.fgov.ehealth.standards.kmehr.cd.v1.CDHCPARTY
import be.fgov.ehealth.standards.kmehr.cd.v1.CDHCPARTYschemes
import be.fgov.ehealth.standards.kmehr.id.v1.IDHCPARTY
import be.fgov.ehealth.standards.kmehr.id.v1.IDHCPARTYschemes
import be.fgov.ehealth.standards.kmehr.id.v1.IDKMEHR
import be.fgov.ehealth.standards.kmehr.id.v1.IDKMEHRschemes
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.xml.bind.JAXBContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.mockito.Mockito.mock
import org.taktik.connector.technical.utils.MarshallerHelper
import org.taktik.connector.technical.service.keydepot.KeyDepotService
import org.taktik.freehealth.middleware.dto.mycarenet.MycarenetError
import org.taktik.freehealth.middleware.service.STSService
import java.io.ByteArrayOutputStream

/**
 * That a corrected catalogue path is reachable — measured end to end on the service's own `extractError`,
 * against a request marshalled by the very helper the service uses. No Spring, no certificate, no network.
 *
 * These catalogues were transcribed from the CIN error tables, and several of their paths contradicted the
 * XSD of the message they describe. A path that contradicts the XSD can never equal the path `nodeDescr`
 * rebuilds from the real document, so the entry was unreachable however well the XPath resolved.
 */
class ErrorCatalogueReachabilityTest {

    /**
     * `ConsultTarifErrors.json` wrote `/RetrieveTransactionRequest/Request/id` with a capital R, while
     * `messageservices_core-1_1.xsd:115-120` declares `<xsd:element name="request" …/>`. uid 2, code 111.
     */
    @Test
    fun tarificationReachesTheRequestIdEntry() {
        val request = RetrieveTransactionRequest().apply {
            request = be.fgov.ehealth.messageservices.core.v1.RequestType().apply {
                id = IDKMEHR().apply {
                    s = IDKMEHRschemes.ID_KMEHR
                    sv = "1.0"
                    value = "00000000000.20260907120000"
                }
            }
        }
        val marshalled = MarshallerHelper(RetrieveTransactionRequest::class.java, RetrieveTransactionRequest::class.java)
            .toXMLByteArray(request)

        val errors = TarificationServiceImpl(mock(STSService::class.java))
            .extractError(marshalled, "111", "/RetrieveTransactionRequest/request/id")

        assertThat(errors).hasSize(1)
        assertThat(errors.single().uid).isEqualTo("2")
        assertThat(errors.single().msgFr)
            .isEqualTo("Le format de l'identification de la requête n'est pas complété ou est erroné")
        assertThat(errors.single().value).isEqualTo("00000000000.20260907120000")
    }

    /**
     * `Chapter4ConsultationErrors.json` wrote `…/transaction/Author` with a capital A, while the kmehr schema
     * declares `author` in lower case in all seven versions shipped here. uid 6, code 111.
     *
     * This also pins the **narrowing**. Chapter4 filters on `(base == null || it.path == base)`, so while the
     * location did not resolve, `base` stayed null, the path filter was bypassed, and all **six** entries
     * carrying code 111 came back. One error, six messages, none of them tied to the offending node. Exactly
     * one now comes back.
     */
    @Test
    fun chapter4ReachesTheTransactionAuthorEntryAndOnlyThatOne() {
        val chapter4 = Chapter4ServiceImpl(
            mock(STSService::class.java),
            mock(KgssServiceImpl::class.java),
            mock(KeyDepotService::class.java)
        )
        val request = Kmehrrequest().apply {
            kmehrmessage = Kmehrmessage().apply {
                folders.add(FolderType().apply {
                    transactions.add(TransactionType().apply {
                        author = AuthorType().apply {
                            hcparties.add(HcpartyType().apply {
                                cds.add(CDHCPARTY().apply {
                                    s = CDHCPARTYschemes.CD_HCPARTY
                                    sv = "1.0"
                                    value = "persphysician"
                                })
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
        val marshalled = ByteArrayOutputStream().apply {
            JAXBContext.newInstance(Kmehrrequest::class.java).createMarshaller().marshal(request, this)
        }.toByteArray()

        assertThat(chapter4.chapter4ConsultationErrors.values.count { it.code == "111" })
            .describedAs("code 111 is carried by several entries — that is what made the dragnet visible")
            .isEqualTo(6)

        val errors = chapter4.extractError(
            marshalled,
            "111",
            chapter4.chapter4ConsultationErrors,
            "/kmehrrequest/kmehrmessage/folder/transaction/author"
        )

        assertThat(errors).hasSize(1)
        assertThat(errors.single().uid).isEqualTo("6")
        assertThat(errors.single().path).isEqualTo("/kmehrrequest/kmehrmessage/folder/transaction/author")
    }

    /**
     * An entry that names no node must stay reachable once the location resolves.
     *
     * A `path` of null is how the catalogues carry an error that designates nothing in the request: the
     * `SOA-*` transport faults of the eHealth ESB, which CLAUDE.md records meeting on real calls
     * (`SOA-01002` on eAgreement, `SOA-02001`, `SOA-03004`). Six of the ten copies filtered on
     * `it.path == base` with no null tolerance, so the moment `base` resolved those thirteen entries became
     * unreachable and the fault fell through to a bare code. GenIns and MemberData already wrote
     * `it.path == null || it.path == base`; that is the form, and it is now everywhere.
     */
    @Test
    fun tarificationReachesATransportFaultThatNamesNoNode() {
        val marshalled = MarshallerHelper(RetrieveTransactionRequest::class.java, RetrieveTransactionRequest::class.java)
            .toXMLByteArray(RetrieveTransactionRequest().apply {
                request = be.fgov.ehealth.messageservices.core.v1.RequestType().apply {
                    id = IDKMEHR().apply {
                        s = IDKMEHRschemes.ID_KMEHR
                        sv = "1.0"
                        value = "00000000000.20260907120000"
                    }
                }
            })

        val errors = TarificationServiceImpl(mock(STSService::class.java))
            .extractError(marshalled, "SOA-01002", "/RetrieveTransactionRequest/request/id")

        assertThat(errors).hasSize(1)
        assertThat(errors.single().uid).isEqualTo("64")
        assertThat(errors.single().path).describedAs("the entry names no node, and keeps naming none").isNull()
    }

    /**
     * What accepting a null path costs elsewhere — measured over the catalogues, not asserted.
     *
     * `(it.path == null || it.path == base)` can only widen a filter, so the question is whether it widens
     * it onto a *wrong* entry. It cannot: no catalogue carries the same `code` on both a null-path entry and
     * a path-bearing one, except `MemberDataErrors` — which already wrote the clause, so nothing there
     * changes either. Every other domain keeps exactly the behaviour it had.
     */
    @Test
    fun acceptingANullPathCannotWidenOntoAWrongEntry() {
        val mapper = ObjectMapper()
        fun catalogue(name: String) = mapper.readValue<Array<MycarenetError>>(
            javaClass.getResourceAsStream("/be/errors/$name.json")!!
        )

        // EfactErrors is read by no extractError at all; every other catalogue of the ten services.
        val consulted = listOf(
            "eAttestErrors", "mhmSubscriptionError", "ConsultTarifErrors",
            "ConsultTarificationMediprimaErrors", "Chapter4AgreementErrors", "Chapter4ConsultationErrors",
            "Chapter4ConsultationWarnings", "DmgConsultationErrors", "DmgNotificationErrors",
            "DmgRegistrationErrors", "DmgListsConsultationErrors", "GenInsErrors", "MemberDataErrors"
        )

        val colliding = consulted.associateWith { name ->
            val entries = catalogue(name)
            val withoutPath = entries.filter { it.path == null }.mapNotNull { it.code }.toSet()
            val withPath = entries.filter { it.path != null }.mapNotNull { it.code }.toSet()
            withoutPath intersect withPath
        }.filterValues { it.isNotEmpty() }

        assertThat(colliding.keys)
            .describedAs("a shared code is the only way the clause could return an entry that does not apply")
            .containsExactly("MemberDataErrors")

        // And where there is no null-path entry at all, the clause is inert by construction.
        assertThat(listOf("eAttestErrors", "mhmSubscriptionError", "Chapter4ConsultationErrors",
                          "Chapter4AgreementErrors", "DmgConsultationErrors", "DmgNotificationErrors")
                       .filter { name -> catalogue(name).any { it.path == null } })
            .isEmpty()
    }

    /**
     * The catalogue is a set of templates, not a scratchpad — measured on a ported domain.
     *
     * `ConsultTarifErrors` is read once into the singleton `@Service`, and `extractError` used to render an
     * error by writing `value` onto the catalogue entry itself, then return that very entry. While nothing
     * resolved, `elements` was always empty and the write never happened; making the location resolve turned
     * it on. Two renderings of the same entry must not see each other.
     */
    @Test
    fun tarificationDoesNotWriteIntoItsSharedCatalogue() {
        val tarification = TarificationServiceImpl(mock(STSService::class.java))

        fun renderOnce(requestId: String) = tarification.extractError(
            MarshallerHelper(RetrieveTransactionRequest::class.java, RetrieveTransactionRequest::class.java)
                .toXMLByteArray(RetrieveTransactionRequest().apply {
                    request = be.fgov.ehealth.messageservices.core.v1.RequestType().apply {
                        id = IDKMEHR().apply {
                            s = IDKMEHRschemes.ID_KMEHR
                            sv = "1.0"
                            value = requestId
                        }
                    }
                }),
            "111",
            "/RetrieveTransactionRequest/request/id"
        ).single()

        val first = renderOnce("11111111111.20260907120000")
        assertThat(first.value).isEqualTo("11111111111.20260907120000")

        val second = renderOnce("22222222222.20260907130000")

        assertThat(second.value).isEqualTo("22222222222.20260907130000")
        assertThat(first.value)
            .describedAs("the first caller's node, after a second caller rendered the same entry")
            .isEqualTo("11111111111.20260907120000")
        assertThat(first).isNotSameAs(second)
    }
}
