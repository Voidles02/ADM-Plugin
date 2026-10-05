package com.tecnor.adm.api

object ActionOrigin {
    private val source = ThreadLocal<String>()
    fun current(): String = source.get() ?: "COMMAND"
    fun <T> within(origin: String, action: () -> T): T {
        val previous = source.get()
        source.set(origin)
        try { return action() } finally { if (previous == null) source.remove() else source.set(previous) }
    }
}