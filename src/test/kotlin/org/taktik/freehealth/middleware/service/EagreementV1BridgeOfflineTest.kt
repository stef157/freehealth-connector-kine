package org.taktik.freehealth.middleware.service

import be.fgov.ehealth.mycarenet.agreement.protocol.v2.AskAgreementRequest
import be.fgov.ehealth.mycarenet.agreement.protocol.v2.AskAgreementResponse
import be.fgov.ehealth.mycarenet.commons.core.v4.ActorType
import be.fgov.ehealth.mycarenet.commons.core.v4.BlobType
import be.fgov.ehealth.mycarenet.commons.core.v4.CareProviderType
import be.fgov.ehealth.mycarenet.commons.core.v4.CommonInputType
import be.fgov.ehealth.mycarenet.commons.core.v4.IdType
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
import java.util.TimeZone
import jakarta.xml.bind.JAXBContext
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
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
                siteID = ValueRefString().apply { value = "site-1" }
                // Populated as `EagreementServiceImpl.askAgreement` builds it: IdType's Nihii is mandatory in v3.
                careProvider = CareProviderType().apply {
                    nihii = NihiiType().apply { quality = "physiotherapist"; value = ValueRefString().apply { value = "00000000000" } }
                    physicalPerson = IdType().apply {
                        name = ValueRefString().apply { value = "Kiné Test" }
                        ssin = ValueRefString().apply { value = "00000000097" }
                        nihii = NihiiType().apply { quality = "physiotherapist"; value = ValueRefString().apply { value = "00000000000" } }
                    }
                }
            }
        }
        routing = RoutingType().apply {
            careReceiver = EagreementServiceImpl.careReceiverFor("00000000097", null, null)
            // As the service computes it: the routing date of a claim-ask that started in the past.
            referenceDate = GregorianCalendar().let { cal ->
                cal.time = EagreementServiceImpl.routingReferenceDate("claim-ask", DateTime().minusDays(10), DateTime()).toDate()
                DatatypeFactory.newInstance().newXMLGregorianCalendar(cal)
            }
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

    /**
     * The production path, measured on the dev on 03/11/2026: `BlobBuilder.build(…, "3.0", …)` sets MessageVersion "3.0" on
     * every blob (`BlobBuilderImpl`, `setMessageVersion`), and `BlobMapper` v4 copies it to the Detail. Leaving it there on v1
     * made the bridge refuse the request. `detailMessageVersion("v1")` must clear it.
     */
    private fun builtBlob() = org.taktik.connector.business.mycarenetdomaincommons.domain.Blob().apply {
        content = detailContent
        id = "blob1"
        contentEncoding = "none"
        contentType = "text/xml"
        contentEncryption = "encryptedForKnownBED"
        messageVersion = "3.0"
    }

    @Test
    fun theBuilderVersionLeftInPlaceIsWhatTheDevRefused() {
        val request = v2Request().apply {
            detail = org.taktik.connector.business.mycarenetcommons.mapper.v4.BlobMapper.mapBlobTypefromBlob(builtBlob())
        }
        assertThatThrownBy { AgreementV1Bridge.v1Xml(request) }.hasMessageContaining("MessageVersion")
    }

    @Test
    fun onV1TheBuilderVersionIsClearedAndTheRequestGoesOut() {
        val blob = builtBlob().apply { messageVersion = EagreementServiceImpl.detailMessageVersion("v1") }
        val request = v2Request().apply {
            detail = org.taktik.connector.business.mycarenetcommons.mapper.v4.BlobMapper.mapBlobTypefromBlob(blob)
        }
        assertThat(AgreementV1Bridge.v4OnlyContent(request)).isEmpty()
        assertThat(wireXml(request)).doesNotContain("MessageVersion")
        System.getenv("EAGREEMENT_V1_XSD")?.let(::File)?.takeIf { it.isFile }?.let { xsd ->
            SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(xsd).newValidator()
                .validate(StreamSource(StringReader(wireXml(request))))
        }
    }

    @Test
    fun onV2TheDetailCarriesV4() {
        assertThat(EagreementServiceImpl.detailMessageVersion("v2")).isEqualTo("V4")
        assertThat(EagreementServiceImpl.detailMessageVersion("v1")).isNull()
    }

    /**
     * Measured on the dev, 03/11/2026, second run: eHealth answered SOA-03006, `'Routing' is not complete`, ReferenceDate
     * expected. The container runs in UTC, so v4 writes `2026-11-03Z`. v3's `XmlDateNoTzAdapter` only strips `+hh:mm`, so
     * it failed on the `Z`, and JAXB dropped the field silently. The request must be BUILT in the zone, not just read.
     */
    @Test
    fun theReferenceDateSurvivesWhenTheRequestIsBuiltInUtc() {
        val saved = TimeZone.getDefault()
        try {
            for (zone in listOf("UTC", "Europe/Brussels", "Pacific/Kiritimati", "Pacific/Pago_Pago")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                org.joda.time.DateTimeZone.setDefault(org.joda.time.DateTimeZone.forID(zone))
                val request = v2Request()
                val day = request.routing.referenceDate.let { "%04d-%02d-%02d".format(it.year, it.month, it.day) }
                val v1 = AgreementV1Bridge.toV1(request, V1AskAgreementRequest::class.java)
                assertThat(v1.routing.referenceDate).describedAs(zone).isNotNull()
                assertThat(wireXml(request)).describedAs(zone).contains(">$day</")
            }
        } finally {
            TimeZone.setDefault(saved)
            org.joda.time.DateTimeZone.setDefault(org.joda.time.DateTimeZone.forTimeZone(saved))
        }
    }

    @Test
    fun v1ByDefaultAndV2OnlyWhenAskedForExplicitly() {
        assertThat(EagreementServiceImpl.agreementVersionOf(null)).isEqualTo("v1")
        assertThat(EagreementServiceImpl.agreementVersionOf("")).isEqualTo("v1")
        assertThat(EagreementServiceImpl.agreementVersionOf("v3")).isEqualTo("v1")
        assertThat(EagreementServiceImpl.agreementVersionOf("v1")).isEqualTo("v1")
        assertThat(EagreementServiceImpl.agreementVersionOf("v2")).isEqualTo("v2")
        assertThat(EagreementServiceImpl.agreementVersionOf(" V2 ")).isEqualTo("v2")
    }

    /** The conversation piece on v1 says what went on the wire, not the v2 object the service built (03/11/2026). */
    @Test
    fun theV1PieceIsTheWireRequest() {
        val request = v2Request()
        val piece = EagreementServiceImpl.transactionRequestXml("v1", request)
        assertThat(piece).contains(AgreementV1Bridge.AGREEMENT_V1, AgreementV1Bridge.CORE_V3)
        assertThat(piece).doesNotContain(AgreementV1Bridge.AGREEMENT_V2, AgreementV1Bridge.CORE_V4, "MessageVersion")
        assertThat(canonical(piece)).isEqualTo(canonical(wireXml(request)))
        assertThat(piece).contains("ReferenceDate")
    }

    /** consultAgreement goes through the same bridge: its v1 request translates, nothing is lost, and the XSD accepts it. */
    @Test
    fun aConsultRequestGoesThroughTheBridgeToo() {
        val v2 = be.fgov.ehealth.mycarenet.agreement.protocol.v2.ConsultAgreementRequest().apply {
            val ask = v2Request()
            commonInput = ask.commonInput; routing = ask.routing; detail = ask.detail; id = ask.id; issueInstant = ask.issueInstant
        }
        val wire = AgreementV1Bridge.wireXml(v2)
        assertThat(wire).contains("ConsultAgreementRequest", AgreementV1Bridge.AGREEMENT_V1).doesNotContain("MessageVersion")
        System.getenv("EAGREEMENT_V1_XSD")?.let(::File)?.takeIf { it.isFile }?.let { xsd ->
            SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(xsd).newValidator()
                .validate(StreamSource(StringReader(wire)))
        }
    }

    /** What goes on the wire: the v1 JAXB object `toV1` returns, marshalled again — as `GenericWsSender` will. */
    private fun wireXml(request: AskAgreementRequest): String {
        val v1 = AgreementV1Bridge.toV1(request, V1AskAgreementRequest::class.java)
        return StringWriter().also { JAXBContext.newInstance(V1AskAgreementRequest::class.java).createMarshaller().marshal(v1, it) }.toString()
    }

    /** Namespace-aware shape of an element: prefixes and `xmlns` declarations do not count, everything else does. */
    private fun canonical(xml: String): String {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val root = factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
        fun walk(e: Element): String {
            val attributes = (0 until e.attributes.length).map { e.attributes.item(it) }
                .filter { it.namespaceURI != XMLConstants.XMLNS_ATTRIBUTE_NS_URI }
                .map { "{${it.namespaceURI ?: ""}}${it.localName}=${it.nodeValue}" }.sorted()
            val children = (0 until e.childNodes.length).map { e.childNodes.item(it) }
            val content = children.joinToString("") { if (it is Element) walk(it) else it.textContent.trim() }
            return "<{${e.namespaceURI}}${e.localName} $attributes>$content</>"
        }
        return walk(root)
    }

    /**
     * The one normalisation the v1 types apply: v3 maps `ReferenceDate` (`xs:date`) through `XmlDateNoTzAdapter`, which
     * drops the `+hh:mm` suffix v4's `XMLGregorianCalendar` writes. That is the form a native v1 request carries.
     */
    private fun withoutReferenceDateZone(canonical: String): String =
        canonical.replace(Regex("(ReferenceDate \\[]>\\d{4}-\\d{2}-\\d{2})[+-]\\d{2}:\\d{2}"), "$1")

    @Test
    fun nothingIsLostOrReformattedBetweenTheRewriteAndTheWire() {
        val request = v2Request()
        assertThat(canonical(wireXml(request))).isEqualTo(withoutReferenceDateZone(canonical(AgreementV1Bridge.v1Xml(request))))
    }

    @Test
    fun theReferenceDayDoesNotDependOnTheJvmZone() {
        val request = v2Request()
        val day = request.routing.referenceDate.let { "%04d-%02d-%02d".format(it.year, it.month, it.day) }
        val saved = TimeZone.getDefault()
        try {
            for (zone in listOf("UTC", "Pacific/Kiritimati", "Pacific/Pago_Pago")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                org.joda.time.DateTimeZone.setDefault(org.joda.time.DateTimeZone.forID(zone))
                assertThat(wireXml(request)).describedAs(zone).contains(">$day</")
            }
        } finally {
            TimeZone.setDefault(saved)
            org.joda.time.DateTimeZone.setDefault(org.joda.time.DateTimeZone.forTimeZone(saved))
        }
    }

    @Test
    fun theV1RequestOnTheWireIsValidAgainstTheOfficialSchema() {
        val xsd = System.getenv("EAGREEMENT_V1_XSD")?.let(::File)
        assumeTrue("EAGREEMENT_V1_XSD not set", xsd != null && xsd.isFile)
        val schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(xsd)
        schema.newValidator().validate(StreamSource(StringReader(wireXml(v2Request()))))
    }
}
