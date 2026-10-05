package com.github.shikejing.artifactdistributionjava.tests;

import com.github.shikejing.artifactdistributionjava.dist.DistributionService;
import com.github.shikejing.artifactdistributionjava.domain.BundleInfo;
import com.github.shikejing.artifactdistributionjava.domain.Manifest;
import com.github.shikejing.artifactdistributionjava.domain.Target;
import com.github.shikejing.artifactdistributionjava.security.AuthService;
import com.github.shikejing.artifactdistributionjava.security.Permission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 兼容性约束：不满足要求的目标被跳过且原因可查，跳过不影响其他目标。
 * 权限：越权操作被拒绝且可追溯。
 */
class CompatibilityAndAuthTest {

    @TempDir
    Path dir;

    @Test
    void incompatibleTargetsAreSkippedWithReasons() {
        TestRig rig = new TestRig(dir);
        rig.registry.register(TestRig.target("ok")); // linux/amd64/2.0
        Target win = new Target("win-box");
        win.setAttributes(Map.of("os", "windows", "arch", "amd64", "runtimeVersion", "2.0"));
        rig.registry.register(win);
        Target old = new Target("old-runtime");
        old.setAttributes(Map.of("os", "linux", "arch", "amd64", "runtimeVersion", "1.0"));
        rig.registry.register(old);

        Manifest m = TestRig.manifest("app", "1.0", TestRig.resource("a.txt", "x"));
        m.setRequirement(TestRig.requirement("linux", "amd64", "2.0"));
        BundleInfo b = rig.builder.buildInfo(m);
        rig.store.saveBundle(b, rig.builder.build(m));

        DistributionService.RolloutResult r = rig.distribution.distribute(
                "ops", b.getBundleId(), List.of("ok", "win-box", "old-runtime"), 1);

        assertEquals("INSTALLED", r.outcomes().get(0).status());
        assertEquals("SKIPPED", r.outcomes().get(1).status());
        assertTrue(r.outcomes().get(1).reason().contains("os mismatch"), r.outcomes().get(1).reason());
        assertEquals("SKIPPED", r.outcomes().get(2).status());
        assertTrue(r.outcomes().get(2).reason().contains("runtimeVersion too low"),
                r.outcomes().get(2).reason());
        assertFalse(r.rolledBack());

        // 跳过不影响 ok 目标推进；被跳过目标上没有版本
        assertEquals(b.getBundleId(), rig.distribution.currentVersionOf("ok", "app").orElseThrow());
        assertTrue(rig.distribution.currentVersionOf("win-box", "app").isEmpty());
        // 原因可查：审计中有 SKIP 记录
        assertTrue(rig.audit.entries().stream()
                .anyMatch(l -> l.contains("SKIP") && l.contains("win-box")));
    }

    @Test
    void unauthorizedOperationsAreRejectedAndAudited() {
        AuthService auth = new AuthService(
                "admin-token:admin,ops-token:ops,viewer-token:viewer",
                "PACK,DISTRIBUTE,ROLLBACK,VIEW,REGISTER_TARGET",
                "DISTRIBUTE,ROLLBACK,VIEW",
                "VIEW");

        // viewer 越权分发被拒绝
        SecurityException denied = assertThrows(SecurityException.class,
                () -> auth.require("viewer-token", Permission.DISTRIBUTE));
        assertTrue(denied.getMessage().contains("viewer"));
        // 未知令牌被拒绝
        assertThrows(SecurityException.class, () -> auth.require("bad-token", Permission.VIEW));
        // ops 可以分发但不能打包
        auth.require("ops-token", Permission.DISTRIBUTE);
        assertThrows(SecurityException.class, () -> auth.require("ops-token", Permission.PACK));
        // admin 全权限
        auth.require("admin-token", Permission.PACK);
    }
}
