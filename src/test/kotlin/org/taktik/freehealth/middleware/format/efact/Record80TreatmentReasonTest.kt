package org.taktik.freehealth.middleware.format.efact

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.taktik.freehealth.middleware.domain.common.Patient
import org.taktik.freehealth.middleware.dto.efact.InvoiceSender
import org.taktik.freehealth.middleware.dto.efact.InvoicingTreatmentReasonCode
import org.taktik.freehealth.middleware.format.efact.segments.Record20Description
import org.taktik.freehealth.middleware.format.efact.segments.Record80Description
import java.io.StringWriter

/**
 * ET 80 Z 17 repeats ET 20 Z 17 - offline, no eHealth call.
 *
 * Reject 801712 (nature F), "Contenu different de celui dans l'ET 20 Z 17". The footer did not write the zone at
 * all, so it went out as 0000 whatever the header held: right for the reason Other, and a rejected invoice for a
 * work accident. Reject 801703 lists the authorised values - 0050, 0060, 0070, 0080, 0090 and 0000.
 */
class Record80TreatmentReasonTest {
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

    private fun patient() = Patient().apply { ssin = "11111111111"; firstName = "Test"; lastName = "Patient" }

    private fun record20(reason: InvoicingTreatmentReasonCode): String {
        val sw = StringWriter()
        BelgianInsuranceInvoicingFormatWriter(sw).writeRecordHeader(
            2, sender(), 1L, reason, "REF1", patient(), "130",
            false, false, false, null, null, null, null, false, null, null, null, null
        )
        return sw.toString().also { assertThat(it).hasSize(350) }
    }

    private fun record80(reason: InvoicingTreatmentReasonCode): String {
        val sw = StringWriter()
        BelgianInsuranceInvoicingFormatWriter(sw).writeRecordFooter(
            4, sender(), 1L, reason, "REF1", patient(), "130", listOf(560011L), 2539L, 3164L, 0L,
            false, null, null, null
        )
        return sw.toString().also { assertThat(it).hasSize(350) }
    }

    private fun zone17(record: String, description: org.taktik.freehealth.middleware.format.efact.segments.RecordOrSegmentDescription): String {
        val zd = description.zoneDescriptionsByZone["17"]!!
        return record.substring(zd.position - 1, zd.position - 1 + zd.length)
    }

    @Test
    fun theFooterRepeatsTheHeaderForEveryReason() {
        for (reason in InvoicingTreatmentReasonCode.values()) {
            assertThat(zone17(record80(reason), Record80Description))
                .describedAs(reason.name)
                .isEqualTo(zone17(record20(reason), Record20Description))
        }
    }

    @Test
    fun theZoneCarriesTheFourPositionCode() {
        assertThat(zone17(record80(InvoicingTreatmentReasonCode.WorkAccident), Record80Description)).isEqualTo("0070")
        assertThat(zone17(record80(InvoicingTreatmentReasonCode.OtherAccident), Record80Description)).isEqualTo("0090")
        assertThat(zone17(record80(InvoicingTreatmentReasonCode.Other), Record80Description)).isEqualTo("0000")
    }

    @Test
    fun onlyTheZoneAndTheChecksumMoveWithTheReason() {
        // The reason Other is what every batch sent so far carries: outside zone 17 and the two trailing checksum
        // positions, a footer written for another reason is the same record.
        val zd = Record80Description.zoneDescriptionsByZone["17"]!!
        fun masked(record: String) =
            record.substring(0, zd.position - 1) + record.substring(zd.position - 1 + zd.length, 348)

        assertThat(masked(record80(InvoicingTreatmentReasonCode.WorkAccident)))
            .isEqualTo(masked(record80(InvoicingTreatmentReasonCode.Other)))
    }
}
