package com.emil.tcautoresearchunofficial.research;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.common.lib.research.ResearchManager;
import thaumcraft.common.lib.research.ResearchNoteData;
import thaumcraft.common.lib.utils.HexUtils;

/**
 * 方案校验：在把解法交给玩家之前，用神秘时代自己的结算规则在本地预演一遍。
 *
 * <p>复刻的是服务端 {@code ResearchManager.checkResearchCompletion}：
 * 笔记里 type == 1 的格子是“目标要素”，必须能通过「一个要素是另一个的组件」的相邻关系
 * 全部串起来；串不起来就永远不会完成——这正是求解器偶尔给出错解时会发生的事。</p>
 *
 * <p>校验只读 {@code note}，在本地副本上推演，不会改动真实笔记。</p>
 */
public final class PlanValidator {

    public static final class Verdict {
        public final boolean valid;
        public final String reason;

        Verdict(boolean valid, String reason) {
            this.valid = valid;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return valid ? "ok" : reason;
        }
    }

    /** 格子的可变副本。 */
    private static final class Cell {
        Aspect aspect;
        int type;

        Cell(Aspect aspect, int type) {
            this.aspect = aspect;
            this.type = type;
        }
    }

    private PlanValidator() {
    }

    public static Verdict validate(ResearchNoteData note, Map<HexUtils.Hex, Aspect> plan, AspectList knowledge) {
        if (note == null || note.hexEntries == null || note.hexes == null) {
            return new Verdict(false, "no-note");
        }
        if (plan == null || plan.isEmpty()) {
            return new Verdict(false, "empty-plan");
        }

        Map<String, Cell> cells = new HashMap<String, Cell>();
        for (Map.Entry<String, ResearchManager.HexEntry> entry : note.hexEntries.entrySet()) {
            ResearchManager.HexEntry hexEntry = entry.getValue();
            if (hexEntry == null) {
                continue;
            }
            cells.put(entry.getKey(), new Cell(hexEntry.aspect, hexEntry.type));
        }

        for (Map.Entry<HexUtils.Hex, Aspect> entry : plan.entrySet()) {
            String path = entry.getKey().toString();
            Cell cell = cells.get(path);
            if (cell == null) {
                // hexEntries 偶尔可能缺少某个格子（hexes 里有），按空格子处理
                if (!note.hexes.containsKey(path)) {
                    return new Verdict(false, "unknown-hex:" + path);
                }
                cell = new Cell(null, 0);
                cells.put(path, cell);
            }
            if (cell.type == 1 && cell.aspect != entry.getValue()) {
                // 覆盖目标要素会让笔记永远无法完成
                return new Verdict(false, "overwrite-target:" + path);
            }
            cell.aspect = entry.getValue();
            cell.type = 2;
        }

        List<String> main = new ArrayList<String>();
        for (Map.Entry<String, Cell> entry : cells.entrySet()) {
            if (entry.getValue().type == 1) {
                main.add(entry.getKey());
            }
        }
        if (main.isEmpty()) {
            return new Verdict(true, "no-targets");
        }

        Set<String> checked = new HashSet<String>();
        Set<String> remains = new HashSet<String>();
        String start = main.remove(0);
        walk(note, cells, start, checked, remains, main, knowledge);
        if (!main.isEmpty()) {
            return new Verdict(false, "disconnected:" + main.size());
        }
        return new Verdict(true, "ok");
    }

    /** 与 TC 的 checkConnections 等价：只沿“组件关系”的相邻格扩散。 */
    private static void walk(ResearchNoteData note, Map<String, Cell> cells, String path, Set<String> checked,
            Set<String> remains, List<String> main, AspectList knowledge) {
        checked.add(path);
        HexUtils.Hex hex = note.hexes.get(path);
        Cell from = cells.get(path);
        if (hex == null || from == null || from.aspect == null) {
            return;
        }
        for (int i = 0; i < 6; i++) {
            HexUtils.Hex neighbour = hex.getNeighbour(i);
            String next = neighbour.toString();
            if (checked.contains(next)) {
                continue;
            }
            Cell to = cells.get(next);
            if (to == null || to.type < 1 || to.aspect == null) {
                continue;
            }
            // 服务端要求双方要素都已被玩家发现，否则这条边不算
            if (!knowledge.aspects.containsKey(from.aspect) || !knowledge.aspects.containsKey(to.aspect)) {
                continue;
            }
            if (!linked(from.aspect, to.aspect)) {
                continue;
            }
            remains.add(next);
            if (to.type == 1) {
                main.remove(next);
            }
            walk(note, cells, next, checked, remains, main, knowledge);
        }
    }

    /** 两个要素之间存在「组件」关系才算连通。 */
    private static boolean linked(Aspect first, Aspect second) {
        Aspect[] components = first.getComponents();
        if (components != null && (components[0] == second || components[1] == second)) {
            return true;
        }
        components = second.getComponents();
        return components != null && (components[0] == first || components[1] == first);
    }
}
