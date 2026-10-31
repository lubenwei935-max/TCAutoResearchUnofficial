package com.emil.tcautoresearchunofficial;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

/**
 * 配置。文件名为 {@code config/tcautoresearchunofficial.cfg}；键名沿用上游模组的
 * {@code AutoResearch}，方便从 TC4AutoResearch 迁移设置时逐项对照。
 */
public final class Config {

    private static Configuration config;

    /** 自动解锁笔记（沿用老键名）。 */
    public static boolean autoSolve = false;
    /** 每一步（合成 / 放置）之间的间隔，单位 tick，越大越保守。 */
    public static int actionIntervalTicks = 4;
    /** 求解器进程超时，单位秒。 */
    public static int solverTimeoutSeconds = 60;
    /** 屏幕上是否显示状态行。 */
    public static boolean showStatus = true;
    /** 完成时是否弹出神秘时代风格提示。 */
    public static boolean notifyOnFinish = true;
    /** 工具条贴在研究台左侧还是右侧：AUTO / LEFT / RIGHT。 */
    public static String toolbarSide = "AUTO";
    /** 在多个候选方案里优先挑「用现成要素、少合成」的那个。 */
    public static boolean preferExisting = true;
    /** 执行前先给出方案预览（消耗清单 + 格子虚影），需要玩家确认。 */
    public static boolean previewPlan = true;
    /** 求解结果没通过校验时最多重解几次。 */
    public static int solverRetries = 2;
    /** 给玩家要素列表加并发保护，规避 Hodgepodge + 神秘时代造成的渲染崩溃。 */
    public static boolean safeAspectIteration = true;

    private static File configDirectory;

    private Config() {
    }

    public static File directory() {
        return configDirectory;
    }

    public static void load(File suggested) {
        File dir = suggested.getParentFile();
        configDirectory = dir;
        config = new Configuration(new File(dir, "tcautoresearchunofficial.cfg"));
        config.load();
        sync();
    }

    private static void sync() {
        autoSolve = config.getBoolean("AutoResearch", "general", autoSolve,
                "Automatically solve the note in the research table.");
        actionIntervalTicks = config.getInt("ActionIntervalTicks", "general", actionIntervalTicks, 1, 40,
                "Ticks between two combine/place packets. Higher is safer on laggy servers.");
        solverTimeoutSeconds = config.getInt("SolverTimeoutSeconds", "general", solverTimeoutSeconds, 5, 600,
                "Timeout for the solver process, in seconds.");
        showStatus = config.getBoolean("ShowStatus", "general", showStatus,
                "Draw a status line next to the research table.");
        notifyOnFinish = config.getBoolean("NotifyOnFinish", "general", notifyOnFinish,
                "Show a Thaumcraft HUD notification when a note is solved.");
        toolbarSide = config.getString("ToolbarSide", "general", toolbarSide,
                "Toolbar position: AUTO, LEFT or RIGHT.", new String[] { "AUTO", "LEFT", "RIGHT" });
        preferExisting = config.getBoolean("PreferExistingAspects", "general", preferExisting,
                "When several solver plans exist, prefer the one that reuses aspects you already own.");
        previewPlan = config.getBoolean("PreviewBeforeSolving", "general", previewPlan,
                "Show a plan preview (consumed aspects + blinking ghosts on the note) and ask before applying.");
        solverRetries = config.getInt("SolverRetries", "general", solverRetries, 0, 5,
                "How many times to re-run the solver when its plan fails the research-table validation.");
        safeAspectIteration = config.getBoolean("SafeAspectIteration", "compat", safeAspectIteration,
                "Replace the player's aspect list with a traversal-safe map. Works around a "
                        + "ConcurrentModificationException crash in the research table GUI caused by "
                        + "Thaumcraft writing that list from the netty thread while it is being rendered "
                        + "(more likely with Hodgepodge's aspect name sorting). Set to false to disable.");
        if (config.hasChanged()) {
            config.save();
        }
    }

    public static void setAuto(boolean value) {
        autoSolve = value;
        if (config != null) {
            config.get("general", "AutoResearch", false).set(value);
            config.save();
        }
    }

    public static void setInterval(int ticks) {
        actionIntervalTicks = Math.max(1, Math.min(40, ticks));
        if (config != null) {
            config.get("general", "ActionIntervalTicks", 4).set(actionIntervalTicks);
            config.save();
        }
    }

    public static void setNotify(boolean value) {
        notifyOnFinish = value;
        if (config != null) {
            config.get("general", "NotifyOnFinish", true).set(value);
            config.save();
        }
    }

    public static void setPreferExisting(boolean value) {
        preferExisting = value;
        if (config != null) {
            config.get("general", "PreferExistingAspects", true).set(value);
            config.save();
        }
    }

    public static void setPreviewPlan(boolean value) {
        previewPlan = value;
        if (config != null) {
            config.get("general", "PreviewBeforeSolving", true).set(value);
            config.save();
        }
    }
}
