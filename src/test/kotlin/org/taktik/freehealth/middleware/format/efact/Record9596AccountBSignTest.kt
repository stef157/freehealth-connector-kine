package org.taktik.freehealth.middleware.format.efact

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.taktik.freehealth.middleware.format.efact.segments.Segment400Record95Description
import org.taktik.freehealth.middleware.format.efact.segments.Segment500Record96Description
import java.io.StringWriter

/**
 * ET 95 Z 405 and ET 96 Z 505, the sign of account B, follow the sign of the record - offline, no eHealth call.
 *
 * They were blank for a week, on the reading of "FR 920000 v02 r03 Fichier Facture.docx" (ZONE 405 reserved to
 * hospital institutions, control 15). The first real answer refuted that reading: OA 100 returned 920999 on a kine
 * file (acceptance, 07/10/2026, send 001) with Z 4061 = 40 "Erreur code signe (# de + ou -)" and Z 5061 = 20, both
 * zones having been written blank. The sign is the record's own (annex 7 section 2.c: one sign per record), so a
 * pure credit note carries "-" on both; the amount that follows (Z 406 / Z 506) stays 0.
 */
class Record9596AccountBSignTest {
    private fun zone(record: String, description: Map<String, org.taktik.freehealth.middleware.format.efact.segments.ZoneDescription>, zone: String): String {
        val zd = description[zone]!!
        return record.substring(zd.position - 1, zd.position - 1 + zd.length)
    }

    private fun record95(amount: Long): String {
        val sw = StringWriter()
        BelgianInsuranceInvoicingFormatWriter(sw).write400("319", 1L, 3L, listOf(560011L), amount)
        return sw.toString().also { assertThat(it).hasSize(350) }
    }

    private fun record96(amount: Long): String {
        val sw = StringWriter()
        BelgianInsuranceInvoicingFormatWriter(sw).write960000("300", 5L, listOf(560011L), amount)
        return sw.toString().also { assertThat(it).hasSize(350) }
    }

    @Test
    fun theAccountBSignFollowsTheRecordOnBothBordereaux() {
        for ((amount, sign) in listOf(2539L to "+", -2914L to "-", 0L to "+")) {
            assertThat(zone(record95(amount), Segment400Record95Description.zoneDescriptionsByZone, "405")).describedAs("95, $amount").isEqualTo(sign)
            assertThat(zone(record96(amount), Segment500Record96Description.zoneDescriptionsByZone, "505")).describedAs("96, $amount").isEqualTo(sign)
        }
    }

    @Test
    fun theAccountCSignFollowsTheRecordOnBothBordereaux() {
        for ((amount, sign) in listOf(2539L to "+", -2914L to "-", 0L to "+")) {
            assertThat(zone(record95(amount), Segment400Record95Description.zoneDescriptionsByZone, "411")).describedAs("95, $amount").isEqualTo(sign)
            assertThat(zone(record96(amount), Segment500Record96Description.zoneDescriptionsByZone, "511")).describedAs("96, $amount").isEqualTo(sign)
        }
    }

    @Test
    fun theAccountBAmountStaysZero() {
        assertThat(zone(record95(2539L), Segment400Record95Description.zoneDescriptionsByZone, "406")).isEqualTo("00000000000")
        assertThat(zone(record96(2539L), Segment500Record96Description.zoneDescriptionsByZone, "506")).isEqualTo("00000000000")
    }

    @Test
    fun theAccountASignStillFollowsTheAmount() {
        assertThat(zone(record95(2539L), Segment400Record95Description.zoneDescriptionsByZone, "403")).isEqualTo("+")
        assertThat(zone(record95(-2914L), Segment400Record95Description.zoneDescriptionsByZone, "403")).isEqualTo("-")
        assertThat(zone(record96(-2914L), Segment500Record96Description.zoneDescriptionsByZone, "503")).isEqualTo("-")
    }
}
