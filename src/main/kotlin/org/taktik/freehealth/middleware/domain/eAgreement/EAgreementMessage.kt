package org.taktik.freehealth.middleware.domain.eAgreement

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import be.cin.types.v1.FaultType
import org.taktik.freehealth.middleware.domain.memberdata.MemberDataBatchResponse
import org.taktik.freehealth.middleware.dto.mycarenet.CommonOutput
import org.taktik.freehealth.middleware.dto.mycarenet.MycarenetError

@JsonIgnoreProperties(ignoreUnknown = true)
class EAgreementMessage (
    var commonOutput: CommonOutput? = null,
    var complete: Boolean? = false,
    var errors: List<MycarenetError>? = null,
    var genericErrors: List<FaultType>? = null,
    var eagreementResponse: List<EAgreementBatchResponse>? = null,
    var io: String? = null,
    var appliesTo: String? = null,
    var reference: String? = null,
    var valueHash: String? = null,
    /**
     * The XAdES of the insurer's message — `EncryptedKnownContent.xades` ([xades]) and the genericAsync
     * `MsgResponse.xadesT` ([xadesT]). The CIN workbook gives EAGREEMENT-RSP a probative force of
     * "Xades-T without Manifest"; which of the two MyCareNet fills has not been observed yet, so both are
     * returned as they arrive (base64 in JSON), unverified. Null on a message that could not be decoded.
     */
    var xades: ByteArray? = null,
    var xadesT: ByteArray? = null
)
