package com.babbur.waypointer.api;

/**
 * Closeable handle returned by API registrations and session overlays.
 *
 * <p>{@link AutoCloseable#close()} declares {@code throws Exception}, which
 * makes normal mod lifecycle cleanup noisy. This narrower contract keeps call
 * sites simple while still working with try-with-resources.
 */
@FunctionalInterface
public interface WaypointerHandle extends AutoCloseable {
    @Override
    void close();
}
