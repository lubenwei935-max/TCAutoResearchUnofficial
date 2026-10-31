package com.emil.tcautoresearchunofficial.util;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;

/** 容器 GUI 的实际尺寸 / 左上角，兼容不同版本的字段名。 */
public final class GuiGeometry {

    public final int left;
    public final int top;
    public final int width;
    public final int height;

    private GuiGeometry(int left, int top, int width, int height) {
        this.left = left;
        this.top = top;
        this.width = width;
        this.height = height;
    }

    public static GuiGeometry of(GuiContainer gui) {
        int w = Reflect.getInt(gui, 176, "xSize", "field_146999_f");
        int h = Reflect.getInt(gui, 166, "ySize", "field_147000_g");
        int left = Reflect.getInt(gui, Integer.MIN_VALUE, "guiLeft", "field_147003_i");
        int top = Reflect.getInt(gui, Integer.MIN_VALUE, "guiTop", "field_147009_r");
        if (left == Integer.MIN_VALUE || top == Integer.MIN_VALUE) {
            left = (gui.width - w) / 2;
            top = (gui.height - h) / 2;
        }
        return new GuiGeometry(left, top, w, h);
    }

    /** 屏幕坐标（缩放后）换算成 GUI 内的相对坐标。 */
    public static boolean inside(GuiScreen gui, int mouseX, int mouseY) {
        return mouseX >= 0 && mouseY >= 0 && mouseX < gui.width && mouseY < gui.height;
    }
}
