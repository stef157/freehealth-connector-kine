package org.taktik.connector.business.agreement.service.impl

import be.fgov.ehealth.mycarenet.agreement.protocol.v2.AskAgreementRequest
import be.fgov.ehealth.mycarenet.agreement.protocol.v2.AskAgreementResponse
import be.fgov.ehealth.mycarenet.agreement.protocol.v2.ConsultAgreementRequest
import be.fgov.ehealth.mycarenet.agreement.protocol.v2.ConsultAgreementResponse
import be.fgov.ehealth.mycarenet.commons.protocol.v4.SendRequestType
import jakarta.xml.bind.JAXBContext
import org.taktik.connector.business.agreement.service.AgreementService as V1AgreementService
import org.taktik.connector.business.agreementv2.service.AgreementService
import org.taktik.connector.technical.service.sts.security.SAMLToken
import java.io.StringReader
import java.io.StringWriter
import be.fgov.ehealth.agreement.protocol.v1.AskAgreementRequest as V1AskAgreementRequest
import be.fgov.ehealth.agreement.protocol.v1.AskAgreementResponse as V1AskAgreementResponse
import be.fgov.ehealth.agreement.protocol.v1.ConsultAgreementRequest as V1ConsultAgreementRequest
import be.fgov.ehealth.agreement.protocol.v1.ConsultAgreementResponse as V1ConsultAgreementResponse

/**
 * Sends an eAgreement request built with the v2 types over the **v1** web service.
 *
 * The physiotherapist contract is the eHealth package `MyCareNet-eAgreement_v1_1` (protocol `v1`, mycarenet commons
 * `v3`, `…/MyCareNet/eAgreement/v1`): eHealth publishes "eAgreement V1 est destiné aux kinésithérapeutes", and the
 * caller decided on 03/11/2026 that this package is the source. The two WSDLs differ only by namespace, SOAP action and
 * address; the payload types differ only by the commons version, and commons v4 is a superset of v3. So the request the
 * middleware already builds is translated, not rebuilt: the three namespaces are rewritten and the result is read back
 * into the v1 JAXB classes. The response takes the same road back.
 *
 * What v4 has and v3 does not is **refused, never dropped**: `MessageVersion`, `Reference` and `Issuer` on the Detail,
 * `Actor` in the origin, `Reference`/`Attribute` in the common input, `Subject` in the routing. `MessageVersion` is
 * eAgreement v2 only — the v1 schema has no such attribute — so a request that carries it was built for the wrong
 * service, and saying so beats sending something the XSD rejects.
 */
class AgreementV1Bridge(private val v1: V1AgreementService = AgreementServiceImpl()) : AgreementService {

    override fun askAgreement(samlToken: SAMLToken, askAgreementRequest: AskAgreementRequest?): AskAgreementResponse? {
        val request = toV1(requireNotNull(askAgreementRequest), V1AskAgreementRequest::class.java)
        val response = v1.askAgreement(samlToken, request) ?: return null
        return toV2(response, AskAgreementResponse::class.java).apply {
            upstreamTiming = response.upstreamTiming
            soapRequest = response.soapRequest
            soapResponse = response.soapResponse
        }
    }

    override fun consultAgreement(samlToken: SAMLToken, consultAgreementRequest: ConsultAgreementRequest?): ConsultAgreementResponse? {
        val request = toV1(requireNotNull(consultAgreementRequest), V1ConsultAgreementRequest::class.java)
        val response = v1.consultAgreement(samlToken, request) ?: return null
        return toV2(response, ConsultAgreementResponse::class.java).apply {
            upstreamTiming = response.upstreamTiming
            soapRequest = response.soapRequest
            soapResponse = response.soapResponse
        }
    }

    companion object {
        const val AGREEMENT_V1 = "urn:be:fgov:ehealth:mycarenet:agreement:protocol:v1"
        const val AGREEMENT_V2 = "urn:be:fgov:ehealth:mycarenet:agreement:protocol:v2"
        const val CORE_V3 = "urn:be:fgov:ehealth:mycarenet:commons:core:v3"
        const val CORE_V4 = "urn:be:fgov:ehealth:mycarenet:commons:core:v4"
        const val PROTOCOL_V3 = "urn:be:fgov:ehealth:mycarenet:commons:protocol:v3"
        const val PROTOCOL_V4 = "urn:be:fgov:ehealth:mycarenet:commons:protocol:v4"

        private val V2_TO_V1 = listOf(AGREEMENT_V2 to AGREEMENT_V1, CORE_V4 to CORE_V3, PROTOCOL_V4 to PROTOCOL_V3)

        /** The v4-only content a v1 request cannot carry; empty when the request is translatable. */
        @JvmStatic
        fun v4OnlyContent(request: SendRequestType): List<String> = buildList {
            request.detail?.let {
                if (it.messageVersion != null) add("Detail/@MessageVersion")
                if (it.reference != null) add("Detail/@Reference")
                if (it.issuer != null) add("Detail/@Issuer")
            }
            request.commonInput?.let {
                if (it.reference.isNotEmpty()) add("CommonInput/Reference")
                if (it.attribute.isNotEmpty()) add("CommonInput/Attribute")
                if (it.origin?.actor?.isNotEmpty() == true) add("CommonInput/Origin/Actor")
            }
            if (request.routing?.subject != null) add("Routing/Subject")
        }

        /** The v1 XML of a v2 request, as it goes on the wire. Public for the offline tests. */
        @JvmStatic
        fun v1Xml(request: SendRequestType): String {
            val refused = v4OnlyContent(request)
            check(refused.isEmpty()) { "eAgreement v1 (commons v3) cannot carry ${refused.joinToString()}: these are v2-only" }
            return V2_TO_V1.fold(marshal(request)) { xml, (from, to) -> xml.replace(from, to) }
        }

        @JvmStatic
        fun <T> toV1(request: SendRequestType, v1Class: Class<T>): T = unmarshal(v1Xml(request), v1Class)

        @JvmStatic
        fun <T> toV2(v1Response: Any, v2Class: Class<T>): T =
            unmarshal(V2_TO_V1.fold(marshal(v1Response)) { xml, (to, from) -> xml.replace(from, to) }, v2Class)

        private fun marshal(value: Any): String =
            StringWriter().also { JAXBContext.newInstance(value.javaClass).createMarshaller().marshal(value, it) }.toString()

        private fun <T> unmarshal(xml: String, type: Class<T>): T =
            type.cast(JAXBContext.newInstance(type).createUnmarshaller().unmarshal(StringReader(xml)))
    }
}
