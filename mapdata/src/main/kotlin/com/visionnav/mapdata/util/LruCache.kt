package com.visionnav.mapdata.util

import kotlinx.serialization.json.Json

/** Minimal thread-safe LRU map. */
internal class LruCache<K, V>(private val maxEntries: Int) {
    init {
        require(maxEntries > 0) { "maxEntries must be > 0" }
    }

    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxEntries
    }

    @Synchronized operator fun get(key: K): V? = map[key]

    @Synchronized operator fun set(key: K, value: V) {
        map[key] = value
    }

    @Synchronized fun remove(key: K): V? = map.remove(key)

    @Synchronized fun clear() = map.clear()

    @Synchronized fun size(): Int = map.size

    /** Snapshot of values, most-recently-used last. Does not affect access order. */
    @Synchronized fun values(): List<V> = map.values.toList()
}

/** Shared lenient JSON configuration: unknown fields ignored, nulls tolerated. */
internal val MapDataJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}
