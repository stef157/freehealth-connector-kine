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

package org.taktik.connector.technical.handler

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.util.Properties

/**
 * Per-endpoint timeout keys — offline, no eHealth call.
 *
 * `ConnectionTimeOutHandler` looks a timeout up under `<prop>.<last two URL segments>`. The GenAsync keys used to be
 * written `generic.<domain>`, which no endpoint produces, so every channel silently fell back to 60 s
 * (Sentry KINEDESK-SATELLITE-5, `/eagreement/async/getMessages`). These checks run against the shipped files, so
 * an upstream merge that changes an endpoint URL without its key turns this red.
 */
class ConnectionTimeOutHandlerOfflineTest {
    private val files = listOf(
        "/acpt/org.taktik.connector.technical.properties",
        "/acpt/org.taktik.connector.technical.template.properties",
        "/prod/org.taktik.connector.technical.properties",
        "/prod/org.taktik.connector.technical.template.properties"
    )
    private val timeoutPrefixes = listOf(
        "connector.soaphandler.connection.request.timeout.",
        "connector.soaphandler.connection.connection.timeout."
    )
    // Hub endpoints are not configured here: the client passes the hub URL on each call (HubController), and some
    // hubs serve `…/V3/IntraHub.asmx`.
    private val clientSuppliedEndpoints = setOf("V3.IntraHub.asmx")

    private fun load(path: String) = Properties().apply {
        ConnectionTimeOutHandlerOfflineTest::class.java.getResourceAsStream(path).use { load(it!!) }
    }

    @Test
    fun theSuffixIsTheLastTwoUrlSegments() {
        assertThat(ConnectionTimeOutHandler.endpointProperty("https://prod.mycarenet.be:9443/mcn/bed/ehealth/GenAsync/eagreement"))
            .isEqualTo("GenAsync.eagreement")
        assertThat(ConnectionTimeOutHandler.endpointProperty("https://prod.mycarenet.be:9443/mycarenet/bed/GenAsync/memberData"))
            .isEqualTo("GenAsync.memberData")
        assertThat(ConnectionTimeOutHandler.endpointProperty("https://prod.mycarenet.be/mycarenet-ws/async/generic/hcpfac_12"))
            .isEqualTo("generic.hcpfac_12")
        assertThat(ConnectionTimeOutHandler.endpointProperty("https://prod.mycarenet.be:9443/nip/mycarenet-ws/async/generic/gmd"))
            .isEqualTo("generic.gmd")
        assertThat(ConnectionTimeOutHandler.endpointProperty(null)).isNull()
    }

    @Test
    fun everyGenAsyncEndpointGetsItsTwoMinutes() {
        files.forEach { path ->
            val props = load(path)
            val endpoints = props.stringPropertyNames().filter { it.startsWith("endpoint.genericasync.") }
            assertThat(endpoints).describedAs(path).isNotEmpty
            endpoints.forEach { key ->
                val url = props.getProperty(key).trim()
                val timeoutKey = "connector.soaphandler.connection.request.timeout." + ConnectionTimeOutHandler.endpointProperty(url)
                assertThat(props.getProperty(timeoutKey)).describedAs("$path: $key = $url needs $timeoutKey").isEqualTo("120000")
            }
        }
    }

    @Test
    fun noTimeoutKeyTargetsAnEndpointThatDoesNotExist() {
        files.forEach { path ->
            val props = load(path)
            val suffixes = props.stringPropertyNames().filter { it.startsWith("endpoint.") }
                .mapNotNull { ConnectionTimeOutHandler.endpointProperty(props.getProperty(it).trim()) }.toSet() +
                clientSuppliedEndpoints
            props.stringPropertyNames().forEach { key ->
                timeoutPrefixes.firstOrNull { key.startsWith(it) }?.let { prefix ->
                    assertThat(suffixes).describedAs("$path: $key matches no endpoint").contains(key.removePrefix(prefix))
                }
            }
        }
    }
}
