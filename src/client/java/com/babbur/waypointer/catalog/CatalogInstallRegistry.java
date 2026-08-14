package com.babbur.waypointer.catalog;

import com.babbur.waypointer.Waypointer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class CatalogInstallRegistry {
    private static final int SCHEMA = 1;
    private static final int MAX_RECORDS = 4096;
    private static final int MAX_FILE_BYTES = 128 * 1024;
    private static final Pattern ROUTE_ID = Pattern.compile("[A-Za-z0-9_-]{22}");
    private static final Object IO_LOCK = new Object();

    private final Path file;

    public CatalogInstallRegistry(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public static CatalogInstallRegistry defaultLocation() {
        return new CatalogInstallRegistry(FabricLoader.getInstance().getConfigDir()
                .resolve(Waypointer.MOD_ID)
                .resolve("catalog-installs.json"));
    }

    public Set<String> load() {
        synchronized (IO_LOCK) {
            return Set.copyOf(readIds());
        }
    }

    public void record(String routeId) {
        requireRouteId(routeId);
        synchronized (IO_LOCK) {
            LinkedHashSet<String> ids = new LinkedHashSet<>(readIds());
            if (!ids.add(routeId)) return;
            if (ids.size() > MAX_RECORDS) {
                List<String> bounded = new ArrayList<>(ids);
                ids = new LinkedHashSet<>(bounded.subList(
                        bounded.size() - MAX_RECORDS, bounded.size()));
            }
            writeIds(ids);
        }
    }

    private LinkedHashSet<String> readIds() {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new LinkedHashSet<>();
        rejectUnsafeFile();
        try {
            long size = Files.size(file);
            if (size <= 0 || size > MAX_FILE_BYTES) throw invalidRegistry(null);
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (!root.has("schema") || root.get("schema").getAsInt() != SCHEMA
                    || !root.has("installedRouteIds")
                    || !root.get("installedRouteIds").isJsonArray()) {
                throw invalidRegistry(null);
            }
            JsonArray values = root.getAsJsonArray("installedRouteIds");
            if (values.size() > MAX_RECORDS) throw invalidRegistry(null);
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (JsonElement value : values) {
                if (!value.isJsonPrimitive()
                        || !value.getAsJsonPrimitive().isString()
                        || !ids.add(requireRouteId(value.getAsString()))) {
                    throw invalidRegistry(null);
                }
            }
            return ids;
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof IllegalStateException state
                    && state.getMessage() != null
                    && state.getMessage().startsWith("Catalog install registry")) {
                throw state;
            }
            throw invalidRegistry(failure);
        }
    }

    private void writeIds(Set<String> ids) {
        Path parent = file.getParent();
        if (parent == null) throw new IllegalStateException("Registry path has no parent");
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            if (Files.isSymbolicLink(file)) throw invalidRegistry(null);
            JsonObject root = new JsonObject();
            root.addProperty("schema", SCHEMA);
            JsonArray values = new JsonArray();
            for (String id : ids) values.add(id);
            root.add("installedRouteIds", values);
            byte[] bytes = (root + System.lineSeparator()).getBytes(
                    java.nio.charset.StandardCharsets.UTF_8);
            if (bytes.length > MAX_FILE_BYTES) throw invalidRegistry(null);
            temporary = Files.createTempFile(parent, "catalog-installs-", ".tmp");
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } catch (IOException failure) {
            throw new IllegalStateException("Could not save catalog install registry", failure);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // This registry contains public route IDs only.
                }
            }
        }
    }

    private void rejectUnsafeFile() {
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw invalidRegistry(null);
        }
    }

    private static String requireRouteId(String routeId) {
        if (routeId == null || !ROUTE_ID.matcher(routeId).matches()) {
            throw new IllegalArgumentException("Invalid catalog route ID");
        }
        return routeId;
    }

    private static IllegalStateException invalidRegistry(Throwable cause) {
        String message = "Catalog install registry is invalid. "
                + "Move catalog-installs.json aside manually.";
        return cause == null ? new IllegalStateException(message)
                : new IllegalStateException(message, cause);
    }
}
