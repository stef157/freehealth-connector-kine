package org.taktik.freehealth.middleware.web

import be.cin.nip.async.generic.PostResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver
import org.taktik.connector.technical.exception.MarshallingException
import org.taktik.connector.technical.utils.MarshallerHelper
import org.taktik.freehealth.middleware.web.controllers.EagreementController

/**
 * MarshallerHelper reads every SOAP response, so its failure mostly happens after the request left. kine-data reads
 * any status below 500 as proof that nothing was sent and may replay a write: this failure must never answer 400,
 * which an IllegalArgumentException gets.
 */
class MarshallingFailureStatusOfflineTest {
    private fun unmarshallable(): MarshallingException = try {
        MarshallerHelper(PostResponse::class.java, PostResponse::class.java).toObject("<not-a-post-response".toByteArray())
        throw AssertionError("a malformed reply was unmarshalled")
    } catch (e: MarshallingException) {
        e
    }

    @Test
    fun aMalformedReplyIsAMarshallingExceptionThatIsStillAnIllegalArgumentException() {
        assertThatThrownBy { MarshallerHelper(PostResponse::class.java, PostResponse::class.java).toObject("<not-a-post-response".toByteArray()) }
            .isInstanceOf(MarshallingException::class.java)
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun theAdviceAnswersIt500() {
        assertThat(ExceptionHandlerMethodResolver(ExceptionHandlers::class.java).resolveMethodByThrowable(unmarshallable())!!.name)
            .isEqualTo("handleMarshallingException")
        val response = ExceptionHandlers().handleMarshallingException(MockHttpServletRequest("POST", "/efact/batch"), unmarshallable())
        assertThat(response.statusCode.value()).isEqualTo(500)
    }

    @Test
    fun eAgreementsOwnIllegalArgumentHandlerDoesNotTakeIt() {
        assertThat(ExceptionHandlerMethodResolver(EagreementController::class.java).resolveMethodByThrowable(unmarshallable())!!.name)
            .isEqualTo("handleMarshallingException")
        assertThat(ExceptionHandlerMethodResolver(EagreementController::class.java).resolveMethodByThrowable(IllegalArgumentException("x"))!!.name)
            .isEqualTo("handleBadRequest")
    }
}
