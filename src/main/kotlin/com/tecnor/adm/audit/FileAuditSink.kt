package com.tecnor.adm.audit

import com.tecnor.adm.api.AuditEvent
import com.tecnor.adm.api.AuditSink
import com.tecnor.adm.settings.SettingsSnapshot
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

class FileAuditSink(private val directory: Path, private val logger: Logger, snapshot: SettingsSnapshot) : AuditSink {
    private data class Options(val enabled: Boolean, val maxBytes: Long, val daily: Boolean, val retentionDays: Int)
    private data class Pending(val line: String, val day: String, val settings: Options, val bytes: Int)
    @Volatile private var options = options(snapshot)
    @Volatile private var closed = false
    private val lifecycle = Any()
    private val pending = ArrayBlockingQueue<Pending>(4096)
    private var pendingBytes = 0L
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ADM-audit").apply { isDaemon = true }
    }
    private var output: BufferedOutputStream? = null
    private var date = ""
    private var size = 0L
    private var lastPruneDay = ""

    init { writer.execute { drain() } }

    fun configure(snapshot: SettingsSnapshot) { options = options(snapshot) }

    override fun record(event: AuditEvent) {
        val settings = options
        if (!settings.enabled) return
        val line = encode(event)
        val bytes = line.toByteArray(Charsets.UTF_8).size + 1
        val entry = Pending(line, event.timestamp.atZone(ZoneOffset.UTC).toLocalDate().toString(), settings, bytes)
        val accepted = synchronized(lifecycle) {
            if (closed || pendingBytes + bytes > MAX_PENDING_BYTES || !pending.offer(entry)) false
            else {
                pendingBytes += bytes
                true
            }
        }
        if (!accepted) logger.warning("Audit writer full or closed; record was not queued ($bytes bytes).")
    }

    private fun drain() {
        val batch = ArrayList<Pending>(256)
        try {
            while (!closed || pending.isNotEmpty()) {
                val first = pending.poll(100, TimeUnit.MILLISECONDS) ?: continue
                batch.add(first)
                pending.drainTo(batch, 255)
                try {
                    for (entry in batch) {
                        val bytes = (entry.line + "\n").toByteArray(Charsets.UTF_8)
                        val settings = entry.settings
                        val day = entry.day
                        if (output == null || (settings.daily && date != day) || (size > 0 && size + bytes.size > settings.maxBytes)) {
                            output?.close()
                            output = null
                            Files.createDirectories(directory)
                            pruneOldFiles(day, settings.retentionDays)
                            var sequence = 0
                            var path: Path
                            do { path = directory.resolve("audit-$day-${sequence++}.jsonl") } while (Files.exists(path))
                            output = BufferedOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
                            date = day
                            size = 0
                        }
                        output!!.write(bytes)
                        size += bytes.size
                    }
                    output?.flush()
                } catch (failure: Exception) {
                    runCatching { output?.close() }
                    output = null
                    logger.log(Level.SEVERE, "Audit file write failed; batch records follow in the server log.", failure)
                    batch.forEach {
                        val line = it.line.take(MAX_LOG_FALLBACK_CHARS)
                        val suffix = if (it.line.length > line.length) "...(truncated)" else ""
                        logger.severe("Audit fallback record: $line$suffix")
                    }
                } finally {
                    val drainedBytes = batch.sumOf { it.bytes }.toLong()
                    batch.clear()
                    synchronized(lifecycle) { pendingBytes = (pendingBytes - drainedBytes).coerceAtLeast(0) }
                }
            }
        } finally {
            try {
                output?.close()
            } catch (failure: Exception) {
                logger.log(Level.SEVERE, "Audit file close failed.", failure)
            } finally {
                output = null
            }
        }
    }

    override fun close() { closeWithin(5000) }

    fun closeWithin(milliseconds: Long) {
        synchronized(lifecycle) {
            closed = true
            writer.shutdown()
        }
        try {
            if (!writer.awaitTermination(milliseconds.coerceAtLeast(0), TimeUnit.MILLISECONDS)) logger.warning("Audit writer is still draining queued records.")
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun options(snapshot: SettingsSnapshot) = Options(
        snapshot.value("audit.enabled") != false,
        snapshot.integer("audit.max-file-bytes", 10_485_760).toLong(),
        snapshot.value("audit.rotate-daily") != false,
        snapshot.integer("audit.retention-days", 90).coerceIn(1, 36500)
    )

    private fun pruneOldFiles(day: String, retentionDays: Int) {
        if (lastPruneDay == day) return
        val cutoff = LocalDate.parse(day).minusDays(retentionDays.toLong())
        try {
            Files.list(directory).use { files ->
                files.forEach { path ->
                    val match = AUDIT_FILE.matchEntire(path.fileName.toString()) ?: return@forEach
                    val fileDay = runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull() ?: return@forEach
                    if (fileDay.isBefore(cutoff)) Files.deleteIfExists(path)
                }
            }
            lastPruneDay = day
        } catch (failure: Exception) {
            logger.log(Level.WARNING, "Could not prune old file audit logs.", failure)
        }
    }

    private fun encode(event: AuditEvent): String {
        val fields = linkedMapOf("timestamp" to event.timestamp.toString(), "actor" to event.actor.toString(),
            "actorName" to event.actorName, "target" to event.target.toString(), "targetName" to event.targetName,
            "inventory" to event.inventory, "action" to event.action)
        return fields.entries.joinToString(",", "{", ",\"details\":{") { "${quote(it.key)}:${quote(it.value)}" } +
            event.details.entries.joinToString(",") { "${quote(it.key)}:${quote(it.value)}" } + "}}"
    }

    private fun quote(text: String) = buildString {
        append('"')
        text.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 32) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }

    private companion object {
        const val MAX_PENDING_BYTES = 8L * 1024 * 1024
        const val MAX_LOG_FALLBACK_CHARS = 2048
        val AUDIT_FILE = Regex("audit-(\\d{4}-\\d{2}-\\d{2})-\\d+\\.jsonl")
    }
}