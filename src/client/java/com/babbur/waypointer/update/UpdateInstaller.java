package com.babbur.waypointer.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class UpdateInstaller {

    static final String PENDING_FILE = "pending.properties";
    private static final long PARENT_EXIT_TIMEOUT_SECONDS = 120;
    private static final int MOVE_ATTEMPTS = 40;
    private static final long MOVE_RETRY_MILLIS = 250;

    private UpdateInstaller() {
    }

    public static void main(String[] args) {
        if (args.length != 5) {
            log("expected 5 arguments, got " + args.length);
            System.exit(2);
        }
        try {
            long parentPid = Long.parseLong(args[0]);
            if (!waitForExit(parentPid)) {
                log("Minecraft is still running; leaving the update staged");
                System.exit(1);
            }
            boolean installed = install(Path.of(args[1]), Path.of(args[2]), args[3], Path.of(args[4]));
            System.exit(installed ? 0 : 1);
        } catch (RuntimeException failure) {
            log("update failed: " + failure);
            System.exit(1);
        }
    }

    static boolean install(Path stagingDir, Path stagedJar, String expectedSha512, Path currentJar) {
        return install(stagingDir, stagedJar, expectedSha512, currentJar, MOVE_ATTEMPTS);
    }

    static boolean install(Path stagingDir, Path stagedJar, String expectedSha512, Path currentJar,
                           int moveAttempts) {
        Path modsDir = currentJar.toAbsolutePath().getParent();
        Path target = modsDir.resolve(stagedJar.getFileName());
        Path incoming = modsDir.resolve(".waypointer-update.tmp");
        Path backupDir = stagingDir.resolve("backup");
        Path backup = backupDir.resolve(currentJar.getFileName());
        try {
            if (!stagedJar.getFileName().toString().endsWith(".jar")) {
                log("staged file is not a jar: " + stagedJar);
                return false;
            }
            if (!expectedSha512.equals(sha512(stagedJar))) {
                log("staged jar no longer matches its checksum; discarding it");
                Files.deleteIfExists(stagedJar);
                Files.deleteIfExists(stagingDir.resolve(PENDING_FILE));
                return false;
            }
            if (!Files.isRegularFile(currentJar)) {
                log("installed jar is gone: " + currentJar);
                return false;
            }
            if (Files.exists(target) && !Files.isSameFile(target, currentJar)) {
                log("a different file already uses the new name: " + target);
                return false;
            }

            Files.copy(stagedJar, incoming, StandardCopyOption.REPLACE_EXISTING);
            Files.createDirectories(backupDir);
            if (!moveWithRetries(currentJar, backup, moveAttempts)) {
                log("could not move the old jar out of the mods folder");
                Files.deleteIfExists(incoming);
                return false;
            }
            try {
                move(incoming, target);
            } catch (IOException failure) {
                log("could not place the new jar: " + failure);
                move(backup, currentJar);
                Files.deleteIfExists(incoming);
                return false;
            }
            Files.deleteIfExists(stagingDir.resolve(PENDING_FILE));
            Files.deleteIfExists(stagedJar);
            log("installed " + target.getFileName() + " (previous jar kept in " + backupDir + ")");
            return true;
        } catch (IOException failure) {
            log("update failed: " + failure);
            return false;
        }
    }

    static String sha512(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean waitForExit(long pid) {
        Optional<ProcessHandle> parent = ProcessHandle.of(pid);
        if (parent.isEmpty()) return true;
        try {
            parent.get().onExit().get(PARENT_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (Exception stillRunning) {
            return !parent.get().isAlive();
        }
    }

    private static boolean moveWithRetries(Path from, Path to, int attempts) {
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                move(from, to);
                return true;
            } catch (IOException locked) {
                if (attempt == attempts) {
                    log("move failed: " + locked);
                    return false;
                }
                try {
                    Thread.sleep(MOVE_RETRY_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void log(String message) {
        System.out.println(Instant.now() + " " + message);
    }
}
