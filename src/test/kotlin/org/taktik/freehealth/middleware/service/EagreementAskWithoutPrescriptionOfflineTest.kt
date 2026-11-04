package org.taktik.freehealth.middleware.service

import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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

    private fun ask(
        prescription: String?,
        prescriber: String?,
        requestType: EagreementServiceImpl.RequestTypeEnum = EagreementServiceImpl.RequestTypeEnum.ASK,
        eventCode: String = "claim-ask",
        insuranceRef: String? = null,
        prescriptionDate: DateTime? = prescription?.let { DateTime(2026, 5, 9, 0, 0) }
    ): ObjectNode = utils.getBundleJSON(
        requestType = requestType,
        messageFocusReference = "Claim/Claim1",
        messageEventSystem = EagreementServiceImpl.MessageEventSystemEnum.MESSAGE_EVENTS,
        messageEventCode = eventCode,
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
        insuranceRef = insuranceRef,
        pathologyCode = "fa-6",
        pathologyStartDate = DateTime(2026, 5, 3, 0, 0),
        sctCode = prescription?.let { "91251008" },
        sctDisplay = prescription?.let { "Physical therapy procedure" },
        subTypeCode = null,
        attachments = null,
        prescriptionDate = prescriptionDate
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

    private fun argue(
        prescription: String?,
        prescriber: String?,
        prescriptionDate: DateTime? = prescription?.let { DateTime(2026, 5, 9, 0, 0) }
    ) = ask(
        prescription, prescriber,
        requestType = EagreementServiceImpl.RequestTypeEnum.ARGUE,
        eventCode = "claim-argue",
        insuranceRef = "12345678901234567890",
        prescriptionDate = prescriptionDate
    )

    /**
     * A claim-argue answers a request for information with "texte libre, copie de prescription ou autre annexe"
     * (1-FR-RULES-EAGR-KINE): the prescription is one option among three, and Claim.referral is 0..1. It used
     * to be a 400 for want of `prescription1`. Same shape as the ask — no ServiceRequest at all — because a
     * ServiceRequest without its PDF breaks mcn-2 of be-eagreementservicerequest.
     */
    @Test
    fun anArgueWithoutAPrescriptionHasNoServiceRequestAndNoReferral() {
        val resources = resources(argue(prescription = null, prescriber = null))

        assertThat(resources.map { it.first }).doesNotContain("ServiceRequest")
        val claim = resources.single { it.first == "Claim" }.second
        assertThat(claim.has("referral")).isFalse
        assertThat(claim.get("insurance").first().get("preAuthRef").first().asText()).isEqualTo("12345678901234567890")
    }

    @Test
    fun anArgueWithAPrescriptionKeepsItsServiceRequestAndReferral() {
        val bundle = argue(prescription = "JVBERi0=", prescriber = "00000000004")
        val entries = bundle.get("Bundle").get("entry").toList()
        val serviceRequest = entries.single { it.get("resource").has("ServiceRequest") }
        val claim = entries.single { it.get("resource").has("Claim") }.get("resource").get("Claim")

        assertThat(serviceRequest.get("resource").get("ServiceRequest").has("contained")).isTrue
        assertThat(claim.get("referral").get("reference").asText()).isEqualTo(serviceRequest.get("fullUrl").asText())
    }

    /** Extend keeps the requirement — and it is a 400 before anything is sent. */
    @Test
    fun anExtendWithoutAPrescriptionIsStillRefused() {
        assertThatThrownBy {
            ask(
                prescription = null, prescriber = null,
                requestType = EagreementServiceImpl.RequestTypeEnum.EXTEND,
                eventCode = "claim-extend",
                insuranceRef = "12345678901234567890"
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("prescription1")
    }

    /**
     * /argueAgreement no longer requires the prescriber nor the prescription date. With a prescription1 they are
     * still required — by the builder now, before anything is sent: the ServiceRequest's requester is
     * PractitionerRole2, which only exists with a prescriber, and authoredOn would otherwise become today.
     */
    @Test
    fun anArgueWithAPrescriptionButNoPrescriberIsRefused() {
        assertThatThrownBy { argue(prescription = "JVBERi0=", prescriber = null) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("prescriberNihii")
    }

    @Test
    fun anArgueWithAPrescriptionButNoPrescriptionDateIsRefused() {
        assertThatThrownBy { argue(prescription = "JVBERi0=", prescriber = "00000000004", prescriptionDate = null) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("prescriptionDate")
    }

    /** All or nothing, the other way round too: a prescriber without prescription1 would leave orphan Practitioner/2 entries. */
    @Test
    fun anArgueWithAPrescriberButNoPrescriptionIsRefused() {
        assertThatThrownBy { argue(prescription = null, prescriber = "00000000004") }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("requires a prescription1")
        assertThatThrownBy { argue(prescription = null, prescriber = null, prescriptionDate = DateTime(2026, 5, 9, 0, 0)) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("requires a prescription1")
    }
}
