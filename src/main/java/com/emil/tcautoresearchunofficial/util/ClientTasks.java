package com.emil.tcautoresearchunofficial.util;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * 从任意线程投递任务到客户端主线程。
 *
 * <p>这是本次重构的核心安全约束：工作线程（求解器进程读取线程等）<b>绝不</b>直接触碰
 * {@code Minecraft} / 神秘时代的 GUI 对象。老版本正是在后台线程里调用
 * {@code mc.displayGuiScreen(...)}，把界面在渲染中途换掉，导致 NEI 的
 * {@code GuiContainerManager} 被清空，最终触发 NeverEnoughAnimations 的空指针崩端。</p>
 */
public final class ClientTasks {

    private static final Queue<Runnable> QUEUE = new ArrayDeque<Runnable>();

    private ClientTasks() {
    }

    public static void run(Runnable task) {
        if (task == null) {
            return;
        }
        synchronized (QUEUE) {
            QUEUE.add(task);
        }
    }

    /** 只在客户端 tick 里调用。 */
    public static void drain() {
        for (;;) {
            Runnable task;
            synchronized (QUEUE) {
                task = QUEUE.poll();
            }
            if (task == null) {
                return;
            }
            try {
                task.run();
            } catch (Throwable t) {
                com.emil.tcautoresearchunofficial.TCAutoResearchUnofficial.LOG.error("客户端任务执行失败", t);
            }
        }
    }

    public static void clear() {
        synchronized (QUEUE) {
            QUEUE.clear();
        }
    }
}
