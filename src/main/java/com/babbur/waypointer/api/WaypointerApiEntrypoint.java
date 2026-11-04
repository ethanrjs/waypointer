package com.babbur.waypointer.api;

@FunctionalInterface
public interface WaypointerApiEntrypoint {
    void onWaypointerApiReady(WaypointerApi api);
}
