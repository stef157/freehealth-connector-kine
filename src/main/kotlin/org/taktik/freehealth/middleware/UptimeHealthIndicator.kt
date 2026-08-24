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

import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component
import java.lang.management.ManagementFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Reports how long this JVM has been running, as details of the /actuator/health endpoint.
 * Requires management.endpoint.health.show-details, otherwise only the aggregated status is returned.
 */
@Component
class UptimeHealthIndicator : HealthIndicator {
    override fun health(): Health {
        val runtime = ManagementFactory.getRuntimeMXBean()
        val uptimeMillis = runtime.uptime

        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").apply { timeZone = TimeZone.getTimeZone("UTC") }

        return Health.up()
            .withDetail("uptime", humanReadable(uptimeMillis))
            .withDetail("uptimeMillis", uptimeMillis)
            .withDetail("startedAt", iso.format(Date(runtime.startTime)))
            .build()
    }

    // Java 8 target: Duration.toDaysPart() and friends are not available
    private fun humanReadable(millis: Long): String {
        val days = TimeUnit.MILLISECONDS.toDays(millis)
        val hours = TimeUnit.MILLISECONDS.toHours(millis) % 24
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60

        return when {
            days > 0 -> "${days}d ${hours}h ${minutes}m ${seconds}s"
            hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }
}
