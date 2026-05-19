package dev.ethan.waypointer.screen;

import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.diana.DianaRareMob;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

import static dev.ethan.waypointer.screen.GuiTokens.*;

public final class DianaRareMobShareSettingsScreen extends Screen {

    private final Screen parent;
    private final WaypointerConfig config;

    public DianaRareMobShareSettingsScreen(Screen parent, WaypointerConfig config) {
        super(Component.literal("Diana Mob Sharing"));
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

        DianaRareMob[] mobs = DianaRareMob.values();
        for (int i = 0; i < mobs.length; i++) {
            DianaRareMob mob = mobs[i];
            int col = i < 5 ? col1 : col2;
            int y = top + (i % 5) * rowH;
            addMobCheckbox(col, y, mob);
        }
    }

    private void addMobCheckbox(int x, int y, DianaRareMob mob) {
        Checkbox checkbox = Checkbox.builder(Component.literal(mob.label()), font)
                .pos(x, y)
                .selected(config.dianaRareMobShareEnabled(mob))
                .onValueChange((b, enabled) -> config.setDianaRareMobShareEnabled(mob, enabled))
                .build();
        checkbox.setTooltip(Tooltip.create(Component.literal(
                "Share " + mob.label() + " coordinates in party chat when you dig one up.")));
        addRenderableWidget(checkbox);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        g.drawString(font, getTitle(), PAD_OUTER, PAD_OUTER, TEXT, false);
        String enabled = config.dianaRareMobShareEnabledCount() + "/" + DianaRareMob.values().length + " selected";
        g.drawString(font, enabled, width - PAD_OUTER - font.width(enabled), PAD_OUTER, TEXT_DIM, false);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
