package com.emil.tcautoresearchunofficial.util;

import net.minecraft.client.resources.I18n;
import net.minecraft.util.StatCollector;

import thaumcraft.api.aspects.Aspect;

/** 文本工具：i18n 与要素名称。 */
public final class Txt {

    private Txt() {
    }

    public static String tr(String key, Object... args) {
        try {
            return I18n.format(key, args);
        } catch (Throwable t) {
            return key;
        }
    }

    /** 要素本地化名称（神秘时代自带的 tc.aspect.* 词条）。 */
    public static String aspectName(Aspect aspect) {
        if (aspect == null) {
            return "";
        }
        String name = StatCollector.translateToLocal("tc.aspect." + aspect.getTag());
        return name == null || name.isEmpty() || name.startsWith("tc.aspect.") ? aspect.getTag() : name;
    }

    /** 要素描述（用于悬停提示）。 */
    public static String aspectDesc(Aspect aspect) {
        if (aspect == null) {
            return "";
        }
        String desc = StatCollector.translateToLocal("tc.aspect.help." + aspect.getTag());
        return desc == null || desc.startsWith("tc.aspect.help.") ? "" : desc;
    }

    /** 译文里可能有换行，这里统一按 \n 切开。 */
    public static String[] lines(String text) {
        return text.split("\\n");
    }
}
