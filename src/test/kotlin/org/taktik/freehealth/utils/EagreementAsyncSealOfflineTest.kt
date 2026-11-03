/*
 *
 * Copyright (C) 2018 iCure SA
 *
 * This file is part of FreeHealthConnector.
 *
 * FreeHealthConnector is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation.
 *
 * FreeHealthConnector is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with FreeHealthConnector.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.taktik.freehealth.utils

import be.cin.encrypted.BusinessContent
import be.cin.encrypted.EncryptedKnownContent
import be.cin.types.v1.Blob
import jakarta.activation.DataHandler
import org.taktik.connector.technical.utils.ByteArrayDatasource
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.taktik.connector.technical.service.etee.Crypto
import org.taktik.connector.technical.service.etee.domain.UnsealedData
import org.taktik.connector.technical.utils.MarshallerHelper
import java.io.ByteArrayInputStream

/**
 * The seal of an async message is no longer dropped by the decoder.
 *
 * The decision of an eAgreement medical adviser arrives over the async channel, `encryptedForKnownRecipient`.
 * `decodeDetail` unwrapped the `EncryptedKnownContent` and returned only its business content, so the XAdES the
 * insurer put next to it never reached the caller. kine-data had registered the gap as
 * `fhc:eagreement_inbound_xades_not_exposed`. No network, no certificate: the unseal is mocked, everything
 * after it is the real code.
 */
class EagreementAsyncSealOfflineTest {
    private val payload = "<ResponseList/>".toByteArray()
    private val seal = "<ds:Signature>synthetic seal</ds:Signature>".toByteArray()

    private fun blob(bytes: ByteArray, encryption: String?) = Blob().apply {
        value = DataHandler(ByteArrayDatasource(bytes))
        contentEncryption = encryption
        // Blob.getContentEncoding() answers "deflate" when unset; these bytes are not compressed.
        contentEncoding = "none"
    }

    private fun cryptoUnsealingTo(bytes: ByteArray): Crypto = mock(Crypto::class.java).also {
        `when`(it.unseal(any(), any(ByteArray::class.java))).thenReturn(UnsealedData().apply { content = ByteArrayInputStream(bytes) })
    }

    @Test
    fun anEncryptedMessageKeepsItsSeal() {
        val knownContent = EncryptedKnownContent().apply {
            businessContent = BusinessContent().apply { value = payload }
            xades = seal
        }
        val unsealed = MarshallerHelper(EncryptedKnownContent::class.java, EncryptedKnownContent::class.java).toXMLByteArray(knownContent)

        val decoded = AsyncPayloadDecoder.decodeDetailWithSeal(blob("sealed".toByteArray(), "encryptedForKnownRecipient"), cryptoUnsealingTo(unsealed))

        assertThat(decoded.payload).isEqualTo(payload)
        assertThat(decoded.xades).describedAs("the insurer's XAdES, which decodeDetail used to drop").isEqualTo(seal)
    }

    @Test
    fun anUnencryptedMessageHasNoSealAndTheSamePayload() {
        val decoded = AsyncPayloadDecoder.decodeDetailWithSeal(blob(payload, null), mock(Crypto::class.java))

        assertThat(decoded.xades).isNull()
        assertThat(decoded.payload).isEqualTo(payload)
        assertThat(AsyncPayloadDecoder.decodeDetail(blob(payload, null), mock(Crypto::class.java)))
            .describedAs("decodeDetail, which MDA still calls, returns the same bytes as before")
            .isEqualTo(payload)
    }
}
