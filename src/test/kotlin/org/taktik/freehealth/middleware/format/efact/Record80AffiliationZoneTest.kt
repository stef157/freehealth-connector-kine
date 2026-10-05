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
 * ET 80 Z 7 repeats ET 20 Z 7 - offline, no eHealth call.
 *
 * Reject 800712 (nature F), "Contenu different de celui dans l'ET 20 Z 7", has no exemption for the national
 * unions 2, 4 and 5. The INAMI instructions (edition 2021) state "ZONE 7 : Toujours egale a 000" for the U.N.M.N.
 * (200), the ML (400) and the M.L.O.Z. (500); writeRecordHeader applies it to 2, 4 and 5, and the footer did so for
 * 2 and 5 only - an OA 400 invoice went out with 000 in ET 20 and its affiliation in ET 80.
 */
class Record80AffiliationZoneTest {
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

    private fun zone7(record: String, position: Int, length: Int) = record.substring(position - 1, position - 1 + length)

    private fun record20Zone7(insuranceCode: String): String {
        val sw = StringWriter()
        BelgianInsuranceInvoicingFormatWriter(sw).writeRecordHeader(
            2, sender(), 1L, InvoicingTreatmentReasonCode.Other, "REF1", patient(), insuranceCode,
            false, false, false, null, null, null, null, false, null, null, null, null
        )
        val zd = Record20Description.zoneDescriptionsByZone["7"]!!
        return zone7(sw.toString().also { assertThat(it).hasSize(350) }, zd.position, zd.length)
    }

    private fun record80Zone7(insuranceCode: String): String {
        val sw = StringWriter()
        BelgianInsuranceInvoicingFormatWriter(sw).writeRecordFooter(
            4, sender(), 1L, InvoicingTreatmentReasonCode.Other, "REF1", patient(), insuranceCode, listOf(560011L), 2539L, 3164L, 0L,
            false, null, null, null
        )
        val zd = Record80Description.zoneDescriptionsByZone["7"]!!
        return zone7(sw.toString().also { assertThat(it).hasSize(350) }, zd.position, zd.length)
    }

    @Test
    fun anOa400InvoiceRepeatsTheHeaderZeroInItsFooter() {
        assertThat(record20Zone7("414")).isEqualTo("000")
        assertThat(record80Zone7("414")).isEqualTo("000")
    }

    @Test
    fun theFooterAlwaysRepeatsTheHeader() {
        for (code in listOf("227", "414", "509", "130", "319", "306", "604", "910")) {
            assertThat(record80Zone7(code)).describedAs(code).isEqualTo(record20Zone7(code))
        }
    }

    @Test
    fun otherInsurersKeepTheirAffiliation() {
        assertThat(record80Zone7("130")).isEqualTo("130")
        assertThat(record80Zone7("319")).isEqualTo("319")
        assertThat(record80Zone7("227")).isEqualTo("000")
        assertThat(record80Zone7("509")).isEqualTo("000")
    }
}
