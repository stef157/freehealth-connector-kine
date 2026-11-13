package org.taktik.freehealth.middleware.newrelic

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.UnsynchronizedAppenderBase
import com.fasterxml.jackson.databind.ObjectMapper
import org.taktik.freehealth.middleware.sentry.SentryScrubber
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream

/**
 * Ships log lines to New Relic's Log API, when `NEW_RELIC_LICENSE_KEY` is set, and does nothing otherwise — the
 * fork is public, so the key never sits in the repository.
 *
 * Every line goes through [SentryScrubber], message and stack trace alike: the cause chain of a MyCareNet fault
 * names the SSIN, an XSD failure quotes the offending value. No argument travels, and of the MDC only the
 * request id ([org.taktik.freehealth.middleware.web.RequestIdFilter]), as `request.id`. The appender never
 * blocks the logging thread: lines wait in a bounded queue, a full queue drops them (and counts them), and one
 * daemon thread posts them in gzipped batches. A failed post is reported through logback's own status, never
 * through a logger, which would feed this appender.
 *
 * The New Relic Java agent is deliberately not used: it names transactions after the request URI, and
 * `/mda/{ssin}` carries the SSIN in the path.
 */
class NewRelicLogAppender : UnsynchronizedAppenderBase<ILoggingEvent>() {
    var licenseKey: String = ""
    /** Empty: derived from the key, EU keys starting with `eu`. */
    var endpoint: String = ""
    var environment: String = ""
    var serviceName: String = "fhc"
    var queueSize: Int = 10_000
    var batchSize: Int = 500
    var flushIntervalMillis: Long = 5_000

    internal class Line(
        val timestamp: Long,
        val level: String,
        val logger: String,
        val thread: String,
        val message: String?,
        val stackTrace: String?,
        val requestId: String? = null
    )

    private lateinit var queue: ArrayBlockingQueue<Line>
    private var worker: Thread? = null
    private var client: HttpClient? = null
    private val dropped = AtomicLong()
    private val mapper = ObjectMapper()
    @Volatile private var enabled = false

    override fun start() {
        queue = ArrayBlockingQueue(queueSize.coerceAtLeast(1))
        if (licenseKey.isBlank()) {
            addInfo("New Relic logs disabled: no NEW_RELIC_LICENSE_KEY")
        } else {
            if (endpoint.isBlank()) endpoint = endpointFor(licenseKey)
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
            enabled = true
            worker = Thread(::run, "newrelic-logs").apply { isDaemon = true; start() }
            addInfo("New Relic logs enabled: $endpoint, environment ${environment.ifBlank { "-" }}")
        }
        super.start()
    }

    override fun stop() {
        if (!isStarted) return
        super.stop()
        if (enabled) {
            // no interrupt: it would abort the POST in flight and lose that batch; the worker drains, then exits
            enabled = false
            worker?.join(30_000)
            if (worker?.isAlive == true) worker?.interrupt() else flushAll()
        }
    }

    override fun append(event: ILoggingEvent) {
        if (!enabled || isOwn(event.loggerName)) return
        val line = Line(
            event.timeStamp,
            event.level.toString(),
            event.loggerName,
            event.threadName,
            event.formattedMessage,
            event.throwableProxy?.let { ThrowableProxyUtil.asString(it) },
            event.mdcPropertyMap?.get(REQUEST_ID_MDC)
        )
        if (!queue.offer(line)) dropped.incrementAndGet()
    }

    private fun run() {
        try {
            while (true) {
                val first = queue.poll(if (enabled) flushIntervalMillis else 0, TimeUnit.MILLISECONDS)
                    ?: if (enabled) continue else return
                val batch = mutableListOf(first)
                queue.drainTo(batch, batchSize - 1)
                send(batch)
            }
        } catch (_: InterruptedException) {
            // stop() gave up waiting: what is left in the queue is lost
        }
    }

    private fun flushAll() {
        while (true) {
            val batch = mutableListOf<Line>()
            queue.drainTo(batch, batchSize)
            if (batch.isEmpty()) return
            send(batch)
        }
    }

    private fun send(batch: List<Line>) {
        try {
            val body = gzip(payload(batch, dropped.getAndSet(0)))
            val request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(15))
                .header("Api-Key", licenseKey)
                .header("Content-Type", "application/json")
                .header("Content-Encoding", "gzip")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build()
            val response = client!!.send(request, HttpResponse.BodyHandlers.discarding())
            if (response.statusCode() / 100 != 2) {
                addWarn("New Relic Log API answered ${response.statusCode()}, ${batch.size} lines lost")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            addWarn("New Relic Log API unreachable, ${batch.size} lines lost: ${e.javaClass.name}")
        }
    }

    internal fun payload(batch: List<Line>, droppedSinceLastBatch: Long): ByteArray {
        val common = mutableMapOf<String, Any>("service.name" to serviceName)
        if (environment.isNotBlank()) common["environment"] = environment
        release?.let { common["release"] = it }
        if (droppedSinceLastBatch > 0) common["fhc.droppedLines"] = droppedSinceLastBatch
        val logs = batch.map { line ->
            val attributes = mutableMapOf<String, Any>(
                "level" to line.level,
                "logger.name" to line.logger,
                "thread.name" to line.thread
            )
            scrub(line.stackTrace)?.let { attributes["error.stack"] = it }
            line.requestId?.let { attributes["request.id"] = it }
            mapOf(
                "timestamp" to line.timestamp,
                "message" to (scrub(line.message) ?: ""),
                "attributes" to attributes
            )
        }
        return mapper.writeValueAsBytes(listOf(mapOf("common" to mapOf("attributes" to common), "logs" to logs)))
    }

    companion object {
        const val REQUEST_ID_MDC = "requestId"
        private const val MAX_FIELD = 16_000
        private val ownPackages = listOf(NewRelicLogAppender::class.java.packageName, "jdk.internal.net.http")
        private val release: String? = NewRelicLogAppender::class.java.`package`?.implementationVersion

        fun endpointFor(licenseKey: String) =
            if (licenseKey.startsWith("eu", ignoreCase = true)) "https://log-api.eu.newrelic.com/log/v1"
            else "https://log-api.newrelic.com/log/v1"

        internal fun isOwn(loggerName: String) = ownPackages.any { loggerName.startsWith(it) }

        internal fun scrub(text: String?): String? = SentryScrubber.scrub(text)?.let {
            if (it.length > MAX_FIELD) it.substring(0, MAX_FIELD) + "…[truncated]" else it
        }

        private fun gzip(bytes: ByteArray): ByteArray =
            ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
    }
}
