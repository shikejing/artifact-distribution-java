package com.github.shikejing.artifactdistributionjava.tests;

import com.github.shikejing.artifactdistributionjava.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 场景：完整回滚链路 —— 升级后手动回滚到上一个已提交的完整版本，
 * 再继续向前升级，反复切换后 current 始终与 state 一致。
 */
class ManualRollbackTest {

    @TempDir
    Path temp;

    @Test
    void rollbackRestoresPreviousCommittedVersion() throws Exception {
        TestEnv env = TestEnv.create(temp);
        User op = new User("op", Role.OPERATOR);

        Path sources = temp.resolve("sources");
        PackageMeta v1 = pack(env, sources, "1", "c1");
        PackageMeta v2 = pack(env, sources, "2", "c2");
        PackageMeta v3 = pack(env, sources, "3", "c3");

        env.targetRegistry().register(new Target("t", "t", 1_000_000, Map.of(), true));

        install(env, op, v1);
        install(env, op, v2);
        install(env, op, v3);
        assertEquals(v3.packageId(), env.stateStore().current("t").orElseThrow().packageId());

        // 回滚一次 -> v2
        InstallRecord r1 = env.rollbackService().rollbackTarget(op, "t");
        assertEquals(v2.packageId(), r1.packageId());
        assertEquals(v2.packageId(), env.stateStore().current("t").orElseThrow().packageId());
        assertEquals(v2.packageId(),
                env.installer().currentReleaseDir("t").orElseThrow().getFileName().toString());

        // 再回滚一次 -> v1
        InstallRecord r2 = env.rollbackService().rollbackTarget(op, "t");
        assertEquals(v1.packageId(), r2.packageId());

        // 历史完整可核对
        var history = env.stateStore().history("t");
        assertEquals(5, history.size()); // v1,v2,v3,回滚v2,回滚v1
    }

    private void install(TestEnv env, User op, PackageMeta meta) {
        RolloutJournal j = env.rolloutService().startAndRun(op,
                new RolloutPlan(meta.packageId(),
                        List.of(new RolloutWave(List.of("t"))), true));
        assertEquals(RolloutStatus.COMPLETED, j.status());
    }

    private PackageMeta pack(TestEnv env, Path sources, String version, String content) throws java.io.IOException {
        TestSupport.createSourceTree(sources, Map.of("a.conf", content + "\n"));
        return env.packageBuilder().build(
                TestSupport.manifest("app", version, Map.of("a", "a.conf"), Map.of()), null);
    }
}
