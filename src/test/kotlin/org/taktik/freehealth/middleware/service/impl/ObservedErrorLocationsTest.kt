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
import be.fgov.ehealth.standards.kmehr.cd.v1.CDHCPARTY
import be.fgov.ehealth.standards.kmehr.cd.v1.CDHCPARTYschemes
import be.fgov.ehealth.standards.kmehr.id.v1.IDHCPARTY
import be.fgov.ehealth.standards.kmehr.id.v1.IDHCPARTYschemes
import be.fgov.ehealth.standards.kmehr.id.v1.IDKMEHR
import be.fgov.ehealth.standards.kmehr.id.v1.IDKMEHRschemes
import be.fgov.ehealth.standards.kmehr.schema.v1.AuthorType
import be.fgov.ehealth.standards.kmehr.schema.v1.FolderType
import be.fgov.ehealth.standards.kmehr.schema.v1.HcpartyType
import be.fgov.ehealth.standards.kmehr.schema.v1.Kmehrmessage
import be.fgov.ehealth.standards.kmehr.schema.v1.TransactionType
import jakarta.xml.bind.JAXBContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/**
 * The error locations MyCareNet is **observed** to send, held against the code that consumes them.
 *
 * Everything else in this area was measured against locations of our own devising. Two primary sources
 * publish real ones, and both live outside this repository:
 *
 *  - `Sharepoint/Member Data/level 4/FR-EXEM-MEMD-ALL Données du membre Async - exemples de réponses.pdf`
 *    (April 2021) — six MDA examples carrying four distinct `Location` values, all of the `*:name` shape;
 *    and `connector-packaging-*-5.1.0-java/config/scenarios/careprovider-response-happy.xml`, the official
 *    eHealth connector's own **synchronous** MDA scenario, which sends
 *    `*:AttributeQuery / *:Issuer / text()` with `CROSSCHECK_ISSUER` (separators spaced out: Kotlin nests
 *    block comments). Those are covered by
 *    `MemberDataErrorRenderingOfflineTest`.
 *  - `connector-packaging-*-5.1.0-java/config/kmehrcommons/examples/kmehrResponseWithError.xml` — the only
 *    published kmehr acknowledgement `url`, reproduced verbatim below. It is what this suite is for.
 *
 * No published source anywhere shows a `not(…)` step, which is what `ErrorLocationPath.resolvableSteps`
 * handles: that shape is inferred from the catalogues' own `regex` column (`not.+Issuer`), which can only
 * match a location containing `not(`. It remains a hypothesis, not an observation.
 */
class ObservedErrorLocationsTest {

    /**
     * Verbatim from `config/kmehrcommons/examples/kmehrResponseWithError.xml`, error code 141, newlines and
     * indentation included. Note the shape: MyCareNet writes it **already namespace-agnostic**, as
     * `*[local-name()='x' and namespace-uri()='y']`, with sub-predicates pinning `id`/`cd` values.
     */
    private val publishedKmehrUrl =
        "/*[local-name()='kmehrrequest' and namespace-uri()='http://www.ehealth.fgov.be/medicalagreement/core/v1']" +
            "/*[local-name()='kmehrmessage' and namespace-uri()='http://www.ehealth.fgov.be/medicalagreement/core/v1']" +
            "/*[local-name()='folder' and\n        namespace-uri()='http://www.ehealth.fgov.be/standards/kmehr/schema/v1'" +
            " and *[local-name()='id' and namespace-uri()='http://www.ehealth.fgov.be/standards/kmehr/schema/v1'" +
            " and @S='ID-KMEHR' and text()='1']]/*[local-name()='transaction' and\n        namespace-uri()=" +
            "'http://www.ehealth.fgov.be/standards/kmehr/schema/v1' and *[local-name()='id' and namespace-uri()=" +
            "'http://www.ehealth.fgov.be/standards/kmehr/schema/v1' and @S='ID-KMEHR' and text()='1']]" +
            "/*[local-name()='author' and\n        namespace-uri()='http://www.ehealth.fgov.be/standards/kmehr/schema/v1']" +
            "/*[local-name()='hcparty' and namespace-uri()='http://www.ehealth.fgov.be/standards/kmehr/schema/v1'" +
            " and *[local-name()='cd' and\n        namespace-uri()='http://www.ehealth.fgov.be/standards/kmehr/schema/v1'" +
            " and @S='CD-HCPARTY' and text()='persphysician']]/*[local-name()='id' and namespace-uri()=" +
            "'http://www.ehealth.fgov.be/standards/kmehr/schema/v1' and @S='ID-HCPARTY']"

    /** The request the published url points into, built to satisfy its `text()='1'` / `persphysician` predicates. */
    private val kmehrRequest: ByteArray = ByteArrayOutputStream().apply {
        JAXBContext.newInstance(Kmehrrequest::class.java).createMarshaller().marshal(
            Kmehrrequest().apply {
                kmehrmessage = Kmehrmessage().apply {
                    folders.add(FolderType().apply {
                        ids.add(IDKMEHR().apply { s = IDKMEHRschemes.ID_KMEHR; sv = "1.0"; value = "1" })
                        transactions.add(TransactionType().apply {
                            ids.add(IDKMEHR().apply { s = IDKMEHRschemes.ID_KMEHR; sv = "1.0"; value = "1" })
                            author = AuthorType().apply {
                                hcparties.add(HcpartyType().apply {
                                    cds.add(CDHCPARTY().apply {
                                        s = CDHCPARTYschemes.CD_HCPARTY; sv = "1.1"; value = "persphysician"
                                    })
                                    ids.add(IDHCPARTY().apply {
                                        s = IDHCPARTYschemes.ID_HCPARTY; sv = "1.0"; value = "10013368003"
                                    })
                                })
                            }
                        })
                    })
                }
            },
            this
        )
    }.toByteArray()

    private fun parsed() = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(ByteArrayInputStream(kmehrRequest))

    /**
     * The rewrite leaves the published form alone — which is the point: MyCareNet already writes
     * `local-name()`, so on this shape `namespaceAgnostic` is a no-op and neither helps nor harms. Whatever
     * the resolution work did for the kmehr domains, it did not do it here.
     */
    @Test
    fun theRewriteIsANoOpOnThePublishedKmehrUrl() {
        assertThat(ErrorLocationPath.resolvableSteps(publishedKmehrUrl)).isEqualTo(publishedKmehrUrl)
        assertThat(ErrorLocationPath.namespaceAgnostic(publishedKmehrUrl)).isEqualTo(publishedKmehrUrl)
    }

    /**
     * **The published url cannot be compiled by the XPath the nine kmehr-side domains actually use.**
     *
     * `XPathFactory.newInstance()` yields the JDK implementation — Saxon-HE is a declared dependency but
     * ships no `javax.xml.xpath.XPathFactory` service file — and the JDK caps an expression at
     * `jdk.xml.xpathExprOpLimit` operators, 100 by default. This location has 101, so it throws
     * `JAXP0801002` before any namespace question arises. It threw before the resolution work and it throws
     * after: on **this** location the blocker is the operator limit, not the namespaces.
     *
     * The scope of that is exactly one domain, and must not be widened. This is the Chapter IV commons
     * example, and its 101 operators come from its own predicates; nothing says a Dmg or an eAttest location
     * is as long. For eAttest v3 the opposite is **measured**: the acceptance smoke of 31/08/2026 returned
     * `uid 51` alone for code 156, `eAttestErrors.json` carries four entries for that code and not one
     * null-path entry out of 158, and `EattestV3ServiceImpl` has no code-only bypass — so only
     * `it.path == base` can have matched, which means the real eAttest url compiled and resolved. Its shape
     * is not published; the WARN branches are what will settle it.
     *
     * Saxon, which only `MemberDataServiceImpl` instantiates explicitly, compiles it and resolves it to the
     * right node: the author's `id`, which is exactly what CIN error 141 designates.
     */
    @Test
    fun thePublishedKmehrUrlCompilesOnlyUnderSaxon() {
        val doc = parsed()

        assertThatThrownBy { XPathFactory.newInstance().newXPath().compile(publishedKmehrUrl) }
            .describedAs("the factory nine of the ten domains get")
            .hasMessageContaining("JAXP0801002")

        val nodes = net.sf.saxon.xpath.XPathFactoryImpl().newXPath()
            .compile(publishedKmehrUrl)
            .evaluate(doc, XPathConstants.NODESET) as NodeList

        assertThat(nodes.length).isEqualTo(1)
        assertThat(nodes.item(0).localName).isEqualTo("id")
        assertThat(nodes.item(0).textContent)
            .describedAs("the author's NIHII — what error 141 points at")
            .isEqualTo("10013368003")
    }

    /**
     * What a caller gets today for that error, and why it looks right by accident.
     *
     * The compile throws, so `base` stays null, so Chapter4's first filter falls through its
     * `base == null` bypass — a bypass only Chapter4 and Dmg have — and returns every entry carrying
     * code 141. There is exactly **one**, uid 9, so
     * the answer happens to be correct — a dragnet of one. Change the catalogue to hold two entries for a
     * code and the same path returns both.
     */
    @Test
    fun chapter4AnswersThroughItsOwnCodeOnlyBypass() {
        val chapter4 = Chapter4ServiceImpl(
            org.mockito.Mockito.mock(org.taktik.freehealth.middleware.service.STSService::class.java),
            org.mockito.Mockito.mock(KgssServiceImpl::class.java),
            org.mockito.Mockito.mock(org.taktik.connector.technical.service.keydepot.KeyDepotService::class.java)
        )

        assertThat(chapter4.chapter4ConsultationErrors.values.count { it.code == "141" })
            .describedAs("the bypass is only harmless because this code has one entry")
            .isEqualTo(1)

        val errors = chapter4.extractError(
            kmehrRequest, "141", chapter4.chapter4ConsultationErrors, publishedKmehrUrl
        )

        assertThat(errors.map { it.uid }).containsExactly("9")
        assertThat(errors.single().value)
            .describedAs("nothing resolved, so no offending node is reported")
            .isNull()
    }

    /**
     * **eAttest v3 is not in that situation, and this measures why the smoke proves it.**
     *
     * The acceptance smoke of 31/08/2026 came back with `uid 51` for code 156 — a single entry, carrying the
     * readable `path`. Three facts measured here make that possible only through `it.path == base`, i.e. only
     * if the location MyCareNet sent compiled and resolved against the marshalled request:
     *
     * 1. `eAttestErrors.json` holds **no** null-path entry, so the `it.path == null` arm added by this batch
     *    can never match on eAttest;
     * 2. code 156 carries **four** entries, so a code-only answer would have returned four, not one;
     * 3. `EattestV3ServiceImpl` has no `base == null` bypass at all — only Chapter4 and Dmg do.
     *
     * So the operator-limit finding above is Chapter IV's, and the real eAttest location — whose shape is
     * published nowhere — is under the cap. This test exists so the finding cannot be re-widened by reading.
     */
    @Test
    fun theEattestSmokeCouldOnlyHaveComeFromAResolvedPath() {
        val entries = EattestV3ServiceImpl::class.java.getResourceAsStream("/be/errors/eAttestErrors.json")!!
            .use { com.fasterxml.jackson.databind.ObjectMapper().readTree(it) }
            .let { it["values"] ?: it }

        assertThat(entries.count { it["path"] == null || it["path"].isNull })
            .describedAs("a null path would let the widened filter answer without resolving")
            .isZero()
        assertThat(entries.count { it["code"]?.asText() == "156" })
            .describedAs("a code-only answer for 156 would have returned this many entries, not one")
            .isEqualTo(4)
        assertThat(entries.filter { it["code"]?.asText() == "156" }.map { it["uid"].asText() })
            .contains("51")

        val source = java.io.File(
            "src/main/kotlin/org/taktik/freehealth/middleware/service/impl/EattestV3ServiceImpl.kt"
                                 )
        if (source.exists()) {
            assertThat(source.readText())
                .describedAs("only Chapter4 and Dmg carry the code-only bypass")
                .doesNotContain("filter { it.code == ec }")
        }
    }
}
