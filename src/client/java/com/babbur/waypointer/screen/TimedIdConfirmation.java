package com.babbur.waypointer.screen;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

final class TimedIdConfirmation {

    private final Set<String> armedIds = new LinkedHashSet<>();
    private long armedUntil;

    void arm(Collection<String> ids, long now, long windowMillis) {
        clear();
        if (ids == null || windowMillis <= 0) return;
        for (String id : ids) {
            if (id != null) armedIds.add(id);
        }
        if (!armedIds.isEmpty()) armedUntil = now + windowMillis;
    }

    boolean matches(Collection<String> ids, long now) {
        if (ids == null || armedIds.isEmpty() || now >= armedUntil) return false;
        return armedIds.equals(new LinkedHashSet<>(ids));
    }

    boolean isArmed(long now) {
        return !armedIds.isEmpty() && now < armedUntil;
    }

    boolean expire(long now) {
        if (armedIds.isEmpty() || now < armedUntil) return false;
        clear();
        return true;
    }

    void clear() {
        armedUntil = 0L;
        armedIds.clear();
    }
}
