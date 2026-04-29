package com.cropfarm;

import java.util.Map;

/**
 * Persistence backend for tracked planted crops. Callers should treat
 * put/remove as durable on return.
 */
public interface CropStore extends AutoCloseable {

    /** Insert or replace a crop entry. */
    void put(String locKey, TrackedCrop crop);

    /** Remove a crop entry. No-op if missing. */
    void remove(String locKey);

    /** Load all entries — called once on startup to prime the in-memory cache. */
    Map<String, TrackedCrop> loadAll();

    @Override
    void close();
}
