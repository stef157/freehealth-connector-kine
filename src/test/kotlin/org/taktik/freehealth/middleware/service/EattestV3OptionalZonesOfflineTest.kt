package org.taktik.freehealth.middleware.service

import be.fgov.ehealth.messageservices.mycarenet.core.v1.SendTransactionRequest
import org.assertj.core.api.Assertions.assertThat
import org.joda.time.DateTime
import org.junit.Test
import org.mockito.Mockito.mock
import org.taktik.connector.technical.service.keydepot.KeyDepotService
import org.taktik.connector.technical.utils.MarshallerHelper
import org.taktik.freehealth.middleware.dto.eattest.Eattest
import org.taktik.freehealth.middleware.service.impl.EattestV3ServiceImpl
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Optional zones are absent when the caller gives nothing — never written empty, never written as zero.
 *
 * `FR-KLAY-EAT3-ALL v03r04`, item `supplement`: mandatory for general practitioners, specialists and dentists only;
 * for the other sectors *"Si le patient n'a pas payé de supplément, cette zone n'est pas présente"*, in the CGA and in
 * every CGD (matrix `BE-MBSE-EAT3-ALL`: `O` for physiotherapists and speech therapists). The connector wrote it
 * unconditionally with `doctorSupplement ?: 0.0`, so a physiotherapist could not leave it out. A caller that states
 * `0.0` still gets `0.00`: that is how the physician sectors fill their mandatory zone.
 *
 * `FR-KHCP-MULT-ALL V01r06` §1.1: the prescriber's `firstname` and `familyname` are optional; they were written as
 * empty elements (`?: ""`). Identifiers are synthetic.
 */
class EattestV3OptionalZonesOfflineTest {
    private val service = EattestV3ServiceImpl(mock(STSService::class.java), mock(KeyDepotService::class.java))

    private val builder = EattestV3ServiceImpl::class.java.declaredMethods
        .single { it.name == "getEattestCreateV3SendTransactionRequest" }
        .apply { isAccessible = true }

    private fun build(vararg codes: Eattest.EattestCode) = builder.invoke(
        service, DateTime(2026, 11, 10, 15, 16, 0),
        "11111111111", "22222222222", "Jean", "Kine", "0999999922",
        "physiotherapist",
        "12345678901", "Test", "Patient", "F",
        null,
        null, null, null, null,
        null, null, null,
        Eattest(codes.toList()),
        null, null
    ) as SendTransactionRequest

    private fun code(riziv: String, doctorSupplement: Double? = null, requestor: Eattest.EattestRequestor? = null) =
        Eattest.EattestCode(date = 20261110, riziv = riziv, doctorSupplement = doctorSupplement,
            requestor = requestor, requestorNorm = requestor?.let { 1 })

    private fun Element.children(name: String) = (0 until childNodes.length).map { childNodes.item(it) }
        .filterIsInstance<Element>().filter { it.localName == name }

    private fun transactions(request: SendTransactionRequest): List<Element> {
        val xml = MarshallerHelper(SendTransactionRequest::class.java, SendTransactionRequest::class.java).toXMLByteArray(request)
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(ByteArrayInputStream(xml))
        return doc.getElementsByTagNameNS("*", "transaction").let { nl -> (0 until nl.length).map { nl.item(it) as Element } }
    }

    /** The `supplement` amounts, in document order, each labelled with its transaction kind (`cga`, `cgd`). */
    private fun supplements(request: SendTransactionRequest): List<Pair<String, String>> =
        transactions(request).flatMap { t ->
            val kind = t.children("cd").first().textContent
            t.children("item").filter { item -> item.children("cd").any { it.textContent == "supplement" } }
                .map { kind to it.children("cost").single().children("decimal").single().textContent }
        }

    @Test
    fun withoutASupplementTheZoneIsAbsentEverywhere() {
        assertThat(supplements(build(code("567011"), code("567033")))).isEmpty()
    }

    @Test
    fun aStatedSupplementIsWrittenOnItsLineAndTheTotalIsTheirSum() {
        // Only the second line carries one: the first CGD stays without the zone, the CGA carries the sum (CE 265).
        assertThat(supplements(build(code("561610"), code("561610", doctorSupplement = 10.0))))
            .containsExactly("cga" to "10.00", "cgd" to "10.00")
    }

    @Test
    fun anExplicitZeroIsStillWritten() {
        assertThat(supplements(build(code("101032", doctorSupplement = 0.0))))
            .containsExactly("cga" to "0.00", "cgd" to "0.00")
    }

    @Test
    fun aPrescriberWithoutNamesCarriesNoNameElement() {
        val requestor = Eattest.EattestRequestor(date = 20260930,
            hcp = Eattest.EattestHcParty(idHcParty = "11111111111", cdHcParty = "persphysician"))
        val hcparty = transactions(build(code("567011", requestor = requestor)))
            .single { t -> t.children("cd").first().textContent == "cgd" }
            .children("item").single { item -> item.children("cd").any { it.textContent == "requestor" } }
            .children("content").flatMap { it.children("hcparty") }.single()

        assertThat(hcparty.children("id").map { it.textContent }).containsExactly("11111111111")
        assertThat(hcparty.children("firstname")).isEmpty()
        assertThat(hcparty.children("familyname")).isEmpty()
    }

    @Test
    fun aPrescriberWithNamesKeepsThem() {
        val requestor = Eattest.EattestRequestor(date = 20260930, hcp = Eattest.EattestHcParty(
            idHcParty = "11111111111", cdHcParty = "persphysician", firstName = "Monique", lastName = "Dupont"))
        val hcparty = transactions(build(code("567011", requestor = requestor)))
            .single { t -> t.children("cd").first().textContent == "cgd" }
            .children("item").single { item -> item.children("cd").any { it.textContent == "requestor" } }
            .children("content").flatMap { it.children("hcparty") }.single()

        assertThat(hcparty.children("firstname").map { it.textContent }).containsExactly("Monique")
        assertThat(hcparty.children("familyname").map { it.textContent }).containsExactly("Dupont")
    }
}
