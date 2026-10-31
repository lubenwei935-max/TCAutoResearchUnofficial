package com.emil.tcautoresearchunofficial.research;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.common.lib.utils.HexUtils;

/**
 * 方案评估：在真正发包之前，先在本地把「放哪些要素、要合成几次、缺多少基础要素」算出来。
 *
 * <p>求解器（闭源的 AutoResearch.dll）只给「格子 -> 要素」的清单，可能一次给多行候选。
 * 本类负责把这些候选换算成可比较的代价，从而挑出最省要素的那个；同时在要素不够时
 * 算清楚缺多少基础要素，供预览界面展示。</p>
 *
 * <p>注意这里只做<b>估算</b>：真实合成还会受神秘时代「源质/加成要素」等规则影响，
 * 所以预览里标注为“估算”，实际执行时仍以服务端返回的数量为准。</p>
 */
public final class PlanEvaluator {

    /** 一次方案评估的结果。 */
    public static final class Result {
        public boolean feasible = true;
        public int placements;
        public int combines;
        /** 用现成要素直接满足的放置数（不需要合成）。 */
        public int covered;
        /** 需要放置的要素数量。 */
        public final Map<Aspect, Integer> required = new LinkedHashMap<Aspect, Integer>();
        /** 基础要素缺口（负数表示缺多少个）。 */
        public final Map<Aspect, Integer> missingPrimal = new LinkedHashMap<Aspect, Integer>();
        /** 会被从要素池里拿走的要素（放置 + 合成原料）。 */
        public final Map<Aspect, Integer> consumed = new LinkedHashMap<Aspect, Integer>();

        public int missingTotal() {
            int total = 0;
            for (Integer value : missingPrimal.values()) {
                total += Math.max(0, value);
            }
            return total;
        }

        /** 越小越好。preferExisting = 优先用现成要素（少合成）。 */
        public int score(boolean preferExisting) {
            if (!feasible) {
                return 1000000 + missingTotal() * 1000 + combines;
            }
            int craft = Math.max(0, placements - covered);
            if (preferExisting) {
                return craft * 100 + combines * 10 + placements;
            }
            return combines * 100 + craft * 10 + placements;
        }
    }

    private PlanEvaluator() {
    }

    public static Result evaluate(Map<HexUtils.Hex, Aspect> plan, AspectList pool) {
        Result result = new Result();
        if (plan == null || plan.isEmpty()) {
            result.feasible = false;
            return result;
        }

        Map<Aspect, Integer> avail = new HashMap<Aspect, Integer>();
        if (pool != null) {
            for (Map.Entry<Aspect, Integer> entry : pool.aspects.entrySet()) {
                avail.put(entry.getKey(), entry.getValue());
            }
        }

        Map<Aspect, Integer> need = new LinkedHashMap<Aspect, Integer>();
        for (Aspect aspect : plan.values()) {
            Integer current = need.get(aspect);
            need.put(aspect, current == null ? 1 : current + 1);
        }
        result.required.putAll(need);

        for (Map.Entry<Aspect, Integer> entry : need.entrySet()) {
            Aspect target = entry.getKey();
            for (int i = 0; i < entry.getValue(); i++) {
                result.placements++;
                if (take(avail, target, result)) {
                    result.covered++;
                    continue;
                }
                if (!craft(target, avail, result, 0)) {
                    result.feasible = false;
                    continue;
                }
                // craft() 已经把一个成品放进了 avail，这里再拿去放置
                take(avail, target, result);
            }
        }
        return result;
    }

    /** 从池子里拿走一个；成功返回 true。 */
    private static boolean take(Map<Aspect, Integer> avail, Aspect aspect, Result result) {
        Integer have = avail.get(aspect);
        if (have == null || have <= 0) {
            return false;
        }
        avail.put(aspect, have - 1);
        Integer used = result.consumed.get(aspect);
        result.consumed.put(aspect, used == null ? 1 : used + 1);
        return true;
    }

    /** 保证产出一个 target：原料不够就继续往下合成。 */
    private static boolean craft(Aspect target, Map<Aspect, Integer> avail, Result result, int depth) {
        if (depth > 16) {
            return false;
        }
        Aspect[] components = target.getComponents();
        if (components == null) {
            Integer missing = result.missingPrimal.get(target);
            result.missingPrimal.put(target, missing == null ? 1 : missing + 1);
            return false;
        }
        for (Aspect component : components) {
            if (take(avail, component, result)) {
                continue;
            }
            if (!craft(component, avail, result, depth + 1)) {
                return false;
            }
            take(avail, component, result);
        }
        Integer produced = avail.get(target);
        avail.put(target, produced == null ? 1 : produced + 1);
        result.combines++;
        return true;
    }

    /** 按数量从多到少排序，方便界面展示。 */
    public static List<Map.Entry<Aspect, Integer>> sorted(Map<Aspect, Integer> map) {
        List<Map.Entry<Aspect, Integer>> list = new ArrayList<Map.Entry<Aspect, Integer>>(map.entrySet());
        list.sort(new java.util.Comparator<Map.Entry<Aspect, Integer>>() {
            @Override
            public int compare(Map.Entry<Aspect, Integer> a, Map.Entry<Aspect, Integer> b) {
                return b.getValue().compareTo(a.getValue());
            }
        });
        return list;
    }
}
