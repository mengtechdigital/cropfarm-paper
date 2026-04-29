package com.cropfarm;

import java.util.UUID;

/**
 * Immutable record describing one planted crop instance.
 */
public record TrackedCrop(String cropId, long plantedAtMillis, UUID owner) { }
