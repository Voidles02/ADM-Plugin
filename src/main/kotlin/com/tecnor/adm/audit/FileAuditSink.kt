package com.tecnor.adm.audit

import com.tecnor.adm.api.AuditEvent
import com.tecnor.adm.api.AuditSink
import com.tecnor.adm.settings.SettingsSnapshot
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.ZoneOffset
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

class FileAuditSink(private val directory: Path, private val logger: Logger, snapshot: SettingsSnapshot) : AuditSink {
    private data class Options(val enabled: Boolean, val maxBytes: Long, val daily: Boolean)
    private data class Pending(val line: String, val day: String, val settings: Options)
    @Volatile private var options = options(snapshot)
    @Volatile private var closed = false
    private val lifecycle = Any()
    private val pending = ArrayBlockingQueue<Pending>(4096)
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ADM-audit").apply { isDaemon = true }
    }
    private var output: BufferedOutputStream? = null
    private var date = ""
    private var size = 0L

    init { writer.execute { drain() } }

    fun configure(snapshot: SettingsSnapshot) { options = options(snapshot) }

    override fun record(event: AuditEvent) {
        val settings = options
        if (!settings.enabled) return
        val line = encode(event)
        val entry = Pending(line, event.timestamp.atZone(ZoneOffset.UTC).toLocalDate().toString(), settings)
        val accepted = synchronized(lifecycle) { !closed && pending.offer(entry) }
        if (!accepted) logger.warning("Audit writer full or closed; fallback record: $line")
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
                    batch.forEach { logger.severe("Audit fallback record: ${it.line}") }
                } finally {
                    batch.clear()
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
        snapshot.value("audit.rotate-daily") != false
    )

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
}