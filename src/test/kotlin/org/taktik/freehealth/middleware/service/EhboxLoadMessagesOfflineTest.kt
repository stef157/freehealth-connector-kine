package org.taktik.freehealth.middleware.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.mockito.Mockito.mock
import org.taktik.connector.business.ehbox.v3.exception.EhboxCryptoException
import org.taktik.connector.technical.service.keydepot.KeyDepotService
import org.taktik.freehealth.middleware.dto.ehbox.DocumentMessage
import org.taktik.freehealth.middleware.dto.ehbox.ErrorMessage
import org.taktik.freehealth.middleware.service.impl.EhboxServiceImpl

/**
 * A listed eHealthBox message is never dropped from the list.
 *
 * `loadMessages` mapped each message through `mapNotNull`: a failure other than decryption, or a message type the
 * mapper does not know, returned `null` and the message vanished — the caller could not know it was there. The
 * decryption placeholder existed but carried no id, so it could be neither moved nor deleted.
 */
class EhboxLoadMessagesOfflineTest {
    private val service = EhboxServiceImpl(mock(STSService::class.java), mock(KeyDepotService::class.java))
    private val decryptionFailure
        get() = EhboxCryptoException(mutableListOf(), org.taktik.connector.business.ehbox.api.domain.ErrorMessage<Any>())

    @Test
    fun aMessageThatCannotBeReadBecomesAPlaceholderWithItsId() {
        val entry = service.readListedMessage("msg-1", { throw IllegalStateException("broken") }) { null }

        assertThat(entry).isInstanceOf(ErrorMessage::class.java)
        assertThat(entry.id).isEqualTo("msg-1")
        assertThat((entry as ErrorMessage).errorCode).isEqualTo("READ_ERROR")
    }

    @Test
    fun anUnknownMessageTypeIsNotDroppedEither() {
        val entry = service.readListedMessage("msg-2", { null }) { null }

        assertThat(entry.id).isEqualTo("msg-2")
        assertThat((entry as ErrorMessage).errorCode).isEqualTo("UNSUPPORTED_MESSAGE")
    }

    @Test
    fun anUndecryptableMessageNowCarriesItsId() {
        val entry = service.readListedMessage("msg-3", { throw decryptionFailure }) { null }

        assertThat(entry.id).isEqualTo("msg-3")
        assertThat((entry as ErrorMessage).title).isEqualTo("Impossible to decrypt message using provided Keystores")
    }

    @Test
    fun anAlternateKeystoreStillReadsWhatTheFirstCouldNot() {
        val read = DocumentMessage(id = "msg-4")
        val entry = service.readListedMessage("msg-4", { throw decryptionFailure }) { read }

        assertThat(entry).isSameAs(read)
    }

    @Test
    fun aReadableMessageIsReturnedUntouched() {
        val read = DocumentMessage(id = "msg-5")
        assertThat(service.readListedMessage("msg-5", { read }) { null }).isSameAs(read)
    }
}
