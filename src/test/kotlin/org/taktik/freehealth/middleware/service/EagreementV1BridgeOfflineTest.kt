package org.taktik.freehealth.middleware.service

import be.fgov.ehealth.mycarenet.agreement.protocol.v2.AskAgreementRequest
import be.fgov.ehealth.mycarenet.agreement.protocol.v2.AskAgreementResponse
import be.fgov.ehealth.mycarenet.commons.core.v4.ActorType
import be.fgov.ehealth.mycarenet.commons.core.v4.BlobType
import be.fgov.ehealth.mycarenet.commons.core.v4.CareProviderType
import be.fgov.ehealth.mycarenet.commons.core.v4.CommonInputType
import be.fgov.ehealth.mycarenet.commons.core.v4.LicenseType
import be.fgov.ehealth.mycarenet.commons.core.v4.NihiiType
import be.fgov.ehealth.mycarenet.commons.core.v4.OriginType
import be.fgov.ehealth.mycarenet.commons.core.v4.PackageType
import be.fgov.ehealth.mycarenet.commons.core.v4.RequestType
import be.fgov.ehealth.mycarenet.commons.core.v4.RoutingType
import be.fgov.ehealth.mycarenet.commons.core.v4.ValueRefString
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.joda.time.DateTime
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.taktik.connector.business.agreement.service.impl.AgreementV1Bridge
import org.taktik.freehealth.middleware.service.impl.EagreementServiceImpl
import java.io.File
import java.io.StringReader
import java.util.GregorianCalendar
import javax.xml.XMLConstants
import javax.xml.datatype.DatatypeFactory
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import be.fgov.ehealth.agreement.protocol.v1.AskAgreementRequest as V1AskAgreementRequest
import be.fgov.ehealth.agreement.protocol.v1.AskAgreementResponse as V1AskAgreementResponse
import be.fgov.ehealth.mycarenet.commons.core.v3.BlobType as V3BlobType
import be.fgov.ehealth.mycarenet.commons.core.v3.CommonOutputType as V3CommonOutputType
import be.fgov.ehealth.mycarenet.commons.protocol.v3.ResponseReturnType as V3ResponseReturnType

/**
 * eAgreement over the **v1** web service (`MyCareNet-eAgreement_v1_1`, the physiotherapist contract) — offline.
 *
 * The request the middleware builds with the v2 types must reach the wire as a v1 request: protocol `v1`, commons `v3`,
 * and no `MessageVersion`, which exists only in commons v4 (eAgreement v2). Set `EAGREEMENT_V1_XSD` to the package's
 * `ehealth-mycarenet-agreement/XSD/mycarenet-agreement-protocol-1_1.xsd` to also validate against the official schema;
 * the package is eHealth's and is not redistributed here.
 */
class EagreementV1BridgeOfflineTest {
    private val detailContent = "chiffré".toByteArray(Charsets.UTF_8)

    private fun v2Request(): AskAgreementRequest = AskAgreementRequest().apply {
        commonInput = CommonInputType().apply {
            request = RequestType().apply { isIsTest = true }
            inputReference = "IR-1"
            origin = OriginType().apply {
                `package` = PackageType().apply {
                    license = LicenseType().apply { username = "user"; password = "secret" }
                    name = ValueRefString().apply { value = "kinedesk" }
                }
                careProvider = CareProviderType().apply {
                    nihii = NihiiType().apply { quality = "physiotherapist"; value = ValueRefString().apply { value = "00000000000" } }
                }
            }
        }
        routing = RoutingType().apply {
            careReceiver = EagreementServiceImpl.careReceiverFor("00000000097", null, null)
            referenceDate = DatatypeFactory.newInstance().newXMLGregorianCalendar(GregorianCalendar())
        }
        detail = BlobType().apply {
            value = detailContent
            id = "blob1"
            contentEncoding = "none"
            contentType = "text/xml"
            contentEncryption = "encryptedForKnownBED"
        }
        id = "_id1"
        issueInstant = DateTime()
    }

    @Test
    fun theRequestGoesOutInTheV1AndV3Namespaces() {
        val xml = AgreementV1Bridge.v1Xml(v2Request())
        assertThat(xml).contains(AgreementV1Bridge.AGREEMENT_V1, AgreementV1Bridge.CORE_V3)
        assertThat(xml).doesNotContain(AgreementV1Bridge.AGREEMENT_V2, AgreementV1Bridge.CORE_V4, AgreementV1Bridge.PROTOCOL_V4)
        assertThat(xml).doesNotContain("MessageVersion")
    }

    @Test
    fun theV1RequestKeepsWhatWasBuilt() {
        val v1 = AgreementV1Bridge.toV1(v2Request(), V1AskAgreementRequest::class.java)
        assertThat(v1.routing.careReceiver.ssin).isEqualTo("00000000097")
        assertThat(v1.commonInput.origin.careProvider.nihii.value.value).isEqualTo("00000000000")
        assertThat(v1.commonInput.origin.`package`.name.value).isEqualTo("kinedesk")
        assertThat(v1.commonInput.inputReference).isEqualTo("IR-1")
        assertThat(v1.detail.value).isEqualTo(detailContent)
        assertThat(v1.detail.contentEncryption).isEqualTo("encryptedForKnownBED")
    }

    @Test
    fun messageVersionIsRefusedBecauseItIsV2Only() {
        val request = v2Request().apply { detail.messageVersion = EagreementServiceImpl.MESSAGE_VERSION }
        assertThat(AgreementV1Bridge.v4OnlyContent(request)).containsExactly("Detail/@MessageVersion")
        assertThatThrownBy { AgreementV1Bridge.v1Xml(request) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("MessageVersion")
    }

    @Test
    fun theOtherV4OnlyContentIsRefusedToo() {
        val request = v2Request().apply {
            detail.reference = "r"
            commonInput.origin.actor.add(ActorType())
        }
        assertThat(AgreementV1Bridge.v4OnlyContent(request)).containsExactly("Detail/@Reference", "CommonInput/Origin/Actor")
    }

    @Test
    fun theV1ResponseComesBackAsAV2Response() {
        val v1Response = V1AskAgreementResponse().apply {
            `return` = V3ResponseReturnType().apply {
                commonOutput = V3CommonOutputType().apply { inputReference = "IR-1"; nipReference = "NIP-9"; outputReference = "OR-2" }
                detail = V3BlobType().apply { value = detailContent; id = "blob1"; contentEncoding = "none"; contentType = "text/xml" }
            }
        }
        val v2 = AgreementV1Bridge.toV2(v1Response, AskAgreementResponse::class.java)
        assertThat(v2.`return`.commonOutput.nipReference).isEqualTo("NIP-9")
        assertThat(v2.`return`.commonOutput.outputReference).isEqualTo("OR-2")
        assertThat(v2.`return`.detail.value).isEqualTo(detailContent)
    }

    @Test
    fun v1OnlyWhenAskedForExplicitly() {
        assertThat(EagreementServiceImpl.agreementVersionOf(null)).isEqualTo("v2")
        assertThat(EagreementServiceImpl.agreementVersionOf("")).isEqualTo("v2")
        assertThat(EagreementServiceImpl.agreementVersionOf("v3")).isEqualTo("v2")
        assertThat(EagreementServiceImpl.agreementVersionOf("v1")).isEqualTo("v1")
        assertThat(EagreementServiceImpl.agreementVersionOf(" V1 ")).isEqualTo("v1")
    }

    @Test
    fun theV1RequestIsValidAgainstTheOfficialSchema() {
        val xsd = System.getenv("EAGREEMENT_V1_XSD")?.let(::File)
        assumeTrue("EAGREEMENT_V1_XSD not set", xsd != null && xsd.isFile)
        val schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(xsd)
        schema.newValidator().validate(StreamSource(StringReader(AgreementV1Bridge.v1Xml(v2Request()))))
    }
}
