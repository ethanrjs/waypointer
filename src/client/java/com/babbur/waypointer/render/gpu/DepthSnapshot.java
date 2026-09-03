package com.babbur.waypointer.render.gpu;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

/** Level-depth copy used by post-world compositing. */
public final class DepthSnapshot implements AutoCloseable {

    private GpuTexture texture;
    private GpuTextureView view;
    private int width;
    private int height;
    private boolean validThisFrame;

    public void capture() {
        int targetWidth = OverlayPassCompat.mainWidth();
        int targetHeight = OverlayPassCompat.mainHeight();
        if (targetWidth <= 0 || targetHeight <= 0) {
            validThisFrame = false;
            return;
        }
        if (texture == null || width != targetWidth || height != targetHeight) {
            close();
            texture = OverlayPassCompat.createDepthTexture(() -> "Waypointer overlay depth", targetWidth, targetHeight);
            view = OverlayPassCompat.createTextureView(texture);
            width = targetWidth;
            height = targetHeight;
        }
        OverlayPassCompat.copyDepth(OverlayPassCompat.mainDepthTexture(), texture, width, height);
        validThisFrame = true;
    }

    public void invalidate() {
        validThisFrame = false;
    }

    public boolean isValid() {
        return validThisFrame && view != null;
    }

    public GpuTextureView view() {
        return view;
    }

    @Override
    public void close() {
        GpuTextureView viewToClose = view;
        GpuTexture textureToClose = texture;
        view = null;
        texture = null;
        width = 0;
        height = 0;
        validThisFrame = false;

        Throwable failure = null;
        try {
            if (viewToClose != null) {
                viewToClose.close();
            }
        } catch (RuntimeException | LinkageError error) {
            failure = error;
        }
        try {
            if (textureToClose != null) {
                textureToClose.close();
            }
        } catch (RuntimeException | LinkageError error) {
            if (failure == null) {
                failure = error;
            } else {
                failure.addSuppressed(error);
            }
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof LinkageError linkageError) {
            throw linkageError;
        }
    }
}
