package org.taktik.freehealth.middleware.service

import com.sun.org.apache.xpath.internal.operations.Bool
import org.taktik.connector.business.domain.dmg.DmgAcknowledge
import org.taktik.freehealth.middleware.dto.efact.EfactMessage
import org.taktik.freehealth.middleware.dto.efact.EfactSendResponse
import org.taktik.freehealth.middleware.dto.efact.FlatFileWithMetadata
import org.taktik.freehealth.middleware.dto.efact.InvoicesBatch
import java.util.UUID

interface EfactService {

    fun sendBatch(keystoreId: UUID, tokenId: UUID, passPhrase: String, batch: InvoicesBatch): EfactSendResponse
    fun loadMessages(keystoreId: UUID,
                     tokenId: UUID,
                     passPhrase: String,
                     hcpNihii: String,
                     hcpSsin: String,
                     hcpFirstName: String,
                     hcpLastName: String,
                     language: String,
                     limit: Int
        ): List<EfactMessage>

    fun loadMediprimaMessages(keystoreId: UUID,
                              tokenId: UUID,
                              passPhrase: String,
                              hcpNihii: String,
                              hcpSsin: String,
                              hcpFirstName: String,
                              hcpLastName: String,
                              language: String,
                              limit: Int
    ): List<EfactMessage>

    fun confirmAcks(
        keystoreId: UUID,
        tokenId: UUID,
        passPhrase: String,
        hcpNihii: String,
        hcpSsin: String,
        hcpFirstName: String,
        hcpLastName: String,
        valueHashes: List<String>
    ): Boolean

    /**
     * Confirms tACKs by their `Reference` (`TAckReferences`), the genericAsync v1.2 form. `confirmAcks` sends the tACK
     * content (`TAckContents`), which the catalogue limits to v1.1; on the v1.2 endpoint (`hcpfac_12`) it is refused
     * with "No values found in msgbox" while the tACK is still being redelivered (measured 07/10/2026).
     */
    fun confirmAcksByReferences(
        keystoreId: UUID,
        tokenId: UUID,
        passPhrase: String,
        hcpNihii: String,
        hcpSsin: String,
        hcpFirstName: String,
        hcpLastName: String,
        references: List<String>
    ): Boolean

    fun confirmMediprimaAcks(
        keystoreId: UUID,
        tokenId: UUID,
        passPhrase: String,
        hcpNihii: String,
        hcpSsin: String,
        hcpFirstName: String,
        hcpLastName: String,
        valueHashes: List<String>
    ): Boolean

    fun confirmMessages(
        keystoreId: UUID,
        tokenId: UUID,
        passPhrase: String,
        hcpNihii: String,
        hcpSsin: String,
        hcpFirstName: String,
        hcpLastName: String,
        valueHashes: List<String>
    ): Boolean

    fun confirmMediprimaMessages(
        keystoreId: UUID,
        tokenId: UUID,
        passPhrase: String,
        hcpNihii: String,
        hcpSsin: String,
        hcpFirstName: String,
        hcpLastName: String,
        valueHashes: List<String>
    ): Boolean

    fun makeFlatFile(batch: InvoicesBatch, isTest: Boolean, isMediprima: Boolean): String
    fun makeFlatFileCoreWithMetadata(batch: InvoicesBatch, isTest: Boolean, isMediprima: Boolean): FlatFileWithMetadata
}
