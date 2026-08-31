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
import org.taktik.freehealth.middleware.dto.efact.EIDItem
import org.taktik.freehealth.middleware.dto.efact.InvoiceItem
import org.taktik.freehealth.middleware.dto.efact.InvoiceSender
import org.taktik.freehealth.middleware.format.efact.segments.Record50Description
import org.taktik.freehealth.middleware.format.efact.segments.Record51Description
import org.taktik.freehealth.middleware.format.efact.segments.Record52Description
import java.io.StringWriter
import java.math.BigInteger

/**
 * ET 52 Z 19, the agreement number — offline, no eHealth call.
 *
 * Normative source: INAMI *instructions de facturation electronique*, annexe 6.8 (EDITION 2021), which places
 * `19  20 A  132-151  Numero d'accord` in the type 52 record, and annexe 26.4 (kinesitherapeutes, p. 204, MISE A JOUR
 * 2021/9, publication 28-06-2022), titled "Enregistrement de type 52 (facultatif, sauf zone 19)", which marks
 * Z 19 "obligation de completer". The zone detail sheet adds: "le numero d'accord, recu via eAgreement, doit etre
 * complete", structure XXXYYYYYYYYYYYYYYYDD, and footnote (4) "a partir du 01/05/22, cette zone doit
 * obligatoirement etre completee par les kinesitherapeutes". Annexe 7 point f lists ET 52 Z 19 among the
 * alphanumerical zones that must be filled with zeroes when unused.
 *
 * The same annexe 26.4 also marks "Z 9 Type de saisie document identite" and "Z 10 Type de support document
 * identite" as "Obligation de completer". That column is qualified by the zone descriptions, which is why an
 * agreement number alone is written rather than refused: ET 52 ZONE 9 (p. 543, 1 A - 49) and ZONE 10 (p. 544,
 * 1 A - 50) both open with "Zone facultative jusqu'a ce que la verification de l'identite du patient par lecture du
 * document d'identite devienne obligatoire. Pour les praticiens de l'art infirmier, l'obligation entrera en vigueur
 * le 1/10/2017." Nursing is the only profession given a date; both pages carry MISE A JOUR 2021/32, publication
 * 28-04-2026, later than annexe 26.4 itself (p. 204, MISE A JOUR 2021/9, publication 28-06-2022). Eight of the
 * seventeen ET 52 zone descriptions carry that clause — Z 3, 6a-6b, 9, 10, 11, 12-13, 16, 17, every identity
 * capture zone — and Z 19 is the only non structural zone without it, which is what the annexe title says.
 *
 * The reference above to annexe 26.4 as "MISE A JOUR 2021/33, publication 23-06-2026" was wrong: 2021/33 is the
 * marker of the compiled document ("MISE A JOUR 2021/33 INCLUSE", cover page), not of that page.
 */
class Record52AgreementNumberTest {
    private val agreementNumber = "30600000000000000103"

    /** ET 10 Z 18 = 50: physiotherapist. Any other value leaves the sector's own annexe in charge. */
    private fun sender(professionCode: Int? = BelgianInsuranceInvoicingFormatWriter.KINE_PROFESSION_CODE) =
        InvoiceSender().apply {
            nihii = 54123456789L
            bce = 999999922L
            ssin = "12345678901"
            firstName = "Jean"
            lastName = "Kine"
            phoneNumber = 32470000000L
            conventionCode = 0
            this.professionCode = professionCode
        }

    private fun patient() = Patient().apply { ssin = "86103130262"; firstName = "Test"; lastName = "Patient" }

    private fun eidItem() = EIDItem(20260729000000L, 1030, "5910212346", 0, 1)

    /**
     * A 567011 session on 29/07/2026 — the nominal accepted scenario of the CIN physiotherapist test manual.
     *
     * The line names its own provider, as every real batch does: ET 50 Z 15 and ET 52 Z 15 both read
     * [InvoiceItem.doctorIdentificationNumber] (annexe 26.4 p. 204). Here they coincide with the sender, which is
     * the solo practitioner case.
     */
    private fun item() = InvoiceItem().apply {
        codeNomenclature = 567011L
        dateCode = 20260729L
        reimbursedAmount = 1000L
        doctorIdentificationNumber = "54123456789"
    }

    private fun write(icd: InvoiceItem, sender: InvoiceSender = sender()): String {
        val sw = StringWriter()
        val recordNumber = BelgianInsuranceInvoicingFormatWriter(sw).writeEid(3, icd, patient(), sender)
        return sw.toString().also { assertThat(recordNumber).isEqualTo(if (it.isEmpty()) 3 else 4) }
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
        assertThat(record.take(2)).isEqualTo("52")
        assertThat(record.takeLast(2)).isEqualTo(expectedCheckDigits(record.dropLast(2)))
    }

    private fun zone19(record: String): String {
        val zd = Record52Description.zoneDescriptionsByZone["19"]!!
        assertThat(zd.position).isEqualTo(132)
        assertThat(zd.length).isEqualTo(20)
        return record.substring(zd.position - 1, zd.position - 1 + zd.length)
    }

    // 1 + 3 + 4 — a physiotherapy 567011 with an agreement number produces a 350 character record 52 with valid
    // check digits. Annexe 26.4 requires the identity document capture in the very same record, hence the eidItem.
    @Test
    fun physiotherapyItemWithAgreementNumberProducesRecord52() {
        val record = write(item().apply {
            eidItem = eidItem()
            agreementNumber = this@Record52AgreementNumberTest.agreementNumber
        })

        assertWellFormed(record)
        assertThat(record.substring(9, 16)).isEqualTo("0567011")   // Z 4, = ET 50 Z 4
        assertThat(record.substring(16, 24)).isEqualTo("20260729") // Z 5, = ET 50 Z 5
    }

    // 2 — ET 52 Z 19 holds exactly the number supplied, at positions 132-151
    @Test
    fun zone19HoldsExactlyTheSuppliedNumber() {
        val record = write(item().apply {
            eidItem = eidItem()
            agreementNumber = this@Record52AgreementNumberTest.agreementNumber
        })

        assertThat(zone19(record)).isEqualTo(agreementNumber)
    }

    // The zone descriptions make Z 9 and Z 10 "facultative jusqu'a ce que la verification de l'identite ... devienne
    // obligatoire" (p. 543, p. 544), so a physiotherapist sends Z 19 with or without an eID capture. This test
    // replaces the one that locked the opposite: it asserted a refusal that contradicted the source.
    @Test
    fun agreementNumberWithoutEidIsWrittenForAPhysiotherapist() {
        val record = write(item().apply { agreementNumber = this@Record52AgreementNumberTest.agreementNumber })

        assertWellFormed(record)
        assertThat(zone19(record)).isEqualTo(agreementNumber)
        // the two zones that used to gate this record stay at their default: 1 A, so a blank. Nothing is invented.
        assertThat(record.substring(48, 49)).isEqualTo(" ")   // Z 9,  1 A - 49
        assertThat(record.substring(49, 50)).isEqualTo(" ")   // Z 10, 1 A - 50
    }

    // ... and the record does not depend on the sector at all: the profession code reaches ET 10 Z 18, not this one
    @Test
    fun agreementNumberWithoutEidWritesTheSameRecordForEveryProfession() {
        val kine = write(item().apply { agreementNumber = this@Record52AgreementNumberTest.agreementNumber })

        listOf(null, 30).forEach { professionCode ->
            val record = write(
                item().apply { agreementNumber = this@Record52AgreementNumberTest.agreementNumber },
                sender(professionCode))

            assertWellFormed(record)
            assertThat(zone19(record)).isEqualTo(agreementNumber)
            assertThat(record).isEqualTo(kine)
        }
    }

    // 5 — with no agreement number and no eID, nothing at all is written, as before
    @Test
    fun withoutAgreementNumberAndWithoutEidNothingIsWritten() {
        assertThat(write(item())).isEmpty()
    }

    // 5 + 7 — an eID item alone still produces the exact record it produced before ET 52 Z 19 existed
    @Test
    fun eidAloneIsByteForByteUnchanged() {
        val record = write(item().apply { eidItem = eidItem() })

        assertWellFormed(record)
        assertThat(record).isEqualTo(
            "52" + "000003" + "0" + "0567011" + "20260729" + "20260729" + "000" + "0086103130262" +
                "1" + "1" + "0" + "1030" + "000000000000" + "054123456789" + "5910212346     " +
                "0000000000000000000000001" + "0".repeat(229) + "68"
        )
        // the zone that did not exist before is where the reserve zeroes used to be
        assertThat(zone19(record)).isEqualTo(Record52Description.EMPTY_AGREEMENT_NUMBER)
    }

    // 8 — eID data and agreement number coexist in a single conformant record 52
    @Test
    fun eidAndAgreementNumberCoexistInOneRecord() {
        val record = write(item().apply {
            eidItem = eidItem()
            agreementNumber = this@Record52AgreementNumberTest.agreementNumber
        })

        assertWellFormed(record)
        assertThat(zone19(record)).isEqualTo(agreementNumber)
        // every eID zone is untouched: only positions 132-151 differ from the eID-only record
        val eidOnly = write(item().apply { eidItem = eidItem() })
        assertThat(record.take(131)).isEqualTo(eidOnly.take(131))
        assertThat(record.substring(151, 348)).isEqualTo(eidOnly.substring(151, 348))
    }

    // 6 — insuranceRef keeps feeding ET 51 Z 42 and never reaches ET 52 Z 19
    @Test
    fun insuranceRefStillGoesToRecord51Zone42() {
        val sw = StringWriter()
        val icd = item().apply {
            insuranceRef = "1234567890"
            insuranceRefDate = 20260729L
            doctorIdentificationNumber = "54123456789"
        }
        BelgianInsuranceInvoicingFormatWriter(sw)
            .writeInvolvementRecordContent(3, sender(), 2026, 7, patient(), false, icd)
        val record = sw.toString()

        assertThat(record).hasSize(350)
        assertThat(record.take(2)).isEqualTo("51")
        val zd = Record51Description.zoneDescriptionsByZone["42"]!!
        assertThat(record.substring(zd.position - 1, zd.position - 1 + 10)).isEqualTo("1234567890")

        // and the same item, written as a record 52, leaves Z 19 empty
        assertThat(zone19(write(icd.apply { eidItem = eidItem() }))).isEqualTo(Record52Description.EMPTY_AGREEMENT_NUMBER)
    }

    /**
     * Nothing at all reaches the Writer when a validation fails, so a refused record cannot corrupt the flat file.
     * This is the invariant PR #100 was reviewed against ("move the requires before the first ws.write"): the
     * agreement number zones are now computed before the eID requires, which is safe only because WriterSession
     * buffers every write into a map and emits on writeFieldsWithCheckSum() alone. Asserted rather than assumed.
     */
    @Test
    fun aRefusedRecordEmitsNothingAtAll() {
        val refused = listOf<InvoiceItem.() -> Unit>(
            { agreementNumber = "306" },                                           // malformed Z 19
            { eidItem = eidItem().apply { readType = "Z" } },                       // invalid Z 9
            { eidItem = eidItem().apply { deviceType = "Z" } }                      // invalid Z 10
        )

        refused.forEach { spoil ->
            val sw = StringWriter()
            val writer = BelgianInsuranceInvoicingFormatWriter(sw)
            val icd = item().apply(spoil)

            assertThatThrownBy { writer.writeEid(3, icd, patient(), sender()) }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThat(sw.toString()).isEmpty()
        }
    }

    // the layout stays a valid 350 position paving
    @Test
    fun record52LayoutPavesExactly350Positions() {
        val zones = Record52Description.zoneDescriptions
        var expected = 1
        zones.forEach {
            assertThat(it.position).`as`("zone ${it.zone} starts at ${it.position}").isEqualTo(expected)
            expected += it.length
        }
        assertThat(expected - 1).isEqualTo(350)
    }

    @Test
    fun malformedAgreementNumbersAreRefusedRatherThanSilentlyPadded() {
        listOf("306", "3060000000000000010A", "30600000000000000103 ").forEach { malformed ->
            assertThatThrownBy { write(item().apply { eidItem = eidItem(); agreementNumber = malformed }) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("ET 52 Z 19")
                .hasMessageContaining("expected exactly 20 digits")
        }
    }

    // the number reaches a 400 response body and the logs through the exception message: it must not be in there
    @Test
    fun theRefusalMessageNeverEchoesTheAgreementNumber() {
        listOf("306", "3060000000000000010A", "30600000000000000103 ", agreementNumber.dropLast(1)).forEach { value ->
            assertThatThrownBy { write(item().apply { eidItem = eidItem(); agreementNumber = value }) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .matches({ it.message?.contains(value.trim()) == false }, "message must not echo the value")
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // ET 52 Z 15 — the provider of the line, not the sender of the batch.
    //
    // Annexe 26.4 (kinesitherapeutes, p. 204) prescribes for the type 52 record: "15  Identification
    // dispensateur  = ET 50 Z 15", and ET 50 ZONE 15 (p. 468) reads "le numero d'identification du dispensateur
    // de soins qui a reellement effectue la prestation ... toujours precede d'un zero dans la premiere position".
    // Two consequences are asserted here: the value comes from the item, exactly as ET 50 reads it, and a value
    // shorter than the zone is completed on the LEFT, never on the right.
    // ---------------------------------------------------------------------------------------------------------

    private fun zone15(record: String): String {
        val zd = Record52Description.zoneDescriptionsByZone["15"]!!
        assertThat(zd.position).isEqualTo(68)
        assertThat(zd.length).isEqualTo(12)
        return record.substring(zd.position - 1, zd.position - 1 + zd.length)
    }

    /** ET 50 Z 15 of the same item, read from the record the writer really produces. */
    private fun record50Zone15(icd: InvoiceItem, sender: InvoiceSender = sender()): String {
        val sw = StringWriter()
        BelgianInsuranceInvoicingFormatWriter(sw)
            .writeRecordContent(3, sender, 2026, 7, patient(), false, "300", icd)
        val zd = Record50Description.zoneDescriptionsByZone["15"]!!
        return sw.toString().substring(zd.position - 1, zd.position - 1 + zd.length)
    }

    /** A substitute billing through the practice's batch: ET 50 names them, so ET 52 must name them too. */
    @Test
    fun zone15CarriesTheProviderOfTheLineNotTheSenderOfTheBatch() {
        val substitute = item().apply { eidItem = eidItem(); doctorIdentificationNumber = "11478761004" }
        val record = write(substitute)
        assertWellFormed(record)
        assertThat(zone15(record)).isEqualTo("011478761004")
        assertThat(zone15(record)).isNotEqualTo("054123456789")
    }

    /** The equality the annexe states, held record against record rather than by reading the writer. */
    @Test
    fun zone15IsAlwaysWhatRecord50Zone15Holds() {
        listOf(null, "54123456789", "11478761004", "1478761004").forEach { provider ->
            val icd = item().apply { eidItem = eidItem(); doctorIdentificationNumber = provider }
            assertThat(zone15(write(icd)))
                .describedAs("provider %s", provider)
                .isEqualTo(record50Zone15(icd))
        }
    }

    /**
     * p. 468 asks for the number to be preceded by a zero, i.e. completed on the left. A ten position
     * identification completed on the RIGHT reads as a different, larger number, and nothing is malformed —
     * which is why no validation catches it.
     */
    @Test
    fun aShortIdentificationIsCompletedOnTheLeftNotOnTheRight() {
        val short = "1478761004"
        val record = write(item().apply { eidItem = eidItem(); doctorIdentificationNumber = short },
            sender().apply { nihii = short.toLong() })
        assertWellFormed(record)
        assertThat(zone15(record)).isEqualTo("00" + short)
        assertThat(zone15(record)).isNotEqualTo("0" + short + "0")
    }

    /**
     * When the line names no provider the zone holds zeroes, because that is what ET 50 Z 15 holds — the record
     * follows ET 50 even where ET 50 itself is empty. Before this rule the zone carried the batch sender, so a
     * batch that never sets the field sees this zone change from the sender to zeroes.
     */
    @Test
    fun zone15IsZeroWhenTheLineNamesNoProvider() {
        val anonymous = { item().apply { eidItem = eidItem(); doctorIdentificationNumber = null } }
        val record = write(anonymous())
        assertWellFormed(record)
        assertThat(zone15(record)).isEqualTo("000000000000")
        assertThat(zone15(record)).isEqualTo(record50Zone15(anonymous()))
    }

    /** A medical house bills 109594 / 400396 under its own number, and ET 52 must not diverge from that either. */
    @Test
    fun aMedicalHouseSharesRecord50sOwnRuleForZone15() {
        // isMedicalHouse is a computed getter over the NIHII, not a settable flag: it wants a number starting
        // with 8 whose last three digits are one of "111"/"110"/"100"/"101"/"001"/"010"/"011".
        val house = sender().apply { nihii = 81000000111L }
        assertThat(house.isMedicalHouse).isTrue()
        listOf(109594L to "081000000111", 567011L to "000000000000").forEach { (code, expected) ->
            val icd = item().apply { codeNomenclature = code; eidItem = eidItem() }
            val record = write(icd, house)
            assertWellFormed(record)
            assertThat(zone15(record)).describedAs("code %s", code).isEqualTo(expected)
            assertThat(zone15(record)).isEqualTo(record50Zone15(icd, house))
        }
    }
}
