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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 部分失败：推进过程中任一目标应用失败需停止推进并回到上一个完整版本，
 * 不得留下半应用状态。
 */
class PartialFailureTest {

    @TempDir
    Path dir;

    @Test
    void failureHaltsRolloutAndRollsBackInstalledTargets() {
        TestRig rig = new TestRig(dir);
        rig.registry.register(TestRig.target("t1"));
        rig.registry.register(TestRig.target("t2"));
        rig.registry.register(TestRig.target("t3"));

        Manifest v1 = TestRig.manifest("app", "1.0", TestRig.resource("a.txt", "v1"));
        Manifest v2 = TestRig.manifest("app", "2.0", TestRig.resource("a.txt", "v2"));
        BundleInfo b1 = rig.builder.buildInfo(v1);
        BundleInfo b2 = rig.builder.buildInfo(v2);
        rig.store.saveBundle(b1, rig.builder.build(v1));
        rig.store.saveBundle(b2, rig.builder.build(v2));

        // 先全量铺 v1
        DistributionService.RolloutResult r1 = rig.distribution.distribute(
                "ops", b1.getBundleId(), List.of("t1", "t2", "t3"), 1);
        assertTrue(r1.outcomes().stream().allMatch(o -> "INSTALLED".equals(o.status())));

        // v2 灰度：t2 应用失败
        rig.distribution.setApplyFailurePredicate(tid -> tid.equals("t2"));
        DistributionService.RolloutResult r2 = rig.distribution.distribute(
                "ops", b2.getBundleId(), List.of("t1", "t2", "t3"), 1);

        assertTrue(r2.rolledBack(), "rollout must roll back installed targets");
        assertEquals("INSTALLED", r2.outcomes().get(0).status());
        assertEquals("FAILED", r2.outcomes().get(1).status());
        assertEquals(2, r2.outcomes().size(), "t3 must not be attempted after failure");

        // t1 回到上一个完整版本 v1，t2/t3 仍是 v1
        for (String tid : List.of("t1", "t2", "t3")) {
            assertEquals(b1.getBundleId(),
                    rig.distribution.currentVersionOf(tid, "app").orElseThrow(),
                    tid + " must be back on v1");
        }
        // 不得留下半应用状态：无 PENDING 记录
        List<InstallRecord> pending = rig.store.listInstallRecords().stream()
                .filter(r -> r.getState() == InstallState.PENDING).toList();
        assertTrue(pending.isEmpty(), "no PENDING records may remain");
    }
}
