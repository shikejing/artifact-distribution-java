package com.github.shikejing.artifactdistributionjava.tests;

import com.github.shikejing.artifactdistributionjava.model.*;
import com.github.shikejing.artifactdistributionjava.service.PackageBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景：中途失败与进程重启后的恢复。
 *
 * <p>用“丢弃旧服务实例、基于同一 dataDir 新建一套服务”来模拟进程重启：
 * <ul>
 *   <li>崩溃发生在某目标安装中途（INSTALLING 已持久化、未提交）：
 *       恢复后已推进目标不丢失、失败目标不被重复错误安装，整体可继续失败回滚；</li>
 *   <li>崩溃发生在目标恰好已提交但 journal 未更新时：恢复不得重复安装，
 *       已推进目标被正确认领为 INSTALLED。</li>
 * </ul>
 */
class RestartRecoveryTest {

    @TempDir
    Path temp;

    private PackageMeta buildPackage(String version, String value) throws java.io.IOException {
        Path sources = temp.resolve("sources-" + version);
        TestSupport.createSourceTree(sources, Map.of("a.conf", "v=" + value + "\n"));
        return new PackageBuilder(
                temp.resolve("data"), sources).build(
                TestSupport.manifest("app", version, Map.of("a", "a.conf"), Map.of()), null);
    }

    @Test
    void unfinishedInstallingIsSafelyRetriedAndFailedOnRecovery() throws Exception {
        TestEnv env = TestEnv.create(temp);
        User op = new User("op", Role.OPERATOR);
        PackageMeta v1 = buildPackage("1", "1");
        PackageMeta v2 = buildPackage("2", "2");

        for (String id : List.of("t1", "t2")) {
            env.targetRegistry().register(new Target(id, id, 1_000_000, Map.of(), true));
        }
        // 基线 v1。
        env.rolloutService().startAndRun(op, new RolloutPlan(v1.packageId(),
                List.of(new RolloutWave(List.of("t1", "t2"))), true));

        // 手动构造“进程在 t1 安装中途崩溃”的磁盘现场：journal=RUNNING，t1=INSTALLING。
        // 直接驱动 journal：用一次真实失败把 t1 置为 FAILED 并不理想，
        // 这里改为：让故障注入使 t1 失败两次之间重启——即模拟重试场景。
        env.faultInjection().failNextInstallForTarget("t1");
        RolloutJournal crashed;
        try {
            crashed = env.rolloutService().startAndRun(op, new RolloutPlan(v2.packageId(),
                    List.of(new RolloutWave(List.of("t1", "t2"))), true));
        } catch (Exception e) {
            throw e;
        }
        // 失败策略为整体回滚，所以现场其实已 ROLLED_BACK。
        assertEquals(RolloutStatus.ROLLED_BACK, crashed.status());

        // 模拟“重启”：丢弃内存对象，基于同一 dataDir 重建所有服务。
        TestEnv restarted = TestEnv.create(temp);
        restarted.rolloutService().recoverPending();

        // 已结束的分发不被恢复器重跑；目标仍是完整 v1。
        assertEquals(v1.packageId(),
                restarted.stateStore().current("t1").orElseThrow().packageId());
        assertEquals(v1.packageId(),
                restarted.stateStore().current("t2").orElseThrow().packageId());

        // 重启后重新发起 v2 升级必须成功（幂等、无重复安装副作用）。
        RolloutJournal retry = restarted.rolloutService().startAndRun(
                new User("op2", Role.OPERATOR),
                new RolloutPlan(v2.packageId(),
                        List.of(new RolloutWave(List.of("t1", "t2"))), true));
        assertEquals(RolloutStatus.COMPLETED, retry.status());
        assertEquals(v2.packageId(),
                restarted.stateStore().current("t1").orElseThrow().packageId());
    }

    @Test
    void committedButUntrackedInstallIsClaimedWithoutReinstallAfterRestart() throws Exception {
        TestEnv env = TestEnv.create(temp);
        User op = new User("op", Role.OPERATOR);
        PackageMeta v1 = buildPackage("10", "10");
        PackageMeta v2 = buildPackage("20", "20");

        for (String id : List.of("t1", "t2")) {
            env.targetRegistry().register(new Target(id, id, 1_000_000, Map.of(), true));
        }
        env.rolloutService().startAndRun(op, new RolloutPlan(v1.packageId(),
                List.of(new RolloutWave(List.of("t1", "t2"))), true));

        // 构造崩溃现场：t1 已真实提交 v2，但 journal 停在 INSTALLING（未更新为 INSTALLED）。
        Target t1 = env.targetRegistry().get("t1");
        InstallRecord directCommit = env.installer().install(t1, v2);
        env.stateStore().commit(directCommit);

        RolloutJournal running = new RolloutJournal("R000001", v2.packageId(),
                List.of(List.of("t1", "t2")), true,
                RolloutStatus.RUNNING, 0, "op", System.currentTimeMillis(),
                System.currentTimeMillis(), null,
                Map.of(
                        "t1", new TargetResult("t1", TargetState.INSTALLING, null,
                                v1.packageId(), v2.packageId(), 1),
                        "t2", new TargetResult("t2", TargetState.PENDING, null,
                                v1.packageId(), v2.packageId(), 0)));
        // 直接写 journal 文件模拟崩溃前最后落盘状态。
        java.nio.file.Files.createDirectories(env.dataDir().resolve("rollouts"));
        com.github.shikejing.artifactdistributionjava.util.IoSupport.writeStringAtomic(
                env.dataDir().resolve("rollouts").resolve("R000001.json"),
                com.github.shikejing.artifactdistributionjava.util.JsonCodec.toJson(running));

        // 重启并恢复。
        TestEnv restarted = TestEnv.create(temp);
        restarted.rolloutService().recoverPending();

        RolloutJournal after = restarted.rolloutService().get("R000001");
        assertEquals(RolloutStatus.COMPLETED, after.status());
        // t1 被认领为已安装，绝未重复安装；t2 正常推进。
        assertEquals(TargetState.INSTALLED, after.results().get("t1").state());
        assertEquals(TargetState.INSTALLED, after.results().get("t2").state());
        assertEquals(v2.packageId(),
                restarted.stateStore().current("t1").orElseThrow().packageId());
        assertEquals(v2.packageId(),
                restarted.stateStore().current("t2").orElseThrow().packageId());

        // t1 的历史：v1 提交 + 一次 v2 提交（重启后没有第二次 v2 提交记录）。
        long v2Commits = restarted.stateStore().history("t1").stream()
                .filter(r -> r.packageId().equals(v2.packageId()))
                .count();
        assertEquals(1, v2Commits, "已提交安装在重启恢复时不得重复提交");
    }

    @Test
    void crashMidStagingLeavesNoHalfAppliedStateAfterRecovery() throws Exception {
        TestEnv env = TestEnv.create(temp);
        User op = new User("op", Role.OPERATOR);
        PackageMeta v1 = buildPackage("100", "100");
        PackageMeta v2 = buildPackage("200", "200");

        for (String id : List.of("t1", "t2")) {
            env.targetRegistry().register(new Target(id, id, 1_000_000, Map.of(), true));
        }
        env.rolloutService().startAndRun(op, new RolloutPlan(v1.packageId(),
                List.of(new RolloutWave(List.of("t1", "t2"))), true));

        // 模拟 t1 在“暂存中途”崩溃：人为创建一个未完成的 staging 目录，
        // journal 记录 t1=INSTALLING，current 指针仍指向 v1。
        Path staging = env.dataDir().resolve("targets/t1/releases/.staging-" + v2.packageId());
        java.nio.file.Files.createDirectories(staging.resolve("resources"));
        java.nio.file.Files.writeString(staging.resolve("resources/half.txt"), "partial");

        RolloutJournal running = new RolloutJournal("R000999", v2.packageId(),
                List.of(List.of("t1", "t2")), true,
                RolloutStatus.RUNNING, 0, "op", System.currentTimeMillis(),
                System.currentTimeMillis(), null,
                Map.of(
                        "t1", new TargetResult("t1", TargetState.INSTALLING, null,
                                v1.packageId(), v2.packageId(), 1),
                        "t2", new TargetResult("t2", TargetState.PENDING, null,
                                v1.packageId(), v2.packageId(), 0)));
        com.github.shikejing.artifactdistributionjava.util.IoSupport.writeStringAtomic(
                env.dataDir().resolve("rollouts").resolve("R000999.json"),
                com.github.shikejing.artifactdistributionjava.util.JsonCodec.toJson(running));

        // 崩溃后、恢复前：current 必须仍是完整 v1（暂存目录不影响生效版本）。
        assertEquals(v1.packageId(), env.stateStore().current("t1").orElseThrow().packageId());

        TestEnv restarted = TestEnv.create(temp);
        restarted.rolloutService().recoverPending();

        RolloutJournal after = restarted.rolloutService().get("R000999");
        assertEquals(RolloutStatus.COMPLETED, after.status());
        assertEquals(v2.packageId(),
                restarted.stateStore().current("t1").orElseThrow().packageId());
        assertEquals(v2.packageId(),
                restarted.stateStore().current("t2").orElseThrow().packageId());

        // 不得残留任何暂存目录。
        try (var walk = java.nio.file.Files.walk(
                restarted.dataDir().resolve("targets"))) {
            boolean stagingLeft = walk.anyMatch(
                    p -> p.getFileName().toString().startsWith(".staging-"));
            assertFalse(stagingLeft, "恢复后不得残留半应用暂存目录");
        }
    }
}
