package com.github.shikejing.artifactdistributionjava.tests;

import com.github.shikejing.artifactdistributionjava.exception.QuotaExceededException;
import com.github.shikejing.artifactdistributionjava.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景：
 * <ul>
 *   <li>配额超限：拒绝安装并给出包含配额/占用/需求/超出量的可核对原因；</li>
 *   <li>兼容性：环境不满足的目标被跳过且原因可查，跳过不影响其他目标推进。</li>
 * </ul>
 */
class QuotaAndCompatibilityTest {

    @TempDir
    Path temp;

    private PackageMeta buildV1(TestEnv env) throws java.io.IOException {
        Path sources = temp.resolve("sources");
        TestSupport.createSourceTree(sources, Map.of(
                "big.bin", "0123456789".repeat(100),  // 1000 字节
                "os.conf", "linux\n"));
        return env.packageBuilder().build(
                TestSupport.manifest("quota-app", "1.0",
                        Map.of("big", "big.bin", "osconf", "os.conf"),
                        Map.of("os", "linux", "runtime.min", "17")), null);
    }

    @Test
    void rejectsInstallWhenQuotaExceededWithVerifiableReason() throws Exception {
        TestEnv env = TestEnv.create(temp);
        PackageMeta v1 = buildV1(env);

        // 配额只有 1500 字节，而包需要 1006 字节左右；新装总占用超配额。
        Target small = new Target("small", "small", 500, Map.of("os", "linux", "runtime", "17"), true);
        env.targetRegistry().register(small);

        QuotaExceededException ex = assertThrows(QuotaExceededException.class,
                () -> env.quotaService().checkQuota(small, v1));
        String message = ex.getMessage();
        assertTrue(message.contains("配额=500"), message);
        assertTrue(message.contains("需求=" + v1.contentSize()), message);
        assertTrue(message.contains("超出=" + (v1.contentSize() - 500)), message);
        assertTrue(message.contains("small"), message);

        // 通过分发验证：配额不足目标 FAILED，且同波次其他目标不再推进、整体回滚。
        Target ok = new Target("ok", "ok", 1_000_000,
                Map.of("os", "linux", "runtime", "17"), true);
        env.targetRegistry().register(ok);
        User op = new User("op", Role.OPERATOR);
        RolloutJournal journal = env.rolloutService().startAndRun(op,
                new RolloutPlan(v1.packageId(),
                        List.of(new RolloutWave(List.of("small", "ok"))), true));
        assertEquals(RolloutStatus.ROLLED_BACK, journal.status());
        assertEquals(TargetState.FAILED, journal.results().get("small").state());
        assertTrue(journal.results().get("small").reason().contains("配额不足"));
        assertEquals(TargetState.PENDING, journal.results().get("ok").state(),
                "失败后必须停止，后续目标不得推进");
    }

    @Test
    void incompatibleTargetsAreSkippedButOthersStillProgress() throws Exception {
        TestEnv env = TestEnv.create(temp);
        PackageMeta v1 = buildV1(env);

        // compatible：满足 os=linux 且 runtime>=17
        env.targetRegistry().register(new Target("good", "good", 1_000_000,
                Map.of("os", "linux", "runtime", "18"), true));
        // 运行时版本太低
        env.targetRegistry().register(new Target("old-runtime", "old-runtime", 1_000_000,
                Map.of("os", "linux", "runtime", "11"), true));
        // 缺少 os 属性
        env.targetRegistry().register(new Target("no-os", "no-os", 1_000_000,
                Map.of("runtime", "17"), true));
        // 停用目标
        env.targetRegistry().register(new Target("disabled", "disabled", 1_000_000,
                Map.of("os", "linux", "runtime", "17"), false));

        User op = new User("op", Role.OPERATOR);
        RolloutJournal journal = env.rolloutService().startAndRun(op,
                new RolloutPlan(v1.packageId(),
                        List.of(new RolloutWave(List.of("good", "old-runtime", "no-os", "disabled"))),
                        true));

        assertEquals(RolloutStatus.COMPLETED, journal.status(),
                "兼容性跳过不应导致分发失败");
        assertEquals(TargetState.INSTALLED, journal.results().get("good").state());
        assertEquals(TargetState.SKIPPED, journal.results().get("old-runtime").state());
        assertEquals(TargetState.SKIPPED, journal.results().get("no-os").state());
        assertEquals(TargetState.SKIPPED, journal.results().get("disabled").state());

        assertTrue(journal.results().get("old-runtime").reason().contains("低于"));
        assertTrue(journal.results().get("no-os").reason().contains("缺少环境属性[os]"));
        assertTrue(journal.results().get("disabled").reason().contains("停用"));

        // 跳过的目标没有任何安装状态。
        assertTrue(env.stateStore().current("old-runtime").isEmpty());
        assertTrue(env.stateStore().current("no-os").isEmpty());
        // 兼容目标正常安装。
        assertEquals(v1.packageId(),
                env.stateStore().current("good").orElseThrow().packageId());

        // 占用视图：good 上有占用，按包/按资源都可核对。
        OccupancyView view = env.occupancyTracker().targetOccupancy("good");
        assertEquals(v1.contentSize(), view.usedBytes());
        assertEquals(1_000_000, view.quotaBytes());
        assertEquals(v1.contentSize(), view.bytesByPackage().values().iterator().next());
        assertEquals(1000L, view.bytesByResource().get("big"));
        assertEquals(6L, view.bytesByResource().get("osconf"));

        // 全局占用：资源 big 只在 good 一份。
        assertEquals(1000L, env.occupancyTracker().occupancyByResource().get("big"));
        assertEquals(v1.contentSize(),
                env.occupancyTracker().occupancyByPackage().values().iterator().next());
    }
}
