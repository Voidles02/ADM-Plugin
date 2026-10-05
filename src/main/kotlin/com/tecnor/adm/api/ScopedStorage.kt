package com.tecnor.adm.api

import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CompletableFuture

object StorageTaskScope {
    private val scope = ThreadLocal<String>()
    fun current(): String = scope.get() ?: "infrastructure"
    fun <T> within(module: String, work: () -> T): T {
        val previous = scope.get()
        scope.set(module)
        try { return work() } finally { if (previous == null) scope.remove() else scope.set(previous) }
    }
}

class ScopedStorage(private val backing: Storage, private val module: String) : Storage {
    override val health get() = backing.health
    override val pendingTasks get() = backing.pendingTasks
    override fun pendingByModule() = backing.pendingByModule()
    override fun <T> submit(work: (Connection) -> T): CompletableFuture<T> = StorageTaskScope.within(module) { backing.submit(work) }
    override fun resolve(input: String): CompletableFuture<StoredPlayer?> = StorageTaskScope.within(module) { backing.resolve(input) }
    override fun reportFailure(failure: Throwable) = backing.reportFailure(failure)
    override fun close() = Unit
}