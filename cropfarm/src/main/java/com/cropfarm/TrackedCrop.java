package com.cropfarm;

import java.util.UUID;

/**
 * Immutable record describing one planted crop instance.
 * owner may be null for legacy entries planted before per-player caps existed.
 */
public record TrackedCrop(String cropId, long plantedAtMillis, UUID owner) { }
