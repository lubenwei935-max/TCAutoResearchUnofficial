package com.emil.tcautoresearchunofficial.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;

import com.emil.tcautoresearchunofficial.TCAutoResearchUnofficial;
import com.emil.tcautoresearchunofficial.util.ClientTasks;

/**
 * 与 NotEnoughItems 的弱耦合桥接。
 *
 * <p>背景：NEI 的 {@code GuiInfo.hideItemPanelSlot} 会遍历 {@code gui.buttonList}，
 * <b>只看按钮坐标、不看 visible</b>，凡是与物品格重叠的按钮都会让 NEI 隐藏该格。
 * 本模组的工具条/面板控件都塞在 buttonList 里，所以面板收起后如果按钮还留在原位，
 * NEI 会一直认为那些格子“被盖住”，表现为物品布局回不去。</p>
 *
 * <p>本类只做一件事：面板开合后请求 NEI 重算一次物品面板布局。用反射调用，
 * 没装 NEI 时静默跳过，不产生硬依赖。</p>
 */
public final class NeiBridge {

    private static boolean unavailable;

    private NeiBridge() {
    }

    /** 安排一次 NEI 物品面板重算（在下一个客户端 tick 里执行，避开渲染阶段）。 */
    public static void refreshItemPanel(final GuiContainer gui) {
        if (gui == null || unavailable) {
            return;
        }
        ClientTasks.run(new Runnable() {
            @Override
            public void run() {
                if (Minecraft.getMinecraft().currentScreen != gui) {
                    return;
                }
                try {
                    Class<?> panels = Class.forName("codechicken.nei.ItemPanels");
                    Field field = panels.getField("itemPanel");
                    Object itemPanel = field.get(null);
                    if (itemPanel == null) {
                        return;
                    }
                    Method resize = itemPanel.getClass().getMethod("resize", GuiContainer.class);
                    resize.invoke(itemPanel, gui);
                } catch (ClassNotFoundException e) {
                    // 没装 NEI
                    unavailable = true;
                } catch (Throwable t) {
                    unavailable = true;
                    TCAutoResearchUnofficial.LOG.warn("刷新 NEI 物品面板布局失败（不影响本模组）: {}", t.toString());
                }
            }
        });
    }
}
