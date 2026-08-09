package com.babbur.waypointer.screen;

import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.codec.WaypointExportCodec;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.WaypointGroup;

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
        return WaypointCodec.Options.builder()
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
                ? "Optional title shown by Waypointer imports"
                : target.displayName() + " exports do not support Waypointer labels";
    }

    static String previewRouteName(
            String sourceName, String label, WaypointExportCodec.Target target, int selectedRouteCount) {
        String sanitized = WaypointCodec.Options.sanitizeLabel(label);
        return target.supportsLabel() && selectedRouteCount == 1 && !sanitized.isBlank()
                ? sanitized : sourceName;
    }

    static String previewOverflowText(int hiddenLines) {
        int safeHidden = Math.max(0, hiddenLines);
        return "..." + safeHidden + " more line" + (safeHidden == 1 ? "" : "s");
    }

    record FitSummary(int characters, int wireBytes, int commandBytes,
                      boolean chatOk, boolean commandOk, String messageKey) {}
}
