package dev.zhiku.ingestion;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 轮询持久化任务表，把认领到的任务交给有界的执行器并发处理。测试中禁用，测试直接调用 worker。
 * 不使用 @Async：任务表就是队列；执行器只是并发槽，一个卡住的任务只占一个槽。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "zhiku.ingestion.worker-enabled", havingValue = "true")
class IngestionScheduling {
  /** 一个槽一轮连续处理的任务上限：之后让出槽，下一次轮询再续。 */
  static final int MAX_JOBS_PER_POLL = 20;

  private final IngestionWorker worker;
  private final ExecutorService pool;
  private final Semaphore slots;

  IngestionScheduling(IngestionWorker worker, IngestionSettings settings) {
    this.worker = worker;
    this.slots = new Semaphore(settings.concurrency());
    var counter = new AtomicInteger();
    this.pool = Executors.newFixedThreadPool(settings.concurrency(), r -> {
      var t = new Thread(r, "ingestion-" + counter.incrementAndGet());
      t.setDaemon(true);
      return t;
    });
  }

  /**
   * 每个当前空闲的槽启动一轮处理。只取这一瞬间的空槽数：本轮结束时槽会归还，
   * 不能在同一次轮询里立刻再排一轮，否则上限形同虚设。认领后持有租约，任务不在本地排队等线程。
   */
  @Scheduled(fixedDelayString = "${zhiku.ingestion.poll-interval}")
  void poll() {
    worker.reapExpired();
    int permits = slots.drainPermits();
    int submitted = 0;
    try {
      for (; submitted < permits; submitted++) pool.execute(this::drain);
    } catch (RuntimeException e) {
      slots.release(permits - submitted);
      throw e;
    }
  }

  private void drain() {
    try {
      for (int n = 0; n < MAX_JOBS_PER_POLL; n++) {
        var job = worker.claim();
        if (job.isEmpty()) return;
        worker.process(job.get());
      }
    } finally {
      slots.release();
    }
  }

  @PreDestroy
  void shutdown() {
    pool.shutdownNow();
    try {
      pool.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
