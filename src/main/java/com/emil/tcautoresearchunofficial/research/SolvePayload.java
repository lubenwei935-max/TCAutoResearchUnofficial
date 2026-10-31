package com.emil.tcautoresearchunofficial.research;

import java.util.LinkedHashMap;
import java.util.Map;

import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.common.lib.research.ResearchManager;
import thaumcraft.common.lib.research.ResearchNoteData;

/**
 * 构造求解器入参。
 *
 * <p>协议必须与作者的原生求解器严格一致（老版本用字符串加号拼接，这里改成
 * {@link StringBuilder} 一次成型，大笔记下少生成几百个中间字符串）：</p>
 *
 * <pre>
 * 玩家已发现要素: tag:amount&amp;...  ^  全部要素配方: tag[:c1:c2]&amp;...  ^  笔记格子: path[:tag]&amp;...
 * </pre>
 */
public final class SolvePayload {

    private SolvePayload() {
    }

    public static String build(ResearchNoteData note, AspectList knowledge) {
        if (note == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(2048);

        for (Map.Entry<Aspect, Integer> entry : knowledge.aspects.entrySet()) {
            sb.append(entry.getKey().getTag()).append(':').append(entry.getValue()).append('&');
        }
        sb.append('^');

        for (Map.Entry<String, Aspect> entry : Aspect.aspects.entrySet()) {
            Aspect aspect = entry.getValue();
            Aspect[] components = aspect.getComponents();
            sb.append(aspect.getTag());
            if (components != null) {
                sb.append(':').append(components[0].getTag()).append(':').append(components[1].getTag());
            }
            sb.append('&');
        }
        sb.append('^');

        for (Map.Entry<String, ResearchManager.HexEntry> entry : note.hexEntries.entrySet()) {
            ResearchManager.HexEntry hexEntry = entry.getValue();
            sb.append(entry.getKey());
            if (hexEntry != null && hexEntry.aspect != null) {
                sb.append(':').append(hexEntry.aspect.getTag());
            }
            sb.append('&');
        }
        return sb.toString();
    }

    /** 把求解器输出解析成「格子 -> 要素」。纯字符串处理，可在工作线程调用。 */
    public static Map<String, String> parseLines(Iterable<String> lines) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (String line : lines) {
            if (line == null || line.isEmpty()) {
                continue;
            }
            for (String item : line.split("&")) {
                String[] parts = item.split("\\|");
                if (parts.length != 2) {
                    continue;
                }
                String path = parts[0].trim();
                String tag = parts[1].trim();
                if (!path.isEmpty() && !tag.isEmpty()) {
                    result.put(path, tag);
                }
            }
        }
        return result;
    }
}
