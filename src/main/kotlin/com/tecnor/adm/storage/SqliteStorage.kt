package com.tecnor.adm.storage

import com.tecnor.adm.api.Storage
import com.tecnor.adm.api.StorageHealth
import com.tecnor.adm.api.StoredPlayer
import com.tecnor.adm.api.StorageTaskScope
import org.bukkit.plugin.java.JavaPlugin
import java.net.URI
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.Driver
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.logging.Level

class SqliteStorage(private val plugin: JavaPlugin) : Storage {
    @Volatile override var health = StorageHealth("STARTING")
        private set
    @Volatile private var connectionAvailable = false
    private var connection: Connection? = null
    private var driverLoader: URLClassLoader? = null
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
                runCatching { driverLoader?.close() }
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
                val driverClass = runCatching { Class.forName("org.sqlite.JDBC", true, javaClass.classLoader) }.getOrElse {
                    val supplied = listOf(directory.resolve("sqlite-jdbc-3.53.4.0.jar"), directory.resolve("libraries/sqlite-jdbc.jar"))
                        .firstOrNull { Files.isRegularFile(it) }
                    val jar = supplied ?: directory.resolve("libraries/sqlite-jdbc-3.53.4.0.jar")
                    Files.createDirectories(jar.parent)
                    if (!Files.isRegularFile(jar)) {
                        val temporary = jar.resolveSibling("sqlite-jdbc.download")
                        try {
                            val remote = URI("https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/3.53.4.0/sqlite-jdbc-3.53.4.0.jar").toURL().openConnection()
                            remote.connectTimeout = 5000
                            remote.readTimeout = 5000
                            remote.getInputStream().use { Files.copy(it, temporary, StandardCopyOption.REPLACE_EXISTING) }
                            Files.move(temporary, jar, StandardCopyOption.REPLACE_EXISTING)
                        } finally {
                            Files.deleteIfExists(temporary)
                        }
                    }
                    val loader = URLClassLoader(arrayOf(jar.toUri().toURL()), javaClass.classLoader)
                    driverLoader = loader
                    Class.forName("org.sqlite.JDBC", true, loader)
                }
                val driver = driverClass.getDeclaredConstructor().newInstance() as Driver
                val database = driver.connect("jdbc:sqlite:${directory.resolve("adm.sqlite").toAbsolutePath()}", Properties())
                    ?: error("SQLite driver rejected the database URL")
                connection = database
                database.createStatement().use { statement ->
                    statement.execute("PRAGMA busy_timeout=3000")
                    statement.execute("PRAGMA journal_mode=WAL")
                    statement.execute("PRAGMA foreign_keys=ON")
                    statement.execute("PRAGMA cache_size=-1024")
                    statement.execute("PRAGMA temp_store=FILE")
                    statement.execute("PRAGMA mmap_size=0")
                }
                migrate(database)
                database.transaction {
                    database.update("CREATE TEMP TABLE adm_connection_test(value INTEGER NOT NULL)")
                    database.update("INSERT INTO adm_connection_test(value) VALUES(1)")
                    check(database.queryFirst("SELECT value FROM adm_connection_test") { it.getInt(1) } == 1) { "SQLite read/write self-check failed" }
                    database.update("DROP TABLE adm_connection_test")
                }
                val sqliteVersion = checkNotNull(database.queryFirst("SELECT sqlite_version()") { it.getString(1) })
                connectionAvailable = true
                plugin.logger.info("SQLite $sqliteVersion connected at ${directory.resolve("adm.sqlite").toAbsolutePath()}; migrations and read/write self-check passed")
                success(start)
                ready.complete(true)
            } catch (failure: Throwable) {
                failed(failure)
                connectionAvailable = false
                runCatching { connection?.close() }
                connection = null
                runCatching { driverLoader?.close() }
                driverLoader = null
                ready.complete(false)
            } finally {
                finished("storage")
            }
        }
    }

    private fun migrate(db: Connection) {
        val version = checkNotNull(db.queryFirst("PRAGMA user_version") { it.getInt(1) })
        require(version <= 4) { "Database schema $version is newer than this ADM version" }
        if (version < 1) db.transaction {
            db.update("CREATE TABLE players(uuid TEXT PRIMARY KEY, name TEXT NOT NULL, ip TEXT NOT NULL, first_login INTEGER NOT NULL, last_login INTEGER NOT NULL, rank INTEGER NOT NULL DEFAULT 0, immune INTEGER NOT NULL DEFAULT 0)")
            db.update("CREATE TABLE names(name TEXT NOT NULL COLLATE NOCASE, uuid TEXT NOT NULL REFERENCES players(uuid), first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL, PRIMARY KEY(name,uuid))")
            db.update("CREATE INDEX names_lookup ON names(name COLLATE NOCASE,last_seen DESC)")
            db.update("CREATE INDEX players_ip ON players(ip)")
            db.update("CREATE TABLE punishments(id INTEGER PRIMARY KEY AUTOINCREMENT, target TEXT NOT NULL, target_name TEXT NOT NULL, ip TEXT, kind TEXT NOT NULL, reason TEXT NOT NULL, staff TEXT NOT NULL, staff_name TEXT NOT NULL, created INTEGER NOT NULL, expires INTEGER, revoked INTEGER, cleared INTEGER NOT NULL DEFAULT 0)")
            db.update("CREATE INDEX punishment_target ON punishments(target,created DESC)")
            db.update("CREATE INDEX punishment_ip ON punishments(ip,kind)")
            db.update("PRAGMA user_version=1")
        }
        if (version < 2) db.transaction {
            db.update("CREATE TABLE frozen(uuid TEXT PRIMARY KEY, staff TEXT NOT NULL, created INTEGER NOT NULL)")
            db.update("CREATE TABLE staff_snapshots(uuid TEXT PRIMARY KEY, data BLOB NOT NULL, created INTEGER NOT NULL)")
            db.update("PRAGMA user_version=2")
        }
        if (version < 3) db.transaction {
            db.update("CREATE TABLE reports(id INTEGER PRIMARY KEY AUTOINCREMENT, reporter TEXT NOT NULL, reporter_name TEXT NOT NULL, target TEXT NOT NULL, target_name TEXT NOT NULL, reason TEXT NOT NULL, created INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'OPEN', claimed_by TEXT, closed_by TEXT)")
            db.update("CREATE INDEX report_order ON reports(created DESC,id DESC)")
            db.update("CREATE TABLE audit(id INTEGER PRIMARY KEY AUTOINCREMENT, staff TEXT NOT NULL, staff_name TEXT NOT NULL, target TEXT NOT NULL, target_name TEXT NOT NULL, action TEXT NOT NULL, details TEXT NOT NULL, created INTEGER NOT NULL, source TEXT NOT NULL)")
            db.update("CREATE INDEX audit_time ON audit(created DESC,id DESC)")
            db.update("CREATE INDEX audit_staff ON audit(staff_name COLLATE NOCASE,created DESC)")
            db.update("CREATE INDEX audit_target ON audit(target_name COLLATE NOCASE,created DESC)")
            db.update("CREATE INDEX audit_action ON audit(action COLLATE NOCASE,created DESC)")
            db.update("PRAGMA user_version=3")
        }
        if (version < 4) db.transaction {
            db.update("CREATE TABLE IF NOT EXISTS hud_preferences(uuid TEXT PRIMARY KEY,sounds INTEGER NOT NULL DEFAULT 1,confirmations INTEGER NOT NULL DEFAULT 1,compact INTEGER NOT NULL DEFAULT 0)")
            db.update("PRAGMA user_version=4")
        }
    }

    override fun <T> submit(work: (Connection) -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        if (ready.isDone && !connectionAvailable) {
            future.completeExceptionally(IllegalStateException("SQLite storage is unavailable: ${health.lastError.ifBlank { health.state }}"))
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
                            val db = connection ?: error("SQLite connection unavailable")
                            val result = operation(db)
                            success(start)
                            future.complete(result)
                        } catch (failure: Throwable) {
                            failed(failure)
                            if (runCatching { connection?.isClosed != false }.getOrDefault(true)) connectionAvailable = false
                            future.completeExceptionally(failure)
                        } finally { finished(module) }
                    } else {
                        future.completeExceptionally(IllegalStateException("SQLite storage is unavailable: ${health.lastError.ifBlank { health.state }}"))
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
        else db.queryFirst("SELECT p.* FROM names n JOIN players p ON n.uuid=p.uuid WHERE n.name=? COLLATE NOCASE ORDER BY n.last_seen DESC LIMIT 1", input) { it.storedPlayer() }
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
            if (task is SqliteStorage.DatabaseTask<*>) task.reject(failure)
        }
    }
}