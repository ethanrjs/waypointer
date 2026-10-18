package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.CatalogPublishRequest;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;

import java.text.Normalizer;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;

final class CatalogPublishFormModel {
    static final int TITLE_MAX = 80;
    static final int DESCRIPTION_MIN = 10;
    static final int DESCRIPTION_MAX = 500;
    static final int DESCRIPTION_COUNTER_START = 100;

    private final List<WaypointGroup> groups = new ArrayList<>();
    private final Runnable edited;
    private String title;
    private String description = "";
    private CatalogPublishRequest.Visibility visibility =
            CatalogPublishRequest.Visibility.UNLISTED;

    CatalogPublishFormModel(WaypointGroup group, Runnable edited) {
        groups.add(Objects.requireNonNull(group, "group"));
        this.edited = edited == null ? () -> { } : edited;
        this.title = group.name() == null ? "" : group.name().trim();
    }

    WaypointGroup group() {
        return groups.getFirst();
    }

    List<WaypointGroup> groups() {
        return List.copyOf(groups);
    }

    boolean contains(WaypointGroup group) {
        return groups.stream().anyMatch(existing -> existing.id().equals(group.id()));
    }

    boolean addGroups(List<WaypointGroup> additions) {
        if (!additions.stream().allMatch(this::accepts)) return false;
        boolean changed = false;
        for (WaypointGroup group : additions) {
            if (contains(group)) continue;
            groups.add(group);
            changed = true;
        }
        if (changed) edited.run();
        return changed;
    }

    boolean removeGroups(List<WaypointGroup> removals) {
        var ids = removals.stream().map(WaypointGroup::id).toList();
        if (groups.stream().allMatch(group -> ids.contains(group.id()))) return false;
        boolean changed = groups.removeIf(group -> ids.contains(group.id()));
        if (changed) edited.run();
        return changed;
    }

    static boolean eligible(WaypointGroup group) {
        return group != null && !group.isEmpty() && !group.temp() && !group.runtimeOnly()
                && !unsafeDungeonRoute(group) && !unpublishableZone(group.zoneId());
    }

    boolean accepts(WaypointGroup candidate) {
        return eligible(candidate) && candidate.routeKind() == group().routeKind();
    }

    static boolean eligibleBundle(List<WaypointGroup> groups) {
        return !groups.isEmpty() && groups.stream().allMatch(CatalogPublishFormModel::eligible)
                && groups.stream().map(WaypointGroup::routeKind).distinct().count() == 1;
    }

    String zoneId() {
        if (group().routeKind() == WaypointGroup.RouteKind.DUNGEON) return "dungeon";
        return groups.stream().map(WaypointGroup::zoneId).distinct().count() == 1
                ? group().zoneId() : "multiple";
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
        for (WaypointGroup group : groups) {
            if (group.routeKind() != group().routeKind()) return Validation.MIXED_ROUTES;
            if (unsafeDungeonRoute(group)) return Validation.DUNGEON_ROUTE;
            if (group.isEmpty()) return Validation.EMPTY_ROUTE;
            if (group.temp() || group.runtimeOnly()) return Validation.TEMPORARY_ROUTE;
            if (unpublishableZone(group.zoneId())) return Validation.UNPUBLISHABLE_ZONE;
        }
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
        return group().name() == null ? "" : group().name().trim();
    }

    static boolean unsafeDungeonRoute(WaypointGroup group) {
        return group.routeKind() != WaypointGroup.RouteKind.DUNGEON && dungeonRoute(group);
    }

    static boolean dungeonRoute(WaypointGroup group) {
        if (group.routeKind() == WaypointGroup.RouteKind.DUNGEON) return true;
        String zone = Zone.canonicalId(group.zoneId());
        return zone.equals("dungeon")
                || zone.startsWith("dungeon_f")
                || zone.startsWith("dungeon_m");
    }

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
        DUNGEON_ROUTE,
        MIXED_ROUTES,
        EMPTY_ROUTE,
        TEMPORARY_ROUTE,
        UNPUBLISHABLE_ZONE,
        TITLE_REQUIRED,
        DESCRIPTION_TOO_SHORT,
        DESCRIPTION_TOO_LONG
    }
}
