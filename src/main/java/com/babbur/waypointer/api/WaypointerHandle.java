package com.babbur.waypointer.api;

/**
 * Closeable handle returned by API registrations and session overlays.
 *
 * <p>{@link #close()} declares no checked exceptions and supports try-with-resources.
 */
@FunctionalInterface
public interface WaypointerHandle extends AutoCloseable {
    @Override
    void close();
}
