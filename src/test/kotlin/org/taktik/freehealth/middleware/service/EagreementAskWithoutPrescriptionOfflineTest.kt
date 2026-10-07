package org.taktik.freehealth.middleware.service

import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.joda.time.DateTime
import org.junit.Test
import org.taktik.freehealth.middleware.service.impl.EagreementServiceImpl
import org.taktik.freehealth.middleware.service.impl.EagreementServiceUtilsImpl

/**
 * A claim-ask without a prescription — step 6.1.1 of the CIN test procedure (FR-MPTI-EAGR-KIN V2.0), which
 * expects the insurer to answer MISSING_PRESCRIPTION_IN_PHYSIO_CLAIM: "Claim/referral: l'élément n'est pas
 * présent dans le claim". The builder used to dereference the absent prescription.
 */
class EagreementAskWithoutPrescriptionOfflineTest {
    private val utils = EagreementServiceUtilsImpl()

    private fun ask(prescription: String?, prescriber: String?): ObjectNode = utils.getBundleJSON(
        requestType = EagreementServiceImpl.RequestTypeEnum.ASK,
        messageFocusReference = "Claim/Claim1",
        messageEventSystem = EagreementServiceImpl.MessageEventSystemEnum.MESSAGE_EVENTS,
        messageEventCode = "claim-ask",
        patientFirstName = "Patient",
        patientLastName = "Test",
        patientGender = "male",
        patientSsin = "00000000097",
        patientIo = null,
        patientIoMembership = null,
        hcpNihii = "00000000527",
        hcpFirstName = "Kine",
        hcpLastName = "Test",
        prescriberNihii = prescriber,
        prescriberFirstName = prescriber?.let { "Medecin" },
        prescriberLastName = prescriber?.let { "Test" },
        orgNihii = null,
        organizationType = null,
        prescription1 = prescription,
        prescription2 = null,
        agreementStartDate = DateTime(2026, 5, 14, 0, 0),
        agreementEndDate = null,
        agreementType = "physiotherapy-fa",
        numberOfSessionForPrescription1 = prescription?.let { 18f },
        numberOfSessionForPrescription2 = null,
        insuranceRef = null,
        pathologyCode = "fa-6",
        pathologyStartDate = DateTime(2026, 5, 3, 0, 0),
        sctCode = prescription?.let { "91251008" },
        sctDisplay = prescription?.let { "Physical therapy procedure" },
        subTypeCode = null,
        attachments = null,
        prescriptionDate = prescription?.let { DateTime(2026, 5, 9, 0, 0) }
    )!!

    private fun resources(bundle: ObjectNode) =
        bundle.get("Bundle").get("entry").map { it.get("resource").fieldNames().next() to it.get("resource").elements().next() }

    @Test
    fun withoutAPrescriptionThereIsNoServiceRequestAndNoReferral() {
        val resources = resources(ask(prescription = null, prescriber = null))

        assertThat(resources.map { it.first }).doesNotContain("ServiceRequest")
        val claim = resources.single { it.first == "Claim" }.second
        assertThat(claim.has("referral")).isFalse
        // The prescriber travels with the prescription: neither its Practitioner nor its role remains.
        assertThat(resources.count { it.first == "Practitioner" }).isEqualTo(1)
        assertThat(resources.count { it.first == "PractitionerRole" }).isEqualTo(1)
    }

    @Test
    fun withAPrescriptionTheReferralPointsAtTheServiceRequestEntry() {
        val bundle = ask(prescription = "JVBERi0=", prescriber = "00000000004")
        val entries = bundle.get("Bundle").get("entry").toList()
        val serviceRequest = entries.single { it.get("resource").has("ServiceRequest") }
        val claim = entries.single { it.get("resource").has("Claim") }.get("resource").get("Claim")

        assertThat(claim.get("referral").get("reference").asText()).isEqualTo(serviceRequest.get("fullUrl").asText())
    }
}
