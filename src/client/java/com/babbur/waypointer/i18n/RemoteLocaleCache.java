package com.babbur.waypointer.i18n;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;

final class RemoteLocaleCache {
    private final Path root;
    private final TranslationCatalogValidator validator;

    RemoteLocaleCache(Path root, TranslationCatalogValidator validator) {
        this.root = root;
        this.validator = validator;
    }

    Optional<byte[]> load(String commit, String locale, RemoteLocaleManifest.Entry entry) {
        Path file = file(commit, locale);
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) return Optional.empty();
            if (Files.size(file) != entry.bytes()) return Optional.empty();
            byte[] bytes = Files.readAllBytes(file);
            validator.validate(bytes, entry, locale);
            return Optional.of(bytes);
        } catch (IOException | RuntimeException failure) {
            return Optional.empty();
        }
    }

    void store(String commit, String locale, RemoteLocaleManifest.Entry entry, byte[] bytes) throws IOException {
        validator.validate(bytes, entry, locale);
        Path target = file(commit, locale);
        Files.createDirectories(target.getParent());
        if (Files.isSymbolicLink(target.getParent())) throw new IOException("Locale cache directory is a symbolic link");
        Path temporary = target.resolveSibling("." + locale + "." + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            validator.validate(Files.readAllBytes(temporary), entry, locale);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path file(String commit, String locale) {
        if (!commit.matches("[0-9a-f]{40}") || !locale.matches("[a-z0-9]+(?:_[a-z0-9]+)*")) {
            throw new IllegalArgumentException("Unsafe locale cache path");
        }
        Path path = root.resolve(commit).resolve(locale + ".json").normalize();
        if (!path.startsWith(root.normalize())) throw new IllegalArgumentException("Locale cache path escaped its root");
        return path;
    }
}
