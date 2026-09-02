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
import org.junit.Test
import org.taktik.freehealth.middleware.domain.common.Patient
import org.taktik.freehealth.middleware.dto.efact.InvoiceItem
import org.taktik.freehealth.middleware.dto.efact.InvoiceSender
import org.taktik.freehealth.middleware.format.efact.segments.Record50Description
import java.io.StringWriter
import java.math.BigInteger

/**
 * ET 50 sign of the four amount zones on a credit note — offline, no eHealth call.
 *
 * Normative source: `FR-MPTI-FAC--Manuel Procedure Notes de credit, version mai 2026`,
 * sha256 cbea7bf1fc81f99fe5628b9bf73634c8e98f714578c7464c1007e5aafe434fa9, section "Instructions",
 * heading "Record 50" (paragraphs 96-100 of word/document.xml), which lists FOUR zones together:
 *
 *   "50 zone 19 = Intervention de l'assurance (montant negatif)"
 *   "50 zone 27 = Intervention personnelle patient (montant negatif)"
 *   "50 zone 30-31 = supplement (montant negatif)"
 *   "50 zone 22 nombre d'unites (negatif)"
 *
 * followed by "Quelques remarques importantes" (paragraph 103):
 *
 *   "Meme si le montant est egal a << 0 >>, il faut mettre un << - >> devant."
 *
 * The manual does not treat the four zones differently: the remark governs the list it follows.
 *
 * The billing instructions (annexe 7 suite 1, point c, p. 72) only PERMIT a different sign on a zero
 * ("Si un montant ou un nombre est egal a zero, le signe algebrique peut etre different de celui des
 * autres zones"). A permission does not cancel a particular obligation it contains: writing "-"
 * satisfies both documents, writing "+0" satisfies one and breaks the other. There is one conformant
 * output, hence no arbitration between the two sources.
 *
 * Scope: record 50 only. The manual's list says "Record 50"; ET 51 and ET 80 are not in it and are
 * deliberately left alone — ET 80 writes "+000000000" on the same credit note and that is not a defect.
 */
class Record50CreditNoteSignTest {
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

    /** A 567011 session whose four amount zones are all zero, so every zone exercises its zero branch. */
    private fun item(supplement: Long = 0L) = InvoiceItem().apply {
        codeNomenclature = 567011L
        dateCode = 20260729L
        reimbursedAmount = 0L
        units = 0
        patientFee = 0L
        doctorSupplement = supplement
        doctorIdentificationNumber = "54123456789"
    }

    private fun write(icd: InvoiceItem, creditNote: Boolean): String {
        val sw = StringWriter()
        val recordNumber = BelgianInsuranceInvoicingFormatWriter(sw)
            .writeRecordContent(3, sender(), 2026, 7, patient(), creditNote, "300", icd)
        return sw.toString().also { assertThat(recordNumber).isEqualTo(4) }
    }

    /** Independent re-implementation of the eFact check digit, so the assertion does not lean on the writer. */
    private fun expectedCheckDigits(recordWithoutCheckDigits: String): String {
        var sum = BigInteger.ZERO
        for (c in recordWithoutCheckDigits) {
            val v = when (c) {
                in '0'..'9' -> (c - '0').toLong()
                ' ' -> 10L
                in 'A'..'Z' -> (c - 'A' + 11).toLong()
                in 'a'..'z' -> (c - 'a' + 11).toLong()
                else -> 37L
            }
            sum = sum.add(BigInteger.valueOf(v))
        }
        val modulo = sum.mod(BigInteger.valueOf(97)).toInt()
        return String.format("%02d", if (modulo == 0) 97 else modulo)
    }

    private fun assertWellFormed(record: String) {
        assertThat(record).hasSize(350)
        assertThat(record.take(2)).isEqualTo("50")
        assertThat(record.takeLast(2)).isEqualTo(expectedCheckDigits(record.dropLast(2)))
    }

    private fun zone(record: String, zoneKey: String): String {
        val zd = Record50Description.zoneDescriptionsByZone[zoneKey]!!
        return record.substring(zd.position - 1, zd.position - 1 + zd.length)
    }

    // The composite key "30,31" is split by RecordOrSegmentDescription.register, so "30" is the key.
    private fun zone30(record: String) = zone(record, "30")

    // 1 — the defect this test was written for: a credit note carrying a ZERO supplement must sign it "-"
    @Test
    fun creditNoteWithAZeroSupplementSignsZone30Minus() {
        val record = write(item(), creditNote = true)

        assertWellFormed(record)
        assertThat(zone30(record)).isEqualTo("-000000000")
    }

    // 2 — the rule the manual actually states is about the LIST of four zones, so assert it on the list.
    // This is the assertion that cannot drift: it reads the record, not the writer.
    @Test
    fun creditNoteSignsAllFourZeroAmountZonesTheSameWay() {
        val record = write(item(), creditNote = true)

        assertThat(zone(record, "19").take(1)).isEqualTo("-")
        assertThat(zone(record, "22").take(1)).isEqualTo("-")
        assertThat(zone(record, "27").take(1)).isEqualTo("-")
        assertThat(zone30(record).take(1)).isEqualTo("-")
    }

    // 3 — a non zero supplement keeps the sign of the number: the connector does not invent one.
    // Kills the mutation "write - whenever creditNote".
    @Test
    fun creditNoteWithANegativeSupplementKeepsTheMinus() {
        assertThat(zone30(write(item(supplement = -500L), creditNote = true))).isEqualTo("-000000500")
    }

    @Test
    fun creditNoteWithAPositiveSupplementKeepsThePlus() {
        assertThat(zone30(write(item(supplement = 500L), creditNote = true))).isEqualTo("+000000500")
    }

    // 4 — SENTINEL, green before and after: an ordinary invoice is untouched. The manual's remark is
    // scoped to credit notes, and every batch already produced must keep writing "+" on a zero.
    @Test
    fun ordinaryInvoiceWithAZeroSupplementStillSignsPlus() {
        val record = write(item(), creditNote = false)

        assertWellFormed(record)
        assertThat(zone30(record)).isEqualTo("+000000000")
        assertThat(zone(record, "19").take(1)).isEqualTo("+")
        assertThat(zone(record, "22").take(1)).isEqualTo("+")
        assertThat(zone(record, "27").take(1)).isEqualTo("+")
    }

    // 5 — SENTINEL, green before and after: an ordinary invoice with a real supplement is untouched too.
    @Test
    fun ordinaryInvoiceWithAPositiveSupplementStillSignsPlus() {
        assertThat(zone30(write(item(supplement = 500L), creditNote = false))).isEqualTo("+000000500")
    }
}
