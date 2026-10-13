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

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import org.mockito.Mockito.mock
import org.taktik.connector.technical.service.keydepot.KeyDepotService
import org.taktik.freehealth.middleware.service.impl.EattestV3ServiceImpl
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

    // the Dentadesk case: a dentist's token, with or without the parameter
    @Test
    fun aDentistsQualityIsWritten() {
        assertThat(resolve(null, null, "dentist")).isEqualTo("dentist")
        assertThat(resolve("dentist", null, "dentist")).isEqualTo("dentist")
        assertThatThrownBy { resolve("physiotherapist", null, "dentist") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
