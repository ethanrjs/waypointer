package com.babbur.waypointer.compat;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.sdl.SDLDialog.*;
import static org.lwjgl.sdl.SDLError.SDL_GetError;
import static org.lwjgl.sdl.SDLProperties.*;

public final class ImageFilePicker {
    private static final AtomicReference<Request> PENDING = new AtomicReference<>();
    // One callback lives with the client. Freeing a callback while it is returning to
    // SDL is unsafe; each dialog's filters and properties are released independently.
    private static final SDL_DialogFileCallback CALLBACK = SDL_DialogFileCallback.create((ignored, files, filter) -> {
        Request request = PENDING.getAndSet(null);
        if (request == null) return;
        String error = files == MemoryUtil.NULL ? SDL_GetError() : null;
        long first = files == MemoryUtil.NULL ? MemoryUtil.NULL : MemoryUtil.memGetAddress(files);
        String selected = first == MemoryUtil.NULL ? null : MemoryUtil.memUTF8(first);
        request.close();
        Minecraft.getInstance().execute(() -> {
            if (error != null) request.result.completeExceptionally(new IllegalStateException(error));
            else request.result.complete(selected);
        });
    });

    private ImageFilePicker() {}

    public static CompletableFuture<String> open(String title, List<String> extensions, String description) {
        Request request = new Request();
        if (!PENDING.compareAndSet(null, request)) {
            return CompletableFuture.failedFuture(new IllegalStateException("A file dialog is already open"));
        }
        try {
            request.properties = SDL_CreateProperties();
            if (request.properties == 0) throw new IllegalStateException(SDL_GetError());
            request.name = MemoryUtil.memUTF8(description);
            request.pattern = MemoryUtil.memUTF8(String.join(";", extensions));
            request.filters = SDL_DialogFileFilter.calloc(1);
            request.filters.get(0).name(request.name).pattern(request.pattern);
            SDL_SetPointerProperty(request.properties, SDL_PROP_FILE_DIALOG_FILTERS_POINTER, request.filters.address());
            SDL_SetNumberProperty(request.properties, SDL_PROP_FILE_DIALOG_NFILTERS_NUMBER, 1);
            SDL_SetPointerProperty(request.properties, SDL_PROP_FILE_DIALOG_WINDOW_POINTER, Minecraft.getInstance().getWindow().handle());
            SDL_SetStringProperty(request.properties, SDL_PROP_FILE_DIALOG_TITLE_STRING, title);
            // SDL retains filter pointers until its callback, so they must not be stack allocations.
            SDL_ShowFileDialogWithProperties(SDL_FILEDIALOG_OPENFILE, CALLBACK, 0, request.properties);
        } catch (RuntimeException | LinkageError failure) {
            if (PENDING.compareAndSet(request, null)) request.close();
            request.result.completeExceptionally(failure);
        }
        return request.result;
    }

    private static final class Request implements AutoCloseable {
        final CompletableFuture<String> result = new CompletableFuture<>();
        int properties;
        ByteBuffer name;
        ByteBuffer pattern;
        SDL_DialogFileFilter.Buffer filters;

        @Override public void close() {
            if (properties != 0) SDL_DestroyProperties(properties);
            if (filters != null) filters.free();
            MemoryUtil.memFree(name);
            MemoryUtil.memFree(pattern);
        }
    }
}
