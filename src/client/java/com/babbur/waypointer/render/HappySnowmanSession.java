package com.babbur.waypointer.render;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.core.WaypointPaint;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Session-only visual override enabled by the intentionally unadvertised command. */
public final class HappySnowmanSession {
    private static final Identifier SKIN_ID =
            Identifier.fromNamespaceAndPath(Waypointer.MOD_ID, "happy_snowman");
    private static final ClientAsset.Texture SKIN_TEXTURE = new ClientAsset.Texture() {
        @Override
        public Identifier id() {
            return SKIN_ID;
        }

        @Override
        public Identifier texturePath() {
            return SKIN_ID;
        }
    };
    private static final String SKIN_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAMAAACdt4HsAAAAOVBMVEUAAAAAAAADUweq5f+u5f+05f+65f+84P/D5f/H5f/J5f/NAADS5f/T6//e8P/t5f/v9//3+//4jQBwcn8aAAAAAXRSTlMAQObYZgAAAldJREFUWMPtl90SmjAQhVXU9UTPMfT9H7YX2YUEiEJ70067OsOAy+cm+8PhdHIzAAAsjqeOpfeQbtfH+neAJAGAEoEe4GXpNgyvtAaQEglQpNgFvNN1SNfbYwOgUSIgElQXMNxfgyV7bQGyA/AJkCxdH/e0tQRJ2rOE4ZHS/d4HqFgPYMmGW7LbRhYkCfiWxvvjfbNrmiMoN5CUJEUqv9dD9c9x91lniVIwPtdDU0Aas85l6WflUSQQWalcf/QBWTq7STkA0odstICFHQZQmiDTJpAkdwDMuygA3lNxeQcg8gXUZTBdfl7Kxw/P5/NZjn4hOhGowrWSQrMShVUdCwO5ioISKbEqa2+q0uKTXxbBDqCOoAtQB1BCm0M9tATfrKj/snnRmYBh3mSYHwxVdgDSv24kNZ7l9TDf3/o1s5AS5ZkAKY25LklEZfp3CZDfwLJySpp6ixAFyZsr/FaA7M5Zms/9mrImgPv1AaWUS1vHEsqPLSAjfwNMexAtUvutNjGcyw7FBZ8LE6DyW6Qxluv95xMue1uLIoWp7YkVgNUM8D7WXAYVYLMOzBMdNWMWs4QEzARAgqH88eS3mgc+A8ysKe7KopStLeUti+Fx+lX7ywGXy6UZoM3wPBbF6ffsDwCc/tu/bku94OfcDVjqBSqTOgjAQhfwSARLvXBoCVt6odIFe7TzWi9sPg8+hL/QCx1d0AdIyuNCL4zK2rkP8AdjaIM4z/42cxCgBlDXxmGA9gMWemFxvieNS70wvQLsBTR6oamDPYCVXujpgm/vD7NeaHTBqpR/AqbwTaLwf8cgAAAAAElFTkSuQmCC";

    private static boolean active;
    private static ClientPacketListener activationConnection;
    private static boolean textureRegistered;
    private static WaypointPaint facePaint;

    private HappySnowmanSession() {}

    public static void install() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> deactivate());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> deactivate());
    }

    public static void activate() {
        ensureLoaded();
        activationConnection = Minecraft.getInstance().getConnection();
        active = activationConnection != null;
    }

    public static void deactivate() {
        active = false;
        activationConnection = null;
    }

    public static boolean active() {
        if (active && Minecraft.getInstance().getConnection() != activationConnection) {
            deactivate();
        }
        return active;
    }

    public static WaypointPaint facePaint() {
        return active() ? facePaint : null;
    }

    public static PlayerSkin override(PlayerSkin original) {
        if (!active() || original == null) return original;
        return PlayerSkin.insecure(SKIN_TEXTURE, original.cape(), original.elytra(), PlayerModelType.WIDE);
    }

    private static void ensureLoaded() {
        if (textureRegistered) return;
        try {
            NativeImage image = NativeImage.read(Base64.getDecoder().decode(SKIN_PNG));
            facePaint = facePaint(image);
            Minecraft.getInstance().getTextureManager().register(
                    SKIN_ID, new DynamicTexture(() -> "happy_snowman", image));
            textureRegistered = true;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load the happy snowman skin", e);
        }
    }

    static WaypointPaint facePaint(NativeImage skin) {
        Map<Integer, Integer> paletteSlots = new LinkedHashMap<>();
        byte[] pixels = new byte[WaypointPaint.PIXEL_COUNT];
        for (WaypointPaint.Face face : WaypointPaint.Face.values()) {
            for (int y = 0; y < WaypointPaint.SIZE; y++) {
                for (int x = 0; x < WaypointPaint.SIZE; x++) {
                    int skinX = 8 + x / 2;
                    int skinY = 8 + y / 2;
                    int base = skin.getPixel(skinX, skinY);
                    int overlay = skin.getPixel(40 + x / 2, skinY);
                    int rgb = ((overlay >>> 24) != 0 ? overlay : base) & 0xFFFFFF;
                    int slot = paletteSlots.computeIfAbsent(rgb, ignored -> paletteSlots.size());
                    if (slot >= WaypointPaint.PALETTE_SIZE) {
                        throw new IllegalStateException("Happy snowman face exceeds the waypoint palette");
                    }
                    pixels[WaypointPaint.pixelOffset(face, x, y)] = (byte) slot;
                }
            }
        }
        int[] palette = new int[WaypointPaint.PALETTE_SIZE];
        paletteSlots.forEach((rgb, slot) -> palette[slot] = rgb);
        return new WaypointPaint(palette, pixels);
    }
}
