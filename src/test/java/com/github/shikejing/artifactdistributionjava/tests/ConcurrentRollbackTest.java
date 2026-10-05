package com.github.shikejing.artifactdistributionjava.tests;

import com.github.shikejing.artifactdistributionjava.domain.BundleInfo;
import com.github.shikejing.artifactdistributionjava.domain.InstallRecord;
import com.github.shikejing.artifactdistributionjava.domain.InstallState;
import com.github.shikejing.artifactdistributionjava.domain.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发回滚：分发与回滚并发进行时，同一目标在任一时刻安装的必须是
 * 某个已提交的完整版本，不得出现资源级混合。
 */
class ConcurrentRollbackTest {

    @TempDir
    Path dir;

    @Test
    void targetAlwaysAtACompleteCommittedVersion() throws Exception {
        TestRig rig = new TestRig(dir);
        rig.registry.register(TestRig.target("t1"));

        Manifest v1 = TestRig.manifest("app", "1.0", TestRig.resource("a.txt", "v1"));
        Manifest v2 = TestRig.manifest("app", "2.0", TestRig.resource("a.txt", "v2"));
        BundleInfo b1 = rig.builder.buildInfo(v1);
        BundleInfo b2 = rig.builder.buildInfo(v2);
        rig.store.saveBundle(b1, rig.builder.build(v1));
        rig.store.saveBundle(b2, rig.builder.build(v2));

        rig.distribution.distribute("ops", b1.getBundleId(), List.of("t1"), 1);

        Set<String> allowed = Set.of(b1.getBundleId(), b2.getBundleId());
        List<String> violations = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        int rounds = 50;

        Thread distributor = new Thread(() -> {
            await(start);
            for (int i = 0; i < rounds; i++) {
                rig.distribution.distribute("ops", b2.getBundleId(), List.of("t1"), 1);
            }
        });
        Thread rollbacker = new Thread(() -> {
            await(start);
            for (int i = 0; i < rounds; i++) {
                rig.distribution.rollback("ops", "t1", "app");
            }
        });
        Thread observer = new Thread(() -> {
            await(start);
            for (int i = 0; i < 2000; i++) {
                Optional<String> current = rig.distribution.currentVersionOf("t1", "app");
                if (current.isEmpty()) {
                    violations.add("no committed version visible at observation " + i);
                } else if (!allowed.contains(current.get())) {
                    violations.add("current version is not a complete known version: " + current.get());
                }
                // 任一时刻不允许出现 PENDING 之外的两份 COMMITTED 指向未知内容；
                // 且绝不能出现资源级混合：当前版本必须整体等于某个已提交包
                List<InstallRecord> committed = rig.store.installRecordsOf("t1").stream()
                        .filter(r -> r.getState() == InstallState.COMMITTED)
                        .toList();
                for (InstallRecord r : committed) {
                    if (!allowed.contains(r.getBundleId())) {
                        violations.add("committed record of unknown bundle " + r.getBundleId());
                    }
                }
            }
        });

        distributor.start();
        rollbacker.start();
        observer.start();
        start.countDown();
        distributor.join(30_000);
        rollbacker.join(30_000);
        observer.join(30_000);

        assertTrue(violations.isEmpty(), () -> "violations: " + violations.stream().limit(5).toList());
        // 结束后目标仍停在某个完整版本
        assertTrue(rig.distribution.currentVersionOf("t1", "app").isPresent());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
