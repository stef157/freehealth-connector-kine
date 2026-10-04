package org.taktik.freehealth.middleware.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.taktik.freehealth.middleware.service.impl.EagreementServiceUtilsImpl

/**
 * `Patient.identifier` of an eAgreement request, per the CIN DATA definition (eAgreement kine space,
 * `3-EN-DATA-EAGR-KINE`, sheet TECH_Sync Requests, rows 76-83): the SSIN prevails; the insurer, when
 * given, travels as `assigner` — mandatory for argue, which Kiné-Desk sends with SSIN + insurer.
 */
class EagreementPatientIdentifierOfflineTest {
    private val utils = EagreementServiceUtilsImpl()
    private val ssinSystem = "https://www.ehealth.fgov.be/standards/fhir/core/NamingSystem/ssin"

    @Test
    fun anSsinWithAnInsurerCarriesTheInsurerAsAssigner() {
        val id = utils.patientIdentifier("00000000097", "300", null)
        assertThat(id.system).isEqualTo(ssinSystem)
        assertThat(id.value).isEqualTo("00000000097")
        assertThat(id.assigner?.identifier?.value).isEqualTo("300")
    }

    @Test
    fun anSsinAloneHasNoAssigner() {
        val id = utils.patientIdentifier("00000000097", null, null)
        assertThat(id.value).isEqualTo("00000000097")
        assertThat(id.assigner).isNull()
    }

    @Test
    fun aRegistrationNumberCarriesItsInsurer() {
        val id = utils.patientIdentifier(null, "300", "0123456")
        assertThat(id.system).isNotEqualTo(ssinSystem)
        assertThat(id.value).isEqualTo("0123456")
        assertThat(id.assigner?.identifier?.value).isEqualTo("300")
    }

    @Test
    fun theSsinPrevailsOverARegistrationNumber() {
        val id = utils.patientIdentifier("00000000097", "300", "0123456")
        assertThat(id.value).isEqualTo("00000000097")
        assertThat(id.assigner?.identifier?.value).isEqualTo("300")
    }
}
