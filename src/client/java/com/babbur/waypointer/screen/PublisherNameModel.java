package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.PublisherNamePolicy;

final class PublisherNameModel {
    enum Stage {
        ENTRY,
        CONFIRM,
        COMPLETE
    }

    enum AdvanceResult {
        REJECTED,
        SHOW_CONFIRMATION,
        CONFIRMED
    }

    private Stage stage = Stage.ENTRY;
    private String name;

    PublisherNameModel(String suggestedName) {
        name = PublisherNamePolicy.valid(suggestedName) ? suggestedName : "";
    }

    Stage stage() {
        return stage;
    }

    String name() {
        return name;
    }

    boolean valid() {
        return PublisherNamePolicy.valid(name);
    }

    void edit(String value) {
        name = value == null ? "" : value;
    }

    AdvanceResult advance() {
        if (!valid()) return AdvanceResult.REJECTED;
        if (stage == Stage.ENTRY) {
            stage = Stage.CONFIRM;
            return AdvanceResult.SHOW_CONFIRMATION;
        }
        if (stage == Stage.CONFIRM) {
            stage = Stage.COMPLETE;
            return AdvanceResult.CONFIRMED;
        }
        return AdvanceResult.REJECTED;
    }

    String confirmedName() {
        if (stage != Stage.COMPLETE) {
            throw new IllegalStateException("publisher name is not confirmed");
        }
        return name;
    }

    boolean back() {
        if (stage == Stage.CONFIRM) {
            stage = Stage.ENTRY;
            return false;
        }
        return true;
    }
}
