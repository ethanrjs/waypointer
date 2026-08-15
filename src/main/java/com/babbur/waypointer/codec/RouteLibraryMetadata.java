package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.RouteFolder;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointPaint;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record RouteLibraryMetadata(
        List<ManualColorsEntry> manualColors,
        List<FolderDefinition> folders,
        List<PaintEntry> paints) {

    public static final int MAX_GROUPS = 256;
    public static final int MAX_FOLDERS = 256;
    public static final int MAX_FOLDER_NAME_CHARS = 64;
    public static final int MAX_FOLDER_NAME_BYTES = 256;

    private static final RouteLibraryMetadata EMPTY =
            new RouteLibraryMetadata(List.of(), List.of(), List.of());

    public RouteLibraryMetadata {
        manualColors = copyNoNulls(manualColors, "manualColors");
        folders = copyNoNulls(folders, "folders");
        paints = copyNoNulls(paints, "paints");
        if (manualColors.size() > MAX_GROUPS) {
            throw new IllegalArgumentException("route library has too many manual color entries");
        }
        if (folders.size() > MAX_FOLDERS) {
            throw new IllegalArgumentException("route library has too many folders");
        }
        if (paints.size() > MAX_GROUPS) {
            throw new IllegalArgumentException("route library has too many paint entries");
        }
        rejectDuplicateGroupOrdinals(
                manualColors, ManualColorsEntry::groupOrdinal, "manual color");
        rejectDuplicateGroupOrdinals(
                paints, PaintEntry::groupOrdinal, "paint");
    }

    /** Pre-paint shape; callers without painted routes keep this arity. */
    public RouteLibraryMetadata(
            List<ManualColorsEntry> manualColors, List<FolderDefinition> folders) {
        this(manualColors, folders, List.of());
    }

    public static RouteLibraryMetadata empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return manualColors.isEmpty() && folders.isEmpty() && paints.isEmpty();
    }

    public static RouteLibraryMetadata capture(
            ActiveGroupManager manager, List<WaypointGroup> groups) {
        Objects.requireNonNull(groups, "groups");
        if (groups.size() > MAX_GROUPS) {
            throw new IllegalArgumentException("route library has too many groups");
        }

        Map<String, Integer> ordinalById = new LinkedHashMap<>();
        List<ManualColorsEntry> manualColors = new ArrayList<>();
        List<PaintEntry> paints = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            WaypointGroup group = Objects.requireNonNull(groups.get(i), "group");
            if (ordinalById.putIfAbsent(group.id(), i) != null) {
                throw new IllegalArgumentException("route library contains duplicate group IDs");
            }
            List<Integer> colors = group.manualColorSnapshot();
            if (colors.size() != group.size()) {
                throw new IllegalArgumentException("manual color count does not match waypoint count");
            }
            boolean differs = false;
            for (int waypointIndex = 0; waypointIndex < colors.size(); waypointIndex++) {
                int color = colors.get(waypointIndex);
                if (color < 0 || color > 0xFFFFFF) {
                    throw new IllegalArgumentException("manual color is outside the RGB range");
                }
                if (color != (group.get(waypointIndex).color() & 0xFFFFFF)) differs = true;
            }
            if (differs) manualColors.add(new ManualColorsEntry(i, colors));
            if (group.paint() != null) {
                paints.add(new PaintEntry(i, group.paint(), group.paintEnabled()));
            }
        }

        List<FolderDefinition> folders = new ArrayList<>();
        if (manager != null) {
            for (RouteFolder folder : manager.folders()) {
                List<Integer> members = new ArrayList<>();
                for (String groupId : manager.groupIdsInFolder(folder.id())) {
                    Integer ordinal = ordinalById.get(groupId);
                    if (ordinal != null) members.add(ordinal);
                }
                if (!members.isEmpty()) {
                    folders.add(new FolderDefinition(
                            folder.name(), folder.color(), folder.collapsed(), members));
                }
            }
        }

        RouteLibraryMetadata metadata =
                new RouteLibraryMetadata(manualColors, folders, paints);
        metadata.validateForGroups(groups);
        return metadata;
    }

    public void validateForGroups(List<WaypointGroup> groups) {
        Objects.requireNonNull(groups, "groups");
        if (groups.size() > MAX_GROUPS) {
            throw new IllegalArgumentException("route library has too many groups");
        }
        Set<String> groupIds = new HashSet<>();
        for (WaypointGroup group : groups) {
            if (group == null) throw new IllegalArgumentException("route library contains a null group");
            if (!groupIds.add(group.id())) {
                throw new IllegalArgumentException("route library contains duplicate group IDs");
            }
        }

        for (ManualColorsEntry entry : manualColors) {
            int ordinal = requireGroupOrdinal(
                    entry.groupOrdinal(), groups.size(), "manual color");
            if (entry.colors().size() != groups.get(ordinal).size()) {
                throw new IllegalArgumentException(
                        "manual color count does not match waypoint count");
            }
        }

        for (PaintEntry entry : paints) {
            requireGroupOrdinal(entry.groupOrdinal(), groups.size(), "paint");
        }

        Set<Integer> folderMembers = new HashSet<>();
        for (FolderDefinition folder : folders) {
            String zoneId = null;
            for (int memberOrdinal : folder.memberOrdinals()) {
                int ordinal = requireGroupOrdinal(memberOrdinal, groups.size(), "folder member");
                if (!folderMembers.add(ordinal)) {
                    throw new IllegalArgumentException(
                            "a route cannot belong to more than one imported folder");
                }
                WaypointGroup group = groups.get(ordinal);
                if (group.temp() || group.runtimeOnly()
                        || group.routeKind() != WaypointGroup.RouteKind.REGULAR) {
                    throw new IllegalArgumentException(
                            "route folders can contain only saved regular routes");
                }
                if (zoneId == null) zoneId = group.zoneId();
                if (!zoneId.equals(group.zoneId())) {
                    throw new IllegalArgumentException(
                            "route folder members must use the same zone");
                }
            }
        }
    }

    public void applyTo(List<WaypointGroup> groups) {
        if (manualColors.isEmpty() && paints.isEmpty()) return;
        validateForGroups(groups);
        for (ManualColorsEntry entry : manualColors) {
            if (!groups.get(entry.groupOrdinal()).setManualColorSnapshot(entry.colors())) {
                throw new IllegalArgumentException(
                        "manual color count does not match waypoint count");
            }
        }
        for (PaintEntry entry : paints) {
            WaypointGroup group = groups.get(entry.groupOrdinal());
            group.setPaint(entry.paint());
            group.setPaintEnabled(entry.enabled());
        }
    }

    public void installFolders(ActiveGroupManager manager, List<WaypointGroup> groups) {
        Objects.requireNonNull(manager, "manager");
        if (folders.isEmpty()) return;
        validateForGroups(groups);
        for (FolderDefinition folder : folders) {
            for (int ordinal : folder.memberOrdinals()) {
                WaypointGroup imported = groups.get(ordinal);
                if (manager.get(imported.id()) != imported) {
                    throw new IllegalStateException(
                            "imported routes must be added before their folders");
                }
            }
        }
        for (FolderDefinition folder : folders) {
            List<String> memberIds = folder.memberOrdinals().stream()
                    .map(groups::get)
                    .map(WaypointGroup::id)
                    .toList();
            String zoneId = groups.get(folder.memberOrdinals().getFirst()).zoneId();
            RouteFolder installed = manager.createFolder(
                    folder.name(), zoneId, memberIds, folder.color());
            if (folder.collapsed()) manager.setFolderCollapsed(installed.id(), true);
        }
    }

    public record ManualColorsEntry(int groupOrdinal, List<Integer> colors) {
        public ManualColorsEntry {
            if (groupOrdinal < 0) {
                throw new IllegalArgumentException("negative manual color group ordinal");
            }
            colors = copyNoNulls(colors, "colors");
            for (int color : colors) {
                if (color < 0 || color > 0xFFFFFF) {
                    throw new IllegalArgumentException("manual color is outside the RGB range");
                }
            }
        }
    }

    public record PaintEntry(int groupOrdinal, WaypointPaint paint, boolean enabled) {
        public PaintEntry {
            if (groupOrdinal < 0) {
                throw new IllegalArgumentException("negative paint group ordinal");
            }
            Objects.requireNonNull(paint, "paint");
        }
    }

    public record FolderDefinition(
            String name, int color, boolean collapsed, List<Integer> memberOrdinals) {
        public FolderDefinition {
            name = requireFolderName(name);
            if (color < 0 || color > 0xFFFFFF) {
                throw new IllegalArgumentException("folder color is outside the RGB range");
            }
            memberOrdinals = copyNoNulls(memberOrdinals, "memberOrdinals");
            if (memberOrdinals.isEmpty()) {
                throw new IllegalArgumentException("route library folders must contain a route");
            }
            if (memberOrdinals.size() > MAX_GROUPS) {
                throw new IllegalArgumentException("route library folder has too many members");
            }
            Set<Integer> unique = new HashSet<>();
            for (int ordinal : memberOrdinals) {
                if (ordinal < 0) throw new IllegalArgumentException("negative folder member ordinal");
                if (!unique.add(ordinal)) {
                    throw new IllegalArgumentException("route library folder has duplicate members");
                }
            }
        }
    }

    private static String requireFolderName(String value) {
        String name = Objects.requireNonNull(value, "name").trim();
        if (name.isEmpty()) throw new IllegalArgumentException("folder name must not be blank");
        if (name.length() > MAX_FOLDER_NAME_CHARS
                || name.getBytes(StandardCharsets.UTF_8).length > MAX_FOLDER_NAME_BYTES) {
            throw new IllegalArgumentException("folder name is too long");
        }
        return name;
    }

    private static int requireGroupOrdinal(int ordinal, int groupCount, String field) {
        if (ordinal < 0 || ordinal >= groupCount) {
            throw new IllegalArgumentException(field + " group ordinal is out of bounds");
        }
        return ordinal;
    }

    private static <T> List<T> copyNoNulls(List<T> values, String field) {
        Objects.requireNonNull(values, field);
        for (T value : values) {
            if (value == null) {
                throw new IllegalArgumentException(field + " cannot contain null");
            }
        }
        return List.copyOf(values);
    }

    private static <T> void rejectDuplicateGroupOrdinals(
            List<T> entries, java.util.function.ToIntFunction<T> ordinal,
            String field) {
        Set<Integer> seen = new HashSet<>();
        for (T entry : entries) {
            if (!seen.add(ordinal.applyAsInt(entry))) {
                throw new IllegalArgumentException("duplicate route library " + field + " group ordinal");
            }
        }
    }
}
