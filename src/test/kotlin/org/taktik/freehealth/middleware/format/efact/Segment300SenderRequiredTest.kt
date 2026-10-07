package org.taktik.freehealth.middleware.format.efact

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import org.taktik.freehealth.middleware.dto.efact.InvoiceSender
import java.io.StringWriter

/**
 * The three sender fields of the 300 header (Z 305, 306, 307) are mandatory - offline, no eHealth call.
 *
 * A missing one used to reach `!!` and surface as a bare 500 NullPointerException that named nothing (07/10/2026,
 * POST /efact/flat with a sender lacking lastName). It must now be an IllegalArgumentException naming the field,
 * which ExceptionHandlers maps to 400.
 */
class Segment300SenderRequiredTest {
    private fun sender() = InvoiceSender().apply {
        nihii = 12345678901L
        ssin = "00000000000"
        lastName = "Kine"
        firstName = "Alice"
        phoneNumber = 470000000L
    }

    private fun write(sender: InvoiceSender) =
        BelgianInsuranceInvoicingFormatWriter(StringWriter())
            .write200and300(sender, 1L, "REF", 1, 1L, 2026, 10, false)

    @Test
    fun aCompleteSenderIsWritten() {
        val sw = StringWriter()
        BelgianInsuranceInvoicingFormatWriter(sw).write200and300(sender(), 1L, "REF", 1, 1L, 2026, 10, false)
        assertThat(sw.toString()).isNotEmpty()
    }

    @Test
    fun eachMissingSenderFieldIsANamedIllegalArgument() {
        for ((field, strip) in listOf<Pair<String, (InvoiceSender) -> Unit>>(
            "lastName" to { it.lastName = null },
            "firstName" to { it.firstName = null },
            "phoneNumber" to { it.phoneNumber = null },
        )) {
            val incomplete = sender().also(strip)
            assertThatThrownBy { write(incomplete) }
                .describedAs(field)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("sender.$field")
        }
    }
}
