package org.taktik.freehealth.middleware.service

import be.cin.mycarenet.esb.common.v2.OrigineType
import be.cin.nip.async.generic.Get
import be.cin.nip.async.generic.MsgQuery
import be.cin.nip.async.generic.Query
import jakarta.xml.bind.JAXBContext
import jakarta.xml.bind.Marshaller
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.taktik.connector.technical.service.keydepot.KeyDepotService
import org.taktik.freehealth.middleware.service.impl.EagreementServiceImpl
import java.io.File
import java.net.URI
import jakarta.xml.soap.MessageFactory
import jakarta.xml.soap.SOAPHeader
import org.taktik.connector.technical.handler.WsAddressingHandlerV200508
import org.taktik.connector.technical.handler.domain.WsAddressingHeader
import java.io.StringReader
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory

/**
 * The eAgreement GenAsync `get` as `EagreementServiceImpl.getMessages` builds it, validated against the CIN's
 * official genericAsync V1.5 schema — offline, no eHealth/MyCareNet call, no identity, no secret.
 *
 * Why: since 02/10/2026 every eAgreement `get` on pilot ends in a 502 from the MyCareNet gateway after ~105 s.
 * Before blaming the platform, the request itself has to be shown conformant.
 *
 * - The Origin comes from the REAL private `buildOriginType` (reflection), not a copy.
 * - MsgQuery / TAckQuery / Reply-to-etk are copied from `getMessages`; [the source guard][sourceStillMatches]
 *   fails as soon as that block changes, so the copy cannot drift silently.
 * - The schema is the CIN file shipped with the eAgreement V2 package (`NIPPIN HV - genericAsync V1.5`), read
 *   from the Kiné-Desk repository: `-Dcin.genericasync.xsd=<path>` overrides the default location; the test is
 *   skipped (not passed) when the file is absent.
 */
class EagreementGetConformityOfflineTest {
    private val xsd = File(
        System.getProperty("cin.genericasync.xsd")
            ?: "/Users/stephane/PhpstormProjects/kine-data/docs/officiel/cin/eAgreementV2/Level 2/NIPPIN HV - genericAsync V1.5/GenericAsync.xsd"
    )

    private val source = File("src/main/kotlin/org/taktik/freehealth/middleware/service/impl/EagreementServiceImpl.kt")

    /** The block of `getMessages` this test reproduces, whitespace-normalised. */
    private val copiedBlock = """
        val getHeader = WsAddressingHeader(URI("urn:be:cin:nip:async:generic:get:query")).apply {
            messageID = URI(IdGeneratorFactory.getIdGenerator("uuid").generateId())
            // CIN genericAsync catalogue §3.3.6: "Even if the Ws-addressing "To" header is required, its
            // value may be left empty." The handler writes <wsa:To/> for any non-null URI.
            to = URI("")
        }
        val replyToEtk = extractEtk(credential)?.encoded

        val get = Get().apply {
            this.replyToEtk = replyToEtk
            msgQuery = MsgQuery().apply {
                isInclude = true
                // ONE message per get. eAgreement responses are encryptedForKnownRecipient, and the CIN
                // genericAsync catalogue (§3.3.6, MsgQuery) sets the default to "1" when encrypted content
                // is possible -- "Use 1 to download the messages sequentially" -- and warns ("Encryption and
                // timeout") that encrypting large batches may delay the get significantly. Conformance, not
                // a cure: measured on 04/10/2026, Max=1 did not lift the 502 seen since 02/10.
                max = 1
                this.messageNames.addAll(
                    listOf(
                        "eAgreement-response"
                    )
                )
            }
            // No tACK requested. eAgreement requests go out synchronously (askAgreement & co. on the eHealth
            // web service), never through the GenAsync post, so this channel has no tACK of ours to fetch.
            // Requesting them anyway meant repeated gets without the confirm the catalogue requires
            // ("Repetitive call limitation", §3.3.6); @Include=false is the documented way to ask for none.
            tAckQuery = Query().apply {
                isInclude = false
            }
            origin = buildOriginType(hcpNihii, hcpFirstName, "physiotherapist", hcpSsin)
        }
    """

    private fun normalise(s: String) = s.replace(Regex("\\s+"), " ").trim()

    @Test
    fun sourceStillMatches() {
        assertThat(normalise(source.readText())).contains(normalise(copiedBlock))
    }

    private fun buildGet(): Get {
        // Dummy package identity, read by buildOriginType from the configuration (system properties, as the
        // `-D` overrides in production). Never a real licence.
        System.setProperty("genericasync.eagreement.package.name", "Test-Package")
        System.setProperty("genericasync.eagreement.package.license.username", "test-licence")
        System.setProperty("genericasync.eagreement.package.license.password", "test-password")
        val service = EagreementServiceImpl(mock(STSService::class.java), mock(KeyDepotService::class.java))
        val buildOrigin = EagreementServiceImpl::class.java.getDeclaredMethod(
            "buildOriginType", String::class.java, String::class.java, String::class.java, String::class.java
        ).apply { isAccessible = true }
        // MPTI-style placeholders: no real NIHII, no real SSIN.
        val origin = buildOrigin.invoke(service, "99999999", "Test", "physiotherapist", "00000000097") as OrigineType

        return Get().apply {
            this.replyToEtk = byteArrayOf(1, 2, 3, 4)
            msgQuery = MsgQuery().apply {
                isInclude = true
                max = 1
                this.messageNames.addAll(listOf("eAgreement-response"))
            }
            tAckQuery = Query().apply {
                isInclude = false
            }
            this.origin = origin
        }
    }

    @Test
    fun theHandlerWritesAnEmptyWsaTo() {
        val header = WsAddressingHeader(URI("urn:be:cin:nip:async:generic:get:query")).apply {
            messageID = URI("urn:uuid:00000000-0000-0000-0000-000000000000")
            to = URI("")
        }
        val soap = MessageFactory.newInstance().createMessage()
        WsAddressingHandlerV200508::class.java
            .getDeclaredMethod("processOptionalElements", WsAddressingHeader::class.java, SOAPHeader::class.java)
            .apply { isAccessible = true }
            .invoke(WsAddressingHandlerV200508(), header, soap.soapHeader)

        val to = soap.soapHeader.getElementsByTagNameNS("http://www.w3.org/2005/08/addressing", "To")
        assertThat(to.length).isEqualTo(1)
        assertThat(to.item(0).textContent).isEmpty()
    }

    private fun marshal(get: Get): String = StringWriter().also {
        JAXBContext.newInstance(Get::class.java).createMarshaller().apply {
            setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true)
        }.marshal(get, it)
    }.toString()

    @Test
    fun getValidatesAgainstTheOfficialGenericAsyncV15Schema() {
        assumeTrue("official XSD not found at $xsd", xsd.isFile)
        val xml = marshal(buildGet())

        // Printed for the record, licence password masked.
        println(xml.replace(Regex("(<(?:[a-z0-9]+:)?Password>)[^<]*"), "$1***"))

        // The CIN package ships `commonTypes-v1.5.xsd` importing `xml.xsd` and `xmlmime.xsd` WITHOUT shipping them.
        // Both are W3C standard schemas: they are served from the connector's own copies, and only those two.
        val factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
        val defaultResolver = factory.resourceResolver
        factory.resourceResolver = org.w3c.dom.ls.LSResourceResolver { type, ns, publicId, systemId, baseUri ->
            val w3c = when (systemId) {
                "xml.xsd" -> File("src/main/resources/external/XSD/xml.xsd")
                "xmlmime.xsd" -> File("src/main/resources/external/XSD/xmlmime.xsd")
                else -> null
            }
            if (w3c == null) defaultResolver?.resolveResource(type, ns, publicId, systemId, baseUri)
            else FileInput(w3c, publicId, baseUri)
        }
        val schema = factory.newSchema(xsd)
        schema.newValidator().validate(StreamSource(StringReader(xml)))

        // One encrypted message per get (catalogue §3.3.6), and no tACK requested at all.
        assertThat(normalise(xml)).contains(normalise("<ns2:MsgQuery Include=\"true\"> <ns2:Max>1</ns2:Max>"))
        assertThat(normalise(xml)).contains("<ns2:TAckQuery Include=\"false\"/>")
    }
}

/** Minimal LSInput over a local file (the JDK ships no public implementation). */
private class FileInput(file: File, private var publicId: String?, private var baseUri: String?) : org.w3c.dom.ls.LSInput {
    private var systemId: String? = file.toURI().toString()
    private var data: String? = file.readText()
    override fun getCharacterStream(): java.io.Reader? = null
    override fun setCharacterStream(characterStream: java.io.Reader?) {}
    override fun getByteStream(): java.io.InputStream? = null
    override fun setByteStream(byteStream: java.io.InputStream?) {}
    override fun getStringData(): String? = data
    override fun setStringData(stringData: String?) { data = stringData }
    override fun getSystemId(): String? = systemId
    override fun setSystemId(systemId: String?) { this.systemId = systemId }
    override fun getPublicId(): String? = publicId
    override fun setPublicId(publicId: String?) { this.publicId = publicId }
    override fun getBaseURI(): String? = baseUri
    override fun setBaseURI(baseURI: String?) { this.baseUri = baseURI }
    override fun getEncoding(): String? = "UTF-8"
    override fun setEncoding(encoding: String?) {}
    override fun getCertifiedText(): Boolean = false
    override fun setCertifiedText(certifiedText: Boolean) {}
}
