package com.babbur.waypointer.crystal;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Developer tool for building the Crystal Hollows terrain map: while on, it records which blocks
 * are solid in every chunk the game receives, one file per lobby, and points a temporary waypoint
 * at the nearest part of the Hollows no scan has covered yet. The Crystal Hollows map shows the
 * covered area and the open space around the player's height.
 */
public final class CrystalHollowsTerrainScanner {
    public static final String GROUP_ID = "waypointer:crystal_hollows_scan_next";
    private static final String EXTENSION = ".wpch";
    private static final int CHUNKS_PER_TICK = 2;
    private static final long SAVE_EVERY_MS = 60_000;
    /** Map overlay resolution: one pixel per two blocks. */
    private static final int OVERLAY_BLOCKS = 2;
    private static final int OVERLAY_SIZE = (CrystalHollowsTerrain.SIZE_XZ + OVERLAY_BLOCKS - 1) / OVERLAY_BLOCKS;
    private static final int OPEN = 0xB8CFE3EA;
    private static final int UNSEEN = 0xC00B0F13;

    private static CrystalHollowsTerrainScanner instance;

    private final ActiveGroupManager manager;
    private final CrystalHollowsTracker tracker;
    private final Path directory;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Waypointer Crystal Hollows scan");
        thread.setDaemon(true);
        return thread;
    });

    private boolean enabled;
    private CrystalHollowsTerrain session;
    private String sessionLobby;
    private Path sessionFile;
    private boolean dirty;
    private long lastSaveMillis;
    private volatile CrystalHollowsTerrain earlier;
    private int cursor;
    private int ticks;
    private int[] markerChunk;

    private DynamicTexture overlay;
    private Identifier overlayId;
    private int overlayRevision = -1;
    private int overlayFeetY = Integer.MIN_VALUE;

    public CrystalHollowsTerrainScanner(ActiveGroupManager manager, CrystalHollowsTracker tracker, Path directory) {
        this.manager = manager;
        this.tracker = tracker;
        this.directory = directory;
    }

    public static CrystalHollowsTerrainScanner get() {
        return instance;
    }

    public void install() {
        instance = this;
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> finishSession(true));
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean on) {
        if (on == enabled) return;
        enabled = on;
        if (on) {
            loadEarlierScans();
        } else {
            finishSession(false);
            showMarker(null, 0);
        }
    }

    /** Shows a given scan on the map without scanning, for screenshots. */
    public void previewOnly(CrystalHollowsTerrain terrain) {
        enabled = true;
        session = null;
        earlier = terrain;
        overlayRevision = -1;
    }

    public Path directory() {
        return directory;
    }

    /** Chunk columns covered by this lobby's scan or any earlier one, out of all of them. */
    public int coveredChunks() {
        int covered = 0;
        for (int cz = CrystalHollowsTerrain.MIN_CHUNK; cz <= CrystalHollowsTerrain.MAX_CHUNK; cz++) {
            for (int cx = CrystalHollowsTerrain.MIN_CHUNK; cx <= CrystalHollowsTerrain.MAX_CHUNK; cx++) {
                if (covered(cx, cz)) covered++;
            }
        }
        return covered;
    }

    public int sessionChunks() {
        return session == null ? 0 : session.seenChunkCount();
    }

    public int earlierScanCount() {
        try (Stream<Path> files = scanFiles()) {
            return (int) files.count();
        } catch (IOException e) {
            return 0;
        }
    }

    private boolean covered(int cx, int cz) {
        CrystalHollowsTerrain prior = earlier;
        return session != null && session.chunkSeen(cx, cz) || prior != null && prior.chunkSeen(cx, cz);
    }

    private void tick(Minecraft client) {
        if (!enabled) return;
        if (!tracker.active() || client.level == null || client.player == null) {
            finishSession(false);
            showMarker(null, 0);
            return;
        }
        String lobby = CrystalHollowsLobbyIdentity.currentServerId(client);
        if (lobby == null || lobby.isBlank()) lobby = "lobby";
        if (session == null || !lobby.equals(sessionLobby)) {
            finishSession(false);
            startSession(lobby);
        }
        scanLoadedChunks(client);
        if (++ticks % 20 == 0) updateMarker(client);
        if (dirty && System.currentTimeMillis() - lastSaveMillis > SAVE_EVERY_MS) save();
    }

    private void startSession(String lobby) {
        session = new CrystalHollowsTerrain();
        sessionLobby = lobby;
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        sessionFile = directory.resolve(lobby.replaceAll("[^A-Za-z0-9_-]", "_") + "-" + stamp + EXTENSION);
        lastSaveMillis = System.currentTimeMillis();
        dirty = false;
        markerChunk = null;
    }

    /** Saves this lobby's scan and folds it into the earlier ones. */
    private void finishSession(boolean blocking) {
        if (session == null) return;
        if (dirty) {
            CompletableFuture<?> saving = save();
            if (blocking) saving.join();
        }
        CrystalHollowsTerrain finished = session;
        session = null;
        sessionLobby = null;
        CrystalHollowsTerrain prior = earlier;
        if (prior != null) {
            CrystalHollowsTerrain merged = prior.copy();
            merged.fillFrom(finished);
            earlier = merged;
        }
    }

    private CompletableFuture<?> save() {
        if (session == null) return CompletableFuture.completedFuture(null);
        dirty = false;
        lastSaveMillis = System.currentTimeMillis();
        CrystalHollowsTerrain snapshot = session.copy();
        Path target = sessionFile;
        return CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(directory);
                Path temp = target.resolveSibling(target.getFileName() + ".tmp");
                try (OutputStream out = Files.newOutputStream(temp)) {
                    snapshot.write(out);
                }
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Waypointer.LOGGER.warn("Could not save Crystal Hollows scan {}", target, e);
            }
        }, io);
    }

    private Stream<Path> scanFiles() throws IOException {
        if (!Files.isDirectory(directory)) return Stream.empty();
        return Files.list(directory).filter(path -> path.getFileName().toString().endsWith(EXTENSION));
    }

    private void loadEarlierScans() {
        earlier = null;
        CompletableFuture.runAsync(() -> {
            CrystalHollowsTerrain merged = new CrystalHollowsTerrain();
            try (Stream<Path> files = scanFiles()) {
                for (Path file : files.sorted().toList()) {
                    try (InputStream in = Files.newInputStream(file)) {
                        merged.fillFrom(CrystalHollowsTerrain.read(in));
                    } catch (IOException e) {
                        Waypointer.LOGGER.warn("Skipping unreadable Crystal Hollows scan {}", file, e);
                    }
                }
            } catch (IOException e) {
                Waypointer.LOGGER.warn("Could not list Crystal Hollows scans in {}", directory, e);
            }
            earlier = merged;
        }, io);
    }

    /** Scans a couple of newly loaded chunk columns per tick, walking the grid round-robin. */
    private void scanLoadedChunks(Minecraft client) {
        int total = CrystalHollowsTerrain.totalChunks();
        int scanned = 0;
        for (int step = 0; step < total && scanned < CHUNKS_PER_TICK; step++) {
            cursor = (cursor + 1) % total;
            int cx = CrystalHollowsTerrain.MIN_CHUNK + cursor % CrystalHollowsTerrain.CHUNKS;
            int cz = CrystalHollowsTerrain.MIN_CHUNK + cursor / CrystalHollowsTerrain.CHUNKS;
            if (session.chunkSeen(cx, cz)) continue;
            LevelChunk chunk = client.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
            if (chunk == null || chunk.isEmpty()) continue;
            scanChunk(chunk, cx, cz);
            scanned++;
        }
    }

    // blocksMotion is deprecated only as something to override; vanilla still calls it for movement.
    @SuppressWarnings("deprecation")
    private void scanChunk(LevelChunk chunk, int cx, int cz) {
        int x0 = Math.max(CrystalHollowsTerrain.MIN_X, cx * 16);
        int x1 = Math.min(CrystalHollowsTerrain.MIN_X + CrystalHollowsTerrain.SIZE_XZ - 1, cx * 16 + 15);
        int z0 = Math.max(CrystalHollowsTerrain.MIN_Z, cz * 16);
        int z1 = Math.min(CrystalHollowsTerrain.MIN_Z + CrystalHollowsTerrain.SIZE_XZ - 1, cz * 16 + 15);
        LevelChunkSection[] sections = chunk.getSections();
        for (int y = CrystalHollowsTerrain.MIN_Y; y <= CrystalHollowsTerrain.MAX_Y; y++) {
            int index = chunk.getSectionIndex(y);
            if (index < 0 || index >= sections.length) continue;
            LevelChunkSection section = sections[index];
            if (section == null || section.hasOnlyAir()) continue;
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    if (section.getBlockState(x & 15, y & 15, z & 15).blocksMotion()) {
                        session.setSolid(x, y, z, true);
                    }
                }
            }
        }
        session.markChunkSeen(cx, cz);
        dirty = true;
    }

    /** Points a temporary waypoint at the nearest chunk column no scan has covered. */
    private void updateMarker(Minecraft client) {
        double px = client.player.getX(), pz = client.player.getZ();
        int[] best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int cz = CrystalHollowsTerrain.MIN_CHUNK; cz <= CrystalHollowsTerrain.MAX_CHUNK; cz++) {
            for (int cx = CrystalHollowsTerrain.MIN_CHUNK; cx <= CrystalHollowsTerrain.MAX_CHUNK; cx++) {
                if (covered(cx, cz)) continue;
                double dx = cx * 16 + 8 - px, dz = cz * 16 + 8 - pz;
                if (dx * dx + dz * dz < bestDistance) {
                    bestDistance = dx * dx + dz * dz;
                    best = new int[]{cx, cz};
                }
            }
        }
        showMarker(best, (int) Math.floor(client.player.getY()));
    }

    private void showMarker(int[] chunk, int y) {
        if (java.util.Arrays.equals(chunk, markerChunk)) return;
        markerChunk = chunk;
        if (chunk == null) {
            manager.replaceGroupsAtomically(List.of(GROUP_ID), List.of());
            return;
        }
        int percent = coveredChunks() * 100 / CrystalHollowsTerrain.totalChunks();
        String name = Component.translatable("waypointer.crystal.scan.next", percent).getString();
        WaypointGroup group = new WaypointGroup(GROUP_ID, name, "crystal_hollows");
        group.setRuntimeOnly(true);
        group.setEnabled(true);
        group.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        group.setSkipAheadEnabled(false);
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        int x = Math.clamp(chunk[0] * 16 + 8, CrystalHollowsTerrain.MIN_X, CrystalHollowsTerrain.MIN_X + CrystalHollowsTerrain.SIZE_XZ - 1);
        int z = Math.clamp(chunk[1] * 16 + 8, CrystalHollowsTerrain.MIN_Z, CrystalHollowsTerrain.MIN_Z + CrystalHollowsTerrain.SIZE_XZ - 1);
        int clampedY = Math.clamp(y, CrystalHollowsTerrain.MIN_Y, CrystalHollowsTerrain.MAX_Y);
        group.add(Waypoint.at(x, clampedY, z).withName(name).withColor(0xFFB060)
                .withFlags(Waypoint.FLAG_THROUGH_WALL | Waypoint.FLAG_LOCKED_COLOR));
        manager.replaceGroupsAtomically(List.of(GROUP_ID), List.of(group));
    }

    /**
     * Draws the scan over the Crystal Hollows map: unscanned areas dark, and in scanned areas
     * the open space a player could stand in within two blocks of their height.
     */
    public void drawOverlay(GuiGraphicsExtractor g, int x, int y, int size, double playerY) {
        drawOverlay(g, x, y, size, playerY, 0xFFFFFFFF);
    }

    /** As above, with the overlay multiplied by {@code tint} (e.g. lower alpha for a quieter map). */
    public void drawOverlay(GuiGraphicsExtractor g, int x, int y, int size, double playerY, int tint) {
        if (!enabled || size <= 0) return;
        int feetY = (int) Math.floor(playerY);
        refreshOverlay(feetY);
        if (overlayId == null) return;
        g.blit(RenderPipelines.GUI_TEXTURED, overlayId, x, y, 0f, 0f, size, size,
                OVERLAY_SIZE, OVERLAY_SIZE, OVERLAY_SIZE, OVERLAY_SIZE, tint);
    }

    private void refreshOverlay(int feetY) {
        int revision = (session == null ? 0 : session.revision()) * 31
                + (earlier == null ? 0 : earlier.revision() + 1);
        if (overlay != null && revision == overlayRevision && feetY == overlayFeetY) return;
        if (overlay != null && ticks % 10 != 0 && feetY == overlayFeetY) return;
        if (overlay == null) {
            overlay = new DynamicTexture(() -> "waypointer_crystal_hollows_scan", OVERLAY_SIZE, OVERLAY_SIZE, false);
            overlayId = Identifier.fromNamespaceAndPath(Waypointer.MOD_ID, "dynamic/crystal_hollows_scan");
            Minecraft.getInstance().getTextureManager().register(overlayId, overlay);
        }
        overlayRevision = revision;
        overlayFeetY = feetY;
        CrystalHollowsTerrain current = session;
        CrystalHollowsTerrain prior = earlier;
        NativeImage image = overlay.getPixels();
        for (int py = 0; py < OVERLAY_SIZE; py++) {
            int z = CrystalHollowsTerrain.MIN_Z + py * OVERLAY_BLOCKS;
            for (int px = 0; px < OVERLAY_SIZE; px++) {
                int x = CrystalHollowsTerrain.MIN_X + px * OVERLAY_BLOCKS;
                int cx = Math.floorDiv(x, 16), cz = Math.floorDiv(z, 16);
                CrystalHollowsTerrain source = current != null && current.chunkSeen(cx, cz) ? current
                        : prior != null && prior.chunkSeen(cx, cz) ? prior : null;
                int argb;
                if (source == null) argb = UNSEEN;
                else argb = source.walkableNear(x, z, feetY, 2) || source.walkableNear(x + 1, z + 1, feetY, 2)
                        ? OPEN : 0;
                image.setPixelABGR(px, py, abgr(argb));
            }
        }
        overlay.upload();
    }

    private static int abgr(int argb) {
        return (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16);
    }
}
