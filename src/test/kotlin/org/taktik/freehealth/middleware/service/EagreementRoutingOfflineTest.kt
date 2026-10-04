package org.taktik.freehealth.middleware.service

import org.assertj.core.api.Assertions.assertThat
import org.joda.time.DateTime
import org.junit.Test
import org.taktik.freehealth.middleware.service.impl.EagreementServiceImpl

/**
 * Routing of eAgreement requests — offline, per the CIN "Routing rules (kinés) V1.0" (12/07/2023), map
 * "Routing mutations accords": the reference date decides which insurer the intermutualist filter (RepCIN)
 * resolves for a patient who changed insurer.
 */
class EagreementRoutingOfflineTest {
    private val today = DateTime(2026, 10, 4, 10, 0)
    private val past = DateTime(2026, 9, 1, 0, 0)
    private val future = DateTime(2026, 11, 1, 0, 0)

    @Test
    fun askAndExtendUseTheEarlierOfTheRequestedStartAndToday() {
        for (event in listOf("claim-ask", "claim-extend")) {
            assertThat(EagreementServiceImpl.routingReferenceDate(event, past, today)).isEqualTo(past)
            assertThat(EagreementServiceImpl.routingReferenceDate(event, future, today)).isEqualTo(today)
            assertThat(EagreementServiceImpl.routingReferenceDate(event, null, today)).isEqualTo(today)
        }
    }

    @Test
    fun everyOtherOperationUsesToday() {
        for (event in listOf("claim-argue", "claim-cancel", "claim-completeAgreement", "search-type")) {
            assertThat(EagreementServiceImpl.routingReferenceDate(event, past, today)).isEqualTo(today)
        }
    }

    @Test
    fun anInsurerGivenWithAnSsinIsKept() {
        val receiver = EagreementServiceImpl.careReceiverFor("00000000097", "300", null)
        assertThat(receiver.ssin).isEqualTo("00000000097")
        assertThat(receiver.mutuality).isEqualTo("300")
        assertThat(receiver.regNrWithMut).isNull()
    }

    @Test
    fun anSsinAloneLeavesTheRoutingToTheRepcin() {
        val receiver = EagreementServiceImpl.careReceiverFor("00000000097", null, null)
        assertThat(receiver.mutuality).isNull()
        assertThat(receiver.regNrWithMut).isNull()
    }
}
