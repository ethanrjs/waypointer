package com.babbur.waypointer.screen;

import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.codec.WaypointExportCodec;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.WaypointGroup;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

final class ExportPolicy {

    private static final int CHAT_INPUT_LIMIT = 256;
    private static final int COMMAND_WIRE_LIMIT_BYTES = 256;
    private static final int REFERENCE_COMMAND_PREFIX_BYTES = "pc ".length();

    private ExportPolicy() {}

    static String codeBlockPayload(String payload) {
        return "```\n" + (payload == null ? "" : payload) + "\n```";
    }

    static FitSummary fitSummary(String payload) {
        String safePayload = payload == null ? "" : payload;
        int characters = safePayload.length();
        int wireBytes = safePayload.getBytes(StandardCharsets.UTF_8).length;
        int commandBytes = REFERENCE_COMMAND_PREFIX_BYTES + wireBytes;
        boolean chatOk = characters <= CHAT_INPUT_LIMIT;
        boolean commandOk = commandBytes <= COMMAND_WIRE_LIMIT_BYTES;
        String messageKey = commandOk
                ? "waypointer.export.fit.chat_and_commands"
                : chatOk ? "waypointer.export.fit.chat_only" : "waypointer.export.fit.too_long";
        return new FitSummary(characters, wireBytes, commandBytes, chatOk, commandOk, messageKey);
    }

    static WaypointCodec.Options.Builder optionsFromConfig(
            WaypointerConfig config, List<WaypointGroup> selectedGroups) {
        boolean includeWaypointFlags = config.exportIncludeWaypointFlags()
                || containsSubwaypoints(selectedGroups);
        boolean allOff = !config.exportIncludeNames()
                && !config.exportIncludeColors()
                && !config.exportIncludeRadii()
                && !includeWaypointFlags
                && !config.exportIncludeGroupMeta()
                && !config.exportIncludeZone()
                && selectedGroups != null
                && !selectedGroups.isEmpty()
                && selectedGroups.stream().allMatch(group -> group != null
                        && group.routeKind() == WaypointGroup.RouteKind.REGULAR);
        WaypointCodec.Options.Builder builder = allOff
                ? WaypointCodec.Options.BARE_COORDINATES.toBuilder()
                : WaypointCodec.Options.builder();
        return builder
                .includeNames(config.exportIncludeNames())
                .includeColors(config.exportIncludeColors())
                .includeRadii(config.exportIncludeRadii())
                .includeWaypointFlags(includeWaypointFlags)
                .includeGroupMeta(config.exportIncludeGroupMeta())
                .includeZone(config.exportIncludeZone());
    }

    static boolean showSubwaypointWarning(
            WaypointExportCodec.Target target, List<WaypointGroup> selectedGroups) {
        return target != WaypointExportCodec.Target.WAYPOINTER
                && containsSubwaypoints(selectedGroups);
    }

    static boolean containsSubwaypoints(List<WaypointGroup> groups) {
        if (groups == null) return false;
        for (WaypointGroup group : groups) {
            if (group.hasSubwaypoints()) return true;
        }
        return false;
    }

    static String labelTooltipText(WaypointExportCodec.Target target) {
        return target.supportsLabel()
                ? Component.translatableWithFallback(
                        "waypointer.screen.export.label_tooltip.supported",
                        "Optional title shown by Waypointer imports").getString()
                : Component.translatableWithFallback(
                        "waypointer.screen.export.label_tooltip.unsupported",
                        "%1$s exports do not support Waypointer labels",
                        target.displayName()).getString();
    }

    static String previewRouteName(
            String sourceName, String label, WaypointExportCodec.Target target, int selectedRouteCount) {
        String sanitized = WaypointCodec.Options.sanitizeLabel(label);
        return target.supportsLabel() && selectedRouteCount == 1 && !sanitized.isBlank()
                ? sanitized : sourceName;
    }

    static String previewOverflowText(int hiddenLines) {
        int safeHidden = Math.max(0, hiddenLines);
        return safeHidden == 1
                ? Component.translatableWithFallback(
                        "waypointer.screen.export.preview.more.one",
                        "...%1$s more line", safeHidden).getString()
                : Component.translatableWithFallback(
                        "waypointer.screen.export.preview.more.many",
                        "...%1$s more lines", safeHidden).getString();
    }

    record FitSummary(int characters, int wireBytes, int commandBytes,
                      boolean chatOk, boolean commandOk, String messageKey) {}
}
