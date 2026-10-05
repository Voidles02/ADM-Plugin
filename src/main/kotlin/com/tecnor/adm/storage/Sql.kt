package com.tecnor.adm.storage

import com.tecnor.adm.api.StoredPlayer
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

fun Connection.update(sql: String, vararg values: Any?): Int = prepareStatement(sql).use { statement ->
    values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
    statement.executeUpdate()
}

fun <T> Connection.query(sql: String, vararg values: Any?, read: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { statement ->
        values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(read(rows)) } }
    }

fun <T> Connection.queryFirst(sql: String, vararg values: Any?, read: (ResultSet) -> T): T? =
    prepareStatement(sql).use { statement ->
        values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { rows -> if (rows.next()) read(rows) else null }
    }

fun <T> Connection.transaction(work: (Connection) -> T): T {
    autoCommit = false
    try {
        val result = work(this)
        commit()
        return result
    } catch (failure: Throwable) {
        rollback()
        throw failure
    } finally {
        autoCommit = true
    }
}

fun ResultSet.storedPlayer() = StoredPlayer(UUID.fromString(getString("uuid")), getString("name"),
    getString("ip"), getLong("first_login"), getLong("last_login"), getInt("rank"), getBoolean("immune"))