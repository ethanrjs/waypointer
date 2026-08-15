package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.CatalogPublishRequest;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;

import java.text.Normalizer;
import java.util.Objects;

final class CatalogPublishFormModel {
    static final int TITLE_MAX = 80;
    static final int DESCRIPTION_MIN = 10;
    static final int DESCRIPTION_MAX = 500;
    static final int DESCRIPTION_COUNTER_START = 100;

    private final WaypointGroup group;
    private final Runnable edited;
    private String title;
    private String description = "";
    private CatalogPublishRequest.Visibility visibility =
            CatalogPublishRequest.Visibility.UNLISTED;

    CatalogPublishFormModel(WaypointGroup group, Runnable edited) {
        this.group = Objects.requireNonNull(group, "group");
        this.edited = edited == null ? () -> { } : edited;
        this.title = group.name() == null ? "" : group.name().trim();
    }

    WaypointGroup group() {
        return group;
    }

    String title() {
        return title;
    }

    String description() {
        return description;
    }

    CatalogPublishRequest.Visibility visibility() {
        return visibility;
    }

    void setTitle(String value) {
        String next = value == null ? "" : value;
        if (next.equals(title)) return;
        title = next;
        edited.run();
    }

    void setDescription(String value) {
        String next = normalizeDescriptionInput(value);
        if (next.equals(description)) return;
        description = next;
        edited.run();
    }

    void setVisibility(CatalogPublishRequest.Visibility value) {
        CatalogPublishRequest.Visibility next = Objects.requireNonNull(value, "visibility");
        if (next == visibility) return;
        visibility = next;
        edited.run();
    }

    Validation validation() {
        if (group.isEmpty()) return Validation.EMPTY_ROUTE;
        if (group.temp() || group.runtimeOnly()) return Validation.TEMPORARY_ROUTE;
        if (unpublishableZone(group.zoneId())) return Validation.UNPUBLISHABLE_ZONE;
        if (title.trim().isEmpty()) return Validation.TITLE_REQUIRED;
        int length = trimmedCodePointLength(description);
        if (length < DESCRIPTION_MIN) return Validation.DESCRIPTION_TOO_SHORT;
        if (length > DESCRIPTION_MAX) return Validation.DESCRIPTION_TOO_LONG;
        return null;
    }

    boolean valid() {
        return validation() == null;
    }

    String normalizedTitle() {
        return title.trim();
    }

    String normalizedDescription() {
        return description.trim();
    }

    String previewName() {
        String normalized = normalizedTitle();
        if (!normalized.isEmpty()) return normalized;
        return group.name() == null ? "" : group.name().trim();
    }

    /** Routes without a real SkyBlock zone would be unfindable in the catalog. */
    static boolean unpublishableZone(String zoneId) {
        String canonical = Zone.canonicalId(zoneId);
        return Zone.UNKNOWN.id().equals(canonical)
                || Zone.PRIVATE_WORLD.id().equals(canonical);
    }

    static boolean descriptionLengthValid(String description) {
        int length = trimmedCodePointLength(description);
        return length >= DESCRIPTION_MIN && length <= DESCRIPTION_MAX;
    }

    static String normalizeDescriptionInput(String value) {
        if (value == null || value.isEmpty()) return "";
        String normalized = Normalizer.normalize(
                value.replace("\r\n", "\n").replace('\r', '\n'),
                Normalizer.Form.NFKC);
        StringBuilder result = new StringBuilder(Math.min(normalized.length(), DESCRIPTION_MAX));
        int count = 0;
        for (int offset = 0; offset < normalized.length() && count < DESCRIPTION_MAX;) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint != '\n' && (codePoint < 0x20 || codePoint == 0x7f)) continue;
            result.appendCodePoint(codePoint);
            count++;
        }
        return result.toString();
    }

    static int descriptionCharactersRemaining(String description) {
        int length = description == null ? 0
                : description.codePointCount(0, description.length());
        return length < DESCRIPTION_COUNTER_START
                ? -1 : Math.max(0, DESCRIPTION_MAX - length);
    }

    private static int trimmedCodePointLength(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.codePointCount(0, trimmed.length());
    }

    enum Validation {
        EMPTY_ROUTE,
        TEMPORARY_ROUTE,
        UNPUBLISHABLE_ZONE,
        TITLE_REQUIRED,
        DESCRIPTION_TOO_SHORT,
        DESCRIPTION_TOO_LONG
    }
}
