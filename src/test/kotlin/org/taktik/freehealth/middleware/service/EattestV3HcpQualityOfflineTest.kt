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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import org.mockito.Mockito.mock
import org.taktik.connector.technical.service.keydepot.KeyDepotService
import org.taktik.freehealth.middleware.service.impl.EattestV3ServiceImpl
import org.joda.time.DateTime
import java.lang.reflect.InvocationTargetException

/**
 * Which quality an eAttest v3 send or cancellation is written with — offline, no eHealth call, no Spring context.
 *
 * The token already carries the quality it was requested with, and MyCareNet refuses a message whose quality
 * contradicts it with SAML_VALIDATION_ERROR, a fault that names neither the quality nor the node. So the rule is
 * decided here, before anything leaves: absent, the token's; present, a quality the domain can write and the
 * token's own — guardpost excepted, a guard post signing under a physician's token. Anything else is an
 * IllegalArgumentException, which `ExceptionHandlers` answers with a 400.
 */
class EattestV3HcpQualityOfflineTest {
    private val service = EattestV3ServiceImpl(mock(STSService::class.java), mock(KeyDepotService::class.java))

    private val resolve = EattestV3ServiceImpl::class.java
        .getDeclaredMethod("resolveHcpQuality", String::class.java, String::class.java, String::class.java)
        .apply { isAccessible = true }

    private fun resolve(explicit: String?, guardPostNihii: String?, token: String) =
        try {
            resolve.invoke(service, explicit, guardPostNihii, token) as String
        } catch (e: InvocationTargetException) {
            throw e.cause!!
        }

    @Test
    fun absentItIsTheTokensQuality() {
        assertThat(resolve(null, null, "physiotherapist")).isEqualTo("physiotherapist")
        assertThat(resolve("  ", null, "physiotherapist")).isEqualTo("physiotherapist")
        assertThat(resolve(null, null, "doctor")).isEqualTo("doctor")
    }

    @Test
    fun absentWithAGuardPostItIsGuardpost() {
        assertThat(resolve(null, "12345678901", "doctor")).isEqualTo("guardpost")
    }

    @Test
    fun presentAndAgreeingWithTheTokenItIsKept() {
        assertThat(resolve("physiotherapist", null, "physiotherapist")).isEqualTo("physiotherapist")
        assertThat(resolve("guardpost", "12345678901", "doctor")).isEqualTo("guardpost")
    }

    // the case MPTI.15 met from the other side: a physician's quality under a physiotherapist's token
    @Test
    fun aQualityThatContradictsTheTokenIsRefused() {
        assertThatThrownBy { resolve("doctor", null, "physiotherapist") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("doctor").hasMessageContaining("physiotherapist")
    }

    // used to be written as persphysician without a word
    @Test
    fun anUnknownQualityIsRefused() {
        assertThatThrownBy { resolve("docter", null, "doctor") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("docter")
    }

    // no profession is a default: each individual quality the STS issues is written under its own token
    @Test
    fun everyIndividualQualityIsWrittenUnderItsOwnToken() {
        listOf("doctor", "physiotherapist", "dentist", "nurse", "midwife", "logopedist", "dietician", "podologist",
            "clinicalpsychologist", "optician").forEach {
            assertThat(resolve(null, null, it)).isEqualTo(it)
            assertThat(resolve(it, null, it)).isEqualTo(it)
        }
        assertThatThrownBy { resolve("physiotherapist", null, "dentist") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // an organisation authors no attestation, and used to fall through to persphysician
    @Test
    fun anOrganisationsTokenIsRefused() {
        assertThatThrownBy { resolve(null, null, "medicalhouse") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("medicalhouse")
    }

    // guardpost writes the guard post's NIHII on the author; without one it was a bare !! and a 500
    @Test
    fun aGuardpostWithoutItsNihiiIsRefused() {
        assertThatThrownBy { resolve("guardpost", null, "doctor") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("guardPostNihii")
        assertThatThrownBy { resolve("guardpost", "  ", "doctor") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private val cancelRequest = EattestV3ServiceImpl::class.java.declaredMethods
        .first { it.name == "getEattestCancelSendTransactionRequest" }
        .apply { isAccessible = true }

    private fun cancelRequest(quality: String, guardPostNihii: String?, guardPostSsin: String?, guardPostName: String?) =
        cancelRequest.invoke(
            service, DateTime(2026, 10, 14, 10, 0), "11111111111", "22222222222", "Prénom", "Nom", quality,
            "12345678901", "Patient", "Test", "M", null, null, null, null,
            guardPostNihii, guardPostSsin, guardPostName, "ref", "reason", null
        ) as SendTransactionRequest

    // PR #123: a guard post's cancellation is authored by the guard post, as its send is
    @Test
    fun aGuardpostCancellationIsAuthoredByTheGuardPost() {
        val request = cancelRequest("guardpost", "33333333", "44444444444", "Poste de garde")
        val author = request.request.author.hcparties.single()
        assertThat(author.ids.map { it.value }).containsExactly("33333333000", "44444444444")
        assertThat(author.cds.single().value).isEqualTo("guardpost")
        assertThat(author.name).isEqualTo("Poste de garde")
        assertThat(request.request.id.value).startsWith("33333333000.")
        val sender = request.kmehrmessage.header.sender.hcparties.single()
        assertThat(sender.ids.map { it.value }).containsExactly("33333333000", "44444444444")
        // the transaction author stays the physician who signs under the guard post
        val txAuthor = request.kmehrmessage.folders.single().transactions.single().author.hcparties.single()
        assertThat(txAuthor.ids.map { it.value }).containsExactly("11111111111", "22222222222")
        assertThat(txAuthor.cds.single().value).isEqualTo("persphysician")
    }

    @Test
    fun aPhysiotherapistsCancellationIsAuthoredByThePhysiotherapist() {
        val request = cancelRequest("physiotherapist", null, null, null)
        val author = request.request.author.hcparties.single()
        assertThat(author.ids.map { it.value }).containsExactly("11111111111", "22222222222")
        assertThat(author.cds.single().value).isEqualTo("persphysiotherapist")
        assertThat(author.firstname).isEqualTo("Prénom")
        assertThat(request.kmehrmessage.folders.single().transactions.single().author.hcparties.single()
            .cds.single().value).isEqualTo("persphysiotherapist")
    }
}
