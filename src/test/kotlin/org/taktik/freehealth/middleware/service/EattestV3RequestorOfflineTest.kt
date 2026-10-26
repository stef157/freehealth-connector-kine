package org.taktik.freehealth.middleware.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.joda.time.DateTime
import org.junit.Test
import org.mockito.Mockito.mock
import org.taktik.connector.technical.service.keydepot.KeyDepotService
import org.taktik.freehealth.middleware.dto.eattest.Eattest
import org.taktik.freehealth.middleware.service.impl.EattestV3ServiceImpl
import java.lang.reflect.InvocationTargetException

/**
 * A code's requestor without its hcp was dereferenced five times with `!!`: a bare 500. The KMEHR request is built
 * before the send, so an IllegalArgumentException naming the field (400) is safe.
 */
class EattestV3RequestorOfflineTest {
    private val service = EattestV3ServiceImpl(mock(STSService::class.java), mock(KeyDepotService::class.java))

    private val builder = EattestV3ServiceImpl::class.java.declaredMethods
        .single { it.name == "getEattestCreateV3SendTransactionRequest" }
        .apply { isAccessible = true }

    private fun send(requestor: Eattest.EattestRequestor) = builder.invoke(
        service, DateTime(2026, 9, 2, 19, 30, 15),
        "54123456789", "12345678901", "Jean", "Kine", "0999999922",
        "physiotherapist",
        "86103130262", "Test", "Patient", "F",
        null,
        null, null, null, null,
        null, null, null,
        Eattest(listOf(Eattest.EattestCode(date = 20260729, riziv = "567011", requestor = requestor))),
        null, null
    )

    private fun cause(block: () -> Any?): Throwable =
        try {
            block(); throw AssertionError("no exception")
        } catch (e: InvocationTargetException) {
            e.targetException
        }

    @Test
    fun aRequestorWithoutHcpIsNamed() {
        val cause = cause { send(Eattest.EattestRequestor(date = 20260701, hcp = null)) }
        assertThat(cause).isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("attest.codes[].requestor.hcp is required when a requestor is given")
    }

    @Test
    fun aRequestorWithItsHcpStillBuilds() {
        assertThat(send(Eattest.EattestRequestor(date = 20260701,
            hcp = Eattest.EattestHcParty(idHcParty = "11111111111", cdHcParty = "persphysician")))).isNotNull
    }
}
