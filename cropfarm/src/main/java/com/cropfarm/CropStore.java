package com.cropfarm;

import java.util.Map;

/**
 * Persistence backend for tracked planted crops. Implementations may be
 * synchronous (SQLite) or batched (legacy YAML). Callers should treat
 * put/remove as durable on return.
 */
public interface CropStore extends AutoCloseable {

    /** Insert or replace a crop entry. */
    void put(String locKey, TrackedCrop crop);

    /** Remove a crop entry. No-op if missing. */
    void remove(String locKey);

    /** Bulk insert (used by migration). Default loops put — override for transactions. */
    default void putAll(Map<String, TrackedCrop> entries) {
        for (Map.Entry<String, TrackedCrop> e : entries.entrySet()) {
            put(e.getKey(), e.getValue());
        }
    }

    /** Load all entries — called once on startup to prime the in-memory cache. */
    Map<String, TrackedCrop> loadAll();

    /** Force any pending writes to disk. SQLite WAL is auto-flushing; YAML writes here. */
    void flush();

    @Override
    void close();
}
