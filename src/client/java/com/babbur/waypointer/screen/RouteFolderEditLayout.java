package com.babbur.waypointer.screen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.babbur.waypointer.screen.GuiTokens.BTN_H;
import static com.babbur.waypointer.screen.GuiTokens.GAP;
import static com.babbur.waypointer.screen.GuiTokens.GAP_TIGHT;
import static com.babbur.waypointer.screen.GuiTokens.PAD_OUTER;

final class RouteFolderEditLayout {
    private static final int PANEL_WIDTH = 360;
    private static final int PANEL_HEIGHT = 180;
    private static final int CANCEL_WIDTH = 60;
    private static final int SAVE_WIDTH = 64;
    private static final int DELETE_WIDTH = 64;

    private RouteFolderEditLayout() {
    }

    static Layout calculate(
            int screenWidth, int screenHeight,
            boolean existingFolder, boolean hasSelection) {
        int panelWidth = Math.min(PANEL_WIDTH, Math.max(1, screenWidth - GAP * 2));
        boolean detailVisible = existingFolder || hasSelection;
        int detailSpace = detailVisible ? 0 : 17;
        int panelHeight = Math.min(PANEL_HEIGHT - detailSpace, Math.max(1, screenHeight - GAP * 2));
        int panelX = Math.max(0, (screenWidth - panelWidth) / 2);
        int panelY = Math.max(0, (screenHeight - panelHeight) / 2);
        int horizontalPadding = panelWidth < 300 ? GAP : PAD_OUTER;
        int verticalPadding = panelHeight < 160 ? GAP : PAD_OUTER;
        int contentX = panelX + horizontalPadding;
        int contentWidth = Math.max(1, panelWidth - horizontalPadding * 2);

        int footerY = panelY + panelHeight - verticalPadding - BTN_H;

        int oneRowWidth = CANCEL_WIDTH + SAVE_WIDTH + GAP;
        if (existingFolder) oneRowWidth += DELETE_WIDTH + GAP;
        boolean wrappedActions = existingFolder && oneRowWidth > contentWidth;

        List<ActionPlacement> actions = new ArrayList<>(4);
        if (wrappedActions) {
            addPair(actions, contentX, contentWidth, footerY,
                    Action.CANCEL, CANCEL_WIDTH, Action.SAVE, SAVE_WIDTH);
            int secondaryY = footerY - GAP - BTN_H;
            int deleteWidth = Math.min(DELETE_WIDTH, contentWidth);
            actions.add(new ActionPlacement(
                    Action.DELETE, contentX + contentWidth - deleteWidth,
                    secondaryY, deleteWidth));
        } else if (existingFolder) {
            int cursor = contentX + contentWidth;
            cursor -= DELETE_WIDTH;
            actions.add(new ActionPlacement(Action.DELETE, cursor, footerY, DELETE_WIDTH));
            cursor -= GAP + SAVE_WIDTH;
            actions.add(new ActionPlacement(Action.SAVE, cursor, footerY, SAVE_WIDTH));
            actions.add(new ActionPlacement(
                    Action.CANCEL, contentX, footerY, CANCEL_WIDTH));
        } else {
            addPair(actions, contentX, contentWidth, footerY,
                    Action.CANCEL, CANCEL_WIDTH, Action.SAVE, SAVE_WIDTH);
        }
        actions.sort(Comparator.comparingInt(ActionPlacement::y)
                .thenComparingInt(ActionPlacement::x));

        int firstActionY = actions.stream()
                .mapToInt(ActionPlacement::y)
                .min()
                .orElse(footerY);
        int titleY = panelY + verticalPadding;
        int detailY = titleY + 14;
        int sectionDividerY = detailY + 13 - detailSpace;
        int nameLabelY = sectionDividerY + GAP;
        int nameFieldY = nameLabelY + 11;
        int colorLabelY = nameFieldY + BTN_H + GAP;
        int colorControlY = colorLabelY + 11;
        boolean fieldLabelsVisible = true;
        if (colorControlY + BTN_H + 2 > firstActionY) {
            detailVisible = false;
            fieldLabelsVisible = false;
            sectionDividerY = titleY + 10;
            nameLabelY = titleY + 10;
            nameFieldY = titleY + 12;
            colorLabelY = nameFieldY + BTN_H + GAP_TIGHT;
            colorControlY = colorLabelY;
        }
        int validationY = colorControlY + BTN_H + GAP_TIGHT;
        boolean validationVisible = validationY + 8 + GAP <= firstActionY;

        int previewSize = BTN_H;
        int previewX = contentX;
        int resetWidth = Math.max(1, Math.min(64, contentWidth / 4));
        int resetX = contentX + contentWidth - resetWidth;
        int colorFieldX = previewX + previewSize + GAP_TIGHT;
        int colorFieldWidth = Math.max(1, resetX - GAP_TIGHT - colorFieldX);
        return new Layout(
                panelX, panelY, panelWidth, panelHeight,
                contentX, contentWidth,
                titleY, detailY, sectionDividerY, detailVisible,
                nameLabelY, nameFieldY,
                colorLabelY, colorControlY, fieldLabelsVisible,
                validationY, validationVisible,
                previewX, previewSize, colorFieldX, colorFieldWidth,
                resetX, resetWidth,
                footerY, wrappedActions, List.copyOf(actions));
    }

    private static void addPair(
            List<ActionPlacement> actions, int contentX, int contentWidth, int y,
            Action leftAction, int preferredLeftWidth,
            Action rightAction, int preferredRightWidth) {
        int gap = Math.min(GAP, Math.max(0, contentWidth - 2));
        int available = Math.max(2, contentWidth - gap);
        int leftWidth = preferredLeftWidth;
        int rightWidth = preferredRightWidth;
        if (leftWidth + rightWidth > available) {
            leftWidth = available / 2;
            rightWidth = available - leftWidth;
        }
        actions.add(new ActionPlacement(leftAction, contentX, y, leftWidth));
        actions.add(new ActionPlacement(
                rightAction, contentX + contentWidth - rightWidth, y, rightWidth));
    }

    enum Action {
        CANCEL,
        SAVE,
        DELETE
    }

    record ActionPlacement(Action action, int x, int y, int width) {
        int right() {
            return x + width;
        }
    }

    record Layout(
            int panelX, int panelY, int panelWidth, int panelHeight,
            int contentX, int contentWidth,
            int titleY, int detailY, int sectionDividerY, boolean detailVisible,
            int nameLabelY, int nameFieldY,
            int colorLabelY, int colorControlY, boolean fieldLabelsVisible,
            int validationY, boolean validationVisible,
            int previewX, int previewSize,
            int colorFieldX, int colorFieldWidth,
            int resetX, int resetWidth,
            int footerY, boolean wrappedActions,
            List<ActionPlacement> actions) {

        int panelBottom() {
            return panelY + panelHeight;
        }

        int contentRight() {
            return contentX + contentWidth;
        }

        ActionPlacement action(Action action) {
            return actions.stream()
                    .filter(placement -> placement.action() == action)
                    .findFirst()
                    .orElse(null);
        }
    }
}
