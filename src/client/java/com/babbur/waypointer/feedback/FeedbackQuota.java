package com.babbur.waypointer.feedback;

import com.babbur.waypointer.Waypointer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.function.Supplier;

/** Tracks this device's UTC daily send quota; the service also enforces its own limit. */
public final class FeedbackQuota {
    public static final int DAILY_LIMIT = 20;

    private static volatile FeedbackQuota shared;

    private final Path file;
    private final Supplier<LocalDate> today;
    private String day = "";
    private int count;
    private boolean loaded;

    public FeedbackQuota(Path file, Supplier<LocalDate> today) {
        this.file = file;
        this.today = today;
    }

    public static FeedbackQuota shared() {
        FeedbackQuota quota = shared;
        if (quota == null) {
            quota = new FeedbackQuota(FabricLoader.getInstance().getConfigDir()
                    .resolve(Waypointer.MOD_ID).resolve("feedback-quota.json"),
                    () -> LocalDate.now(ZoneOffset.UTC));
            shared = quota;
        }
        return quota;
    }

    public synchronized int sentToday() {
        refresh();
        return count;
    }

    public synchronized int remainingToday() {
        return Math.max(0, DAILY_LIMIT - sentToday());
    }

    public synchronized boolean exhausted() {
        return remainingToday() == 0;
    }

    /** Records one confirmed send; a failed or rejected upload never counts. */
    public synchronized void recordSend() {
        refresh();
        count++;
        store();
    }

    private void refresh() {
        if (!loaded) {
            loaded = true;
            load();
        }
        String current = today.get().toString();
        if (!current.equals(day)) {
            day = current;
            count = 0;
        }
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            day = root.has("day") ? root.get("day").getAsString() : "";
            count = root.has("count") ? Math.max(0, root.get("count").getAsInt()) : 0;
        } catch (Exception failure) {
            Waypointer.LOGGER.debug("Ignoring unreadable feedback quota file", failure);
            day = "";
            count = 0;
        }
    }

    private void store() {
        if (file == null) return;
        JsonObject root = new JsonObject();
        root.addProperty("day", day);
        root.addProperty("count", count);
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(file, root + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (Exception failure) {
            Waypointer.LOGGER.warn("Could not save the feedback quota", failure);
        }
    }
}
