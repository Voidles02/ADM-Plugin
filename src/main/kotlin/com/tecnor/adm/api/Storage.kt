package com.tecnor.adm.api

import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CompletableFuture

data class StorageHealth(
    val state: String,
    val lastSuccess: Long = 0,
    val latencyMs: Long = 0,
    val lastError: String = ""
)

data class StoredPlayer(
    val id: UUID,
    val name: String,
    val ip: String,
    val firstLogin: Long,
    val lastLogin: Long,
    val rank: Int,
    val immune: Boolean
)

interface Storage : AutoCloseable {
    val health: StorageHealth
    val pendingTasks: Int get() = 0
    fun pendingByModule(): Map<String, Int> = emptyMap()
    fun <T> submit(work: (Connection) -> T): CompletableFuture<T>
    fun resolve(input: String): CompletableFuture<StoredPlayer?>
    fun reportFailure(failure: Throwable)
    override fun close()
}