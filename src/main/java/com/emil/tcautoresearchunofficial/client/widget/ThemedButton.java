package com.emil.tcautoresearchunofficial.client.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;

import thaumcraft.api.aspects.Aspect;

/**
 * 统一风格的小控件：工具条标签（{@link Style#TAB}）、要素格子（{@link Style#CELL}）、
 * 普通文字按钮（{@link Style#CHIP}）。
 *
 * <p>点击动作直接在 {@link #mousePressed} 里执行：这样不需要改别人的 GUI，
 * 也不需要 {@code actionPerformed} 注入。</p>
 */
public class ThemedButton extends GuiButton {

    public enum Style {
        TAB, CELL, CHIP
    }

    public interface Action {
        void run(ThemedButton button);
    }

    private final Style style;
    private Action action;
    private Aspect aspect;
    private int badge = -1;
    private boolean highlighted;
    private String tooltip;

    public ThemedButton(int id, int x, int y, int width, int height, String label, Style style) {
        super(id, x, y, width, height, label);
        this.style = style;
    }

    public ThemedButton onAction(Action action) {
        this.action = action;
        return this;
    }

    public ThemedButton aspect(Aspect aspect) {
        this.aspect = aspect;
        return this;
    }

    public ThemedButton badge(int badge) {
        this.badge = badge;
        return this;
    }

    public ThemedButton highlighted(boolean highlighted) {
        this.highlighted = highlighted;
        return this;
    }

    public ThemedButton tooltip(String tooltip) {
        this.tooltip = tooltip;
        return this;
    }

    public String tooltip() {
        return tooltip;
    }

    public Aspect aspectValue() {
        return aspect;
    }

    public Style style() {
        return style;
    }

    public boolean isHovered(int mouseX, int mouseY) {
        return mouseX >= xPosition && mouseY >= yPosition
                && mouseX < xPosition + width && mouseY < yPosition + height;
    }

    @Override
    public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        // GuiScreen 会遍历整个 buttonList，隐藏按钮必须自己拒绝点击
        if (!visible || !enabled || !isHovered(mouseX, mouseY)) {
            return false;
        }
        if (action != null) {
            action.run(this);
        }
        return true;
    }

    /**
     * 原版会在 {@code GuiScreen.drawScreen} 里画按钮，而那个时机在
     * {@code DrawScreenEvent.Pre} 之后、{@code drawDefaultBackground} 之下 ——
     * 面板会被整屏的暗色遮罩压暗，所以这里禁用自动绘制，
     * 统一由 {@code ResearchOverlay} 在 {@code DrawScreenEvent.Post} 里调用 {@link #render}。
     */
    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        // 刻意留空
    }

    public void render(Minecraft mc, int mouseX, int mouseY) {
        if (!visible) {
            return;
        }
        int x = xPosition;
        int y = yPosition;
        int w = width;
        int h = height;
        boolean hovered = enabled && isHovered(mouseX, mouseY);

        Gui.drawRect(x, y, x + w, y + h, enabled ? (hovered ? Theme.BUTTON_HOVER : Theme.BUTTON_BG) : 0x60000000);
        int border = highlighted ? Theme.GOLD : (hovered ? Theme.GOLD_DIM : Theme.BORDER_DIM);
        Gui.drawRect(x, y, x + w, y + 1, border);
        Gui.drawRect(x, y + h - 1, x + w, y + h, border);
        Gui.drawRect(x, y, x + 1, y + h, border);
        Gui.drawRect(x + w - 1, y, x + w, y + h, border);

        if (aspect != null) {
            drawAspect(mc, x + 2, y + 2);
            if (badge >= 0) {
                String text = badge > 99 ? "99+" : String.valueOf(badge);
                int textWidth = mc.fontRenderer.getStringWidth(text);
                // 数量压一小块半透明底，避免和 32x32 缩下来的图标糊在一起
                Gui.drawRect(x + w - textWidth - 2, y + h - 9, x + w - 1, y + h - 1, 0xB0100C08);
                int color = badge > 0 ? Theme.TEXT : Theme.TEXT_DISABLED;
                mc.fontRenderer.drawStringWithShadow(text, x + w - 1 - textWidth, y + h - 8, color);
            }
            if (highlighted) {
                Gui.drawRect(x + 1, y + h - 3, x + w - 1, y + h - 1, Theme.GOLD);
            }
            return;
        }

        if (displayString != null && !displayString.isEmpty()) {
            int color = !enabled ? Theme.TEXT_DISABLED : (hovered ? Theme.TEXT_BRIGHT : Theme.TEXT);
            mc.fontRenderer.drawStringWithShadow(displayString,
                    x + (w - mc.fontRenderer.getStringWidth(displayString)) / 2,
                    y + (h - 8) / 2 + (style == Style.CHIP ? 0 : 1), color);
        }
    }

    private void drawAspect(Minecraft mc, int x, int y) {
        // 32x32 独立贴图，必须用 0..1 的完整 UV（详见 AspectIcon）
        AspectIcon.draw(mc, aspect, x, y, 16);
    }
}
