package com.emil.tcautoresearchunofficial.solver;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import com.emil.tcautoresearchunofficial.Config;
import com.emil.tcautoresearchunofficial.TCAutoResearchUnofficial;

/**
 * 求解器进程的统一入口。
 *
 * <p>与老实现的差别：</p>
 * <ul>
 *   <li>单线程池，永远只有一个求解进程，不会因为反复点按钮而堆线程；</li>
 *   <li>不再调用 {@code tasklist} / {@code taskkill}（老版本每次求解都会起一个
 *       {@code tasklist} 进程，还依赖系统语言与命令行输出格式）；</li>
 *   <li>取消与超时直接 {@code destroy()} / {@code destroyForcibly()}；</li>
 *   <li>stderr 重定向到日志文件，出问题能查；</li>
 *   <li>回调仍在线程池里执行，调用方负责用 {@code ClientTasks} 切回主线程。</li>
 * </ul>
 */
public final class SolverService {

    public interface Callback {
        /** 求解器正常结束，返回它输出的原始文本行。 */
        void onResult(List<String> lines);

        /** 求解失败：平台不支持、超时、进程异常等。 */
        void onFailure(String reason);
    }

    private static final SolverService INSTANCE = new SolverService();

    public static SolverService get() {
        return INSTANCE;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "TCAutoResearchUnofficial-Solver");
            thread.setDaemon(true);
            return thread;
        }
    });

    private volatile Process current;
    private volatile boolean busy;
    private volatile boolean cancelled;
    private volatile boolean finished;

    private SolverService() {
    }

    public boolean isBusy() {
        return busy;
    }

    public void submit(final String payload, final Callback callback) {
        synchronized (this) {
            if (busy) {
                callback.onFailure("busy");
                return;
            }
            busy = true;
            cancelled = false;
            // 每次提交都要重置，否则上一轮的看门狗标志会让新任务永远不会超时退出
            finished = false;
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                execute(payload, callback);
            }
        });
    }

    /** 终止当前求解（换笔记、关界面、手动取消都会调用）。 */
    public void cancel() {
        cancelled = true;
        Process process = current;
        if (process != null) {
            try {
                process.destroy();
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    private void execute(String payload, Callback callback) {
        File executable;
        try {
            executable = AutoResearchBinary.ensureExtracted();
        } catch (Throwable t) {
            finish(callback, null, "solver-missing: " + t.getMessage());
            return;
        }

        List<String> lines = new ArrayList<String>();
        String failure = null;
        Process process = null;
        Thread watchdog = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(executable.getAbsolutePath(), payload);
            builder.directory(executable.getParentFile());
            File log = new File(executable.getParentFile(), "solver.log");
            builder.redirectError(ProcessBuilder.Redirect.appendTo(log));
            process = builder.start();
            current = process;
            try {
                process.getOutputStream().close();
            } catch (Throwable ignored) {
                // 求解器不读 stdin
            }

            final Process watchTarget = process;
            watchdog = new Thread(new Runnable() {
                @Override
                public void run() {
                    long deadline = System.currentTimeMillis() + Config.solverTimeoutSeconds * 1000L;
                    while (!finished && System.currentTimeMillis() < deadline) {
                        try {
                            Thread.sleep(200L);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    if (!finished) {
                        TCAutoResearchUnofficial.LOG.warn("求解器超过 {} 秒未结束，强制终止",
                                Config.solverTimeoutSeconds);
                        try {
                            watchTarget.destroyForcibly();
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                    }
                }
            }, "TCAutoResearchUnofficial-SolverWatchdog");
            watchdog.setDaemon(true);
            watchdog.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        lines.add(line);
                    }
                }
            } finally {
                reader.close();
            }

            int code = process.waitFor();
            if (cancelled) {
                failure = "cancelled";
            } else if (code != 0) {
                failure = "exit=" + code;
            }
        } catch (Throwable t) {
            failure = t.getClass().getSimpleName() + ": " + t.getMessage();
            TCAutoResearchUnofficial.LOG.error("求解器执行失败", t);
        } finally {
            finished = true;
            if (watchdog != null) {
                watchdog.interrupt();
            }
            current = null;
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        }

        if (failure != null || lines.isEmpty()) {
            finish(callback, null, failure == null ? "empty-output" : failure);
        } else {
            finish(callback, lines, null);
        }
    }

    private void finish(Callback callback, List<String> lines, String failure) {
        busy = false;
        try {
            if (failure != null) {
                callback.onFailure(failure);
            } else {
                callback.onResult(lines);
            }
        } catch (Throwable t) {
            TCAutoResearchUnofficial.LOG.error("求解回调异常", t);
        }
    }
}
