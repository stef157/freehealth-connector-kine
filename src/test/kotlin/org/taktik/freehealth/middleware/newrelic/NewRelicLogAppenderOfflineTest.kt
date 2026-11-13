package org.taktik.freehealth.middleware.newrelic

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream

/**
 * What leaves for New Relic, offline: the appender posts to a loopback server, which keeps the bodies and headers.
 * Identifiers are synthetic, one per role.
 */
class NewRelicLogAppenderOfflineTest {
    private class Received(val headers: Map<String, String?>, val body: ByteArray)

    private lateinit var server: HttpServer
    private val received = CopyOnWriteArrayList<Received>()
    private val context = LoggerContext().apply { setMDCAdapter(org.slf4j.MDC.getMDCAdapter()) }

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/log/v1") { exchange ->
            received += Received(
                listOf("Api-Key", "Content-Encoding", "Content-Type").associateWith { exchange.requestHeaders.getFirst(it) },
                exchange.requestBody.readBytes()
            )
            exchange.sendResponseHeaders(202, -1)
            exchange.close()
        }
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
        context.stop()
    }

    private fun appender(key: String) = NewRelicLogAppender().apply {
        context = this@NewRelicLogAppenderOfflineTest.context
        licenseKey = key
        endpoint = "http://127.0.0.1:${server.address.port}/log/v1"
        environment = "test"
        flushIntervalMillis = 50
        start()
    }

    private fun logs(): List<Map<*, *>> = received.flatMap { r ->
        val json = ObjectMapper().readValue(GZIPInputStream(r.body.inputStream()).readBytes(), List::class.java)
        json.flatMap { ((it as Map<*, *>)["logs"] as List<*>).map { l -> l as Map<*, *> } }
    }

    @Test
    fun aLineArrivesGzippedWithTheKeyAndScrubbed() {
        val appender = appender("synthetic-key")
        val logger = context.getLogger("org.taktik.freehealth.middleware.service.impl.MemberDataServiceImpl")
        logger.addAppender(appender)
        logger.level = Level.INFO

        logger.info("mda: patient 12345678901, keystore 123e4567-e89b-12d3-a456-426614174000, value '22222222222'")
        logger.error(
            "send failed",
            IllegalStateException("INCORRECT_INSS_PHYSIOTHERAPIST_SAML 11111111111", RuntimeException("cvc: 'Dupont' invalid"))
        )
        appender.stop()

        assertThat(received).isNotEmpty
        assertThat(received.first().headers["Api-Key"]).isEqualTo("synthetic-key")
        assertThat(received.first().headers["Content-Encoding"]).isEqualTo("gzip")

        val all = String(received.flatMap { GZIPInputStream(it.body.inputStream()).readBytes().toList() }.toByteArray())
        listOf("12345678901", "11111111111", "22222222222", "123e4567-e89b", "Dupont").forEach {
            assertThat(all).doesNotContain(it)
        }

        val lines = logs()
        assertThat(lines).hasSize(2)
        assertThat(lines[0]["message"]).isEqualTo("mda: patient [digits], keystore [uuid], value '[value]'")
        val attributes = lines[1]["attributes"] as Map<*, *>
        assertThat(attributes["level"]).isEqualTo("ERROR")
        assertThat(attributes["logger.name"]).isEqualTo(logger.name)
        assertThat(attributes["error.stack"] as String).contains("INCORRECT_INSS_PHYSIOTHERAPIST_SAML [digits]")
    }

    @Test
    fun theServiceAndEnvironmentTravelAsCommonAttributes() {
        val appender = appender("synthetic-key")
        val logger = context.getLogger("x")
        logger.addAppender(appender)
        org.slf4j.MDC.put(NewRelicLogAppender.REQUEST_ID_MDC, "kd-attest.42")
        org.slf4j.MDC.put("other", "not shipped")
        try { logger.info("hello") } finally { org.slf4j.MDC.clear() }
        appender.stop()

        val attributes = logs().single()["attributes"] as Map<*, *>
        assertThat(attributes["request.id"]).isEqualTo("kd-attest.42")
        assertThat(attributes.keys).doesNotContain("other")

        val json = ObjectMapper().readValue(GZIPInputStream(received.first().body.inputStream()).readBytes(), List::class.java)
        val common = ((json[0] as Map<*, *>)["common"] as Map<*, *>)["attributes"] as Map<*, *>
        assertThat(common["service.name"]).isEqualTo("fhc")
        assertThat(common["environment"]).isEqualTo("test")
    }

    @Test
    fun withoutAKeyNothingLeaves() {
        val appender = appender("")
        val logger = context.getLogger("x")
        logger.addAppender(appender)
        logger.info("hello")
        appender.stop()

        assertThat(received).isEmpty()
    }

    @Test
    fun theRegionFollowsTheKey() {
        assertThat(NewRelicLogAppender.endpointFor("eu01xxabcdef")).isEqualTo("https://log-api.eu.newrelic.com/log/v1")
        assertThat(NewRelicLogAppender.endpointFor("abcdefNRAL")).isEqualTo("https://log-api.newrelic.com/log/v1")
    }

    @Test
    fun itsOwnHttpClientIsNotShipped() {
        assertThat(NewRelicLogAppender.isOwn("jdk.internal.net.http.HttpClientImpl")).isTrue()
        assertThat(NewRelicLogAppender.isOwn("org.taktik.freehealth.middleware.service.impl.X")).isFalse()
    }
}
