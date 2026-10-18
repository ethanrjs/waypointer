package com.babbur.waypointer.location;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TabListTexts {

    private static final Pattern AREA = Pattern.compile("^\\s*Area:\\s*(.+?)\\s*$");
    private static final Pattern FORMATTING = Pattern.compile("§.");

    private TabListTexts() {}

    public static String areaName(Minecraft mc) {
        ClientPacketListener connection = mc == null ? null : mc.getConnection();
        if (connection == null) return null;
        for (PlayerInfo info : connection.getListedOnlinePlayers()) {
            Component display = info.getTabListDisplayName();
            if (display == null) continue;
            String area = areaName(display.getString());
            if (area != null) return area;
        }
        return null;
    }

    static String areaName(String line) {
        if (line == null || line.indexOf("Area:") < 0) return null;
        Matcher matcher = AREA.matcher(FORMATTING.matcher(line).replaceAll(""));
        if (!matcher.matches()) return null;
        String area = matcher.group(1).trim();
        return area.isEmpty() ? null : area;
    }
}
