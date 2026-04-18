package dev.ethan.waypointer.location;

import dev.ethan.waypointer.core.Zone;

import java.util.function.Consumer;

/**
 * A source that can emit Zone change signals. Swappable so we can pick the best
 * available source at runtime -- the Hypixel Mod API when installed, scoreboard
 * scraping otherwise.
 */
public interface ZoneSource {

    /** Begin tracking and invoke the listener whenever the detected Zone changes. */
    void register(Consumer<Zone> listener);
}
