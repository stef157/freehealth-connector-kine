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
     * A location the CIN writes in its own notation must not take the response down.
     *
     * `MemberDataErrors.json` indexes nine of its own entries as
     * `…/Facet[urn:be:cin:nippin:insurability]/Dimension[requestType]` — a bare URN inside a predicate,
     * which is `nodeDescr`'s notation and not an XPath expression. Compiling it raises
     * `Namespace prefix 'urn' has not been declared`, and MDA was one of the four copies of `extractError`
     * with no try/catch: the exception left the function, through `errors.forEach` in the response builder,
     * and failed the whole call. An error MyCareNet reported precisely became a 500 with no message.
     *
     * Measured on the original code: `XPathExpressionException` out of `extractError`.
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
