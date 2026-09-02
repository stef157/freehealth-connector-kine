/*
 * Copyright (C) 2018 Taktik SA
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

package org.taktik.freehealth.middleware.service

import be.fgov.ehealth.messageservices.mycarenet.core.v1.SendTransactionRequest
import org.assertj.core.api.Assertions.assertThat
import org.joda.time.DateTime
import org.junit.Test
import org.mockito.Mockito.mock
import org.taktik.connector.technical.service.keydepot.KeyDepotService
import org.taktik.freehealth.middleware.service.impl.EattestV3ServiceImpl

/**
 * eAttest v3 cancellation: `request/date` must be the reference instant, not the wall clock — offline,
 * no eHealth call, no Spring context.
 *
 * Normative source: `FR-MPTI-EAT3-PPS Manuel procedure de test eAttestV3 Duplicata V1.0`, section 4.1.2,
 * which requires the second attempt to carry the payload of the first: "InputReference SC2 = InputReference
 * SC1", "Kmehr-ID SC2 = Kmehr-ID SC 1", attemptNbr = 2, "Vous ne pouvez apporter aucune modification au
 * message Kmehr". A duplicate that regenerates zones from the clock is a modification.
 *
 * The manual fixes `request/id` (the Kmehr-ID) and the KMEHR message. It does not name `request/date`.
 * What this test locks is therefore not the manual's letter but the fork's own symmetry: the send builder
 * writes `date = refDateTime; time = refDateTime` (line 643) while the cancel builder wrote
 * `date = now; time = now` (line 1278) — the same message shape, two clocks. With `now` the caller can
 * inject, a replayed cancellation could not be byte-identical to the attempt it replays.
 *
 * The assertion that cannot drift is the last one: `request/date` must carry the instant already encoded
 * in `request/id`, which the manual DOES fix. The two are read from the same object, not from the builder.
 */
class EattestV3CancelRequestDateOfflineTest {
    private val service = EattestV3ServiceImpl(mock(STSService::class.java), mock(KeyDepotService::class.java))

    private val builder = EattestV3ServiceImpl::class.java
        .getDeclaredMethod(
            "getEattestCancelSendTransactionRequest",
            DateTime::class.java,
            String::class.java, String::class.java, String::class.java, String::class.java,
            String::class.java, String::class.java, String::class.java, String::class.java,
            String::class.java, String::class.java, String::class.java, String::class.java,
            String::class.java, String::class.java,
            java.lang.Long::class.java
        ).apply { isAccessible = true }

    /** `now` is INJECTED, never taken from the wall clock: two calls in the same second would pass by accident. */
    private fun cancel(now: DateTime, referenceDate: Long?): SendTransactionRequest =
        builder.invoke(
            service, now,
            "54123456789", "12345678901", "Jean", "Kine",
            "86103130262", "Test", "Patient", "F",
            null, null, null, null,
            "REF-1", "Erreur d'encodage",
            referenceDate
        ) as SendTransactionRequest

    private val referenceDate = 20260902172640L
    private val clockA = DateTime(2026, 9, 2, 19, 30, 15)
    private val clockB = DateTime(2026, 9, 2, 21, 4, 51)

    // 1 — the defect: two cancellations composed on two different clocks, same referenceDate, must carry
    // the same request/date. Red before the fix, where the zone read `now`.
    @Test
    fun aReplayedCancellationCarriesTheSameRequestDate() {
        val first = cancel(clockA, referenceDate)
        val second = cancel(clockB, referenceDate)

        assertThat(second.request.date).isEqualTo(first.request.date)
        assertThat(second.request.time).isEqualTo(first.request.time)
    }

    // 2 — the structural assertion, and the one that cannot drift: request/date must carry the very instant
    // that request/id encodes. request/id IS fixed by the manual, so this ties the unnamed zone to the named one.
    @Test
    fun requestDateCarriesTheInstantEncodedInRequestId() {
        val request = cancel(clockA, referenceDate).request

        assertThat(request.id.value).isEqualTo("54123456789.$referenceDate")
        assertThat(request.date.toString("yyyyMMddHHmmss")).isEqualTo(referenceDate.toString())
        assertThat(request.time.toString("yyyyMMddHHmmss")).isEqualTo(referenceDate.toString())
    }

    // 3 — non vacuity: two DIFFERENT reference dates must produce different request/date. A constant would
    // pass test 1 and make every cancellation share one identity.
    @Test
    fun twoDistinctCancellationsCarryDistinctRequestDates() {
        val first = cancel(clockA, referenceDate)
        val second = cancel(clockA, 20260902172641L)

        assertThat(second.request.date).isNotEqualTo(first.request.date)
        assertThat(second.request.id.value).isNotEqualTo(first.request.id.value)
    }

    // 4 — SENTINEL, green before and after: with no referenceDate the builder falls back to `now`, exactly as
    // before. No existing caller changes behaviour — FhcEAttestGateway emitted no `date` until the DUP1 wiring.
    @Test
    fun withoutAReferenceDateTheClockStillGovernsEverything() {
        val request = cancel(clockA, null).request

        assertThat(request.date.toString("yyyyMMddHHmmss")).isEqualTo(clockA.toString("yyyyMMddHHmmss"))
        assertThat(request.id.value).isEqualTo("54123456789." + clockA.toString("yyyyMMddHHmmss"))
    }

    // 5 — the KMEHR header was already deterministic on both sides (DUP1 §1.3); locked here so a future edit
    // cannot desynchronise the envelope from the message it wraps.
    @Test
    fun theKmehrHeaderCarriesTheSameInstantAsTheRequest() {
        val transaction = cancel(clockA, referenceDate)

        assertThat(transaction.kmehrmessage.header.date.toString("yyyyMMddHHmmss")).isEqualTo(referenceDate.toString())
        assertThat(transaction.kmehrmessage.header.date).isEqualTo(transaction.request.date)
    }
}
