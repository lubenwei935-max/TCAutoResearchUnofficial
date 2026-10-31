package com.emil.tcautoresearchunofficial.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import net.minecraftforge.client.event.GuiScreenEvent;

import com.emil.tcautoresearchunofficial.TCAutoResearchUnofficial;
import com.emil.tcautoresearchunofficial.research.ResearchAssistant;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import thaumcraft.client.gui.GuiResearchTable;

/**
 * 只在 Forge 事件上挂钩子：打开界面时往 buttonList 里塞控件，绘制时画面板。
 *
 * <p>没有 Mixin、没有 {@code displayGuiScreen}、没有后台线程碰 GUI ——
 * 和 NEI/NEA 的渲染补丁彻底解耦。</p>
 *
 * <p><b>所有回调都包了 try/catch。</b>这不是防御式编程洁癖：FML 的
 * {@code InitGuiEvent.Post} 是在 {@code GuiScreen.setWorldAndResolution} 内部触发的，
 * 一旦监听器抛异常，异常会穿出去，导致 NEI 注入在 {@code GuiContainer.setWorldAndResolution}
 * 末尾的那段“创建 GuiContainerManager”直接跳过 —— 下一个 tick
 * {@code GuiContainer.updateScreen} 就会 NPE（“Ticking screen”）。</p>
 */
public final class ClientEvents {

    private static ResearchOverlay overlay;
    private static boolean drawErrorLogged;

    @SubscribeEvent
    public void onInitGui(GuiScreenEvent.InitGuiEvent.Post event) {
        try {
            GuiScreen gui = event.gui;
            if (!(gui instanceof GuiResearchTable)) {
                overlay = null;
                ResearchAssistant.get().detach();
                return;
            }

            @SuppressWarnings("unchecked")
            List<GuiButton> buttons = event.buttonList;
            GuiResearchTable table = (GuiResearchTable) gui;

            ResearchOverlay previous = overlay;
            if (previous != null && previous.gui() == table) {
                // 窗口尺寸变化会重新 initGui，先把旧控件摘掉
                previous.dispose(buttons);
            }
            overlay = null;
            ResearchAssistant.get().attach(table);
            // build() 是最后才把控件塞进 buttonList 的，所以这里抛出也不会留下半成品
            overlay = new ResearchOverlay(table, buttons);
        } catch (Throwable t) {
            overlay = null;
            ResearchAssistant.get().detach();
            TCAutoResearchUnofficial.LOG.error("研究台工具条初始化失败（已忽略，不影响 NEI / 原版界面）", t);
        }
    }

    @SubscribeEvent
    public void onDrawPost(GuiScreenEvent.DrawScreenEvent.Post event) {
        ResearchOverlay current = overlay;
        if (current == null || current.gui() != event.gui) {
            return;
        }
        try {
            current.drawFront(Minecraft.getMinecraft(), event.mouseX, event.mouseY);
        } catch (Throwable t) {
            // 画崩了只会没有面板，绝不能把整个渲染循环带走
            overlay = null;
            if (!drawErrorLogged) {
                drawErrorLogged = true;
                TCAutoResearchUnofficial.LOG.error("研究台工具条绘制失败（已关闭本次面板）", t);
            }
        }
    }
}
