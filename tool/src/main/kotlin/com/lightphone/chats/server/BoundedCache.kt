package com.lightphone.chats.server

/** A small weighted LRU. Oversized values can be displayed without being retained. */
class BoundedCache<V>(
    private val maxWeight: Long,
    private val weightOf: (V) -> Long,
) {
    init { require(maxWeight > 0) }

    private val entries = LinkedHashMap<String, V>(16, 0.75f, true)
    private var weight = 0L
    private var valid = true

    @Synchronized
    operator fun get(key: String): V? = entries[key]

    @Synchronized
    fun put(key: String, value: V) {
        if (!valid) return
        val valueWeight = weightOf(value)
        require(valueWeight >= 0)
        entries.remove(key)?.let { weight -= weightOf(it) }
        if (valueWeight > maxWeight) return
        entries[key] = value
        weight += valueWeight
        val iterator = entries.entries.iterator()
        while (weight > maxWeight && iterator.hasNext()) {
            weight -= weightOf(iterator.next().value)
            iterator.remove()
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        weight = 0
    }

    /** Retired UI sessions may still have decodes finishing on another thread. */
    @Synchronized
    fun invalidate() {
        valid = false
        clear()
    }
}
