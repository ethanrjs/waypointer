package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.RouteFolder;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointPaint;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteLibraryCodecTest {

    @Test
    void roundTripRestoresHiddenManualColorsAndFolders() {
        ActiveGroupManager source = new ActiveGroupManager();
        WaypointGroup first = group("first", "First", 1);
        first.add(Waypoint.at(4, 5, 6).withColor(0x445566));
        first.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        first.set(0, first.get(0).withColor(0xABCDEF));
        first.set(1, first.get(1).withColor(0x123456));
        List<Integer> hiddenManualColors = first.manualColorSnapshot();
        first.setStaticColor(0x0A0B0C);
        first.setGradientMode(WaypointGroup.GradientMode.STATIC);
        WaypointGroup second = group("second", "Second", 7);
        source.addAll(List.of(first, second));
        source.addFolder(new RouteFolder(
                "source-folder", "Mining", "hub", true, 0x2468AC),
                List.of(first.id(), second.id()));

        List<WaypointGroup> live = List.of(first, second);
        RouteLibraryMetadata metadata = RouteLibraryMetadata.capture(source, live);
        String encoded = RouteLibraryCodec.encode(
                live.stream().map(WaypointGroup::exportSnapshot).toList(),
                WaypointCodec.Options.FULL_FIDELITY.toBuilder().label("Library").build(),
                metadata);
        WaypointImporter.ImportResult imported = WaypointImporter.importAny(encoded);

        assertTrue(encoded.startsWith(RouteLibraryCodec.MAGIC));
        assertTrue(encoded.startsWith(RouteLibraryCodec.MAGIC + "."));
        assertEquals("Library", imported.label());
        assertEquals(hiddenManualColors,
                imported.groups().getFirst().manualColorSnapshot());
        assertEquals(0x0A0B0C, imported.groups().getFirst().get(0).color());

        ActiveGroupManager target = new ActiveGroupManager();
        target.addAll(imported.groups());
        imported.libraryMetadata().installFolders(target, imported.groups());
        String importedFirstId = imported.groups().getFirst().id();
        String importedSecondId = imported.groups().get(1).id();
        RouteFolder installed = target.folderForGroup(importedFirstId);
        assertEquals("Mining", installed.name());
        assertEquals(0x2468AC, installed.color());
        assertTrue(installed.collapsed());
        assertNotEquals("source-folder", installed.id());
        assertEquals(List.of(importedFirstId, importedSecondId),
                target.groupIdsInFolder(installed.id()));
    }

    @Test
    void emptyMetadataLeavesRawWpV9AndThirdPartyPayloadsUnchanged() {
        WaypointGroup group = group("route", "Route", 1);
        List<WaypointGroup> groups = List.of(group);
        String raw = WaypointCodec.encode(groups, WaypointCodec.Options.FULL_FIDELITY);

        assertEquals(raw, RouteLibraryCodec.encode(
                groups, WaypointCodec.Options.FULL_FIDELITY,
                RouteLibraryMetadata.empty()));
        assertTrue(raw.startsWith(WaypointCodec.MAGIC));
        assertTrue(WaypointImporter.importAny(raw).libraryMetadata().isEmpty());
        String catalog = WaypointCodec.encodeCatalog(groups);
        assertTrue(catalog.startsWith(WaypointCodec.MAGIC));
        assertEquals(1, WaypointCodec.decodeCanonicalV9(catalog).groups().size());

        RouteLibraryMetadata metadata = new RouteLibraryMetadata(
                List.of(new RouteLibraryMetadata.ManualColorsEntry(
                        0, group.manualColorSnapshot())),
                List.of());
        String before = WaypointExportCodec.encode(
                groups, WaypointCodec.Options.FULL_FIDELITY,
                WaypointExportCodec.Target.SKYBLOCKER);
        String after = WaypointExportCodec.encode(
                groups, WaypointCodec.Options.FULL_FIDELITY,
                WaypointExportCodec.Target.SKYBLOCKER, metadata);
        assertEquals(before, after);
    }

    @Test
    void compactWrapperIsShorterAndLegacyWrappersStillDecode() {
        String compact = metadataPayload();
        String body = compact.substring(RouteLibraryCodec.MAGIC.length());
        String json = RouteLibraryCodec.decodeBody(body);
        String legacy = RouteLibraryCodec.MAGIC
                + Base64.getUrlEncoder().withoutPadding().encodeToString(
                json.getBytes(StandardCharsets.UTF_8));

        assertTrue(compact.length() < legacy.length(),
                "compact=" + compact.length() + " legacy=" + legacy.length());
        RouteLibraryCodec.Decoded compactDecoded = RouteLibraryCodec.decode(compact);
        RouteLibraryCodec.Decoded legacyDecoded = RouteLibraryCodec.decode(legacy);
        assertEquals(compactDecoded.label(), legacyDecoded.label());
        assertEquals(compactDecoded.metadata(), legacyDecoded.metadata());
        assertEquals(compactDecoded.groups().getFirst().waypoints(),
                legacyDecoded.groups().getFirst().waypoints());
    }

    @Test
    void lossyOptionsDoNotLeakHiddenColorsOrRouteLibraryMetadata() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup group = group("route", "Route", 1);
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.set(0, group.get(0).withColor(0xABCDEF));
        List<Integer> hiddenColors = group.manualColorSnapshot();
        group.setGradientMode(WaypointGroup.GradientMode.STATIC);
        WaypointGroup companion = group("companion", "Companion", 9);
        manager.addAll(List.of(group, companion));
        manager.addFolder(new RouteFolder(
                "folder", "Folder", "hub", true, 0x123456),
                List.of(group.id(), companion.id()));
        List<WaypointGroup> live = List.of(group, companion);
        RouteLibraryMetadata metadata = RouteLibraryMetadata.capture(manager, live);
        List<WaypointGroup> snapshots = live.stream()
                .map(WaypointGroup::exportSnapshot).toList();

        WaypointCodec.Options noColors = WaypointCodec.Options.FULL_FIDELITY.toBuilder()
                .includeColors(false)
                .build();
        WaypointImporter.ImportResult colorLoss = WaypointImporter.importAny(
                RouteLibraryCodec.encode(snapshots, noColors, metadata));

        assertTrue(colorLoss.libraryMetadata().manualColors().isEmpty());
        assertEquals(RouteFolder.DEFAULT_COLOR,
                colorLoss.libraryMetadata().folders().getFirst().color());
        assertNotEquals(hiddenColors,
                colorLoss.groups().getFirst().manualColorSnapshot());

        WaypointCodec.Options noGroupMetadata =
                WaypointCodec.Options.FULL_FIDELITY.toBuilder()
                        .includeGroupMeta(false)
                        .build();
        WaypointImporter.ImportResult metadataLoss = WaypointImporter.importAny(
                RouteLibraryCodec.encode(snapshots, noGroupMetadata, metadata));

        assertTrue(metadataLoss.libraryMetadata().folders().isEmpty());
        assertEquals(hiddenColors,
                metadataLoss.groups().getFirst().manualColorSnapshot());

        WaypointCodec.Options noColorsOrGroupMetadata =
                noGroupMetadata.toBuilder().includeColors(false).build();
        assertTrue(RouteLibraryCodec.encode(snapshots, noColorsOrGroupMetadata, metadata)
                .startsWith(WaypointCodec.MAGIC));
    }

    @Test
    void paintedRoutesRoundTripThroughShareCodes() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup group = group("route", "Route", 1);
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        byte[] pixels = new byte[WaypointPaint.PIXEL_COUNT];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (byte) (i % WaypointPaint.PALETTE_SIZE);
        }
        WaypointPaint paint = new WaypointPaint(
                WaypointPaint.defaultPalette(0x123456), pixels);
        group.setPaint(paint);
        group.setPaintEnabled(true);
        manager.add(group);
        RouteLibraryMetadata metadata = RouteLibraryMetadata.capture(
                manager, List.of(group));
        assertEquals(1, metadata.paints().size());

        String encoded = RouteLibraryCodec.encode(
                List.of(group.exportSnapshot()),
                WaypointCodec.Options.FULL_FIDELITY, metadata);
        assertTrue(encoded.startsWith(RouteLibraryCodec.MAGIC));

        WaypointGroup decoded = WaypointImporter.importAny(encoded)
                .groups().getFirst();
        assertEquals(paint, decoded.paint());
        assertTrue(decoded.paintEnabled());

        assertThrows(IllegalArgumentException.class, () -> RouteLibraryCodec.decode(
                mutate(encoded, root -> root.getAsJsonArray("paints").get(0)
                        .getAsJsonObject().addProperty("pixels", "AAAA"))));

        String stripped = RouteLibraryCodec.encode(
                List.of(group.exportSnapshot()),
                WaypointCodec.Options.FULL_FIDELITY.toBuilder()
                        .includeColors(false).build(),
                metadata);
        assertTrue(stripped.startsWith(WaypointCodec.MAGIC),
                "stripping colors must also strip paint and skip the wrapper");
        assertNull(WaypointImporter.importAny(stripped).groups().getFirst().paint());
    }

    @Test
    void importResultKeepsTheThreeArgumentConstructor() {
        WaypointGroup group = group("route", "Route", 1);

        WaypointImporter.ImportResult result = new WaypointImporter.ImportResult(
                WaypointImporter.Source.JSON, List.of(group), null);

        assertEquals("", result.label());
        assertTrue(result.libraryMetadata().isEmpty());
    }

    @Test
    void captureKeepsOnlyFoldersRepresentedByExportedRoutes() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup selected = group("selected", "Selected", 1);
        WaypointGroup omitted = group("omitted", "Omitted", 2);
        WaypointGroup elsewhere = group("elsewhere", "Elsewhere", 3);
        manager.addAll(List.of(selected, omitted, elsewhere));
        manager.addFolder(new RouteFolder(
                "partial", "Partial", "hub", false),
                List.of(selected.id(), omitted.id()));
        manager.addFolder(new RouteFolder(
                "not-selected", "Not selected", "hub", false),
                List.of(elsewhere.id()));
        manager.addFolder(new RouteFolder(
                "empty", "Empty", "hub", false), List.of());

        RouteLibraryMetadata metadata = RouteLibraryMetadata.capture(
                manager, List.of(selected));

        assertEquals(1, metadata.folders().size());
        assertEquals("Partial", metadata.folders().getFirst().name());
        assertEquals(List.of(0), metadata.folders().getFirst().memberOrdinals());
    }

    @Test
    void decoderRejectsWrongTypesBoundsCountsAndDuplicateOrdinals() {
        String base = metadataPayload();

        assertThrows(IllegalArgumentException.class, () -> RouteLibraryCodec.decode(
                mutate(base, root -> {
                    var members = root.getAsJsonArray("folders").get(0)
                            .getAsJsonObject().getAsJsonArray("members");
                    members.add(members.get(0).deepCopy());
                })));
        assertThrows(IllegalArgumentException.class, () -> RouteLibraryCodec.decode(
                mutate(base, root -> {
                    JsonObject duplicate = root.getAsJsonArray("folders").get(0)
                            .deepCopy().getAsJsonObject();
                    duplicate.addProperty("name", "Duplicate membership");
                    root.getAsJsonArray("folders").add(duplicate);
                })));
        assertThrows(IllegalArgumentException.class, () -> RouteLibraryCodec.decode(
                mutate(base, root -> root.getAsJsonArray("manualColors").get(0)
                        .getAsJsonObject().getAsJsonArray("colors").add(0x1000000))));
        assertThrows(IllegalArgumentException.class,
                () -> RouteLibraryCodec.decode(base.replace("WPL:1:", "WPL:2:")));
    }

    private static String metadataPayload() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup group = group("route", "Route", 1);
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.set(0, group.get(0).withColor(0x112233));
        group.setGradientMode(WaypointGroup.GradientMode.STATIC);
        WaypointGroup companion = group("companion", "Companion", 5);
        manager.addAll(List.of(group, companion));
        manager.addFolder(new RouteFolder(
                "folder", "Folder", "hub", false, 0x123456),
                List.of(group.id(), companion.id()));
        List<WaypointGroup> live = List.of(group, companion);
        RouteLibraryMetadata metadata = RouteLibraryMetadata.capture(manager, live);
        return RouteLibraryCodec.encode(
                live.stream().map(WaypointGroup::exportSnapshot).toList(),
                WaypointCodec.Options.FULL_FIDELITY, metadata);
    }

    private static String mutate(String payload, Consumer<JsonObject> mutation) {
        String encoded = payload.substring(RouteLibraryCodec.MAGIC.length());
        JsonObject root = JsonParser.parseString(RouteLibraryCodec.decodeBody(encoded))
                .getAsJsonObject();
        mutation.accept(root);
        return RouteLibraryCodec.MAGIC + RouteLibraryCodec.encodeBody(root.toString());
    }

    private static WaypointGroup group(String id, String name, int x) {
        WaypointGroup group = new WaypointGroup(id, name, "hub");
        group.add(Waypoint.at(x, 70, x).withColor(0x102030));
        return group;
    }
}
