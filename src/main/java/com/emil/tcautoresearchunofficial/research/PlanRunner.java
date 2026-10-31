package com.emil.tcautoresearchunofficial.research;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;

import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.client.gui.GuiResearchTable;
import thaumcraft.common.lib.research.ResearchManager;
import thaumcraft.common.lib.research.ResearchNoteData;
import thaumcraft.common.lib.utils.HexUtils;
import thaumcraft.common.tiles.TileResearchTable;

/**
 * 按 tick 逐步执行「合成要素 → 放置要素」的计划。
 *
 * <p>老版本在后台线程里 {@code Thread.sleep(500)} 然后发网络包，既不准（服务器还没处理完
 * 就发下一步）也不安全（可能读到一个已经关掉的 GUI 对象）。这里改成：</p>
 * <ul>
 *   <li>只在客户端 tick 里推进一步，间隔由配置决定；</li>
 *   <li>用 {@code pending} 记录「已经发出去、服务器还没同步回来」的增减量，
 *       因此不用傻等，也不会因为要素数量没刷新而重复合成；</li>
 *   <li>每一步都重新读实时要素数量，服务器拒绝时不会无限循环。</li>
 * </ul>
 */
public final class PlanRunner {

    public enum State {
        RUNNING, DONE, FAILED, CANCELLED
    }

    /** 任务类型：只影响完成时的提示文案，以及是否需要绑定当前笔记。 */
    public enum Kind {
        SOLVE, UNLOCK_ALL, COMBINE
    }

    // 单次任务上限：面板最多一次合成 64 个，深层要素的合成链会成倍增长，留足余量
    private static final int MAX_STEPS = 2000;
    private static final int MAX_DEPTH = 16;

    private final GuiResearchTable gui;
    private final int interval;
    private final Kind kind;
    /** 是否依赖当前笔记（只有“解笔记”才是；合成要素不需要笔记）。 */
    private final boolean needsNote;
    /** 创建计划时的笔记身份，用于在服务端换掉笔记时立刻停手。 */
    private final String noteSig;
    /** 目标要素 -> 需要的数量。 */
    private final Map<Aspect, Integer> need = new LinkedHashMap<Aspect, Integer>();
    private final Deque<Placement> placements = new ArrayDeque<Placement>();
    /**
     * 已发出、服务端还没同步回来的增减量。
     *
     * <p>必须随服务端数据“回收”，否则会重复计数：例如合成一次，服务端同步后
     * live 已经 +1，而 pending 还留着 +1，可用量就变成 +2 —— 请求合成 4 个
     * 只做 2 个，正是这个 bug。</p>
     */
    private final Map<Aspect, Integer> pending = new HashMap<Aspect, Integer>();
    /** 每个要素上次观察到的服务端数量，用于把 pending 与服务端对齐。 */
    private final Map<Aspect, Integer> lastLive = new HashMap<Aspect, Integer>();
    /** 本 tick 的要素快照，避免一次 tick 里反复复制 AspectList。 */
    private AspectList snapshot;
    private int idleTicks;
    /** 连续多少次“记账被清空但服务端毫无变化”，用来识别服务器直接拒绝的情况。 */
    private int stalls;

    private State state = State.RUNNING;
    private String failReason;
    private Aspect missing;
    private int cooldown;
    private int stepsDone;
    private int totalSteps;
    private int placementsDone;
    private String lastAction = "";

    private PlanRunner(GuiResearchTable gui, int interval, Kind kind, boolean needsNote) {
        this.gui = gui;
        this.interval = Math.max(1, interval);
        this.kind = kind;
        this.needsNote = needsNote;
        this.noteSig = needsNote ? ResearchAssistant.signature(ResearchTableAccess.note(gui)) : "";
    }

    public Kind kind() {
        return kind;
    }

    // ------------------------------------------------------------------ 工厂

    /** 按求解器给出的「格子 -> 要素」清单执行。 */
    public static PlanRunner forPlacements(GuiResearchTable gui,
            Map<HexUtils.Hex, Aspect> plan, int interval) {
        PlanRunner runner = new PlanRunner(gui, interval, Kind.SOLVE, true);
        ResearchNoteData note = ResearchTableAccess.note(gui);
        if (note == null) {
            runner.state = State.FAILED;
            runner.failReason = "no-note";
            return runner;
        }
        for (Map.Entry<HexUtils.Hex, Aspect> entry : plan.entrySet()) {
            ResearchManager.HexEntry hexEntry = note.hexEntries.get(entry.getKey().toString());
            if (hexEntry != null && hexEntry.aspect == entry.getValue()) {
                continue;
            }
            runner.placements.add(new Placement(entry.getKey(), entry.getValue()));
            runner.incrementNeed(entry.getValue(), 1);
        }
        runner.recount();
        if (runner.placements.isEmpty()) {
            runner.state = State.DONE;
        }
        return runner;
    }

    /** 解锁全部要素：把玩家还没发现的要素各补 1 个。 */
    public static PlanRunner forUnlockAll(GuiResearchTable gui, int interval) {
        PlanRunner runner = new PlanRunner(gui, interval, Kind.UNLOCK_ALL, false);
        AspectList knowledge = ResearchTableAccess.knowledge();
        for (Aspect aspect : Aspect.aspects.values()) {
            if (knowledge.getAmount(aspect) <= 0) {
                runner.incrementNeed(aspect, 1);
            }
        }
        runner.recount();
        if (runner.need.isEmpty()) {
            runner.state = State.DONE;
            runner.failReason = "all-known";
        }
        return runner;
    }

    /**
     * 再合成 {@code count} 个指定要素。
     *
     * <p>注意这里的语义是<b>增量</b>：面板上写 3 就是「再做 3 个」，与当前拥有多少无关。
     * 之前的实现把它当成「目标总量」（{@code count > have} 才动手），于是填一个小于
     * 现有数量的数字就什么都不发生 —— 这正是那个 bug。</p>
     */
    public static PlanRunner forCombine(GuiResearchTable gui, Aspect aspect, int count, int interval) {
        PlanRunner runner = new PlanRunner(gui, interval, Kind.COMBINE, false);
        if (aspect != null && count > 0) {
            int have = ResearchTableAccess.knowledge().getAmount(aspect);
            // need 是「保证至少有多少」，所以目标 = 现有 + 还要做的数量
            runner.incrementNeed(aspect, have + count);
        }
        runner.recount();
        if (runner.need.isEmpty()) {
            runner.state = State.DONE;
            runner.failReason = "nothing-to-do";
        }
        return runner;
    }

    // ------------------------------------------------------------------ 驱动

    public void tick() {
        if (state != State.RUNNING) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != gui) {
            state = State.CANCELLED;
            return;
        }
        if (needsNote && !noteSig.isEmpty()
                && !noteSig.equals(ResearchAssistant.signature(ResearchTableAccess.note(gui)))) {
            state = State.CANCELLED;
            failReason = "note-changed";
            return;
        }
        snapshot = ResearchTableAccess.knowledge();
        reconcile();
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (stepsDone >= MAX_STEPS) {
            state = State.FAILED;
            failReason = "too-many-steps";
            return;
        }
        TileResearchTable table = ResearchTableAccess.table();
        if (table == null) {
            state = State.FAILED;
            failReason = "no-table";
            return;
        }
        Action action = nextAction();
        if (action == null) {
            return;
        }
        execute(table, action);
        cooldown = interval;
    }

    public void cancel() {
        if (state == State.RUNNING) {
            state = State.CANCELLED;
        }
    }

    private Action nextAction() {
        for (Map.Entry<Aspect, Integer> entry : need.entrySet()) {
            Aspect target = entry.getKey();
            if (available(target) >= entry.getValue()) {
                continue;
            }
            missing = null;
            Action action = combineStep(target, 0);
            if (action != null) {
                return action;
            }
            state = State.FAILED;
            failReason = missing == null ? "unavailable" : ("missing-base:" + missing.getTag());
            return null;
        }

        // 只有“解笔记”才依赖研究台上的笔记；纯合成任务没有笔记也要能跑完
        if (needsNote) {
            ResearchNoteData note = ResearchTableAccess.note(gui);
            if (note == null) {
                state = State.CANCELLED;
                return null;
            }
            if (note.complete) {
                state = State.DONE;
                return null;
            }
            while (!placements.isEmpty()) {
                Placement placement = placements.pollFirst();
                ResearchManager.HexEntry hexEntry = note.hexEntries.get(placement.hex.toString());
                if (hexEntry != null && hexEntry.aspect == placement.aspect) {
                    continue;
                }
                return Action.place(placement.hex, placement.aspect);
            }
        }
        state = State.DONE;
        return null;
    }

    /** 递归找出「为了得到 target 需要先做的一次合成」。 */
    private Action combineStep(Aspect target, int depth) {
        if (depth > MAX_DEPTH) {
            return null;
        }
        Aspect[] components = target.getComponents();
        if (components == null) {
            missing = target;
            return null;
        }
        for (Aspect component : components) {
            if (available(component) < 1) {
                return combineStep(component, depth + 1);
            }
        }
        if (available(components[0]) < 1 || available(components[1]) < 1) {
            missing = available(components[0]) < 1 ? components[0] : components[1];
            return null;
        }
        return Action.combine(target, components[0], components[1]);
    }

    private void execute(TileResearchTable table, Action action) {
        if (action.parent != null) {
            ResearchTableAccess.combine(table, action.first, action.second);
            adjustPending(action.parent, 1);
            adjustPending(action.first, -1);
            adjustPending(action.second, -1);
            lastAction = "combine:" + action.parent.getTag();
        } else {
            ResearchTableAccess.place(table, action.hex, action.aspect);
            adjustPending(action.aspect, -1);
            placementsDone++;
            lastAction = "place:" + action.hex;
        }
        stepsDone++;
    }

    // ------------------------------------------------------------------ 数据

    private int available(Aspect aspect) {
        AspectList current = snapshot != null ? snapshot : ResearchTableAccess.knowledge();
        int live = current.getAmount(aspect);
        Integer delta = pending.get(aspect);
        return Math.max(0, live + (delta == null ? 0 : delta));
    }

    private void adjustPending(Aspect aspect, int delta) {
        if (!lastLive.containsKey(aspect)) {
            // 记下发包前的基线：之后服务端这个数字涨上来，就把 pending 抵掉
            AspectList current = snapshot != null ? snapshot : ResearchTableAccess.knowledge();
            lastLive.put(aspect, current.getAmount(aspect));
        }
        Integer current = pending.get(aspect);
        pending.put(aspect, (current == null ? 0 : current) + delta);
    }

    /**
     * 把 pending 与服务端实际数量对齐：live 涨/跌了多少，就从 pending 里扣掉多少。
     *
     * <p>不做这一步的话，同一次合成会被算两遍（一次来自 live，一次来自 pending），
     * 于是「合成 4 个」实际只做 2 个。</p>
     */
    private void reconcile() {
        if (pending.isEmpty() || snapshot == null) {
            return;
        }
        boolean changed = false;
        for (Map.Entry<Aspect, Integer> entry : pending.entrySet()) {
            Aspect aspect = entry.getKey();
            int delta = entry.getValue() == null ? 0 : entry.getValue();
            int live = snapshot.getAmount(aspect);
            Integer last = lastLive.get(aspect);
            if (last == null) {
                lastLive.put(aspect, live);
                continue;
            }
            int diff = live - last;
            if (diff == 0) {
                continue;
            }
            if (delta > 0 && diff > 0) {
                entry.setValue(Math.max(0, delta - diff));
                changed = true;
            } else if (delta < 0 && diff < 0) {
                entry.setValue(Math.min(0, delta - diff));
                changed = true;
            }
            lastLive.put(aspect, live);
        }
        if (changed) {
            idleTicks = 0;
            stalls = 0;
        } else if (++idleTicks > 100) {
            // 5 秒都没有任何同步（服务器拒绝 / 丢包）：以服务端为准，清掉记账避免卡死
            pending.clear();
            idleTicks = 0;
            if (++stalls >= 2) {
                state = State.FAILED;
                failReason = "no-sync";
            }
        }
    }

    private void incrementNeed(Aspect aspect, int amount) {
        Integer current = need.get(aspect);
        need.put(aspect, (current == null ? 0 : current) + amount);
    }

    private void recount() {
        int total = placements.size();
        for (Map.Entry<Aspect, Integer> entry : need.entrySet()) {
            // 只把“还缺多少”算进总步数，否则进度条会从 3/42 这种奇怪的比例开始
            total += Math.max(0, entry.getValue() - available(entry.getKey()));
        }
        totalSteps = Math.max(total, 1);
    }

    public State state() {
        return state;
    }

    public boolean isRunning() {
        return state == State.RUNNING;
    }

    public String failReason() {
        return failReason;
    }

    public int stepsDone() {
        return stepsDone;
    }

    public int totalSteps() {
        return totalSteps;
    }

    public int placementsDone() {
        return placementsDone;
    }

    public String lastAction() {
        return lastAction;
    }

    public List<Aspect> missingNeeds() {
        List<Aspect> list = new ArrayList<Aspect>();
        for (Map.Entry<Aspect, Integer> entry : need.entrySet()) {
            if (available(entry.getKey()) < entry.getValue()) {
                list.add(entry.getKey());
            }
        }
        return list;
    }

    private static final class Placement {
        final HexUtils.Hex hex;
        final Aspect aspect;

        Placement(HexUtils.Hex hex, Aspect aspect) {
            this.hex = hex;
            this.aspect = aspect;
        }
    }

    private static final class Action {
        final Aspect parent;
        final Aspect first;
        final Aspect second;
        final HexUtils.Hex hex;
        final Aspect aspect;

        private Action(Aspect parent, Aspect first, Aspect second, HexUtils.Hex hex, Aspect aspect) {
            this.parent = parent;
            this.first = first;
            this.second = second;
            this.hex = hex;
            this.aspect = aspect;
        }

        static Action combine(Aspect parent, Aspect first, Aspect second) {
            return new Action(parent, first, second, null, null);
        }

        static Action place(HexUtils.Hex hex, Aspect aspect) {
            return new Action(null, null, null, hex, aspect);
        }
    }
}
