package com.babbur.waypointer.screen;

import static com.babbur.waypointer.screen.GuiTokens.BTN_H;
import static com.babbur.waypointer.screen.GuiTokens.GAP;
import static com.babbur.waypointer.screen.GuiTokens.GAP_SECTION;

final class PublisherNameLayout {
    private static final int PANEL_WIDTH = 336;
    private static final int PANEL_HEIGHT = 176;
    private static final int PADDING = 16;
    private static final int ACTION_WIDTH = 104;
    private static final int CONFIRM_CARD_HEIGHT = 28;

    private PublisherNameLayout() {
    }

    static Layout calculate(
            int screenWidth, int screenHeight, int preferredPrimaryWidth) {
        int panelWidth = Math.min(PANEL_WIDTH, Math.max(1, screenWidth - GAP * 2));
        int panelHeight = Math.min(PANEL_HEIGHT, Math.max(1, screenHeight - GAP * 2));
        int panelX = Math.max(0, (screenWidth - panelWidth) / 2);
        int panelY = Math.max(0, (screenHeight - panelHeight) / 2);
        int horizontalPadding = Math.min(
                panelWidth >= 240 ? PADDING : GAP,
                Math.max(0, (panelWidth - 1) / 2));
        int verticalPadding = Math.min(
                panelHeight >= 140 ? PADDING : GAP,
                Math.max(0, (panelHeight - 1) / 2));
        int contentX = panelX + horizontalPadding;
        int contentWidth = Math.max(1, panelWidth - horizontalPadding * 2);
        int titleY = panelY + verticalPadding;
        int footerY = Math.max(panelY,
                panelY + panelHeight - verticalPadding - BTN_H);

        int actionGap = Math.min(GAP_SECTION, Math.max(0, contentWidth - 2));
        int actionSpace = Math.max(2, contentWidth - actionGap);
        int secondaryWidth = Math.min(ACTION_WIDTH, actionSpace / 2);
        int primaryWidth = Math.min(
                Math.max(1, preferredPrimaryWidth), actionSpace - secondaryWidth);
        int primaryX = contentX + contentWidth - primaryWidth;

        int preferredFieldY = panelY + 72;
        int fieldY = Math.min(preferredFieldY, footerY - BTN_H - GAP);
        fieldY = Math.max(panelY, fieldY);
        int fieldLabelY = Math.max(titleY + 10, fieldY - 9);
        boolean entryDetailsVisible = fieldY >= preferredFieldY;

        int cardY = Math.max(titleY + 12, Math.min(panelY + 58,
                footerY - CONFIRM_CARD_HEIGHT - GAP));
        int questionY = Math.max(titleY + 10, cardY - 20);
        boolean questionVisible = questionY + 10 + GAP <= cardY;
        int warningY = cardY + CONFIRM_CARD_HEIGHT + 12;
        boolean warningVisible = warningY + 22 + GAP <= footerY;

        return new Layout(
                panelX, panelY, panelWidth, panelHeight,
                contentX, contentWidth, titleY, footerY,
                contentX, secondaryWidth, primaryX, primaryWidth,
                fieldLabelY, fieldY, entryDetailsVisible,
                questionY, questionVisible, cardY, warningY, warningVisible);
    }

    record Layout(
            int panelX, int panelY, int panelWidth, int panelHeight,
            int contentX, int contentWidth, int titleY, int footerY,
            int secondaryX, int secondaryWidth,
            int primaryX, int primaryWidth,
            int fieldLabelY, int fieldY, boolean entryDetailsVisible,
            int questionY, boolean questionVisible,
            int cardY, int warningY, boolean warningVisible) {

        int panelBottom() {
            return panelY + panelHeight;
        }

        int contentRight() {
            return contentX + contentWidth;
        }
    }
}
