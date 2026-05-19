package dev.ethan.waypointer.diana;

import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Waypoint;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

public final class DianaWaypointDetector {

    private static final long OUTGOING_SHARE_COOLDOWN_MS = 5_000L;
    private static final String RARE_MOB_LABEL_MARKER = " from ";

    private final WaypointerConfig config;
    private final ActiveGroupManager manager;
    private final Map<DianaRareMob, Long> lastSharedMillis = new EnumMap<>(DianaRareMob.class);

    public DianaWaypointDetector(WaypointerConfig config, ActiveGroupManager manager) {
        this.config = config;
        this.manager = manager;
    }

    public void install() {
        ClientReceiveMessageEvents.MODIFY_GAME.register(this::onMessage);
    }

    private Component onMessage(Component message, boolean overlay) {
        if (overlay) return message;
        if (manager.currentZone() == null || !"hub".equals(manager.currentZone().id())) return message;

        String text = message.getString();
        maybeShareOwnRareMob(text);

        if (config.dianaRareMobWaypoints()) {
            DianaRareMobShareParser.parse(text).ifPresent(share -> {
                long now = System.currentTimeMillis();
                var group = manager.addTempWaypoint(share.x(), share.y(), share.z(),
                        tempLabel(share),
                        config.tempDefaultMode(),
                        config.defaultTempExpiresAtMillis(now));
                if (config.focusTempWaypoints()) {
                    manager.focusTempWaypoint(group, group.size() - 1);
                }
            });
        }
        return message;
    }

    private void maybeShareOwnRareMob(String text) {
        if (!config.dianaRareMobPartySharing()) return;

        DianaRareMob mob = dugRareMob(text);
        if (mob == null || !config.dianaRareMobShareEnabled(mob)) return;

        long now = System.currentTimeMillis();
        Long last = lastSharedMillis.get(mob);
        if (last != null && now - last < OUTGOING_SHARE_COOLDOWN_MS) return;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.connection == null) return;

        BlockPos pos = player.blockPosition();
        player.connection.sendCommand("pc x: " + pos.getX()
                + ", y: " + pos.getY()
                + ", z: " + pos.getZ()
                + " | " + mob.label() + " spawned!");
        lastSharedMillis.put(mob, now);
    }

    private static DianaRareMob dugRareMob(String text) {
        if (text == null || text.isBlank()) return null;

        String lower = text.toLowerCase(Locale.ROOT).trim();
        boolean ownDugMessage = lower.startsWith("you dug out")
                || lower.startsWith("oh! you dug out")
                || lower.startsWith("oi! you dug out");
        if (!ownDugMessage) return null;

        for (DianaRareMob mob : DianaRareMob.values()) {
            if (mob.matches(lower)) return mob;
        }
        return null;
    }

    private static String tempLabel(DianaRareMobShareParser.Share share) {
        return ChatFormatting.LIGHT_PURPLE + share.mobName()
                + ChatFormatting.YELLOW + RARE_MOB_LABEL_MARKER + share.playerName();
    }

    public static boolean isRareMobWaypoint(Waypoint waypoint) {
        if (waypoint == null) return false;
        String name = waypoint.name();
        // Diana chat labels use LIGHT_PURPLE for the mob + YELLOW before " from ".
        // Require those codes so plain user text like "Start from North" does not match.
        return name.contains(String.valueOf(ChatFormatting.LIGHT_PURPLE))
                && name.contains(String.valueOf(ChatFormatting.YELLOW) + RARE_MOB_LABEL_MARKER);
    }
}
