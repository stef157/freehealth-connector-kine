package org.taktik.freehealth.middleware

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.springframework.boot.actuate.health.Status

/**
 * The CIN licence guard - offline, no eHealth call, no Spring context.
 *
 * The licence and the registered package name reach the JVM through JAVA_OPTS. What is measured here is that
 * their absence is reported, that a -D is what settles it, and that no value ever leaves through the health
 * endpoint, which is unauthenticated.
 */
class CinLicenceHealthIndicatorOfflineTest {
    private val keys = listOf("mycarenet.license.username", "mycarenet.license.password", "package.name")

    private fun <T> withSystemProperties(values: Map<String, String>, block: () -> T): T {
        val before = keys.associateWith { System.getProperty(it) }
        try {
            keys.forEach { System.clearProperty(it) }
            values.forEach { (k, v) -> System.setProperty(k, v) }
            return block()
        } finally {
            before.forEach { (k, v) -> if (v == null) System.clearProperty(k) else System.setProperty(k, v) }
        }
    }

    @Test
    fun aLicenceNeedsBothHalves() {
        assertThat(CinLicenceHealthIndicator.evaluate("user", "secret", "Kine-Desk").licenceConfigured).isTrue()
        assertThat(CinLicenceHealthIndicator.evaluate("user", "", "Kine-Desk").licenceConfigured).isFalse()
        assertThat(CinLicenceHealthIndicator.evaluate("", "secret", "Kine-Desk").licenceConfigured).isFalse()
        assertThat(CinLicenceHealthIndicator.evaluate(null, null, null).licenceConfigured).isFalse()
    }

    @Test
    fun theConnectorsOwnPackageNameIsNotARegisteredOne() {
        assertThat(CinLicenceHealthIndicator.evaluate("u", "p", "Freehealth-Connector").packageNameCustom).isFalse()
        assertThat(CinLicenceHealthIndicator.evaluate("u", "p", "").packageNameCustom).isFalse()
        assertThat(CinLicenceHealthIndicator.evaluate("u", "p", "Kine-Desk").packageNameCustom).isTrue()
    }

    // An empty -D is what Compose passes when its .env is missing: it must read as missing, not as configured.
    @Test
    fun anEmptySystemPropertyReadsAsMissing() {
        val health = withSystemProperties(mapOf("mycarenet.license.username" to "", "mycarenet.license.password" to "")) {
            CinLicenceHealthIndicator().health()
        }
        assertThat(health.status).isEqualTo(Status.UP)
        assertThat(health.details["licence"]).isEqualTo("missing")
        assertThat(health.details["packageName"]).isEqualTo("default")
    }

    // No -D at all, the state of a container recreated from a command line that predates the licence: the jar
    // carries no password, and reading an absent key must answer, not throw.
    @Test
    fun anAbsentPasswordReadsAsMissing() {
        val health = withSystemProperties(emptyMap()) { CinLicenceHealthIndicator().health() }
        assertThat(health.status).isEqualTo(Status.UP)
        assertThat(health.details["licence"]).isEqualTo("missing")
    }

    @Test
    fun systemPropertiesSettleItAndNoValueIsReported() {
        val health = withSystemProperties(
            mapOf(
                "mycarenet.license.username" to "synthetic-user",
                "mycarenet.license.password" to "synthetic-secret",
                "package.name" to "Synthetic-Package"
            )
        ) { CinLicenceHealthIndicator().health() }

        assertThat(health.status).isEqualTo(Status.UP)
        assertThat(health.details["licence"]).isEqualTo("configured")
        assertThat(health.details["packageName"]).isEqualTo("custom")
        assertThat(health.details.toString()).doesNotContain("synthetic")
    }
}
