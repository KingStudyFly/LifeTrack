package com.example.lifetrack;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 统一线程池管理，替代散布各处的 new Thread().start()
 *
 * 使用 ThreadPoolExecutor + 有界队列 + CallerRunsPolicy：
 * - 有界队列避免无界堆积导致 OOM
 * - CallerRunsPolicy 在队列满时由调用线程执行任务，提供背压，避免任务被静默丢弃
 */
public final class AppExecutors {

    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int CORE_POOL_SIZE = Math.max(2, Math.min(CPU_COUNT - 1, 4));

    /** IO 密集型任务（数据库读写、文件操作） */
    private static final ExecutorService ioExecutor = new ThreadPoolExecutor(
            CORE_POOL_SIZE, CORE_POOL_SIZE,
            0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(128),
            r -> {
                Thread t = new Thread(r, "lifetrack-io");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    /** 网络请求任务 */
    private static final ExecutorService networkExecutor = new ThreadPoolExecutor(
            3, 3,
            0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(64),
            r -> {
                Thread t = new Thread(r, "lifetrack-network");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    /**
     * 单线程串行任务（预加载等需要顺序执行的场景）
     * corePoolSize=maxPoolSize=1 保证串行；有界队列避免无界堆积。
     */
    private static final ExecutorService singleExecutor = new ThreadPoolExecutor(
            1, 1,
            0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(64),
            r -> {
                Thread t = new Thread(r, "lifetrack-single");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    private AppExecutors() {}

    public static ExecutorService io() {
        return ioExecutor;
    }

    public static ExecutorService network() {
        return networkExecutor;
    }

    public static ExecutorService single() {
        return singleExecutor;
    }
}
