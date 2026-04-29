package com.cropfarm;

/**
 * Immutable record describing one planted crop instance.
 * Persisted via TrackedCrops to crops.yml.
 */
public record TrackedCrop(String cropId, long plantedAtMillis) { }
