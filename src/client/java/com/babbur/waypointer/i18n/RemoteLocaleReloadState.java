package com.babbur.waypointer.i18n;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

final class RemoteLocaleReloadState {
    private enum Status { PENDING, APPLIED }

    private final ConcurrentHashMap<String, Status> statuses = new ConcurrentHashMap<>();

    boolean begin(String token) {
        return statuses.putIfAbsent(token, Status.PENDING) == null;
    }

    void finish(String token, boolean succeeded) {
        if (succeeded) {
            statuses.replace(token, Status.PENDING, Status.APPLIED);
        } else {
            statuses.remove(token, Status.PENDING);
        }
    }

    boolean finishSelected(
            String token,
            String requestedLocale,
            String selectedLocale,
            boolean reloadSucceeded) {
        boolean applied = reloadSucceeded
                && Objects.equals(requestedLocale, selectedLocale);
        finish(token, applied);
        return applied;
    }

    boolean applied(String token) {
        return statuses.get(token) == Status.APPLIED;
    }
}
