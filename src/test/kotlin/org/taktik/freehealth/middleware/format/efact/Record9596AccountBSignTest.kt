package org.taktik.freehealth.middleware.format.efact

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.taktik.freehealth.middleware.format.efact.segments.Segment400Record95Description
import org.taktik.freehealth.middleware.format.efact.segments.Segment500Record96Description
import java.io.StringWriter

/**
 * ET 95 Z 405 and ET 96 Z 505, the sign of account B, are blank - offline, no eHealth call.
 *
 * "FR 920000 v02 r03 Fichier Facture.docx", ZONE 405: "Uniquement reserve aux institutions hospitalieres /
 * Valeurs permises : + -", control 15 "Zone # de blanc et emetteur de la facturation # d'une institution
 * hospitaliere"; ZONE 505: "Zone reservee pour les institutions hospitalieres", same code 15. This writer has no
 * hospital sender (InvoiceSender carries no such notion), so the zone is always blank; the amount that follows
 * (Z 406 / Z 506) stays 0, as its own control 15 requires. The writer used to put "+" in both.
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
    fun theAccountBSignIsBlankOnBothBordereaux() {
        for (amount in listOf(2539L, -2914L, 0L)) {
            assertThat(zone(record95(amount), Segment400Record95Description.zoneDescriptionsByZone, "405")).describedAs("95, $amount").isEqualTo(" ")
            assertThat(zone(record96(amount), Segment500Record96Description.zoneDescriptionsByZone, "505")).describedAs("96, $amount").isEqualTo(" ")
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
