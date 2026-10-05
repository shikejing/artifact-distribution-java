package com.github.shikejing.artifactdistributionjava.tests;

import com.github.shikejing.artifactdistributionjava.dist.DistributionService;
import com.github.shikejing.artifactdistributionjava.domain.BundleInfo;
import com.github.shikejing.artifactdistributionjava.domain.InstallRecord;
import com.github.shikejing.artifactdistributionjava.domain.InstallState;
import com.github.shikejing.artifactdistributionjava.domain.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重启恢复：未完成的分发与已提交的安装状态必须可区分，
 * 重启后不得重复安装或丢失已推进的目标。
 */
class RestartRecoveryTest {

    @TempDir
    Path dir;

    @Test
    void pendingInstallRecycledAndCommittedSurvivesRestart() {
        TestRig rig = new TestRig(dir);
        rig.registry.register(TestRig.target("t1"));
        rig.registry.register(TestRig.target("t2"));

        Manifest v1 = TestRig.manifest("app", "1.0", TestRig.resource("a.txt", "v1"));
        Manifest v2 = TestRig.manifest("app", "2.0", TestRig.resource("a.txt", "v2"));
        BundleInfo b1 = rig.builder.buildInfo(v1);
        BundleInfo b2 = rig.builder.buildInfo(v2);
        rig.store.saveBundle(b1, rig.builder.build(v1));
        rig.store.saveBundle(b2, rig.builder.build(v2));

        // v1 完整推进到 t1/t2
        rig.distribution.distribute("ops", b1.getBundleId(), List.of("t1", "t2"), 1);

        // v2 推进时 t1 在提交前“进程崩溃”，留下 PENDING 记录
        rig.distribution.setCrashBeforeCommitPredicate(tid -> tid.equals("t1"));
        assertThrows(DistributionService.SimulatedCrashException.class,
                () -> rig.distribution.distribute("ops", b2.getBundleId(), List.of("t1"), 1));
        assertEquals(1, rig.store.listInstallRecords().stream()
                .filter(r -> r.getState() == InstallState.PENDING).count());

        // 模拟进程重启：在同一数据目录上重建整组服务
        TestRig restarted = new TestRig(dir);
        restarted.distribution.recoverAfterRestart();

        // PENDING 被回收为 FAILED，已提交的 v1 不丢失
        assertTrue(restarted.store.listInstallRecords().stream()
                .noneMatch(r -> r.getState() == InstallState.PENDING));
        assertEquals(b1.getBundleId(), restarted.distribution.currentVersionOf("t1", "app").orElseThrow());
        assertEquals(b1.getBundleId(), restarted.distribution.currentVersionOf("t2", "app").orElseThrow());

        // 重启后继续推进 v2：正常安装，不重复安装 v1
        restarted.distribution.distribute("ops", b2.getBundleId(), List.of("t1", "t2"), 1);
        assertEquals(b2.getBundleId(), restarted.distribution.currentVersionOf("t1", "app").orElseThrow());
        assertEquals(b2.getBundleId(), restarted.distribution.currentVersionOf("t2", "app").orElseThrow());

        // v2 在每个目标上只有一次 COMMITTED（崩溃留下的 PENDING 不会重复安装）
        for (String tid : List.of("t1", "t2")) {
            List<InstallRecord> committedV2 = restarted.store.installRecordsOf(tid).stream()
                    .filter(r -> r.getBundleId().equals(b2.getBundleId())
                            && r.getState() == InstallState.COMMITTED)
                    .toList();
            assertEquals(1, committedV2.size(), tid + " must commit v2 exactly once");
        }

        // 再次分发 v2 是幂等的：已是最新版本，不产生新的安装记录
        long recordsBefore = restarted.store.listInstallRecords().size();
        restarted.distribution.distribute("ops", b2.getBundleId(), List.of("t1", "t2"), 1);
        assertEquals(recordsBefore, restarted.store.listInstallRecords().size(),
                "re-distributing the current version must not create new install records");
    }
}
