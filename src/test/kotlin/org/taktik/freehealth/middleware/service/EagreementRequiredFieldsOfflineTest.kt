package org.taktik.freehealth.middleware.service

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.joda.time.DateTime
import org.junit.Test
import org.taktik.freehealth.middleware.service.impl.EagreementServiceImpl.MessageEventSystemEnum
import org.taktik.freehealth.middleware.service.impl.EagreementServiceImpl.RequestTypeEnum
import org.taktik.freehealth.middleware.service.impl.EagreementServiceUtilsImpl

/**
 * Optional parameters of the write routes that the bundle builder dereferenced with `!!`: a bare 500
 * NullPointerException naming nothing. The bundle is built before the request leaves, so an
 * IllegalArgumentException naming the parameter (400 through EagreementController) is safe.
 */
class EagreementRequiredFieldsOfflineTest {
    private val utils = EagreementServiceUtilsImpl()

    private fun bundle(
        requestType: RequestTypeEnum,
        prescription: String? = "JVBERi0xLjQK",
        sessions: Float? = 18f,
        agreementType: String? = "physiotherapy-fa",
        agreementStartDate: DateTime? = DateTime(2026, 5, 14, 0, 0)
    ) = utils.getBundleJSON(
        requestType = requestType,
        messageFocusReference = "Claim/Claim1",
        messageEventSystem = MessageEventSystemEnum.MESSAGE_EVENTS,
        messageEventCode = requestType.requestType,
        patientFirstName = "Patient",
        patientLastName = "Test",
        patientGender = "male",
        patientSsin = "00000000097",
        patientIo = null,
        patientIoMembership = null,
        hcpNihii = "00000000527",
        hcpFirstName = "Kine",
        hcpLastName = "Test",
        prescriberNihii = "11111111111",
        prescriberFirstName = "Medecin",
        prescriberLastName = "Test",
        orgNihii = null,
        organizationType = null,
        prescription1 = prescription,
        prescription2 = null,
        agreementStartDate = agreementStartDate,
        agreementEndDate = null,
        agreementType = agreementType,
        numberOfSessionForPrescription1 = sessions,
        numberOfSessionForPrescription2 = null,
        insuranceRef = "22222222222",
        pathologyCode = "fa-6",
        pathologyStartDate = DateTime(2026, 5, 3, 0, 0),
        sctCode = "91251008",
        sctDisplay = "Physical therapy procedure",
        subTypeCode = null,
        attachments = null,
        prescriptionDate = DateTime(2026, 5, 9, 0, 0)
    )

    @Test
    fun anExtensionWithoutStartDateNamesIt() {
        assertThatThrownBy { bundle(RequestTypeEnum.EXTEND, agreementStartDate = null) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessage("agreementStartDate is required for EXTEND")
    }

    @Test
    fun anExtensionWithoutPrescriptionNamesIt() {
        assertThatThrownBy { bundle(RequestTypeEnum.EXTEND, prescription = null) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessage("a prescription1 attachment is required for EXTEND")
    }

    @Test
    fun aPrescriptionWithoutItsSessionCountNamesIt() {
        assertThatThrownBy { bundle(RequestTypeEnum.ASK, sessions = null) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessage("numberOfSessionForPrescription1 is required with a prescription")
    }

    @Test
    fun aCancellationWithoutAgreementTypeNamesIt() {
        assertThatThrownBy { bundle(RequestTypeEnum.CANCEL, agreementType = null) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessage("agreementType is required for CANCEL")
    }
}
