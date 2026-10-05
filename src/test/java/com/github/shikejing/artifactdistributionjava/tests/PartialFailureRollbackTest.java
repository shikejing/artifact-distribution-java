package com.github.shikejing.artifactdistributionjava.tests;

import com.github.shikejing.artifactdistributionjava.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景：部分失败。灰度推进中某目标应用失败，必须停止后续推进，
 * 并把已推进目标整体回到上一个完整版本，不留下半应用状态。
 */
class PartialFailureRollbackTest {

    @TempDir
    Path temp;

    @Test
    void failedTargetStopsRolloutAndRestoresEveryAdvancedTarget() throws Exception {
        TestEnv env = TestEnv.create(temp);

        // 来源与包：v1 为基线版本，v2 修改其中一个资源。
        Path sources = temp.resolve("sources");
        TestSupport.createSourceTree(sources, Map.of(
                "conf/app.conf", "version=1\n",
                "bin/run.sh", "echo v1\n"));
        var builder = env.packageBuilder();
        Manifest v1Manifest = TestSupport.manifest("app", "1.0", Map.of(
                "app.conf", "conf/app.conf",
                "run.sh", "bin/run.sh"), Map.of());
        PackageMeta v1 = builder.build(v1Manifest, null);

        TestSupport.createSourceTree(sources, Map.of(
                "conf/app.conf", "version=2\n",
                "bin/run.sh", "echo v2\n"));
        PackageMeta v2 = builder.build(TestSupport.manifest("app", "2.0", Map.of(
                "app.conf", "conf/app.conf",
                "run.sh", "bin/run.sh"), Map.of()), null);

        // 三个已登记目标，两个波次：波次1=[t1]，波次2=[t2,t3]。
        for (String id : List.of("t1", "t2", "t3")) {
            env.targetRegistry().register(new Target(id, id, 1024 * 1024, Map.of(), true));
        }

        User operator = new User("operator", Role.OPERATOR);

        // 基线：v1 全量安装。
        RolloutJournal base = env.rolloutService().startAndRun(operator,
                new RolloutPlan(v1.packageId(),
                        List.of(new RolloutWave(List.of("t1", "t2", "t3"))), true));
        assertEquals(RolloutStatus.COMPLETED, base.status());
        for (String id : List.of("t1", "t2", "t3")) {
            assertEquals(v1.packageId(), env.stateStore().current(id).orElseThrow().packageId());
        }

        // 让波次2的第一个目标 t2 在安装 v2 时失败。
        env.faultInjection().failNextInstallForTarget("t2");
        RolloutJournal upgrade = env.rolloutService().startAndRun(operator,
                new RolloutPlan(v2.packageId(),
                        List.of(new RolloutWave(List.of("t1")),
                                new RolloutWave(List.of("t2", "t3"))), true));

        // t1 已推进到 v2，随后必须整体回滚；t2 失败；t3 因停止推进保持 v1。
        assertEquals(RolloutStatus.ROLLED_BACK, upgrade.status());
        assertEquals(TargetState.ROLLED_BACK, upgrade.results().get("t1").state());
        assertEquals(TargetState.FAILED, upgrade.results().get("t2").state());
        assertEquals(TargetState.PENDING, upgrade.results().get("t3").state(),
                "失败后同波次后续目标不得继续推进");

        // 所有目标最终都停在上一个完整版本 v1。
        for (String id : List.of("t1", "t2", "t3")) {
            InstallRecord current = env.stateStore().current(id).orElseThrow();
            assertEquals(v1.packageId(), current.packageId(),
                    "目标 " + id + " 必须回到上一个完整版本");
        }

        // t1 的 current 指针必须指向一个完整的 v1 目录（无资源级混合）。
        Path t1Current = env.installer().currentReleaseDir("t1").orElseThrow();
        assertEquals(v1.packageId(), t1Current.getFileName().toString());
        assertTrue(java.nio.file.Files.isRegularFile(t1Current.resolve("resources/conf/app.conf")));
        String content = java.nio.file.Files.readString(t1Current.resolve("resources/conf/app.conf"));
        assertEquals("version=1\n", content, "回滚后资源内容必须是完整旧版本，不得混合");

        // v2 的暂存目录不得残留半应用垃圾（正式目录可保留为可复用的完整版本缓存）。
        try (var walk = java.nio.file.Files.walk(env.dataDir().resolve("targets"))) {
            boolean stagingLeft = walk.anyMatch(p -> p.getFileName().toString().startsWith(".staging-"));
            assertFalse(stagingLeft, "失败安装不得留下暂存/半应用目录");
        }
    }
}
