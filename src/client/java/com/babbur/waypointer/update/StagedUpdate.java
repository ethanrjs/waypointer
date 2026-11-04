package com.babbur.waypointer.update;

import com.babbur.waypointer.Waypointer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

final class StagedUpdate {

    private static final AtomicBoolean HOOK_REGISTERED = new AtomicBoolean();
    private static volatile StagedUpdate armed;

    private final Path dir;
    final String version;
    final Path jar;
    final String sha512;
    final Path currentJar;

    StagedUpdate(Path dir, String version, Path jar, String sha512, Path currentJar) {
        this.dir = dir;
        this.version = version;
        this.jar = jar;
        this.sha512 = sha512;
        this.currentJar = currentJar;
    }

    static Optional<StagedUpdate> load(Path dir) {
        Path file = dir.resolve(UpdateInstaller.PENDING_FILE);
        if (!Files.isRegularFile(file)) return Optional.empty();
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
            String version = properties.getProperty("version");
            String jarName = properties.getProperty("jar");
            String sha512 = properties.getProperty("sha512");
            String current = properties.getProperty("current");
            if (version == null || jarName == null || sha512 == null || current == null
                    || !ModrinthVersionIndex.expectedFileName(jarName, version)) {
                return Optional.empty();
            }
            Path jar = dir.resolve(jarName);
            if (!Files.isRegularFile(jar)) return Optional.empty();
            return Optional.of(new StagedUpdate(dir, version, jar, sha512, Path.of(current)));
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    void save() throws IOException {
        Properties properties = new Properties();
        properties.setProperty("version", version);
        properties.setProperty("jar", jar.getFileName().toString());
        properties.setProperty("sha512", sha512);
        properties.setProperty("current", currentJar.toAbsolutePath().toString());
        Path temp = dir.resolve(UpdateInstaller.PENDING_FILE + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            properties.store(writer, null);
        }
        Files.move(temp, dir.resolve(UpdateInstaller.PENDING_FILE),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    void discard() {
        if (armed == this) armed = null;
        try {
            Files.deleteIfExists(dir.resolve(UpdateInstaller.PENDING_FILE));
            Files.deleteIfExists(jar);
        } catch (IOException failure) {
            Waypointer.LOGGER.warn("Could not remove the staged Waypointer update", failure);
        }
    }

    void armOnExit() {
        armed = this;
        if (HOOK_REGISTERED.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(StagedUpdate::launchArmedInstaller,
                    "Waypointer update installer"));
        }
    }

    private static void launchArmedInstaller() {
        StagedUpdate update = armed;
        if (update == null || !Files.isRegularFile(update.dir.resolve(UpdateInstaller.PENDING_FILE))) return;
        try {
            update.launchInstaller();
        } catch (IOException | RuntimeException failure) {
            Waypointer.LOGGER.warn("Could not start the Waypointer update installer", failure);
        }
    }

    private void launchInstaller() throws IOException {
        Path helperRoot = dir.resolve("installer");
        Path classFile = helperRoot.resolve(UpdateInstaller.class.getName().replace('.', '/') + ".class");
        Files.createDirectories(classFile.getParent());
        try (InputStream in = UpdateInstaller.class.getResourceAsStream(
                UpdateInstaller.class.getSimpleName() + ".class")) {
            if (in == null) throw new IOException("installer class is not readable");
            Files.copy(in, classFile, StandardCopyOption.REPLACE_EXISTING);
        }
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-Xmx64m");
        command.add("-Djava.awt.headless=true");
        command.add("-cp");
        command.add(helperRoot.toAbsolutePath().toString());
        command.add(UpdateInstaller.class.getName());
        command.add(Long.toString(ProcessHandle.current().pid()));
        command.add(dir.toAbsolutePath().toString());
        command.add(jar.toAbsolutePath().toString());
        command.add(sha512);
        command.add(currentJar.toAbsolutePath().toString());
        new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(dir.resolve("install.log").toFile()))
                .redirectInput(ProcessBuilder.Redirect.from(emptyInput()))
                .start();
    }

    private java.io.File emptyInput() throws IOException {
        Path empty = dir.resolve("installer").resolve("stdin");
        if (!Files.exists(empty)) {
            try (OutputStream ignored = Files.newOutputStream(empty)) {
            }
        }
        return empty.toFile();
    }

    private static String javaExecutable() {
        Optional<String> running = ProcessHandle.current().info().command();
        if (running.isPresent() && Files.isExecutable(Path.of(running.get()))) return running.get();
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "javaw.exe" : "java").toString();
    }

    static Optional<StagedUpdate> resumeOrClean(Path dir, String runningVersion) {
        Optional<StagedUpdate> pending = load(dir);
        Optional<StableVersion> running = StableVersion.parse(runningVersion);
        if (pending.isPresent() && running.isPresent()) {
            Optional<StableVersion> staged = StableVersion.parse(pending.get().version);
            if (staged.isPresent() && staged.get().compareTo(running.get()) > 0
                    && Files.isRegularFile(pending.get().currentJar)) {
                pending.get().armOnExit();
                return pending;
            }
        }
        deleteTree(dir);
        return Optional.empty();
    }

    private static void deleteTree(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException failure) {
            Waypointer.LOGGER.debug("Could not clean the Waypointer update folder", failure);
        }
    }
}
