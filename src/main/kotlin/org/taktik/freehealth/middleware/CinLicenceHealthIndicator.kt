/*
 *
 * Copyright (C) 2018 Taktik SA
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

package org.taktik.freehealth.middleware

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component
import org.taktik.connector.technical.config.ConfigFactory

/**
 * Tells whether this JVM was started with a CIN licence and a registered package name, as details of the
 * /actuator/health endpoint and as a WARN at startup.
 *
 * Both travel through JAVA_OPTS: a container recreated from an older command line boots normally and is only
 * found out when MyCareNet refuses its calls. The status stays UP - a licence may legitimately come from the
 * authenticated user instead (User.mcnLicense) - and no value is ever reported, the endpoint being unauthenticated.
 */
@Component
class CinLicenceHealthIndicator : HealthIndicator {
    private val log = LoggerFactory.getLogger(CinLicenceHealthIndicator::class.java)
    private val config = ConfigFactory.getConfigValidator(emptyList())

    @PostConstruct
    fun init() {
        val state = state()
        if (!state.licenceConfigured) {
            log.warn("CIN licence missing: mycarenet.license.username / .password are not set, MyCareNet calls will be refused")
        }
        if (!state.packageNameCustom) {
            log.warn("package.name is still the connector's default '{}': the CIN ties the licence to the registered name", DEFAULT_PACKAGE_NAME)
        }
    }

    override fun health(): Health {
        val state = state()
        return Health.up()
            .withDetail("licence", if (state.licenceConfigured) "configured" else "missing")
            .withDetail("packageName", if (state.packageNameCustom) "custom" else "default")
            .build()
    }

    // Read on every call, through the same path as the services: a -D system property wins over the file.
    // The one-argument getProperty on purpose: the two-argument form resolves its default as another key.
    private fun state() = evaluate(
        config.getProperty("mycarenet.license.username"),
        config.getProperty("mycarenet.license.password"),
        config.getProperty("package.name")
    )

    data class State(val licenceConfigured: Boolean, val packageNameCustom: Boolean)

    companion object {
        const val DEFAULT_PACKAGE_NAME = "Freehealth-Connector"

        fun evaluate(username: String?, password: String?, packageName: String?) = State(
            licenceConfigured = !username.isNullOrBlank() && !password.isNullOrBlank(),
            packageNameCustom = !packageName.isNullOrBlank() && packageName.trim() != DEFAULT_PACKAGE_NAME
        )
    }
}
