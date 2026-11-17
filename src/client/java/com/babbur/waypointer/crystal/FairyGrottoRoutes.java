package com.babbur.waypointer.crystal;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.WaypointGroup;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

/**
 * Places Fairy Grotto routes. A grotto route remembers the Jasper glass around it when its first
 * waypoint is added (the layout is saved and shared with the route); in later lobbies the loaded glass near the player is matched against that
 * layout (any turn, partly mined is fine) and the route is drawn in the grotto that fits.
 */
public final class FairyGrottoRoutes {

    private static final int SCAN_RADIUS = 40;
    private static final int SCAN_HEIGHT = 24;
    private static final int TICK_INTERVAL = 40;
    private static final int EDIT_RETRY_TICKS = 5;
    private static final double REMATCH_DISTANCE_SQ = 64.0 * 64.0;
    private static final Block JASPER_GLASS = block("magenta_stained_glass");
    private static final Block JASPER_PANE = block("magenta_stained_glass_pane");
    private static final Predicate<BlockState> JASPER = FairyGrottoRoutes::isJasper;

    private static volatile FairyGrottoRoutes instance;

    private final ActiveGroupManager manager;
    private final CrystalHollowsTracker tracker;
    private final FairyGrottoStore store;
    private final Runnable onPlacementsChanged;
    private final Map<String, StructurePlacement> placements = new HashMap<>();
    private final ExecutorService matcher = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Waypointer grotto matcher");
        thread.setDaemon(true);
        return thread;
    });
    private CrystalHollowsLobbyState lobby;
    private CompletableFuture<Map<String, StructurePlacement>> pending;
    private int ticks;
    private int lastEditAttemptTick = -EDIT_RETRY_TICKS;

    private FairyGrottoRoutes(ActiveGroupManager manager, CrystalHollowsTracker tracker,
                              FairyGrottoStore store, Runnable onPlacementsChanged) {
        this.manager = manager;
        this.tracker = tracker;
        this.store = store;
        this.onPlacementsChanged = onPlacementsChanged;
    }

    public static void install(ActiveGroupManager manager, CrystalHollowsTracker tracker,
                               Runnable onPlacementsChanged) {
        FairyGrottoRoutes routes = new FairyGrottoRoutes(manager, tracker,
                FairyGrottoStore.loadDefault(), onPlacementsChanged);
        instance = routes;
        ClientTickEvents.END_CLIENT_TICK.register(routes::tick);
    }

    public static boolean isGrottoRoute(WaypointGroup group) {
        return CrystalHollowsStructureRoutes.structureFor(group) == CrystalHollowsStructure.FAIRY_GROTTO;
    }

    /** Where the route sits in this lobby, or null until its grotto has been found. */
    public static StructurePlacement placementFor(WaypointGroup group) {
        FairyGrottoRoutes routes = instance;
        if (routes == null || group == null) return null;
        routes.syncLobby();
        return routes.placements.get(group.id());
    }

    /**
     * Placement for adding or moving waypoints. A route without a layout records the grotto the
     * player stands in; a route with one is matched right away if it has not been yet.
     */
    public static StructurePlacement placementForEdit(WaypointGroup group) {
        FairyGrottoRoutes routes = instance;
        if (routes == null || group == null) return null;
        routes.syncLobby();
        StructurePlacement placed = routes.placements.get(group.id());
        if (placed != null) return placed;
        if (routes.ticks - routes.lastEditAttemptTick < EDIT_RETRY_TICKS) return null;
        routes.lastEditAttemptTick = routes.ticks;
        return routes.placeForEdit(group);
    }

    /** True when the route has waypoints but no grotto layout, so it can never be placed. */
    public static boolean missingLayout(WaypointGroup group) {
        return group != null && !group.isEmpty() && FairyGrottoLayout.fromText(group.grottoLayout()) == null;
    }

    private StructurePlacement placeForEdit(WaypointGroup group) {
        Minecraft client = Minecraft.getInstance();
        if (!inLobby() || client.level == null || client.player == null) return null;
        CrystalHollowsPosition player = playerPosition(client);
        FairyGrottoLayout layout = FairyGrottoLayout.fromText(group.grottoLayout());
        Set<Long> glass = scan(client.level, player);
        StructurePlacement placement;
        if (layout == null) {
            if (!group.isEmpty() || glass.size() < FairyGrottoLayout.MIN_CELLS) return null;
            layout = FairyGrottoLayout.capture(player, glass);
            if (layout == null) return null;
            group.setGrottoLayout(layout.toText());
            placement = StructurePlacement.at(player);
        } else {
            placement = layout.match(glass, player).map(FairyGrottoLayout.Match::placement).orElse(null);
            if (placement == null) return null;
        }
        place(group.id(), placement);
        onPlacementsChanged.run();
        return placement;
    }

    private void tick(Minecraft client) {
        ticks++;
        if (!syncLobby()) return;
        applyFinishedMatches();
        if (ticks % TICK_INTERVAL != 0 || pending != null) return;
        if (client.level == null || client.player == null) return;
        CrystalHollowsPosition player = playerPosition(client);
        Map<String, FairyGrottoLayout> wanted = new HashMap<>();
        for (WaypointGroup group : manager.allGroups()) {
            if (!isGrottoRoute(group) || !group.enabled() || group.isEmpty()) continue;
            FairyGrottoLayout layout = FairyGrottoLayout.fromText(group.grottoLayout());
            if (layout == null) continue;
            StructurePlacement placed = placements.get(group.id());
            if (placed != null && placed.pivot().distanceSquared(player) < REMATCH_DISTANCE_SQ) continue;
            wanted.put(group.id(), layout);
        }
        if (wanted.isEmpty()) return;
        Set<Long> glass = scan(client.level, player);
        if (glass.size() < FairyGrottoLayout.MIN_MATCH) return;
        pending = CompletableFuture.supplyAsync(() -> {
            Map<String, StructurePlacement> found = new HashMap<>();
            wanted.forEach((id, layout) -> layout.match(glass, player)
                    .ifPresent(match -> found.put(id, match.placement())));
            return found;
        }, matcher);
    }

    private void applyFinishedMatches() {
        if (pending == null || !pending.isDone()) return;
        Map<String, StructurePlacement> found;
        try {
            found = pending.join();
        } catch (RuntimeException failed) {
            Waypointer.LOGGER.warn("Fairy Grotto matching failed", failed);
            found = Map.of();
        } finally {
            pending = null;
        }
        boolean changed = false;
        for (Map.Entry<String, StructurePlacement> match : found.entrySet()) {
            if (match.getValue().equals(placements.get(match.getKey()))) continue;
            place(match.getKey(), match.getValue());
            changed = true;
        }
        if (changed) onPlacementsChanged.run();
    }

    private void place(String routeId, StructurePlacement placement) {
        placements.put(routeId, placement);
        store.putLastPlacement(routeId, tracker.serverId(), placement);
    }

    /** Resets placements when the lobby changes; false while not in a Crystal Hollows lobby. */
    private boolean syncLobby() {
        CrystalHollowsLobbyState current = inLobby() ? tracker.lobby() : null;
        if (current == lobby) return current != null;
        lobby = current;
        boolean hadPlacements = !placements.isEmpty();
        placements.clear();
        pending = null;
        String serverId = tracker.serverId();
        if (current != null && serverId != null) {
            for (WaypointGroup group : manager.allGroups()) {
                if (!isGrottoRoute(group)) continue;
                FairyGrottoStore.LastPlacement last = store.lastPlacement(group.id());
                if (last != null && serverId.equals(last.serverId())) placements.put(group.id(), last.placement());
            }
        }
        if (hadPlacements || !placements.isEmpty()) onPlacementsChanged.run();
        return current != null;
    }

    private boolean inLobby() {
        return tracker.active() && tracker.lobby() != null;
    }

    static Set<Long> scan(ClientLevel level, CrystalHollowsPosition center) {
        Set<Long> glass = new HashSet<>();
        int minSectionY = Math.max(level.getMinSectionY(), (center.y() - SCAN_HEIGHT) >> 4);
        int maxSectionY = Math.min(level.getMaxSectionY(), (center.y() + SCAN_HEIGHT) >> 4);
        for (int cx = (center.x() - SCAN_RADIUS) >> 4; cx <= (center.x() + SCAN_RADIUS) >> 4; cx++) {
            for (int cz = (center.z() - SCAN_RADIUS) >> 4; cz <= (center.z() + SCAN_RADIUS) >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                    LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
                    if (section == null || section.hasOnlyAir() || !section.maybeHas(JASPER)) continue;
                    collect(section, cx << 4, sy << 4, cz << 4, center, glass);
                }
            }
        }
        return glass;
    }

    private static void collect(LevelChunkSection section, int baseX, int baseY, int baseZ,
                                CrystalHollowsPosition center, Set<Long> glass) {
        for (int y = 0; y < 16; y++) {
            int worldY = baseY + y;
            if (Math.abs(worldY - center.y()) > SCAN_HEIGHT) continue;
            for (int z = 0; z < 16; z++) {
                int worldZ = baseZ + z;
                if (Math.abs(worldZ - center.z()) > SCAN_RADIUS) continue;
                for (int x = 0; x < 16; x++) {
                    int worldX = baseX + x;
                    if (Math.abs(worldX - center.x()) > SCAN_RADIUS) continue;
                    if (isJasper(section.getBlockState(x, y, z))) {
                        glass.add(FairyGrottoLayout.pack(worldX, worldY, worldZ));
                    }
                }
            }
        }
    }

    private static boolean isJasper(BlockState state) {
        return state.is(JASPER_GLASS) || state.is(JASPER_PANE);
    }

    private static Block block(String id) {
        return BuiltInRegistries.BLOCK.getOptional(Identifier.withDefaultNamespace(id)).orElse(Blocks.AIR);
    }

    private static CrystalHollowsPosition playerPosition(Minecraft client) {
        return new CrystalHollowsPosition((int) Math.floor(client.player.getX()),
                (int) Math.floor(client.player.getY()), (int) Math.floor(client.player.getZ()));
    }
}
