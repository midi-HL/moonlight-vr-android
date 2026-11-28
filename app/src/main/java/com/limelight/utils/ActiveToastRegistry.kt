package com.limelight.utils

import java.util.IdentityHashMap

/** Strongly tracks currently visible facade-owned toasts until expiry or cancellation. */
internal class ActiveToastRegistry<T : Any> {
    private val entries = IdentityHashMap<T, Runnable>()

    @Synchronized
    fun register(item: T, cleanup: Runnable): Runnable? = entries.put(item, cleanup)

    @Synchronized
    fun unregister(item: T): Runnable? = entries.remove(item)

    /** Remove only the registration that scheduled [cleanup], preserving a newer show(). */
    @Synchronized
    fun unregister(item: T, cleanup: Runnable): Boolean {
        if (entries[item] !== cleanup) return false
        entries.remove(item)
        return true
    }

    @Synchronized
    fun drain(): List<Pair<T, Runnable>> = entries.entries.map { it.key to it.value }.also { entries.clear() }

    @Synchronized
    fun size(): Int = entries.size
}
