package com.babbur.waypointer.api;

@FunctionalInterface
public interface WaypointerHandle extends AutoCloseable {
    @Override
    void close();
}
