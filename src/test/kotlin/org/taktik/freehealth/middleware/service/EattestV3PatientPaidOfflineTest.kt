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
import java.lang.reflect.InvocationTargetException
import java.math.BigDecimal
import javax.xml.parsers.DocumentBuilderFactory

/**
 * `patientPaid` per code: the amount the caller states is the one the KMEHR carries.
 *
 * The connector only ever wrote `patientpaid = reimbursement + reglementarySupplement`, so kine-data carried the
 * patient's amount in `reimbursement` to get it there (`FhcEAttestGateway::amountsFor`). Without the field the
 * request must not move by a cent; with it, the CGA total must stay the sum of the CGD lines (CE 264).
 * Identifiers are synthetic.
 */
class EattestV3PatientPaidOfflineTest {
    private val service = EattestV3ServiceImpl(mock(STSService::class.java), mock(KeyDepotService::class.java))

    private val builder = EattestV3ServiceImpl::class.java.declaredMethods
        .single { it.name == "getEattestCreateV3SendTransactionRequest" }
        .apply { isAccessible = true }

    private fun build(vararg codes: Eattest.EattestCode) = builder.invoke(
        service, DateTime(2026, 9, 2, 19, 30, 15),
        "11111111111", "22222222222", "Jean", "Kine", "0999999922",
        "physiotherapist",
        "12345678901", "Test", "Patient", "F",
        null,
        null, null, null, null,
        null, null, null,
        Eattest(codes.toList()),
        null, null
    ) as SendTransactionRequest

    private fun code(riziv: String, reimbursement: Double? = null, reglementarySupplement: Double? = null, patientPaid: String? = null) =
        Eattest.EattestCode(date = 20260729, riziv = riziv, reimbursement = reimbursement,
            reglementarySupplement = reglementarySupplement, patientPaid = patientPaid?.let { BigDecimal(it) })

    /** The `patientpaid` amount of each transaction, in document order: `cga` first, then every `cgd`. */
    private fun patientPaid(request: SendTransactionRequest): List<Pair<String, String>> {
        val xml = MarshallerHelper(SendTransactionRequest::class.java, SendTransactionRequest::class.java).toXMLByteArray(request)
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(ByteArrayInputStream(xml))
        fun Element.children(name: String) = (0 until childNodes.length).map { childNodes.item(it) }
            .filterIsInstance<Element>().filter { it.localName == name }
        val transactions = doc.getElementsByTagNameNS("*", "transaction").let { nl -> (0 until nl.length).map { nl.item(it) as Element } }
        return transactions.flatMap { t ->
            val kind = t.children("cd").first().textContent
            t.children("item").filter { item -> item.children("cd").any { it.textContent == "patientpaid" } }
                .map { kind to it.children("cost").single().children("decimal").single().textContent }
        }
    }

    @Test
    fun withoutPatientPaidTheAmountIsTheOneTheConnectorAlwaysWrote() {
        // Coprime amounts, so that no partial sum can pass for another formula (kine-data measured that trap).
        assertThat(patientPaid(build(code("567011", reimbursement = 29.0, reglementarySupplement = 101.0))))
            .containsExactly("cga" to "130.00", "cgd" to "130.00")
    }

    @Test
    fun theStatedAmountIsWrittenAndTheTotalIsItsSum() {
        assertThat(patientPaid(build(
            code("567011", reimbursement = 99.0, patientPaid = "23.63"),
            code("567033", patientPaid = "0.10")
        ))).containsExactly("cga" to "23.73", "cgd" to "23.63", "cgd" to "0.10")
    }

    @Test
    fun anExplicitZeroLeavesAndAMixedAttestAddsUp() {
        assertThat(patientPaid(build(
            code("567011", reimbursement = 29.0, patientPaid = "0"),
            code("567033", reimbursement = 13.0, reglementarySupplement = 4.0)
        ))).containsExactly("cga" to "17.00", "cgd" to "0.00", "cgd" to "17.00")
    }

    @Test
    fun aNegativeOrSubCentAmountIsRefusedBeforeAnythingIsBuilt() {
        for (bad in listOf("-1.00", "23.635")) {
            val cause = try {
                build(code("567011", patientPaid = bad)); null
            } catch (e: InvocationTargetException) {
                e.targetException
            }
            assertThat(cause).describedAs(bad).isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("attest.codes[0].patientPaid must be a non-negative amount with at most two decimals")
        }
    }
}
