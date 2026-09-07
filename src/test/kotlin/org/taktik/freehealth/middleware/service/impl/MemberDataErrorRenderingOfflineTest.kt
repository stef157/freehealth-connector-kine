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

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.mockito.Mockito.mock
import org.taktik.connector.business.domain.newXMLGregorianCalendar
import org.taktik.connector.technical.service.keydepot.KeyDepotService
import org.taktik.connector.technical.utils.ConnectorXmlUtils
import org.taktik.freehealth.middleware.mapper.MapperFacade
import org.taktik.freehealth.middleware.service.STSService
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import java.time.Instant

/**
 * How MDA renders a MyCareNet acknowledgement error — measured on the service's own `extractError`, against
 * the very `AttributeQuery` the service builds and marshals. No Spring, no certificate, no network.
 *
 * MDA is the one domain of this batch that is in service, so this suite has two jobs. The first is a guard:
 * the path a call takes when MyCareNet answers `Success` must not move. The second is the defect: the CIN
 * names a *missing* element with `not(…)`, which is not a valid XPath step, and eight catalogue entries are
 * told apart by their `regex` alone.
 */
class MemberDataErrorRenderingOfflineTest {

    private val memberData = MemberDataServiceImpl(
        mock(STSService::class.java),
        mock(KeyDepotService::class.java),
        mock(MapperFacade::class.java)
    )

    /** The document the service really sends — `getAttrQuery` marshalled as line 616 marshals it. */
    private val attributeQuery: ByteArray = ConnectorXmlUtils.toByteArray(
        memberData.getAttrQuery(
            inputRef = "202609071200001234",
            issueInstant = newXMLGregorianCalendar(
                gc = DateTime(1757246400000L, DateTimeZone.forID("Europe/Brussels")).toGregorianCalendar()
            ),
            facets = null,
            hospitalized = false,
            requestType = "information",
            hcpNihii = "99007334527",
            patientSsin = "12345678901",
            io = null,
            ioMembership = null,
            startDate = Instant.ofEpochMilli(1629849600000L),
            endDate = Instant.ofEpochMilli(1629936000000L)
        ) as Any
    )

    // ------------------------------------------------------------------ the invariants

    /**
     * A detail carrying no `Location` never reaches the XPath: `extractError` returns on `errorUrl?.let`.
     * With a successful call carrying no `statusDetail` at all — `errors` is built from
     * `status?.statusDetail?.anies` — these are the two reasons a call that works cannot be touched by
     * anything done to the resolution.
     */
    @Test
    fun aDetailWithoutALocationRendersNothing() {
        assertThat(memberData.extractError(attributeQuery, "urn:oasis:names:tc:SAML:2.0:status:Success", null, null, null))
            .isEmpty()
    }

    // ------------------------------------------------------------------ the defect

    /**
     * That a location resolves at all.
     *
     * The `AttributeQuery` MDA sends is namespace-qualified and serialised with prefixes —
     * `<ns3:AttributeQuery>`, `<ns6:Subject>`. `extractError` parsed it with `isNamespaceAware = false`,
     * which leaves `localName` null and makes every DOM node answer to its prefixed `nodeName`. Saxon still
     * matched the `*:name` form the CIN actually sends (measured — see
     * `theLocationsTheCinPublishesAllRender`), but an **unprefixed** location like `/AttributeQuery` matched
     * nothing, and so did the parent of a truncated `not(…)` step, which is unprefixed by construction.
     *
     * uid 68 `MUTATION` and uid 96 `ONLY_FIVE_PERIODS_RETURNED` (`Success` / `PartialAnswer`) carry no
     * `path`, so reaching them needs nothing but a nodeset. Whether MyCareNet ever sends them with a
     * `Location` is a different question, and the answer is documented as no: the `PartialAnswer` example in
     * `FR-MPTI-MEMD-ALL … R9.pdf` p. 14 has `DetailCode` / `DetailSource` / `Message` and no `Location` at
     * all, which returns on `errorUrl?.let`. This test says what happens *if* one ever carries one.
     */
    @Test
    fun theSuccessWarningsAreRendered() {
        fun warning(detailCode: String) = memberData.extractError(
            attributeQuery,
            "urn:oasis:names:tc:SAML:2.0:status:Success",
            "urn:be:cin:nippin:SAML:status:PartialAnswer",
            "/AttributeQuery",
            detailCode
        )

        assertThat(warning("MUTATION").map { it.uid }).containsExactly("68")
        assertThat(warning("MUTATION").single().msgFr).isEqualTo("Il y une mutation durant la période")
        assertThat(warning("ONLY_FIVE_PERIODS_RETURNED").map { it.uid }).containsExactly("96")
    }

    /**
     * A deep location, and the predicate-stripping fallback that goes with it.
     *
     * `nodeDescr` writes a node as `localName[idAttribute]`, so the `base` rebuilt from the first `Facet` is
     * `/AttributeQuery/Extensions/Facet[urn:be:cin:nippin:insurability]` while the catalogue entry says
     * `/AttributeQuery/Extensions/Facet`. The second filter strips the predicates and matches — a branch
     * that had never run.
     */
    @Test
    fun aFacetLocationReachesTheFacetEntry() {
        val errors = memberData.extractError(
            attributeQuery,
            "urn:oasis:names:tc:SAML:2.0:status:Requester",
            null,
            "/AttributeQuery/Extensions/Facet",
            "UNKNOWN_FACET"
        )

        assertThat(errors.map { it.uid }).containsExactly("23")
        assertThat(errors.single().path).isEqualTo("/AttributeQuery/Extensions/Facet")
    }

    // ------------------------------------------------------------------ the documented locations

    /**
     * The five `Location` values the CIN itself publishes, from
     * `Sharepoint/Member Data/level 4/FR-EXEM-MEMD-ALL Données du membre Async - exemples de réponses.pdf`
     * (April 2021) — the only observed shapes there are, and none of them was guessed here:
     *
     * Separators are spaced out below only because Kotlin nests block comments and a bare slash-star would
     * open one; the values in the test body are verbatim.
     *
     * | § | detailCode | Location |
     * |---|---|---|
     * | 1 | `UNKNOWN_NISS_ROUTING` | `*:AttributeQuery / *:Subject / *:NameID` |
     * | 2 | `BO_INVALID_REGNBR` | the same, plus `[@Format='urn:be:cin:nippin:member:ssin@mut']` |
     * | 3 | `BO_UNKNOWN_REGNBR` | the same, leading separator included, ending in ` / text()` |
     * | 4-6 | `IOSM_EXCEPTION`, `BO_EXCEPTION`, `NO_FACET` | empty element |
     *
     * So the form is `*:name` — prefixed with the wildcard namespace, sometimes without a leading slash,
     * sometimes with an attribute predicate, sometimes ending in `text()`. That is exactly what
     * `ErrorLocationPath.namespaceAgnostic` rewrites, and the URN inside the predicate survives because it
     * sits between quotes, which the rewrite copies verbatim.
     */
    @Test
    fun theLocationsTheCinPublishesAllRender() {
        fun at(location: String, code1: String, code2: String?, detailCode: String) =
            memberData.extractError(attributeQuery, code1, code2, location, detailCode)

        // § 1 — the plain `*:` form, no leading slash. uid 62.
        val routing = at(
            "*:AttributeQuery/*:Subject/*:NameID",
            "urn:oasis:names:tc:SAML:2.0:status:Responder",
            "urn:be:cin:nippin:SAML:status:InputError",
            "UNKNOWN_NISS_ROUTING"
        )
        assertThat(routing.map { it.uid }).containsExactly("62")
        assertThat(routing.single().value)
            .describedAs("the offending node of our own request — the member's SSIN")
            .isEqualTo("12345678901")

        // § 2 — an attribute predicate holding a URN, between quotes.
        val invalidRegNbr = at(
            "*:AttributeQuery/*:Subject/*:NameID[@Format='urn:be:cin:nippin:member:ssin@mut']",
            "urn:oasis:names:tc:SAML:2.0:status:Responder",
            "urn:be:cin:nippin:SAML:status:InternalError",
            "BO_INVALID_REGNBR"
        )
        assertThat(invalidRegNbr)
            .describedAs("the catalogue puts uid 77 at path `/`, which no rebuilt base equals — but the error must still be reported")
            .hasSize(1)
        assertThat(invalidRegNbr.single().code).isEqualTo("urn:oasis:names:tc:SAML:2.0:status:Responder")

        // § 3 — a `text()` step. The rebuilt base ends `/#text`, which the catalogue does not carry either.
        // This is the one published form whose rendering changed: it returned an empty set before the batch.
        val unknownRegNbr = at(
            "/*:AttributeQuery/*:Subject/*:NameID/text()",
            "urn:oasis:names:tc:SAML:2.0:status:Responder",
            "urn:be:cin:nippin:SAML:status:InternalError",
            "BO_UNKNOWN_REGNBR"
        )
        assertThat(unknownRegNbr).hasSize(1)

        // § 4-6 — an empty `<Location/>` reaches the function as "", not null.
        assertThat(at("", "urn:oasis:names:tc:SAML:2.0:status:Responder", null, "IOSM_EXCEPTION").map { it.uid })
            .describedAs("an empty location designates nothing, and uid 75 carries no path either")
            .containsExactly("75")
    }

    /**
     * Eight entries of this catalogue are told apart by their `regex` alone, and MDA never read it.
     *
     * They all carry the same code, `urn:oasis:names:tc:SAML:2.0:status:Requester`, and they overlap
     * pairwise on the path: uid 13 `not.+Issuer` and uid 21 `not.+Extensions` both sit on
     * `/AttributeQuery`; uid 36 `not.+NameID` and uid 50 `not.+SubjectConfirmation` both on
     * `/AttributeQuery/Subject`. The `regex` matches the location MyCareNet sent — which is why a `not(…)`
     * step has to be dropped for resolution but kept for the match. Without the clause, a missing `Issuer`
     * answered "Balise d'émetteur manquante" *and* "Balise Extensions manquantes", one of which is false.
     */
    @Test
    fun aMissingIssuerRendersThatOneEntry() {
        val errors = memberData.extractError(
            attributeQuery,
            "urn:oasis:names:tc:SAML:2.0:status:Requester",
            null,
            "/AttributeQuery/not(*:Issuer)",
            null
        )

        assertThat(errors.map { it.uid }).containsExactly("13")
        assertThat(errors.single().msgFr).isEqualTo("Balise d'émetteur manquante")
        assertThat(errors.single().value)
            .describedAs("the location named an element that is not there; the parent's content is not it")
            .isNull()
    }

    /** The other half of the same pair — what proves the `regex` discriminates rather than luck. */
    @Test
    fun aMissingExtensionsRendersTheOtherEntry() {
        val errors = memberData.extractError(
            attributeQuery,
            "urn:oasis:names:tc:SAML:2.0:status:Requester",
            null,
            "/AttributeQuery/not(*:Extensions)",
            null
        )

        assertThat(errors.map { it.uid }).containsExactly("21")
    }

    /** The deeper pair, which also goes through the predicate-stripping second filter. */
    @Test
    fun aMissingNameIdRendersThatOneEntry() {
        val errors = memberData.extractError(
            attributeQuery,
            "urn:oasis:names:tc:SAML:2.0:status:Requester",
            null,
            "/AttributeQuery/Subject/not(*:NameID)",
            null
        )

        assertThat(errors.map { it.uid }).containsExactly("36")
    }

    /**
     * A location that cannot be compiled must not take the response down.
     *
     * The five locations the CIN publishes are all compilable (see above), so this is uniformity rather than
     * an observed failure: MDA was one of four copies of `extractError` with no try/catch, and there any
     * `XPathExpressionException` left the function, travelled through `errors.forEach` in the response
     * builder, and failed the whole call — an error MyCareNet had reported precisely came back as a 500 with
     * no message. Six of the ten copies already caught it.
     *
     * The location used here is the one form known to raise: a bare URN inside a predicate, which is how
     * `MemberDataErrors.json` indexes nine of its own entries (`nodeDescr`'s notation, not XPath).
     * `Namespace prefix 'urn' has not been declared` — measured on the original code as an exception out of
     * `extractError`. Whether MyCareNet ever sends a location in that notation is **not** established.
     */
    @Test
    fun aLocationThatIsNotAnXPathDoesNotTakeTheResponseDown() {
        val errors = memberData.extractError(
            attributeQuery,
            "urn:oasis:names:tc:SAML:2.0:status:Requester",
            null,
            "/AttributeQuery/Extensions/Facet[urn:be:cin:nippin:insurability]/Dimension[requestType]",
            "UNALLOWED_REQUESTTYPE"
        )

        assertThat(errors).hasSize(1)
        assertThat(errors.single().code).isEqualTo("urn:oasis:names:tc:SAML:2.0:status:Requester")
        assertThat(errors.single().path)
            .isEqualTo("/AttributeQuery/Extensions/Facet[urn:be:cin:nippin:insurability]/Dimension[requestType]")
        assertThat(errors.single().msgFr)
            .describedAs("the caller is told the location could not be compiled, and gets the reason")
            .startsWith("Erreur générique, xpath invalide : ")
    }
}
