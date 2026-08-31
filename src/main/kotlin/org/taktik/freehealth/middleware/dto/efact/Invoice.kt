/*
 * Copyright (C) 2018 iCure SA
 *
 * This file is part of iCureBackend.
 *
 * iCureBackend is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2 as published by
 * the Free Software Foundation.
 *
 * iCureBackend is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with iCureBackend.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.taktik.freehealth.middleware.dto.efact

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.taktik.freehealth.middleware.domain.common.Patient
import java.util.LinkedList

@JsonIgnoreProperties(ignoreUnknown = true)
class Invoice {
    var patient: Patient? = null
    var ioCode: String? = null
    var items: MutableList<InvoiceItem> = LinkedList()
    var reason: InvoicingTreatmentReasonCode? = null
    var invoiceRef: String? = null
    var invoiceNumber: Long? = null
    var ignorePrescriptionDate: Boolean = false
    var hospitalisedPatient: Boolean = false
    var creditNote: Boolean = false

    var relatedInvoiceIoCode: String? = null
    var relatedInvoiceNumber: Long? = null
    var relatedBatchSendNumber: Long? = null
    var relatedBatchYearMonth: Long? = null
    var startOfCoveragePeriod: Long? = null //yyyyMMdd

    var internshipNihii: String? = null
    var gnotionNihii: String? = null

    /**
     * Payment approval number, written to ET 20 Z 42-45 (48 positions, instructions de facturation electronique
     * p. 275, named by annexe 26.2 p. 202 as "N° engagement de paiement (MDA)").
     *
     * This is the `paymentApproval` an insurer returns on the MemberData insurability period assertion: exactly 32
     * alphanumerical positions, a hexadecimal digest. The remaining sixteen positions of the zone are structural
     * and the writer composes them, so this field carries the 32 and nothing else.
     *
     * It belongs to the invoice, not to its items: the zone is on the type 20 record, and every line of one
     * invoice necessarily shares the same engagement.
     *
     * Null means no value, and the flat file cannot say more: it writes zeroes both when the insurer engaged
     * nothing and when the network was never consulted. Nothing is inferred from null.
     */
    var paymentApproval: String? = null

    var admissionDate: Long? = null //yyyyMMdd
    var locationNihii: String? = null
    var locationService: Int? = null
    var options: Map<String, String>? = null
}
