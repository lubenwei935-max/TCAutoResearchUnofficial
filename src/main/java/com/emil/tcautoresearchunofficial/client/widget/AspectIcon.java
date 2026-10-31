package com.emil.tcautoresearchunofficial.client.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import thaumcraft.api.aspects.Aspect;

/**
 * 要素图标绘制。
 *
 * <p>两个坑：</p>
 * <ol>
 *   <li>{@code Gui.drawTexturedModalRect} 的 UV 按 <b>256×256 图集</b> 换算
 *       （{@code u = 像素/256}），而要素贴图是 32×32 独立贴图，会只采到左上角 4×4 像素。
 *       所以这里和神秘时代自己的 {@code UtilsFX.drawTag} 一样，用 Tessellator 给出 0..1 的完整 UV。</li>
 *   <li>要素贴图本身是<b>灰度遮罩</b>（实测 ignis.png 像素 R=G=B），
 *       必须乘上 {@code Aspect.getColor()} 才会上色 —— 否则一律是灰白的。</li>
 * </ol>
 */
public final class AspectIcon {

    private AspectIcon() {
    }

    public static void draw(Minecraft mc, Aspect aspect, int x, int y, int size) {
        draw(mc, aspect, x, y, size, 1.0F);
    }

    public static void draw(Minecraft mc, Aspect aspect, int x, int y, int size, float alpha) {
        if (aspect == null) {
            return;
        }
        ResourceLocation image = aspect.getImage();
        if (image == null) {
            Gui.drawRect(x, y, x + size, y + size, vivid(aspect.getColor()) | 0xFF000000);
            return;
        }

        int color = vivid(aspect.getColor());
        float red = (color >> 16 & 0xFF) / 255.0F;
        float green = (color >> 8 & 0xFF) / 255.0F;
        float blue = (color & 0xFF) / 255.0F;
        float clamped = Math.max(0.0F, Math.min(1.0F, alpha));

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(red, green, blue, clamped);

        mc.getTextureManager().bindTexture(image);

        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        tessellator.setColorRGBA_F(red, green, blue, clamped);
        // 贴图 v=0 在上，所以先画下边两个顶点
        tessellator.addVertexWithUV(x, y + size, 0.0D, 0.0D, 1.0D);
        tessellator.addVertexWithUV(x + size, y + size, 0.0D, 1.0D, 1.0D);
        tessellator.addVertexWithUV(x + size, y, 0.0D, 1.0D, 0.0D);
        tessellator.addVertexWithUV(x, y, 0.0D, 0.0D, 0.0D);
        tessellator.draw();

        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    /**
     * 让“浅而低饱和”的要素颜色在灰度遮罩上也能看出颜色。
     *
     * <p>要素贴图是 ~50% 灰的遮罩，颜色要乘上去。像 {@code auram}(0xFFC0FF) 这种本身
     * 饱和度只有 0.25 的浅粉，乘完就是灰紫，肉眼几乎看不出颜色。这里对
     * 「亮度 >= 0.8 且饱和度在 0.08~0.45」的颜色提纯到 0.55，其余（纯灰、白色、
     * 本来就鲜艳的颜色）原样返回。</p>
     */
    private static int vivid(int rgb) {
        int color = rgb & 0xFFFFFF;
        int r = color >> 16 & 0xFF;
        int g = color >> 8 & 0xFF;
        int b = color & 0xFF;
        if (r == g && g == b) {
            // 纯灰/白：本来就是这种颜色（混沌、虚空、天气……），不要凭空染色
            return color;
        }
        float[] hsb = java.awt.Color.RGBtoHSB(r, g, b, null);
        if (hsb[2] >= 0.8F && hsb[1] > 0.08F && hsb[1] < 0.45F) {
            return java.awt.Color.HSBtoRGB(hsb[0], 0.55F, hsb[2]) & 0xFFFFFF;
        }
        return color;
    }
}
