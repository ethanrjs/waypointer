package com.babbur.waypointer.screen;

// Tickets prevent stale async completions from mutating the current view.
final class CatalogScreenRequestTracker {
    private int listGeneration;
    private int detailGeneration;
    private long installAttempt;
    private boolean active;

    record InstallTicket(long attempt, int detailGeneration) {
    }

    void activate() {
        active = true;
    }

    boolean active() {
        return active;
    }

    void invalidateForSearch() {
        listGeneration++;
        detailGeneration++;
    }

    int beginList(boolean resetsSelection) {
        int ticket = ++listGeneration;
        if (resetsSelection) detailGeneration++;
        return ticket;
    }

    boolean acceptsList(int ticket) {
        return active && ticket == listGeneration;
    }

    void selectionChanged() {
        detailGeneration++;
    }

    InstallTicket beginInstall() {
        return new InstallTicket(++installAttempt, ++detailGeneration);
    }

    boolean latestInstallAttempt(InstallTicket ticket) {
        return ticket != null && ticket.attempt() == installAttempt;
    }

    boolean acceptsInstall(InstallTicket ticket, boolean sameTarget) {
        return active
                && latestInstallAttempt(ticket)
                && ticket.detailGeneration() == detailGeneration
                && sameTarget;
    }

    void deactivate() {
        active = false;
        listGeneration++;
        detailGeneration++;
    }
}
