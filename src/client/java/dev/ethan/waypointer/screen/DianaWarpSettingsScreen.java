package dev.ethan.waypointer.screen;

import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.diana.DianaWarp;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

import static dev.ethan.waypointer.screen.GuiTokens.*;

public final class DianaWarpSettingsScreen extends Screen {

    private final Screen parent;
    private final WaypointerConfig config;

    public DianaWarpSettingsScreen(Screen parent, WaypointerConfig config) {
        super(Component.literal("Diana Warps"));
        this.parent = parent;
        this.config = config;
    }

    @Override
    protected void init() {
        int footerY = height - FOOTER_H;
        List<GuiTokens.ButtonSpec> left = new ArrayList<>();
        GuiTokens.ButtonSpec done = new GuiTokens.ButtonSpec("Done", -1, this::onClose,
                Tooltip.create(Component.literal("Return to Diana settings.")));
        GuiTokens.layoutFooter(width, footerY, left, done, this::addRenderableWidget, font);

        int top = PAD_OUTER + font.lineHeight + GAP_SECTION;
        int colGap = GAP_SECTION;
        int colW = (width - PAD_OUTER * 2 - colGap) / 2;
        int col1 = PAD_OUTER;
        int col2 = col1 + colW + colGap;
        int rowH = 24;

        DianaWarp[] warps = DianaWarp.values();
        for (int i = 0; i < warps.length; i++) {
            DianaWarp warp = warps[i];
            int col = i < 4 ? col1 : col2;
            int y = top + (i % 4) * rowH;
            addWarpCheckbox(col, y, warp);
        }
    }

    private void addWarpCheckbox(int x, int y, DianaWarp warp) {
        Checkbox checkbox = Checkbox.builder(Component.literal(warp.label()), font)
                .pos(x, y)
                .selected(config.dianaWarpEnabled(warp))
                .onValueChange((b, enabled) -> config.setDianaWarpEnabled(warp, enabled))
                .build();
        checkbox.setTooltip(Tooltip.create(Component.literal(
                "/" + warp.command() + "  ->  "
                        + warp.x() + ", " + warp.y() + ", " + warp.z())));
        addRenderableWidget(checkbox);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        g.drawString(font, getTitle(), PAD_OUTER, PAD_OUTER, TEXT, false);
        String enabled = config.dianaEnabledWarpCount() + "/" + DianaWarp.values().length + " enabled";
        g.drawString(font, enabled, width - PAD_OUTER - font.width(enabled), PAD_OUTER, TEXT_DIM, false);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
