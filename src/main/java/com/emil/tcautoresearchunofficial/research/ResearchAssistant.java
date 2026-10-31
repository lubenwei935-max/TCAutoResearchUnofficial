package com.emil.tcautoresearchunofficial.research;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

import com.emil.tcautoresearchunofficial.Config;
import com.emil.tcautoresearchunofficial.TCAutoResearchUnofficial;
import com.emil.tcautoresearchunofficial.solver.AutoResearchBinary;
import com.emil.tcautoresearchunofficial.solver.SolverService;
import com.emil.tcautoresearchunofficial.util.ClientTasks;
import com.emil.tcautoresearchunofficial.util.Txt;

import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.client.gui.GuiResearchTable;
import thaumcraft.client.lib.PlayerNotifications;
import thaumcraft.common.lib.research.ResearchManager;
import thaumcraft.common.lib.research.ResearchNoteData;
import thaumcraft.common.lib.utils.HexUtils;

/**
 * 研究台助手：把「界面事件 / 求解器 / 分步执行」串起来的唯一状态机。
 *
 * <p>所有方法都只能在客户端主线程调用（{@link #tick()} 由 tick 事件驱动，
 * 求解器回调先经 {@link ClientTasks} 切回主线程）。</p>
 */
public final class ResearchAssistant {

    private static final ResearchAssistant INSTANCE = new ResearchAssistant();

    private GuiResearchTable table;
    private PlanRunner runner;
    private AspectList knowledge = new AspectList();

    private int pollCounter;
    private String autoHandledSig = "";
    private String pendingSig = "";
    private List<String> lastSolutionLines;
    private String lastSolutionSig = "";

    /** 预览中的方案（等待玩家确认），非 null 表示处于预览模式。 */
    private Map<HexUtils.Hex, Aspect> pendingPlan;
    private PlanEvaluator.Result pendingEvaluation;
    /** 当前笔记已经重解的次数（用于「校验不通过就重解」）。 */
    private int solveAttempts;

    private String message = "";
    private int messageTicks;

    private ResearchAssistant() {
    }

    public static ResearchAssistant get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ 生命周期

    public void attach(GuiResearchTable gui) {
        if (table == gui) {
            return;
        }
        detach();
        table = gui;
        knowledge = ResearchTableAccess.knowledge();
        autoHandledSig = "";
        message = "";
        messageTicks = 0;
        solveAttempts = 0;
    }

    public void detach() {
        if (runner != null) {
            runner.cancel();
            runner = null;
        }
        SolverService.get().cancel();
        table = null;
        pendingSig = "";
        pendingPlan = null;
        pendingEvaluation = null;
    }

    public boolean isAttached() {
        return table != null;
    }

    public GuiResearchTable gui() {
        return table;
    }

    public AspectList knowledge() {
        return knowledge;
    }

    public int have(Aspect aspect) {
        return knowledge.getAmount(aspect);
    }

    public PlanRunner runner() {
        return runner;
    }

    public String message() {
        return messageTicks > 0 ? message : "";
    }

    // ------------------------------------------------------------------ 每帧驱动

    public void tick() {
        if (table == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != table) {
            detach();
            return;
        }
        if (messageTicks > 0) {
            messageTicks--;
        }
        if (++pollCounter >= 10) {
            pollCounter = 0;
            knowledge = ResearchTableAccess.knowledge();
        }

        if (runner != null) {
            runner.tick();
            if (!runner.isRunning()) {
                concludeRunner();
            }
        }

        if (Config.autoSolve && runner == null && !SolverService.get().isBusy()) {
            ResearchNoteData note = ResearchTableAccess.note(table);
            if (note != null && !note.complete) {
                String sig = signature(note);
                if (!sig.equals(autoHandledSig)) {
                    autoHandledSig = sig;
                    solveAttempts = 0;
                    solveCurrent(true);
                }
            }
        }
    }

    private void concludeRunner() {
        PlanRunner finished = runner;
        runner = null;
        if (finished == null) {
            return;
        }
        switch (finished.state()) {
            case DONE:
                if (Config.notifyOnFinish) {
                    switch (finished.kind()) {
                        case SOLVE:
                            toast(Txt.tr("tcautoresearchunofficial.msg.done", finished.placementsDone()));
                            break;
                        case UNLOCK_ALL:
                            toast(Txt.tr("tcautoresearchunofficial.msg.allunlocked"));
                            break;
                        default:
                            toast(Txt.tr("tcautoresearchunofficial.msg.combined", finished.stepsDone()));
                            break;
                    }
                }
                break;
            case FAILED:
                toast(Txt.tr("tcautoresearchunofficial.msg.failed", describeFailure(finished.failReason())));
                break;
            default:
                break;
        }
    }

    private String describeFailure(String reason) {
        if (reason == null) {
            return "?";
        }
        if (reason.startsWith("missing-base:")) {
            return Txt.tr("tcautoresearchunofficial.msg.missingbase",
                    Txt.aspectName(Aspect.getAspect(reason.substring("missing-base:".length()))));
        }
        if ("no-sync".equals(reason)) {
            return Txt.tr("tcautoresearchunofficial.msg.nosync");
        }
        return reason;
    }

    // ------------------------------------------------------------------ 用户操作

    public void toggleAuto() {
        Config.setAuto(!Config.autoSolve);
        if (Config.autoSolve) {
            autoHandledSig = "";
            toast(Txt.tr("tcautoresearchunofficial.msg.autoon"));
        } else {
            toast(Txt.tr("tcautoresearchunofficial.msg.autooff"));
        }
    }

    public void solveCurrent() {
        solveAttempts = 0;
        solveCurrent(false);
    }

    private void solveCurrent(boolean silent) {
        if (table == null) {
            return;
        }
        ResearchNoteData note = ResearchTableAccess.note(table);
        if (note == null) {
            toast(Txt.tr("tcautoresearchunofficial.msg.nonote"));
            return;
        }
        if (note.complete) {
            toast(Txt.tr("tcautoresearchunofficial.msg.complete"));
            return;
        }
        if (SolverService.get().isBusy() || runner != null) {
            toast(Txt.tr("tcautoresearchunofficial.msg.busy"));
            return;
        }
        if (!AutoResearchBinary.isSupportedPlatform()) {
            toast(Txt.tr("tcautoresearchunofficial.msg.unsupported"));
            return;
        }
        EntityPlayer player = ResearchTableAccess.player();
        if (player == null) {
            return;
        }

        final String payload = SolvePayload.build(note, ResearchTableAccess.knowledge(player));
        if (payload.length() > 30000) {
            toast(Txt.tr("tcautoresearchunofficial.msg.toolong"));
            return;
        }
        pendingSig = signature(note);
        if (!silent) {
            toast(Txt.tr("tcautoresearchunofficial.msg.solving"));
        }

        SolverService.get().submit(payload, new SolverService.Callback() {
            @Override
            public void onResult(final List<String> lines) {
                ClientTasks.run(new Runnable() {
                    @Override
                    public void run() {
                        applySolution(lines);
                    }
                });
            }

            @Override
            public void onFailure(final String reason) {
                ClientTasks.run(new Runnable() {
                    @Override
                    public void run() {
                        pendingSig = "";
                        if (reason != null && reason.startsWith("cancelled")) {
                            // 用户主动关界面 / 点停止，不需要再弹错误
                            return;
                        }
                        toast(Txt.tr("tcautoresearchunofficial.msg.solverfail", reason));
                    }
                });
            }
        });
    }

    public void replayLastSolution() {
        if (table == null || lastSolutionLines == null || lastSolutionLines.isEmpty()) {
            toast(Txt.tr("tcautoresearchunofficial.msg.nolastsolution"));
            return;
        }
        ResearchNoteData note = ResearchTableAccess.note(table);
        if (note == null) {
            toast(Txt.tr("tcautoresearchunofficial.msg.nonote"));
            return;
        }
        if (!lastSolutionSig.equals(signature(note))) {
            toast(Txt.tr("tcautoresearchunofficial.msg.differentnote"));
            return;
        }
        if (runner != null) {
            toast(Txt.tr("tcautoresearchunofficial.msg.busy"));
            return;
        }
        List<Candidate> candidates = buildCandidates(lastSolutionLines);
        if (candidates.isEmpty()) {
            toast(Txt.tr("tcautoresearchunofficial.msg.solverfail", "unparsable"));
            return;
        }
        Candidate best = pickBest(candidates);
        if (best == null) {
            toast(Txt.tr("tcautoresearchunofficial.msg.invalidGiveUp", 0));
            return;
        }
        startPlan(best.plan);
    }

    public void unlockAllAspects() {
        if (table == null) {
            return;
        }
        if (runner != null) {
            toast(Txt.tr("tcautoresearchunofficial.msg.busy"));
            return;
        }
        runner = PlanRunner.forUnlockAll(table, Config.actionIntervalTicks);
        if (!runner.isRunning()) {
            if ("all-known".equals(runner.failReason())) {
                runner = null;
                toast(Txt.tr("tcautoresearchunofficial.msg.allknown"));
            } else {
                concludeRunner();
            }
        }
    }

    public void combineAspect(Aspect aspect, int count) {
        if (table == null || aspect == null) {
            return;
        }
        if (runner != null) {
            toast(Txt.tr("tcautoresearchunofficial.msg.busy"));
            return;
        }
        runner = PlanRunner.forCombine(table, aspect, count, Config.actionIntervalTicks);
        if (!runner.isRunning()) {
            runner = null;
            toast(Txt.tr("tcautoresearchunofficial.msg.enough"));
        }
    }

    public void stop() {
        if (runner != null) {
            runner.cancel();
            runner = null;
            toast(Txt.tr("tcautoresearchunofficial.msg.stopped"));
        } else if (SolverService.get().isBusy()) {
            SolverService.get().cancel();
            toast(Txt.tr("tcautoresearchunofficial.msg.stopped"));
        }
    }

    // ------------------------------------------------------------------ 求解结果

    private void applySolution(List<String> lines) {
        if (table == null) {
            return;
        }
        lastSolutionLines = new ArrayList<String>(lines);
        lastSolutionSig = pendingSig;
        pendingSig = "";

        List<Candidate> candidates = buildCandidates(lines);
        if (candidates.isEmpty()) {
            toast(Txt.tr("tcautoresearchunofficial.msg.solverfail", "unparsable"));
            return;
        }
        Candidate best = pickBest(candidates);
        if (best == null) {
            // 没有任何一套方案能通过研究台规则校验 -> 重解
            if (solveAttempts < Config.solverRetries) {
                solveAttempts++;
                toast(Txt.tr("tcautoresearchunofficial.msg.invalidRetry", solveAttempts, Config.solverRetries));
                solveCurrent(true);
            } else {
                solveAttempts = 0;
                toast(Txt.tr("tcautoresearchunofficial.msg.invalidGiveUp", Config.solverRetries));
            }
            return;
        }
        solveAttempts = 0;
        if (Config.previewPlan) {
            // 预览模式：先给玩家看消耗清单和格子虚影，确认后才真正执行
            pendingPlan = best.plan;
            pendingEvaluation = best.evaluation;
            toast(Txt.tr("tcautoresearchunofficial.msg.preview"));
            return;
        }
        startPlan(best.plan);
    }

    /** 求解器可能一次输出多行候选，每行是一套完整方案。 */
    private List<Candidate> buildCandidates(List<String> lines) {
        List<Candidate> candidates = new ArrayList<Candidate>();
        ResearchNoteData note = ResearchTableAccess.note(table);
        if (note == null) {
            return candidates;
        }
        AspectList pool = ResearchTableAccess.knowledge();
        for (String line : lines) {
            Map<String, String> raw = SolvePayload.parseLines(java.util.Collections.singletonList(line));
            if (raw.isEmpty()) {
                continue;
            }
            Map<HexUtils.Hex, Aspect> plan = new LinkedHashMap<HexUtils.Hex, Aspect>();
            for (Map.Entry<String, String> entry : raw.entrySet()) {
                HexUtils.Hex hex = note.hexes.get(entry.getKey());
                Aspect aspect = Aspect.getAspect(entry.getValue());
                if (hex != null && aspect != null) {
                    plan.put(hex, aspect);
                }
            }
            if (plan.isEmpty()) {
                continue;
            }
            PlanEvaluator.Result evaluation = PlanEvaluator.evaluate(plan, pool);
            PlanValidator.Verdict verdict = PlanValidator.validate(note, plan, pool);
            if (!verdict.valid) {
                TCAutoResearchUnofficial.LOG.info("丢弃未通过校验的候选方案: {}", verdict.reason);
            }
            candidates.add(new Candidate(plan, evaluation, verdict));
        }
        return candidates;
    }

    /**
     * 候选评分：只考虑通过校验的方案，要素消耗最少优先；
     * 开启「优先现成要素」时额外偏向少合成的方案。
     */
    private Candidate pickBest(List<Candidate> candidates) {
        Candidate best = null;
        for (Candidate candidate : candidates) {
            if (!candidate.verdict.valid) {
                continue;
            }
            if (best == null || candidate.evaluation.score(Config.preferExisting)
                    < best.evaluation.score(Config.preferExisting)) {
                best = candidate;
            }
        }
        return best;
    }

    private void startPlan(Map<HexUtils.Hex, Aspect> plan) {
        if (table == null) {
            return;
        }
        if (plan == null || plan.isEmpty()) {
            toast(Txt.tr("tcautoresearchunofficial.msg.solverfail", "unparsable"));
            return;
        }
        runner = PlanRunner.forPlacements(table, plan, Config.actionIntervalTicks);
        if (!runner.isRunning()) {
            concludeRunner();
        }
    }

    // ------------------------------------------------------------------ 方案预览

    public boolean isPreviewing() {
        return pendingPlan != null;
    }

    public Map<HexUtils.Hex, Aspect> previewPlan() {
        return pendingPlan;
    }

    public PlanEvaluator.Result previewEvaluation() {
        return pendingEvaluation;
    }

    public void confirmPreview() {
        if (pendingPlan == null) {
            return;
        }
        Map<HexUtils.Hex, Aspect> plan = pendingPlan;
        pendingPlan = null;
        pendingEvaluation = null;
        startPlan(plan);
    }

    public void cancelPreview() {
        if (pendingPlan == null) {
            return;
        }
        pendingPlan = null;
        pendingEvaluation = null;
        toast(Txt.tr("tcautoresearchunofficial.msg.planCancelled"));
    }

    private static final class Candidate {
        final Map<HexUtils.Hex, Aspect> plan;
        final PlanEvaluator.Result evaluation;
        final PlanValidator.Verdict verdict;

        Candidate(Map<HexUtils.Hex, Aspect> plan, PlanEvaluator.Result evaluation, PlanValidator.Verdict verdict) {
            this.plan = plan;
            this.evaluation = evaluation;
            this.verdict = verdict;
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 笔记身份：只取「研究键 + 格子布局」，放/合成要素不会改变它。 */
    public static String signature(ResearchNoteData note) {
        if (note == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(64);
        // 注意：不要把 complete 算进来，否则“最后一步放下后笔记完成”会被当成换了笔记
        sb.append(note.key == null ? "?" : note.key)
                .append('|').append(note.hexes == null ? 0 : note.hexes.size());
        int hash = 0;
        if (note.hexes != null) {
            for (String path : note.hexes.keySet()) {
                hash += path.hashCode();
            }
        }
        return sb.append('|').append(hash).toString();
    }

    private void toast(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        message = text;
        messageTicks = 120;
        try {
            PlayerNotifications.addNotification(text);
        } catch (Throwable t) {
            TCAutoResearchUnofficial.LOG.warn("提示发送失败: {}", t.toString());
        }
        TCAutoResearchUnofficial.LOG.info("[TCAutoResearchUnofficial] {}", text);
    }

    /** 供界面显示：当前是否还有未完成的目标（用于进度条）。 */
    public float progress() {
        PlanRunner current = runner;
        if (current == null || current.totalSteps() <= 0) {
            return 0F;
        }
        return Math.min(1F, current.stepsDone() / (float) current.totalSteps());
    }

    public String statusLine() {
        if (isPreviewing()) {
            return Txt.tr("tcautoresearchunofficial.status.preview");
        }
        if (SolverService.get().isBusy()) {
            return Txt.tr("tcautoresearchunofficial.status.solving");
        }
        PlanRunner current = runner;
        if (current != null) {
            return Txt.tr("tcautoresearchunofficial.status.running", current.stepsDone(), current.totalSteps());
        }
        ResearchNoteData note = ResearchTableAccess.note(table);
        if (note != null && note.complete) {
            return Txt.tr("tcautoresearchunofficial.status.complete");
        }
        return Txt.tr(Config.autoSolve ? "tcautoresearchunofficial.status.auto" : "tcautoresearchunofficial.status.idle");
    }

    /** 研究台被替换（例如服务端重置笔记）时清理残留状态。 */
    public void onNoteChanged() {
        if (runner != null) {
            runner.cancel();
            runner = null;
        }
        SolverService.get().cancel();
        pendingPlan = null;
        pendingEvaluation = null;
        autoHandledSig = "";
    }
}
