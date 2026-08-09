package com.babbur.waypointer.location;

import com.babbur.waypointer.core.Zone;

import java.util.function.Consumer;

public interface ZoneSource {
    void register(Consumer<Zone> listener);
}
