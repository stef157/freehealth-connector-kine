/*
 * Copyright (C) 2018 Taktik SA
 *
 * This file is part of iCureBackend.
 *
 * iCureBackend is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2 as published by
 * the Free Software Foundation.
 *
 * iCureBackend is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with iCureBackend.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.taktik.freehealth.middleware.format.efact

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import org.taktik.freehealth.middleware.domain.common.Patient
import org.taktik.freehealth.middleware.dto.efact.InvoiceSender
import org.taktik.freehealth.middleware.dto.efact.InvoicingTreatmentReasonCode
import org.taktik.freehealth.middleware.format.efact.segments.Record20Description
import java.io.StringWriter

/**
 * ET 20 Z 42-43-44-45, the network reference data — offline, no eHealth call.
 *
 * Normative source: INAMI *instructions de facturation electronique*, edition 2021, **p. 275**
 * (*ENREGISTREMENT DE TYPE 20 ZONE 42-43-44-45*), which declares the 48 positions as
 * `48 A = 32 A + 10 N + 2 N + 1 N + 3 N`, and annexe 26.2 (p. 202), which names the content:
 * `Z 42-45 | Donnees de reference reseau | N° engagement de paiement (MDA)`. The generic label of the zone
 * layout is what long made it read as something else.
 *
 * The 32 alphanumerical positions carry the `paymentApproval` an insurer returns on the MemberData
 * insurability period — a hexadecimal digest, e.g. `41D862C7BDCB08F4CB9C30D96ED1C446`.
 *
 * Two rules from the same page frame the rest: *"Si l'attestation de remplacement n'a ete presentee et le reseau
 * n'a pas ete consulte, le contenu de cette zone est egal a zero"* — hence 48 zeroes when nothing was engaged, in a
 * zone that is alphanumerical and would otherwise be blank filled — and the zone is only to be completed
 * *"que si eTar n'est pas utilisee"*.
 */
class Record20PaymentApprovalTest {
    /** A real engagement, as returned by MemberData on the insurability period assertion. */
    private val paymentApproval = "41D862C7BDCB08F4CB9C30D96ED1C446"

    private fun zoneDescription() = Record20Description.zoneDescriptionsByZone["42"]!!

    private fun sender() = InvoiceSender().apply {
        nihii = 54123456789L
        bce = 999999922L
        ssin = "12345678901"
        firstName = "Jean"
        lastName = "Kine"
        phoneNumber = 32470000000L
        conventionCode = 0
        professionCode = BelgianInsuranceInvoicingFormatWriter.KINE_PROFESSION_CODE
    }

    private fun patient() = Patient().apply { ssin = "86103130262"; firstName = "Test"; lastName = "Patient" }

    /** Writes one ET 20 the way EfactServiceImpl does, and returns its 350 positions. */
    private fun writeRecord20(): String {
        val sw = StringWriter()
        BelgianInsuranceInvoicingFormatWriter(sw).writeRecordHeader(
            2, sender(), 1L, InvoicingTreatmentReasonCode.Other, "REF1", patient(), "300",
            false, false, false, null, null, null, null, false, null, null, null, null
        )
        return sw.toString().also { assertThat(it).hasSize(350) }
    }

    private fun zone4245(record20: String): String {
        val zd = zoneDescription()
        return record20.substring(zd.position - 1, zd.position - 1 + zd.length)
    }

    /** The layout already places the zone; only its type and its writer are missing. */
    @Test
    fun theZoneSitsAtPosition213AndSpans48Positions() {
        assertThat(zoneDescription().position).isEqualTo(213)
        assertThat(zoneDescription().length).isEqualTo(48)
        assertThat(zoneDescription().zonesList).isEqualTo("42,43a,43b,44,45")
    }

    /**
     * p. 275 declares the zone `48 A`. It was forced to `N` so that it would be zero filled rather than blank
     * filled, which is the right output for the wrong reason: `N` also means the value goes through
     * `Long.valueOf`, and no engagement of paiement is a number.
     */
    @Test
    fun theZoneIsAlphanumericalAsTheSourceDeclaresIt() {
        assertThat(zoneDescription().type).isEqualTo(ZoneDescriptionType.ALPHANUMERICAL_SYMBOL)
    }

    /**
     * The sentinel of this batch, green before the change and green after: a zone nobody writes holds 48 zeroes.
     * An alphanumerical zone pads with blanks, so switching the type without giving the zone a default value would
     * silently change every invoice already produced.
     */
    @Test
    fun anUnwrittenZoneHolds48Zeroes() {
        assertThat(zone4245(writeRecord20())).isEqualTo("0".repeat(48))
    }

    /** What the zone must be able to hold, and cannot today. */
    @Test
    fun aRealEngagementCanBeWrittenToTheZone() {
        val sw = StringWriter()
        Zone(zoneDescription(), paymentApproval + "0000000000" + "00" + "2" + "000").write(sw)
        assertThat(sw.toString()).isEqualTo("41D862C7BDCB08F4CB9C30D96ED1C4460000000000002000")
        assertThat(sw.toString()).hasSize(48)
    }


    /** Writes one ET 20 carrying an engagement, through the same entry point EfactServiceImpl uses. */
    private fun writeRecord20(paymentApproval: String?): String {
        val sw = StringWriter()
        BelgianInsuranceInvoicingFormatWriter(sw).writeRecordHeader(
            2, sender(), 1L, InvoicingTreatmentReasonCode.Other, "REF1", patient(), "300",
            false, false, false, null, null, null, null, false, null, null, null, null, paymentApproval
        )
        return sw.toString().also { assertThat(it).hasSize(350) }
    }

    /** The 32 positions the caller supplies, and the sixteen the format imposes, in the order p. 275 gives. */
    @Test
    fun anEngagementIsComposedIntoThe48PositionsOfTheZone() {
        val record = writeRecord20(paymentApproval)
        assertThat(zone4245(record)).isEqualTo("41D862C7BDCB08F4CB9C30D96ED1C4460000000000002000")
        assertThat(zone4245(record).take(32)).isEqualTo(paymentApproval)
        assertThat(zone4245(record).substring(32, 42)).describedAs("Z 43a card number").isEqualTo("0000000000")
        assertThat(zone4245(record).substring(42, 44)).describedAs("Z 43b card version").isEqualTo("00")
        assertThat(zone4245(record).substring(44, 45)).describedAs("Z 44 origin").isEqualTo("2")
        assertThat(zone4245(record).substring(45, 48)).describedAs("Z 45 reserve").isEqualTo("000")
    }

    /** Nothing supplied, nothing inferred: not even the origin digit, which would claim an answer nobody gave. */
    @Test
    fun anAbsentEngagementLeavesTheWholeZoneAtZero() {
        listOf(null, "", "   ").forEach { absent ->
            assertThat(zone4245(writeRecord20(absent))).describedAs("value %s", absent).isEqualTo("0".repeat(48))
        }
    }

    /** The record is unchanged, to the byte and to the check digits, when no engagement is supplied. */
    @Test
    fun theRecordIsByteForByteUnchangedWithoutAnEngagement() {
        assertThat(writeRecord20(null)).isEqualTo(writeRecord20())
    }

    /**
     * A wrong length is refused rather than completed - the catalogue publishes 204225 F, "Numero d'agrement de la
     * consultation du reseau incorrect" - and the refusal never echoes the value, which reaches a 400 body and the
     * logs.
     */
    @Test
    fun aMalformedEngagementIsRefusedRatherThanSilentlyPadded() {
        listOf("41D862C7", paymentApproval + "0", "41D862C7BDCB08F4CB9C30D96ED1C44 ", "41D862C7-DCB08F4CB9C30D96ED1C446")
            .forEach { malformed ->
                assertThatThrownBy { writeRecord20(malformed) }
                    .describedAs("value %s", malformed)
                    .isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining("ET 20 Z 42-45")
                    .matches({ it.message?.contains(malformed.trim()) == false }, "must not echo the value")
            }
    }

    private object ZoneDescriptionType {
        val ALPHANUMERICAL_SYMBOL =
            org.taktik.freehealth.middleware.format.efact.segments.ZoneDescription.ZoneType.ALPHANUMERICAL
    }
}
