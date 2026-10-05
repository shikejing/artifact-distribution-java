package com.github.shikejing.artifactdistributionjava.tests;

import com.github.shikejing.artifactdistributionjava.dist.DistributionService;
import com.github.shikejing.artifactdistributionjava.domain.BundleInfo;
import com.github.shikejing.artifactdistributionjava.domain.Manifest;
import com.github.shikejing.artifactdistributionjava.domain.Quota;
import com.github.shikejing.artifactdistributionjava.domain.Target;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配额超限：超出配额时需拒绝安装并给出可核对的拒绝原因；占用可查询。
 */
class QuotaExceededTest {

    @TempDir
    Path dir;

    @Test
    void installRejectedWhenResourceQuotaExceeded() {
        TestRig rig = new TestRig(dir);
        Target t = TestRig.target("t1");
        t.setQuota(new Quota(10, 1, Long.MAX_VALUE)); // 最多 1 个资源
        rig.registry.register(t);

        Manifest m = TestRig.manifest("app", "1.0",
                TestRig.resource("a.txt", "x"), TestRig.resource("b.txt", "y"));
        BundleInfo b = rig.builder.buildInfo(m);
        rig.store.saveBundle(b, rig.builder.build(m));

        DistributionService.RolloutResult r = rig.distribution.distribute(
                "ops", b.getBundleId(), List.of("t1"), 1);
        assertEquals("FAILED", r.outcomes().get(0).status());
        String reason = r.outcomes().get(0).reason();
        assertTrue(reason.contains("resource count quota exceeded"), reason);
        assertTrue(reason.contains("max=1"), reason);
        assertTrue(reason.contains("requested=+2"), reason);
        // 被拒绝后目标上没有版本
        assertTrue(rig.distribution.currentVersionOf("t1", "app").isEmpty());
        // 拒绝原因可核对：审计中有 QUOTA_REJECT 记录
        assertTrue(rig.audit.entries().stream().anyMatch(l -> l.contains("QUOTA_REJECT")));
    }

    @Test
    void installRejectedWhenByteQuotaExceeded() {
        TestRig rig = new TestRig(dir);
        Target t = TestRig.target("t1");
        t.setQuota(new Quota(10, 10, 4)); // 最多 4 字节
        rig.registry.register(t);

        Manifest m = TestRig.manifest("app", "1.0", TestRig.resource("a.txt", "12345"));
        BundleInfo b = rig.builder.buildInfo(m);
        rig.store.saveBundle(b, rig.builder.build(m));

        DistributionService.RolloutResult r = rig.distribution.distribute(
                "ops", b.getBundleId(), List.of("t1"), 1);
        assertEquals("FAILED", r.outcomes().get(0).status());
        assertTrue(r.outcomes().get(0).reason().contains("byte quota exceeded"),
                r.outcomes().get(0).reason());
    }

    @Test
    void usageTrackedPerBundleAndPerResource() {
        TestRig rig = new TestRig(dir);
        Target t = TestRig.target("t1");
        t.setQuota(new Quota(2, 10, 1024));
        rig.registry.register(t);

        Manifest m1 = TestRig.manifest("app-a", "1.0",
                TestRig.resource("a.txt", "aa"), TestRig.resource("b.txt", "bb"));
        Manifest m2 = TestRig.manifest("app-b", "1.0", TestRig.resource("c.txt", "ccc"));
        BundleInfo b1 = rig.builder.buildInfo(m1);
        BundleInfo b2 = rig.builder.buildInfo(m2);
        rig.store.saveBundle(b1, rig.builder.build(m1));
        rig.store.saveBundle(b2, rig.builder.build(m2));

        rig.distribution.distribute("ops", b1.getBundleId(), List.of("t1"), 1);
        rig.distribution.distribute("ops", b2.getBundleId(), List.of("t1"), 1);

        Map<String, ?> usage = rig.quota.usageOf("t1", rig.distribution.installedBundlesOf("t1"));
        assertEquals(2, usage.size(), "每个资源包的占用都可查");

        // 第三个包超出 maxBundles=2，被拒绝
        Manifest m3 = TestRig.manifest("app-c", "1.0", TestRig.resource("d.txt", "d"));
        BundleInfo b3 = rig.builder.buildInfo(m3);
        rig.store.saveBundle(b3, rig.builder.build(m3));
        DistributionService.RolloutResult r = rig.distribution.distribute(
                "ops", b3.getBundleId(), List.of("t1"), 1);
        assertEquals("FAILED", r.outcomes().get(0).status());
        assertTrue(r.outcomes().get(0).reason().contains("bundle count quota exceeded"),
                r.outcomes().get(0).reason());
        assertFalse(r.rolledBack(), "无已安装目标需要回滚");
    }
}
