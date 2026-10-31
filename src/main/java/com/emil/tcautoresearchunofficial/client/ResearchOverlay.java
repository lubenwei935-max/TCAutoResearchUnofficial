package com.emil.tcautoresearchunofficial.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;

import com.emil.tcautoresearchunofficial.Config;
import com.emil.tcautoresearchunofficial.client.widget.AspectIcon;
import com.emil.tcautoresearchunofficial.client.widget.Theme;
import com.emil.tcautoresearchunofficial.client.widget.ThemedButton;
import com.emil.tcautoresearchunofficial.research.PlanEvaluator;
import com.emil.tcautoresearchunofficial.research.PlanRunner;
import com.emil.tcautoresearchunofficial.research.ResearchAssistant;
import com.emil.tcautoresearchunofficial.util.GuiGeometry;
import com.emil.tcautoresearchunofficial.util.Txt;

import thaumcraft.api.aspects.Aspect;
import thaumcraft.client.gui.GuiResearchTable;
import thaumcraft.common.lib.utils.HexUtils;

/**
 * 研究台侧边工具条 + 面板。
 *
 * <p>所有控件都是 {@code buttonList} 里的普通 {@link GuiButton}（由 {@code InitGuiEvent.Post}
 * 拿到的那个列表），点击、悬停、绘制都走原版逻辑：不需要 Mixin，
 * 也不会在研究台绘制的过程中切换界面 —— 老版本 NEA 空指针崩溃正是那样产生的。</p>
 */
public final class ResearchOverlay {

    private enum Panel {
        NONE, ASPECTS, SETTINGS, PREVIEW
    }

    private static final int ID_AUTO = 100;
    private static final int ID_SOLVE = 101;
    private static final int ID_REPLAY = 102;
    private static final int ID_ALL = 103;
    private static final int ID_ASPECTS = 104;
    private static final int ID_SETTINGS = 105;

    private static final int ID_MINUS = 121;
    private static final int ID_PLUS = 122;
    private static final int ID_COMBINE = 123;
    private static final int ID_AUTO_CHIP = 124;
    private static final int ID_NOTIFY_CHIP = 125;
    private static final int ID_INTERVAL_DOWN = 126;
    private static final int ID_INTERVAL_UP = 127;
    private static final int ID_CLOSE = 128;
    private static final int ID_ACCEPT = 130;
    private static final int ID_CANCEL_PLAN = 131;
    private static final int ID_PREFER_CHIP = 132;
    private static final int ID_PREVIEW_CHIP = 133;

    private static final int CELL_ID = 1000;

    private static final int TAB = 20;
    private static final int STEP = 22;
    private static final int COLS = 8;
    /** 隐藏控件停放位置：必须远离任何 NEI 物品格，见 {@link #park}。 */
    private static final int OFFSCREEN = -10000;

    private final GuiResearchTable gui;
    private final List<GuiButton> buttonList;
    private final ResearchAssistant assistant = ResearchAssistant.get();
    private final List<ThemedButton> widgets = new ArrayList<ThemedButton>();
    private final List<ThemedButton> cells = new ArrayList<ThemedButton>();

    private ThemedButton btnAuto;
    private ThemedButton btnSolve;
    private ThemedButton btnReplay;
    private ThemedButton btnAll;
    private ThemedButton btnAspects;
    private ThemedButton btnSettings;

    private ThemedButton btnMinus;
    private ThemedButton btnPlus;
    private ThemedButton btnCombine;
    private ThemedButton btnAutoChip;
    private ThemedButton btnNotifyChip;
    private ThemedButton btnIntervalDown;
    private ThemedButton btnIntervalUp;
    private ThemedButton btnClose;
    private ThemedButton btnAccept;
    private ThemedButton btnCancelPlan;
    private ThemedButton btnPreferChip;
    private ThemedButton btnPreviewChip;

    private Panel panel = Panel.NONE;
    private Aspect selected;
    private int target = 1;

    private GuiGeometry geometry;
    private int barX;
    private int barY;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int compoundCount;

    public ResearchOverlay(GuiResearchTable gui, List<GuiButton> buttonList) {
        this.gui = gui;
        this.buttonList = buttonList;
        build();
    }

    public GuiResearchTable gui() {
        return gui;
    }

    /** 界面重置时把我们的按钮从列表里摘掉，避免重复的点击区域。 */
    public void dispose(List<GuiButton> list) {
        if (list != null) {
            list.removeAll(widgets);
        }
    }

    // ------------------------------------------------------------------ 构建

    private void build() {
        btnAuto = tab(ID_AUTO, "tcautoresearchunofficial.tab.auto", "tcautoresearchunofficial.tip.auto", new Action() {
            @Override
            public void run() {
                assistant.toggleAuto();
            }
        });
        btnSolve = tab(ID_SOLVE, "tcautoresearchunofficial.tab.solve", "tcautoresearchunofficial.tip.solve", new Action() {
            @Override
            public void run() {
                if (assistant.runner() != null) {
                    assistant.stop();
                } else {
                    assistant.solveCurrent();
                }
            }
        });
        btnReplay = tab(ID_REPLAY, "tcautoresearchunofficial.tab.replay", "tcautoresearchunofficial.tip.replay", new Action() {
            @Override
            public void run() {
                assistant.replayLastSolution();
            }
        });
        btnAll = tab(ID_ALL, "tcautoresearchunofficial.tab.all", "tcautoresearchunofficial.tip.all", new Action() {
            @Override
            public void run() {
                assistant.unlockAllAspects();
            }
        });
        btnAspects = tab(ID_ASPECTS, "tcautoresearchunofficial.tab.aspects", "tcautoresearchunofficial.tip.aspects", new Action() {
            @Override
            public void run() {
                setPanel(panel == Panel.ASPECTS ? Panel.NONE : Panel.ASPECTS);
            }
        });
        btnSettings = tab(ID_SETTINGS, "tcautoresearchunofficial.tab.settings", "tcautoresearchunofficial.tip.settings", new Action() {
            @Override
            public void run() {
                setPanel(panel == Panel.SETTINGS ? Panel.NONE : Panel.SETTINGS);
            }
        });

        btnMinus = chip(ID_MINUS, "-", 20, new Action() {
            @Override
            public void run() {
                target = Math.max(1, target - 1);
            }
        });
        btnPlus = chip(ID_PLUS, "+", 20, new Action() {
            @Override
            public void run() {
                target = Math.min(64, target + 1);
            }
        });
        btnCombine = chip(ID_COMBINE, "tcautoresearchunofficial.panel.combine", 46, new Action() {
            @Override
            public void run() {
                // 合成后面板保持打开：只有玩家再点一次「合」才会收起
                if (selected != null) {
                    assistant.combineAspect(selected, target);
                }
            }
        });
        btnAutoChip = chip(ID_AUTO_CHIP, "tcautoresearchunofficial.setting.auto", 150, new Action() {
            @Override
            public void run() {
                assistant.toggleAuto();
            }
        });
        btnNotifyChip = chip(ID_NOTIFY_CHIP, "tcautoresearchunofficial.setting.notify", 150, new Action() {
            @Override
            public void run() {
                Config.setNotify(!Config.notifyOnFinish);
            }
        });
        btnIntervalDown = chip(ID_INTERVAL_DOWN, "-", 20, new Action() {
            @Override
            public void run() {
                Config.setInterval(Config.actionIntervalTicks - 1);
            }
        });
        btnIntervalUp = chip(ID_INTERVAL_UP, "+", 20, new Action() {
            @Override
            public void run() {
                Config.setInterval(Config.actionIntervalTicks + 1);
            }
        });
        btnClose = chip(ID_CLOSE, "gui.done", 60, new Action() {
            @Override
            public void run() {
                setPanel(Panel.NONE);
            }
        });
        btnAccept = chip(ID_ACCEPT, "tcautoresearchunofficial.preview.accept", 60, new Action() {
            @Override
            public void run() {
                assistant.confirmPreview();
            }
        });
        btnCancelPlan = chip(ID_CANCEL_PLAN, "tcautoresearchunofficial.preview.cancel", 60, new Action() {
            @Override
            public void run() {
                assistant.cancelPreview();
            }
        });
        btnPreferChip = chip(ID_PREFER_CHIP, "tcautoresearchunofficial.setting.prefer", 150, new Action() {
            @Override
            public void run() {
                Config.setPreferExisting(!Config.preferExisting);
            }
        });
        btnPreviewChip = chip(ID_PREVIEW_CHIP, "tcautoresearchunofficial.setting.preview", 150, new Action() {
            @Override
            public void run() {
                Config.setPreviewPlan(!Config.previewPlan);
            }
        });

        int index = 0;
        for (Aspect aspect : Aspect.aspects.values()) {
            if (aspect.getComponents() == null) {
                continue;
            }
            compoundCount++;
            final Aspect value = aspect;
            ThemedButton cell = new ThemedButton(CELL_ID + index++, 0, 0, TAB, TAB, "", ThemedButton.Style.CELL);
            cell.aspect(value).onAction(new ThemedButton.Action() {
                @Override
                public void run(ThemedButton button) {
                    selected = value;
                }
            });
            cells.add(cell);
            widgets.add(cell);
        }

        for (ThemedButton widget : widgets) {
            buttonList.add(widget);
        }
        // 先摆一次位置，避免首帧所有控件都叠在 (0,0)
        layout();
    }

    private ThemedButton tab(int id, String labelKey, String tipKey, final Action action) {
        ThemedButton button = new ThemedButton(id, 0, 0, TAB, TAB, Txt.tr(labelKey), ThemedButton.Style.TAB);
        button.tooltip(Txt.tr(tipKey)).onAction(new ThemedButton.Action() {
            @Override
            public void run(ThemedButton ignored) {
                action.run();
            }
        });
        widgets.add(button);
        return button;
    }

    private ThemedButton chip(int id, String label, int width, final Action action) {
        String text = label.indexOf('.') > 0 ? Txt.tr(label) : label;
        ThemedButton button = new ThemedButton(id, 0, 0, width, 18, text, ThemedButton.Style.CHIP);
        button.onAction(new ThemedButton.Action() {
            @Override
            public void run(ThemedButton ignored) {
                action.run();
            }
        }).visible = false;
        widgets.add(button);
        return button;
    }

    private interface Action {
        void run();
    }

    // ------------------------------------------------------------------ 布局

    private void layout() {
        geometry = GuiGeometry.of(gui);
        int screenW = gui.width;
        int screenH = gui.height;

        boolean right = !"LEFT".equalsIgnoreCase(Config.toolbarSide);
        barX = right ? geometry.left + geometry.width + 6 : geometry.left - TAB - 6;
        if (right && barX + TAB > screenW - 2) {
            barX = geometry.left - TAB - 6;
        } else if (!right && barX < 2) {
            barX = geometry.left + geometry.width + 6;
        }
        barX = clamp(barX, 2, Math.max(2, screenW - TAB - 2));
        barY = clamp(geometry.top + 2, 2, Math.max(2, screenH - STEP * 6 - 2));

        int y = barY;
        ThemedButton[] tabs = new ThemedButton[] { btnAuto, btnSolve, btnReplay, btnAll, btnAspects, btnSettings };
        for (ThemedButton tab : tabs) {
            tab.xPosition = barX;
            tab.yPosition = y;
            tab.visible = true;
            y += STEP;
        }

        btnAuto.highlighted(Config.autoSolve);
        btnSolve.displayString = assistant.runner() != null ? Txt.tr("tcautoresearchunofficial.tab.stop")
                : Txt.tr("tcautoresearchunofficial.tab.solve");
        btnAspects.highlighted(panel == Panel.ASPECTS);
        btnSettings.highlighted(panel == Panel.SETTINGS);

        int rows = Math.max(1, (compoundCount + COLS - 1) / COLS);
        if (panel == Panel.ASPECTS) {
            panelW = COLS * STEP + 12;
            panelH = 24 + rows * STEP + 32;
        } else if (panel == Panel.SETTINGS) {
            panelW = 168;
            panelH = 24 + 6 * STEP + 8;
        } else if (panel == Panel.PREVIEW) {
            int iconRows = Math.max(1, (previewEntries() + 7) / 8);
            panelW = 8 * 20 + 12;
            panelH = 36 + iconRows * 20 + 14 + 24;
        } else {
            panelW = 0;
            panelH = 0;
        }

        if (panel != Panel.NONE) {
            panelX = barX + TAB + 4;
            if (panelX + panelW > screenW - 2) {
                panelX = barX - panelW - 4;
            }
            panelX = clamp(panelX, 2, Math.max(2, screenW - panelW - 2));
            panelY = clamp(barY, 2, Math.max(2, screenH - panelH - 2));
        }

        layoutAspects();
        layoutSettings();
        layoutPreview();
    }

    private int previewEntries() {
        PlanEvaluator.Result evaluation = assistant.previewEvaluation();
        if (evaluation == null) {
            return 0;
        }
        return evaluation.feasible ? evaluation.required.size() : evaluation.missingPrimal.size();
    }

    private void layoutPreview() {
        boolean open = panel == Panel.PREVIEW;
        PlanEvaluator.Result evaluation = assistant.previewEvaluation();
        int buttonY = panelY + panelH - 24;
        place(btnCancelPlan, panelX + panelW - 6 - 60, buttonY, open);
        place(btnAccept, panelX + panelW - 6 - 60 - 4 - 60, buttonY, open);
        if (open) {
            btnAccept.displayString = Txt.tr("tcautoresearchunofficial.preview.accept");
            btnCancelPlan.displayString = Txt.tr("tcautoresearchunofficial.preview.cancel");
            btnAccept.enabled = evaluation != null && evaluation.feasible;
        }
    }

    private void layoutAspects() {
        boolean open = panel == Panel.ASPECTS;
        int index = 0;
        for (ThemedButton cell : cells) {
            cell.visible = open;
            if (!open) {
                park(cell);
                continue;
            }
            int have = assistant.have(cell.aspectValue());
            cell.xPosition = panelX + 6 + (index % COLS) * STEP;
            cell.yPosition = panelY + 24 + (index / COLS) * STEP;
            cell.badge(have).highlighted(cell.aspectValue() == selected);
            String desc = Txt.aspectDesc(cell.aspectValue());
            String name = Txt.aspectName(cell.aspectValue());
            cell.tooltip(Txt.tr("tcautoresearchunofficial.tip.cell", name, have) + (desc.isEmpty() ? "" : "\n" + desc));
            index++;
        }

        int footerY = panelY + panelH - 24;
        place(btnMinus, panelX + 6, footerY, open);
        // 中间要放「合成 N」的文字，所以 + 号往右挪
        place(btnPlus, panelX + 6 + STEP + 72, footerY, open);
        place(btnCombine, panelX + panelW - 6 - 46, footerY, open);

        if (open) {
            btnCombine.displayString = Txt.tr("tcautoresearchunofficial.panel.combine");
        }
    }

    private void layoutSettings() {
        boolean open = panel == Panel.SETTINGS;
        int rowY = panelY + 24;
        place(btnAutoChip, panelX + 8, rowY, open);
        place(btnNotifyChip, panelX + 8, rowY + STEP, open);
        place(btnPreferChip, panelX + 8, rowY + STEP * 2, open);
        place(btnPreviewChip, panelX + 8, rowY + STEP * 3, open);
        place(btnIntervalDown, panelX + panelW - 8 - 20 - 22 - 20, rowY + STEP * 4 + 1, open);
        place(btnIntervalUp, panelX + panelW - 8 - 20, rowY + STEP * 4 + 1, open);
        place(btnClose, panelX + 8, rowY + STEP * 5, open);

        if (open) {
            btnAutoChip.displayString = Txt.tr("tcautoresearchunofficial.setting.auto", onOff(Config.autoSolve));
            btnNotifyChip.displayString = Txt.tr("tcautoresearchunofficial.setting.notify", onOff(Config.notifyOnFinish));
            btnPreferChip.displayString = Txt.tr("tcautoresearchunofficial.setting.prefer", onOff(Config.preferExisting));
            btnPreviewChip.displayString = Txt.tr("tcautoresearchunofficial.setting.preview", onOff(Config.previewPlan));
            btnClose.displayString = Txt.tr("gui.done");
        }
    }

    private String onOff(boolean value) {
        return Txt.tr(value ? "tcautoresearchunofficial.on" : "tcautoresearchunofficial.off");
    }

    private void place(ThemedButton button, int x, int y, boolean visible) {
        button.visible = visible;
        if (visible) {
            button.xPosition = x;
            button.yPosition = y;
        } else {
            park(button);
        }
    }

    /**
     * 把隐藏的控件挪到屏幕外。
     *
     * <p>NEI 的 {@code GuiInfo.hideItemPanelSlot} 只按坐标判断按钮是否压住物品格，
     * <b>不看 visible</b>。隐藏控件如果留在原位，NEI 会一直把下面的物品格判为“被遮挡”
     * 而永久隐藏 —— 这就是面板收起后右侧物品布局回不来的原因。</p>
     */
    private void park(ThemedButton button) {
        button.xPosition = OFFSCREEN;
        button.yPosition = OFFSCREEN;
    }

    /** 切换面板；开合后请求 NEI 重算一次物品面板布局。 */
    private void setPanel(Panel target) {
        if (panel == target) {
            return;
        }
        panel = target;
        NeiBridge.refreshItemPanel(gui);
    }

    // ------------------------------------------------------------------ 绘制

    /** 工具条底槽。 */
    private void drawToolbarStrip() {
        int bottom = barY + STEP * 6;
        Gui.drawRect(barX - 2, barY - 2, barX + TAB + 2, bottom, Theme.PANEL_BG);
        Gui.drawRect(barX - 2, barY - 2, barX + TAB + 2, barY - 1, Theme.GOLD_DIM);
        Gui.drawRect(barX - 2, bottom - 1, barX + TAB + 2, bottom, Theme.BORDER);
    }

    /** 面板边框与底色。 */
    private void drawPanelFrame() {
        Gui.drawRect(panelX, panelY, panelX + panelW, panelY + panelH, Theme.PANEL_BG);
        Gui.drawRect(panelX + 1, panelY + 1, panelX + panelW - 1, panelY + 3, Theme.PANEL_BG_INNER);
        Gui.drawRect(panelX, panelY, panelX + panelW, panelY + 1, Theme.GOLD);
        Gui.drawRect(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, Theme.BORDER);
        Gui.drawRect(panelX, panelY, panelX + 1, panelY + panelH, Theme.BORDER);
        Gui.drawRect(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, Theme.BORDER);
    }

    private void drawAspectPanel(Minecraft mc) {
        String title = Txt.tr("tcautoresearchunofficial.panel.aspects");
        if (selected != null) {
            title = title + " · " + Txt.aspectName(selected);
        }
        mc.fontRenderer.drawStringWithShadow(title, panelX + 8, panelY + 6, Theme.GOLD);

        // 右上角常驻显示「已有 N」，避免把下面的数字误当成目标总量
        if (selected != null) {
            String owned = Txt.tr("tcautoresearchunofficial.panel.owned", assistant.have(selected));
            mc.fontRenderer.drawStringWithShadow(owned,
                    panelX + panelW - 8 - mc.fontRenderer.getStringWidth(owned), panelY + 6,
                    Theme.TEXT_DIM);
        }

        int footerY = panelY + panelH - 21;
        if (selected == null) {
            mc.fontRenderer.drawStringWithShadow(Txt.tr("tcautoresearchunofficial.panel.none"),
                    panelX + 6 + STEP + 2, footerY, Theme.TEXT_DIM);
            return;
        }
        drawAspectIcon(mc, selected, panelX + 6 + STEP + 2, panelY + panelH - 24);
        mc.fontRenderer.drawStringWithShadow(Txt.tr("tcautoresearchunofficial.panel.count", target),
                panelX + 6 + STEP + 22, footerY, Theme.TEXT_BRIGHT);
    }

    private void drawSettingsPanel(Minecraft mc) {
        mc.fontRenderer.drawStringWithShadow(Txt.tr("tcautoresearchunofficial.panel.settings"),
                panelX + 8, panelY + 6, Theme.GOLD);
        mc.fontRenderer.drawStringWithShadow(Txt.tr("tcautoresearchunofficial.setting.interval", Config.actionIntervalTicks),
                panelX + 8, panelY + 24 + STEP * 4 + 6, Theme.TEXT);
    }

    /** 方案预览：可行时列出要放置/消耗的要素，不可行时列出六种基础要素的缺口。 */
    private void drawPreviewPanel(Minecraft mc) {
        mc.fontRenderer.drawStringWithShadow(Txt.tr("tcautoresearchunofficial.panel.preview"),
                panelX + 8, panelY + 6, Theme.GOLD);

        PlanEvaluator.Result evaluation = assistant.previewEvaluation();
        if (evaluation == null) {
            return;
        }
        String summary = evaluation.feasible
                ? Txt.tr("tcautoresearchunofficial.preview.summary", evaluation.placements, evaluation.combines)
                : Txt.tr("tcautoresearchunofficial.preview.insufficient");
        mc.fontRenderer.drawStringWithShadow(summary, panelX + 8, panelY + 20,
                evaluation.feasible ? Theme.TEXT : Theme.WARN);

        Map<Aspect, Integer> source = evaluation.feasible ? evaluation.required : evaluation.missingPrimal;
        int index = 0;
        for (Map.Entry<Aspect, Integer> entry : PlanEvaluator.sorted(source)) {
            int x = panelX + 6 + (index % 8) * 20;
            int y = panelY + 36 + (index / 8) * 20;
            AspectIcon.draw(mc, entry.getKey(), x, y, 16);
            String count = String.valueOf(entry.getValue());
            int textWidth = mc.fontRenderer.getStringWidth(count);
            Gui.drawRect(x + 16 - textWidth - 1, y + 9, x + 16, y + 17, 0xB0100C08);
            mc.fontRenderer.drawStringWithShadow(count, x + 16 - textWidth, y + 9, Theme.TEXT_BRIGHT);
            index++;
        }

        String note = evaluation.feasible ? Txt.tr("tcautoresearchunofficial.preview.consume")
                : Txt.tr("tcautoresearchunofficial.preview.uncertain");
        mc.fontRenderer.drawStringWithShadow(note, panelX + 8, panelY + panelH - 36,
                evaluation.feasible ? Theme.TEXT_DIM : Theme.WARN);
    }

    /** 在笔记的每个格子上画闪烁的要素虚影（坐标与神秘时代自己画要素时一致）。 */
    private void drawPreviewGhosts(Minecraft mc) {
        Map<HexUtils.Hex, Aspect> plan = assistant.previewPlan();
        if (plan == null || geometry == null) {
            return;
        }
        double phase = (System.currentTimeMillis() % 1400L) / 1400.0D;
        float alpha = 0.25F + 0.55F * (0.5F + 0.5F * (float) Math.sin(phase * Math.PI * 2.0D));
        for (Map.Entry<HexUtils.Hex, Aspect> entry : plan.entrySet()) {
            HexUtils.Pixel pixel = entry.getKey().toPixel(9);
            int x = geometry.left + 161 + (int) Math.round(pixel.x);
            int y = geometry.top + 75 + (int) Math.round(pixel.y);
            AspectIcon.draw(mc, entry.getValue(), x, y, 16, alpha);
        }
    }

    /** 整个浮层：面板底色 → 控件 → 悬停提示 → 状态行，全部画在原版界面之上。 */
    public void drawFront(Minecraft mc, int mouseX, int mouseY) {
        // 求解器给出方案后自动切到预览面板；预览结束（采用/取消）再收起
        if (assistant.isPreviewing()) {
            if (panel != Panel.PREVIEW) {
                setPanel(Panel.PREVIEW);
            }
        } else if (panel == Panel.PREVIEW) {
            setPanel(Panel.NONE);
        }

        layout();
        drawPreviewGhosts(mc);
        drawToolbarStrip();
        if (panel != Panel.NONE) {
            drawPanelFrame();
            if (panel == Panel.ASPECTS) {
                drawAspectPanel(mc);
            } else if (panel == Panel.SETTINGS) {
                drawSettingsPanel(mc);
            } else {
                drawPreviewPanel(mc);
            }
        }
        for (ThemedButton widget : widgets) {
            if (widget.visible) {
                widget.render(mc, mouseX, mouseY);
            }
        }

        for (ThemedButton widget : widgets) {
            if (widget.visible && widget.tooltip() != null && widget.isHovered(mouseX, mouseY)) {
                drawTooltip(mc, mouseX, mouseY, widget.tooltip());
                break;
            }
        }

        if (!Config.showStatus || geometry == null) {
            return;
        }
        int x = Math.max(2, geometry.left);
        int y = Math.min(geometry.top + geometry.height + 4, gui.height - 22);
        mc.fontRenderer.drawStringWithShadow(assistant.statusLine(), x, y, Theme.TEXT_DIM);

        String message = assistant.message();
        if (!message.isEmpty()) {
            mc.fontRenderer.drawStringWithShadow(message, x, y + 10, Theme.GOLD);
        }

        PlanRunner runner = assistant.runner();
        if (runner != null) {
            int width = geometry.width;
            int progressY = y + 20;
            Gui.drawRect(x, progressY, x + width, progressY + 2, 0x80000000);
            Gui.drawRect(x, progressY, x + (int) (width * assistant.progress()), progressY + 2, Theme.GOLD);
        }
    }

    private void drawAspectIcon(Minecraft mc, Aspect aspect, int x, int y) {
        AspectIcon.draw(mc, aspect, x, y, 16);
    }

    private void drawTooltip(Minecraft mc, int mouseX, int mouseY, String text) {
        String[] lines = text.split("\\n");
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, mc.fontRenderer.getStringWidth(line));
        }
        int height = lines.length * 10 + 2;
        int x = mouseX + 10;
        int y = mouseY - 6;
        if (x + width + 6 > gui.width) {
            x = gui.width - width - 6;
        }
        y = clamp(y, 2, Math.max(2, gui.height - height - 2));
        Gui.drawRect(x - 3, y - 3, x + width + 3, y + height, 0xF0100C08);
        Gui.drawRect(x - 3, y - 3, x + width + 3, y - 2, Theme.GOLD_DIM);
        for (int i = 0; i < lines.length; i++) {
            mc.fontRenderer.drawStringWithShadow(lines[i], x, y + i * 10, Theme.TEXT);
        }
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }
}
