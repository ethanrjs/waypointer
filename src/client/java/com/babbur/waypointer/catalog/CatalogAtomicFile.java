package com.babbur.waypointer.catalog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Writes through a temporary file and uses an atomic move when available. */
final class CatalogAtomicFile {
    private CatalogAtomicFile() {
    }

    static boolean create(
            Path target, byte[] bytes, String temporaryPrefix,
            Permissions permissions) throws IOException {
        return write(target, bytes, temporaryPrefix, false, permissions);
    }

    static void replace(
            Path target, byte[] bytes, String temporaryPrefix) throws IOException {
        write(target, bytes, temporaryPrefix, true, ignored -> { });
    }

    static void replace(
            Path target, byte[] bytes, String temporaryPrefix,
            Permissions permissions) throws IOException {
        write(target, bytes, temporaryPrefix, true, permissions);
    }

    private static boolean write(
            Path target, byte[] bytes, String temporaryPrefix,
            boolean replace, Permissions permissions) throws IOException {
        Path parent = target.getParent();
        if (parent == null) throw new IOException("Atomic file path has no parent");
        Path temporary = Files.createTempFile(parent, temporaryPrefix, ".tmp");
        try {
            permissions.apply(temporary);
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                writeFully(channel, ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            boolean moved = move(temporary, target, replace);
            if (moved) temporary = null;
            permissions.apply(target);
            return moved;
        } finally {
            if (temporary != null) Files.deleteIfExists(temporary);
        }
    }

    private static boolean move(
            Path temporary, Path target, boolean replace) throws IOException {
        try {
            if (replace) {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            }
            return true;
        } catch (AtomicMoveNotSupportedException ignored) {
            try {
                if (replace) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(temporary, target);
                }
                return true;
            } catch (FileAlreadyExistsException race) {
                return false;
            }
        } catch (FileAlreadyExistsException race) {
            return false;
        }
    }

    static void writeFully(java.nio.channels.WritableByteChannel channel, ByteBuffer data)
            throws IOException {
        while (data.hasRemaining()) channel.write(data);
    }

    @FunctionalInterface
    interface Permissions {
        void apply(Path path) throws IOException;
    }
}
