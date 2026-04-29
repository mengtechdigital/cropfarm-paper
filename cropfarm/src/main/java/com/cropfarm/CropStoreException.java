package com.cropfarm;

/**
 * Thrown when a {@link CropStore} operation fails fatally. Unchecked so the
 * interface signatures stay clean; callers should catch this around loadAll
 * and disable the plugin rather than running with broken persistence.
 */
public class CropStoreException extends RuntimeException {
    public CropStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
