package com.tecnor.adm.storage

import com.tecnor.adm.api.Storage
import com.tecnor.adm.api.StorageHealth
import com.tecnor.adm.api.StoredPlayer
import com.tecnor.adm.api.StorageTaskScope
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.logging.Level

class EmbeddedStorage(private val plugin: JavaPlugin) : Storage {
    @Volatile override var health = StorageHealth("STARTING")
        private set
    @Volatile private var connectionAvailable = false
    private var connection: Connection? = null
    private val pending = ConcurrentHashMap<String, Int>()
    private val failureLogs = LinkedHashMap<String, Pair<Long, Int>>()
    override val pendingTasks: Int get() = pending.values.sum()
    override fun pendingByModule(): Map<String, Int> = pending.toMap()

    private fun admitted(module: String) { pending.compute(module) { _, count -> (count ?: 0) + 1 } }
    private fun finished(module: String) { pending.computeIfPresent(module) { _, count -> if (count <= 1) null else count - 1 } }
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(128),
        { task -> Thread({
            try { task.run() } finally {
                connectionAvailable = false
                runCatching { connection?.close() }
            }
        }, "ADM-database").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    val ready = CompletableFuture<Boolean>()

    init {
        admitted("storage")
        executor.execute {
            val start = System.nanoTime()
            try {
                val directory = plugin.dataFolder.toPath()
                Files.createDirectories(directory)
                Class.forName("org.h2.Driver", true, javaClass.classLoader)
                val databasePath = directory.resolve("adm").toAbsolutePath().normalize()
                val database = DriverManager.getConnection(
                    "jdbc:h2:file:$databasePath;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=3000;CACHE_SIZE=2048;WRITE_DELAY=0"
                )
                connection = database
                migrate(database)
                database.transaction {
                    val version = checkNotNull(database.queryFirst("SELECT version FROM adm_schema") { it.getInt(1) })
                    check(database.update("UPDATE adm_schema SET version=? WHERE version=?", version + 1, version) == 1)
                    check(database.queryFirst("SELECT version FROM adm_schema") { it.getInt(1) } == version + 1)
                    check(database.update("UPDATE adm_schema SET version=? WHERE version=?", version, version + 1) == 1)
                    check(database.queryFirst("SELECT version FROM adm_schema") { it.getInt(1) } == version)
                }
                val h2Version = checkNotNull(database.queryFirst("SELECT H2VERSION()") { it.getString(1) })
                connectionAvailable = true
                plugin.logger.info("H2 $h2Version connected at ${databasePath}.mv.db; migrations and read/write self-check passed")
                success(start)
                ready.complete(true)
            } catch (failure: Throwable) {
                failed(failure)
                connectionAvailable = false
                runCatching { connection?.close() }
                connection = null
                ready.complete(false)
            } finally {
                finished("storage")
            }
        }
    }

    private fun migrate(db: Connection) {
        db.update("CREATE TABLE IF NOT EXISTS adm_schema(version INTEGER NOT NULL)")
        val storedVersion = db.queryFirst("SELECT version FROM adm_schema") { it.getInt(1) }
        if (storedVersion == null) db.update("INSERT INTO adm_schema(version) VALUES(0)")
        var version = storedVersion ?: 0
        require(version <= 4) { "Database schema $version is newer than this ADM version" }
        if (version < 1) db.transaction {
            db.update("CREATE TABLE IF NOT EXISTS players(uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(16) NOT NULL, ip VARCHAR(45) NOT NULL, first_login BIGINT NOT NULL, last_login BIGINT NOT NULL, player_rank INTEGER NOT NULL DEFAULT 0, immune BOOLEAN NOT NULL DEFAULT FALSE)")
            db.update("CREATE TABLE IF NOT EXISTS names(name VARCHAR_IGNORECASE(16) NOT NULL, uuid VARCHAR(36) NOT NULL REFERENCES players(uuid), first_seen BIGINT NOT NULL, last_seen BIGINT NOT NULL, PRIMARY KEY(name,uuid))")
            db.update("CREATE INDEX IF NOT EXISTS names_lookup ON names(name,last_seen DESC)")
            db.update("CREATE INDEX IF NOT EXISTS players_ip ON players(ip)")
            db.update("CREATE TABLE IF NOT EXISTS punishments(id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, target VARCHAR(36) NOT NULL, target_name VARCHAR(255) NOT NULL, ip VARCHAR(45), kind VARCHAR(16) NOT NULL, reason TEXT NOT NULL, staff VARCHAR(36) NOT NULL, staff_name VARCHAR(255) NOT NULL, created BIGINT NOT NULL, expires BIGINT, revoked BIGINT, cleared BOOLEAN NOT NULL DEFAULT FALSE)")
            db.update("CREATE INDEX IF NOT EXISTS punishment_target ON punishments(target,created DESC)")
            db.update("CREATE INDEX IF NOT EXISTS punishment_ip ON punishments(ip,kind)")
            db.update("UPDATE adm_schema SET version=1")
        }
        version = maxOf(version, 1)
        if (version < 2) db.transaction {
            db.update("CREATE TABLE IF NOT EXISTS frozen(uuid VARCHAR(36) PRIMARY KEY, staff VARCHAR(36) NOT NULL, created BIGINT NOT NULL)")
            db.update("CREATE TABLE IF NOT EXISTS staff_snapshots(uuid VARCHAR(36) PRIMARY KEY, data BLOB NOT NULL, created BIGINT NOT NULL)")
            db.update("UPDATE adm_schema SET version=2")
        }
        version = maxOf(version, 2)
        if (version < 3) db.transaction {
            db.update("CREATE TABLE IF NOT EXISTS reports(id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, reporter VARCHAR(36) NOT NULL, reporter_name VARCHAR(16) NOT NULL, target VARCHAR(36) NOT NULL, target_name VARCHAR(16) NOT NULL, reason TEXT NOT NULL, created BIGINT NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'OPEN', claimed_by VARCHAR(255), closed_by VARCHAR(255))")
            db.update("CREATE INDEX IF NOT EXISTS report_order ON reports(created DESC,id DESC)")
            db.update("CREATE TABLE IF NOT EXISTS audit(id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, staff VARCHAR(36) NOT NULL, staff_name VARCHAR_IGNORECASE(255) NOT NULL, target VARCHAR(36) NOT NULL, target_name VARCHAR_IGNORECASE(255) NOT NULL, action VARCHAR_IGNORECASE(255) NOT NULL, details TEXT NOT NULL, created BIGINT NOT NULL, source VARCHAR(32) NOT NULL)")
            db.update("CREATE INDEX IF NOT EXISTS audit_time ON audit(created DESC,id DESC)")
            db.update("CREATE INDEX IF NOT EXISTS audit_staff ON audit(staff_name,created DESC)")
            db.update("CREATE INDEX IF NOT EXISTS audit_target ON audit(target_name,created DESC)")
            db.update("CREATE INDEX IF NOT EXISTS audit_action ON audit(action,created DESC)")
            db.update("UPDATE adm_schema SET version=3")
        }
        version = maxOf(version, 3)
        if (version < 4) db.transaction {
            db.update("CREATE TABLE IF NOT EXISTS hud_preferences(uuid VARCHAR(36) PRIMARY KEY,sounds BOOLEAN NOT NULL DEFAULT TRUE,confirmations BOOLEAN NOT NULL DEFAULT TRUE,compact BOOLEAN NOT NULL DEFAULT FALSE)")
            db.update("UPDATE adm_schema SET version=4")
        }
    }

    override fun <T> submit(work: (Connection) -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        if (ready.isDone && !connectionAvailable) {
            future.completeExceptionally(IllegalStateException("Embedded storage is unavailable: ${health.lastError.ifBlank { health.state }}"))
            return future
        }
        val module = StorageTaskScope.current()
        val source = com.tecnor.adm.api.ActionOrigin.current()
        val task = DatabaseTask(module, source, work, future)
        admitted(module)
        try {
            executor.execute(task)
        } catch (failure: RejectedExecutionException) {
            failed(failure)
            task.reject(failure)
        }
        return future
    }

    private inner class DatabaseTask<T>(
        private val module: String,
        private val source: String,
        work: (Connection) -> T,
        private val future: CompletableFuture<T>
    ) : Runnable {
        private var work: ((Connection) -> T)? = work
        private val start = System.nanoTime()

        override fun run() {
            val operation = work ?: return
            work = null
            StorageTaskScope.within(module) {
                com.tecnor.adm.api.ActionOrigin.within(source) {
                    if (connectionAvailable) {
                        try {
                            val db = connection ?: error("Embedded database connection unavailable")
                            val result = operation(db)
                            success(start)
                            future.complete(result)
                        } catch (failure: Throwable) {
                            failed(failure)
                            if (runCatching { connection?.isClosed != false }.getOrDefault(true)) connectionAvailable = false
                            future.completeExceptionally(failure)
                        } finally { finished(module) }
                    } else {
                        future.completeExceptionally(IllegalStateException("Embedded storage is unavailable: ${health.lastError.ifBlank { health.state }}"))
                        finished(module)
                    }
                }
            }
        }

        fun reject(failure: Throwable) {
            work = null
            finished(module)
            future.completeExceptionally(failure)
        }
    }

    override fun resolve(input: String): CompletableFuture<StoredPlayer?> = submit { db ->
        val id = runCatching { UUID.fromString(input).toString() }.getOrNull()
        if (id != null) db.queryFirst("SELECT * FROM players WHERE uuid=?", id) { it.storedPlayer() }
        else db.queryFirst("SELECT p.* FROM names n JOIN players p ON n.uuid=p.uuid WHERE n.name=? ORDER BY n.last_seen DESC LIMIT 1", input) { it.storedPlayer() }
    }

    private fun success(start: Long) {
        health = StorageHealth("CONNECTED", System.currentTimeMillis(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), health.lastError)
    }

    private fun failed(failure: Throwable) {
        health = health.copy(state = "FAILED", lastError = failure.message ?: failure.javaClass.simpleName)
        val signature = "${failure.javaClass.name}:${failure.message.orEmpty()}"
        val now = System.currentTimeMillis()
        val suppressed = synchronized(failureLogs) {
            val previous = failureLogs[signature]
            if (previous == null || now - previous.first >= 60_000) {
                failureLogs[signature] = now to 0
                while (failureLogs.size > 64) failureLogs.remove(failureLogs.keys.first())
                previous?.second ?: 0
            } else {
                failureLogs[signature] = previous.first to previous.second + 1
                null
            }
        }
        if (suppressed != null) {
            val detail = if (suppressed == 0) "" else " ($suppressed identical failures suppressed in the last minute)"
            plugin.logger.log(Level.SEVERE, "ADM database operation failed$detail", failure)
        }
    }

    override fun reportFailure(failure: Throwable) { failed(failure) }

    override fun close() { closeWithin(5000) }

    fun closeWithin(milliseconds: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds.coerceAtLeast(0))
        executor.shutdown()
        try {
            if (!executor.awaitTermination((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)) {
                plugin.logger.severe("ADM database did not flush within the shutdown deadline")
                rejectQueuedTasks()
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            rejectQueuedTasks()
        }
        health = health.copy(state = "CLOSED")
        pending.clear()
    }

    private fun rejectQueuedTasks() {
        val failure = RejectedExecutionException("ADM database closed before queued work could run")
        executor.shutdownNow().forEach { task ->
            if (task is EmbeddedStorage.DatabaseTask<*>) task.reject(failure)
        }
    }
}