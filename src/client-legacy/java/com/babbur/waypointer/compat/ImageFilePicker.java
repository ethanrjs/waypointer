package com.babbur.waypointer.compat;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

public final class ImageFilePicker {
    private ImageFilePicker() {}

    public static CompletableFuture<String> open(String title, List<String> extensions, String description) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(extensions.size());
            for (String extension : extensions) filters.put(stack.UTF8("*." + extension));
            filters.flip();
            return CompletableFuture.completedFuture(
                    TinyFileDialogs.tinyfd_openFileDialog(title, null, filters, description, false));
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }
}
