package me.ddggdd135.slimeae.tasks;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import me.ddggdd135.slimeae.SlimeAEPlugin;
import me.ddggdd135.slimeae.api.enums.AETaskType;
import me.ddggdd135.slimeae.api.events.AEPostTaskEvent;
import me.ddggdd135.slimeae.api.events.AEPreTaskEvent;
import me.ddggdd135.slimeae.api.interfaces.IMEObject;
import me.ddggdd135.slimeae.core.NetworkInfo;
import org.bukkit.Bukkit;

/**
 * 周期性触发"耗时网络 tick", 例如链式总线的 take/push, 各 ME 设备的工作.
 * <p>本任务自身仍由 Bukkit 异步调度器派发(每周期一次),
 * 但每个 NetworkInfo 的迭代被分发到内置线程池, 从而让多个网络并行 tick.
 * 网络内串行执行, 由 NetworkInfo.storageLock 保证存储原子性.</p>
 */
public class NetworkTimeConsumingTask implements Runnable {
    private int tickRate;
    private boolean halted = false;

    private volatile boolean paused = false;

    private ExecutorService workerPool;
    private int poolSize;

    public void start(@Nonnull SlimeAEPlugin plugin) {
        this.tickRate = Slimefun.getCfg().getInt("URID.custom-ticker-delay");

        int configured = plugin.getConfig().getInt("network-time-consuming.threads", 0);
        if (configured <= 0) {
            configured = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        }
        this.poolSize = configured;
        this.workerPool = createPool(this.poolSize);

        Bukkit.getScheduler().runTaskLaterAsynchronously(SlimeAEPlugin.getInstance(), this, 10);
    }

    private static ExecutorService createPool(int size) {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "SlimeAE-NetWorker-" + counter.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        };
        return new ThreadPoolExecutor(size, size, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), tf);
    }

    @Override
    public void run() {
        long startTime = System.currentTimeMillis();

        run0();

        if (halted) return;

        long elapsed = System.currentTimeMillis() - startTime;
        long nextDelay = Math.max(tickRate * 50L - elapsed, 0) / 50;

        Bukkit.getScheduler().runTaskLaterAsynchronously(SlimeAEPlugin.getInstance(), this, nextDelay);
    }

    public void run0() {
        if (paused) {
            return;
        }

        try {
            if (halted) return;

            AEPreTaskEvent preTaskEventEvent = new AEPreTaskEvent(AETaskType.NETWORK_TIME_CONSUMING);
            Bukkit.getPluginManager().callEvent(preTaskEventEvent);
            if (preTaskEventEvent.isCancelled()) return;

            Set<NetworkInfo> allNetworkData = new HashSet<>(SlimeAEPlugin.getNetworkData().AllNetworkData);

            ExecutorService pool = this.workerPool;
            if (pool == null || pool.isShutdown()) {
                // 池未就绪或已关闭, 退化为当前线程串行执行, 保证插件可用.
                for (NetworkInfo networkInfo : allNetworkData) {
                    tickOneNetwork(networkInfo);
                }
            } else {
                List<Future<?>> futures = new ArrayList<>(allNetworkData.size());
                for (NetworkInfo networkInfo : allNetworkData) {
                    futures.add(pool.submit(() -> tickOneNetwork(networkInfo)));
                }
                // 等待全部网络 tick 完成, 让本周期 elapsed 测量准确, 避免下一周期叠加导致积压.
                for (Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Exception ex) {
                        SlimeAEPlugin.getInstance()
                                .getLogger()
                                .log(Level.SEVERE, ex, () -> "Worker exception while ticking a network");
                    }
                }
            }

            AEPostTaskEvent postTaskEvent = new AEPostTaskEvent(AETaskType.NETWORK_TIME_CONSUMING);
            Bukkit.getPluginManager().callEvent(postTaskEvent);
        } catch (Exception | LinkageError x) {
            SlimeAEPlugin.getInstance()
                    .getLogger()
                    .log(Level.SEVERE, x, () -> "An Exception was caught while ticking Networks for SlimeAE");
        }
    }

    private void tickOneNetwork(@Nonnull NetworkInfo networkInfo) {
        if (networkInfo.isDisposed()) return;
        try {
            // 网络内部串行: NetworkInfo.storageLock 已经保证存储原子性,
            // 但 ChainedBus 之间的 inventory 写入也不应并发踩同一目标容器, 故此处不再二级并行.
            for (org.bukkit.Location loc : networkInfo.getChildren()) {
                IMEObject slimefunItem =
                        SlimeAEPlugin.getNetworkData().AllNetworkBlocks.get(loc);
                if (slimefunItem == null) continue;
                try {
                    slimefunItem.onNetworkTimeConsumingTick(loc.getBlock(), networkInfo);
                } catch (Exception | LinkageError perObj) {
                    SlimeAEPlugin.getInstance()
                            .getLogger()
                            .log(Level.SEVERE, perObj, () -> "Exception while ticking block at " + loc);
                }
            }
        } catch (Exception | LinkageError netEx) {
            SlimeAEPlugin.getInstance().getLogger().log(Level.SEVERE, netEx, () -> "Exception while ticking network");
        }
    }

    public boolean isHalted() {
        return halted;
    }

    public void halt() {
        halted = true;
        ExecutorService pool = this.workerPool;
        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException ie) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
            this.workerPool = null;
        }
    }

    public int getTickRate() {
        return tickRate;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    /**
     * 暴露给外部诊断: 当前工作线程池是否仍然存活.
     */
    public boolean isPoolAlive() {
        ExecutorService pool = this.workerPool;
        return pool != null && !pool.isShutdown();
    }

    /**
     * 兼容旧调用点: 旧实现没有 isRunning 字段, 这里近似为"未停止且未暂停".
     */
    public boolean isRunning() {
        return !halted && !paused;
    }
}
