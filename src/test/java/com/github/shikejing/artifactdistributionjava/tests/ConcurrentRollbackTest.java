package com.github.shikejing.artifactdistributionjava.tests;

import com.github.shikejing.artifactdistributionjava.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景：分发与回滚并发。
 * 同一目标在并发交错下，任一时刻都必须安装某个“已提交的完整版本”，
 * 不允许出现资源级混合（一个版本的部分文件 + 另一个版本的部分文件）。
 */
class ConcurrentRollbackTest {

    @TempDir
    Path temp;

    @Test
    void concurrentRolloutsAndRollbacksKeepOnlyCompleteVersions() throws Exception {
        TestEnv env = TestEnv.create(temp);
        User operator = new User("operator", Role.OPERATOR);

        String targetId = "target-concurrent";
        env.targetRegistry().register(new Target(targetId, targetId, 1024L * 1024 * 64,
                Map.of(), true));

        // 准备 6 个版本，每个版本改动两个资源。
        Path sources = temp.resolve("sources");
        PackageMeta[] versions = new PackageMeta[6];
        for (int v = 0; v < versions.length; v++) {
            TestSupport.createSourceTree(sources, Map.of(
                    "conf/a.conf", "version=" + v + "\n",
                    "conf/b.conf", "generation=" + (v * 10) + "\n"));
            Manifest manifest = TestSupport.manifest("concurrent-app", String.valueOf(v),
                    Map.of("a", "conf/a.conf", "b", "conf/b.conf"), Map.of());
            versions[v] = env.packageBuilder().build(manifest, null);
        }

        // 先装 v0。
        env.rolloutService().startAndRun(operator,
                new RolloutPlan(versions[0].packageId(),
                        List.of(new RolloutWave(List.of(targetId))), true));

        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger checks = new AtomicInteger();

        // 观察线程：在整个过程中反复读取 current 指针指向的两个资源，
        // 校验它们始终属于同一个版本（版本号一致、无混搭）。
        for (int i = 0; i < 3; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    long deadline = System.currentTimeMillis() + 4000;
                    Path releases = env.dataDir().resolve("targets").resolve(targetId)
                            .resolve("releases");
                    while (System.currentTimeMillis() < deadline) {
                        Path current = env.installer().currentReleaseDir(targetId).orElse(null);
                        if (current != null) {
                            Path a = current.resolve("resources/conf/a.conf");
                            Path b = current.resolve("resources/conf/b.conf");
                            if (Files.isRegularFile(a) && Files.isRegularFile(b)) {
                                String av = Files.readString(a);
                                String bv = Files.readString(b);
                                checks.incrementAndGet();
                                // b = version*10，校验两个文件来自同一版本。
                                int version = Integer.parseInt(
                                        av.replace("version=", "").trim());
                                String expectedB = "generation=" + (version * 10) + "\n";
                                if (!expectedB.equals(bv)) {
                                    errors.incrementAndGet();
                                }
                                // current 目录名必须等于已登记版本 id（完整版本）。
                                String dirId = current.getFileName().toString();
                                boolean knownVersion = false;
                                for (PackageMeta m : versions) {
                                    if (m.packageId().equals(dirId)) {
                                        knownVersion = true;
                                        break;
                                    }
                                }
                                if (!knownVersion) {
                                    errors.incrementAndGet();
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }

        // 升级线程：依次分发 v1..v5。
        pool.submit(() -> {
            try {
                start.await();
                for (int v = 1; v < versions.length; v++) {
                    env.rolloutService().startAndRun(operator,
                            new RolloutPlan(versions[v].packageId(),
                                    List.of(new RolloutWave(List.of(targetId))), true));
                }
            } catch (Exception e) {
                errors.incrementAndGet();
            }
        });

        // 回滚线程：反复手动回滚到上一版本。
        pool.submit(() -> {
            try {
                start.await();
                long deadline = System.currentTimeMillis() + 3500;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        env.rollbackService().rollbackTarget(operator, targetId);
                    } catch (Exception acceptable) {
                        // 没有更早版本可回滚时会抛异常，属于正常竞争结果。
                    }
                }
            } catch (Exception e) {
                errors.incrementAndGet();
            }
        });

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertTrue(checks.get() > 100, "观察次数应足够多，实际=" + checks.get());
        assertTrue(errors.get() == 0,
                "并发过程中发现资源级混合或非完整版本，错误次数=" + errors.get());

        // 最终状态必须是某个完整版本，且 state 与指针一致。
        InstallRecord finalRecord = env.stateStore().current(targetId).orElseThrow();
        Path finalDir = env.installer().currentReleaseDir(targetId).orElseThrow();
        assertTrue(finalDir.getFileName().toString().equals(finalRecord.packageId()));
    }
}
