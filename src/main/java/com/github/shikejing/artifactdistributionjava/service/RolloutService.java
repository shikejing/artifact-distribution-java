package com.github.shikejing.artifactdistributionjava.service;

import com.github.shikejing.artifactdistributionjava.exception.ConflictException;
import com.github.shikejing.artifactdistributionjava.exception.NotFoundException;
import com.github.shikejing.artifactdistributionjava.exception.PackageVerificationException;
import com.github.shikejing.artifactdistributionjava.exception.QuotaExceededException;
import com.github.shikejing.artifactdistributionjava.exception.RolloutException;
import com.github.shikejing.artifactdistributionjava.model.*;
import com.github.shikejing.artifactdistributionjava.util.IoSupport;
import com.github.shikejing.artifactdistributionjava.util.JsonCodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 批次灰度分发编排器。
 *
 * <p>核心保证：
 * <ul>
 *   <li><b>波次推进</b>：按 plan 中 waves 的顺序逐批、批内逐目标推进；
 *       每个目标经过 兼容性检查 → 配额检查 → 暂存安装 → 原子提交。</li>
 *   <li><b>失败策略</b>：任一目标应用失败立即停止后续推进；rollbackOnFailure=true 时
 *       把本次已推进的目标全部恢复到分发前的上一个完整版本（新装的取消生效），
 *       绝不会留下半应用状态。</li>
 *   <li><b>兼容性跳过</b>：环境不满足（或目标停用）标记 SKIPPED 并记录原因，
 *       不阻断同批及后续波次的其他目标。</li>
 *   <li><b>可恢复</b>：journal 对每个目标的状态独立原子持久化；
 *       INSTALLING 是显式的“未完成”标记，与 INSTALLED（已提交）严格区分。
 *       进程重启后扫描 RUNNING 的 journal：已提交的不重装，未提交的安全重试，
 *       已推进的目标不丢失。</li>
 *   <li><b>并发</b>：安装/回滚在每个目标的互斥锁内进行，可与回滚并发，
 *       但同一目标永远只在完整版本间切换。</li>
 * </ul>
 */
public class RolloutService {

    private final Path rolloutsDir;
    private final PackageStore packageStore;
    private final TargetRegistry targetRegistry;
    private final QuotaService quotaService;
    private final CompatibilityChecker compatibilityChecker;
    private final Installer installer;
    private final StateStore stateStore;
    private final TargetLocks targetLocks;
    private final AuditLog auditLog;

    /** 编排锁：串行化分发的创建与执行调度（目标级安装仍由 TargetLocks 保护）。 */
    private final ReentrantLockHolder orchestrationLock = new ReentrantLockHolder();

    public RolloutService(Path dataDir,
                          PackageStore packageStore,
                          TargetRegistry targetRegistry,
                          QuotaService quotaService,
                          CompatibilityChecker compatibilityChecker,
                          Installer installer,
                          StateStore stateStore,
                          TargetLocks targetLocks,
                          AuditLog auditLog) {
        this.rolloutsDir = dataDir.resolve("rollouts");
        this.packageStore = packageStore;
        this.targetRegistry = targetRegistry;
        this.quotaService = quotaService;
        this.compatibilityChecker = compatibilityChecker;
        this.installer = installer;
        this.stateStore = stateStore;
        this.targetLocks = targetLocks;
        this.auditLog = auditLog;
        IoSupport.ensureDirectory(rolloutsDir);
    }

    /**
     * 创建并同步执行一次分发。
     */
    public RolloutJournal startAndRun(User actor, RolloutPlan plan) {
        validatePlan(plan);
        PackageMeta meta = packageStore.verifyAndLoadMeta(plan.packageId());

        orchestrationLock.lock();
        RolloutJournal journal;
        try {
            String rolloutId = nextRolloutId();
            List<List<String>> waves = plan.waves().stream()
                    .map(w -> List.copyOf(w.targetIds()))
                    .map(List::copyOf)
                    .toList();

            Map<String, TargetResult> results = new HashMap<>();
            Set<String> unique = new HashSet<>();
            for (List<String> wave : waves) {
                for (String targetId : wave) {
                    if (!unique.add(targetId)) {
                        throw new IllegalArgumentException("目标在多个波次中重复出现: " + targetId);
                    }
                    Target target = targetRegistry.get(targetId); // 未登记直接拒绝
                    String from = stateStore.current(targetId)
                            .map(InstallRecord::packageId).orElse(null);
                    results.put(targetId, new TargetResult(
                            targetId, TargetState.PENDING, null, from, meta.packageId(), 0));
                }
            }

            long now = System.currentTimeMillis();
            journal = new RolloutJournal(rolloutId, meta.packageId(), waves,
                    plan.rollbackOnFailure(), RolloutStatus.RUNNING, 0,
                    actor.username(), now, now, null, results);
            persist(journal);
            auditLog.record(actor.username(), "START_ROLLOUT", rolloutId, "SUCCESS",
                    "packageId=" + meta.packageId() + ", waves=" + waves.size());
        } finally {
            orchestrationLock.unlock();
        }

        return runToCompletion(journal);
    }

    /**
     * 继续执行一个未完成的分发（手动恢复）。
     */
    public RolloutJournal resume(String rolloutId) {
        RolloutJournal journal = get(rolloutId);
        if (journal.status() != RolloutStatus.RUNNING && journal.status() != RolloutStatus.PENDING) {
            throw new ConflictException("分发已结束，无法恢复: " + rolloutId + " (" + journal.status() + ")");
        }
        return runToCompletion(journal);
    }

    public RolloutJournal get(String rolloutId) {
        Path file = rolloutsDir.resolve(rolloutId + ".json");
        if (!Files.isRegularFile(file)) {
            throw new NotFoundException("分发不存在: " + rolloutId);
        }
        return JsonCodec.fromJson(IoSupport.readString(file), RolloutJournal.class);
    }

    public List<RolloutJournal> list() {
        List<RolloutJournal> journals = new ArrayList<>();
        try (var files = Files.list(rolloutsDir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".json"))
                    .forEach(f -> journals.add(
                            JsonCodec.fromJson(IoSupport.readString(f), RolloutJournal.class)));
        } catch (IOException e) {
            throw new IllegalStateException("列出分发失败", e);
        }
        journals.sort(java.util.Comparator.comparing(RolloutJournal::rolloutId));
        return journals;
    }

    /**
     * 进程重启后调用：恢复所有未完成（RUNNING）的分发。
     * 已 COMPLETED/FAILED/ROLLED_BACK 的不会被触碰。
     */
    public void recoverPending() {
        for (RolloutJournal journal : list()) {
            if (journal.status() == RolloutStatus.RUNNING || journal.status() == RolloutStatus.PENDING) {
                auditLog.record("system", "RECOVER_ROLLOUT", journal.rolloutId(), "SUCCESS",
                        "重启后恢复未完成分发，从波次 " + journal.waveIndex() + " 继续");
                runToCompletion(journal);
            }
        }
    }

    // ------------------------------------------------------------------
    // 核心状态机
    // ------------------------------------------------------------------

    private RolloutJournal runToCompletion(RolloutJournal journal) {
        orchestrationLock.lock();
        try {
            PackageMeta meta = packageStore.verifyAndLoadMeta(journal.packageId());

            // 重启恢复：先对账每个目标的实际状态，识别崩溃点。
            journal = reconcileAfterRestart(journal);

            for (int waveIndex = journal.waveIndex();
                 waveIndex < journal.waves().size();
                 waveIndex++) {

                List<String> wave = journal.waves().get(waveIndex);
                for (String targetId : wave) {
                    TargetResult result = journal.results().get(targetId);

                    // 恢复场景：已完成/已跳过/已回滚的目标绝不重复处理。
                    if (result.state() == TargetState.INSTALLED
                            || result.state() == TargetState.SKIPPED
                            || result.state() == TargetState.ROLLED_BACK) {
                        continue;
                    }
                    if (result.state() == TargetState.FAILED) {
                        // 崩溃发生在失败处理之前：补上失败策略。
                        return applyFailurePolicy(journal, result.reason());
                    }

                    journal = updateResult(journal, targetId, TargetState.INSTALLING,
                            result.reason(), result.attempts() + 1);
                    try {
                        processTarget(journal, targetId, meta);
                    } catch (CompatibilitySkip skip) {
                        journal = updateResult(journal, targetId, TargetState.SKIPPED,
                                skip.getMessage(), 0);
                        auditLog.record(journal.actor(), "ROLLOUT_SKIP", targetId, "SKIPPED",
                                "rollout=" + journal.rolloutId() + "，" + skip.getMessage());
                        continue;
                    } catch (Exception failure) {
                        journal = updateResult(journal, targetId, TargetState.FAILED,
                                failure.getMessage(), result.attempts());
                        auditLog.record(journal.actor(), "ROLLOUT_TARGET_FAIL", targetId, "FAILED",
                                "rollout=" + journal.rolloutId() + "，" + failure.getMessage());
                        return applyFailurePolicy(journal, failure.getMessage());
                    }

                    journal = updateResult(journal, targetId, TargetState.INSTALLED, null, 0);
                    auditLog.record(journal.actor(), "ROLLOUT_TARGET_OK", targetId, "SUCCESS",
                            "rollout=" + journal.rolloutId()
                                    + "，packageId=" + meta.packageId());
                }

                // 该波次全部处理完，推进波次游标（崩溃后不会重跑整个波次）。
                journal = withWaveIndex(journal, waveIndex + 1);
            }

            journal = withStatus(journal, RolloutStatus.COMPLETED, null);
            auditLog.record(journal.actor(), "FINISH_ROLLOUT", journal.rolloutId(), "SUCCESS",
                    "分发完成");
            return journal;
        } finally {
            orchestrationLock.unlock();
        }
    }

    private void processTarget(RolloutJournal journal, String targetId, PackageMeta meta) {
      targetLocks.withLock(targetId, () -> {
            Target target = targetRegistry.get(targetId);

            if (!target.enabled()) {
                throw new CompatibilitySkip("目标已停用，跳过");
            }
            Optional<String> incompatible = compatibilityChecker.check(meta, target);
            if (incompatible.isPresent()) {
                throw new CompatibilitySkip(incompatible.get());
            }
            // 配额不足是硬拒绝：标记失败并触发既定停止/回滚策略，原因可核对。
            try {
                quotaService.checkQuota(target, meta);
            } catch (QuotaExceededException e) {
                throw e;
            }

            InstallRecord record = installer.install(target, meta);
            stateStore.commit(record);
            return null;
        });
    }

    /**
     * 失败策略：停止推进；若要求回滚，则把本次已安装/可能已提交的目标全部
     * 恢复到分发前版本，然后标记 ROLLED_BACK，否则标记 FAILED。
     */
    private RolloutJournal applyFailurePolicy(RolloutJournal journal, String failureReason) {
        if (journal.rollbackOnFailure()) {
            List<String> restored = new ArrayList<>();
            Map<String, TargetResult> results = new HashMap<>(journal.results());
            for (TargetResult result : results.values()) {
                // 失败目标本身未提交，保持 FAILED；只回滚真正推进过/可能已提交的目标。
                if (result.state() == TargetState.INSTALLED
                        || result.state() == TargetState.INSTALLING) {
                    restoreTo(journal, result, results);
                    restored.add(result.targetId());
                }
            }
            journal = new RolloutJournal(journal.rolloutId(), journal.packageId(),
                    journal.waves(), journal.rollbackOnFailure(),
                    RolloutStatus.ROLLED_BACK, journal.waveIndex(),
                    journal.actor(), journal.createdAt(), System.currentTimeMillis(),
                    "失败后整体回滚: " + failureReason, results);
            persist(journal);
            auditLog.record(journal.actor(), "ROLLBACK_AFTER_FAILURE", journal.rolloutId(),
                    "SUCCESS", "已恢复目标: " + restored + "；原因: " + failureReason);
            return journal;
        }

        journal = withStatus(journal, RolloutStatus.FAILED, failureReason);
        auditLog.record(journal.actor(), "FAIL_ROLLOUT", journal.rolloutId(), "FAILED",
                failureReason);
        return journal;
    }

    /**
     * 把单个目标恢复到分发前状态：有旧版本则切回（必要时用包仓库重新落盘），
     * 分发前为空则取消生效（清除 current 指针与状态）。
     */
    private void restoreTo(RolloutJournal journal, TargetResult result,
                           Map<String, TargetResult> results) {
        String targetId = result.targetId();
        targetLocks.withLock(targetId, () -> {
            String fromPackageId = result.fromPackageId();
            if (fromPackageId == null) {
                installer.deactivate(targetId);
                stateStore.clearCurrent(targetId);
            } else {
                Target target = targetRegistry.get(targetId);
                PackageMeta oldMeta = packageStore.verifyAndLoadMeta(fromPackageId);
                if (installer.activate(targetId, fromPackageId).isEmpty()) {
                    // 旧版本目录已不在本地：用已校验的包重新安装同一完整版本。
                    InstallRecord record = installer.install(target, oldMeta);
                    stateStore.commit(record);
                } else {
                    stateStore.commit(new InstallRecord(targetId, oldMeta.packageId(),
                            oldMeta.name(), oldMeta.version(),
                            System.currentTimeMillis(), oldMeta.contentSize()));
                }
            }
            return null;
        });
        results.put(targetId, new TargetResult(targetId, TargetState.ROLLED_BACK,
                "回滚至分发前版本: " + result.fromPackageId(),
                result.fromPackageId(), result.toPackageId(), result.attempts()));
    }

    /**
     * 重启后对账：INSTALLING 的目标可能恰好已提交（指针已切但 journal 未更新），
     * 也可能未提交（暂存中崩溃）。据此修正状态，避免重复安装或丢失进度。
     */
    private RolloutJournal reconcileAfterRestart(RolloutJournal journal) {
        Map<String, TargetResult> results = journal.results();
        Map<String, TargetResult> fixed = new HashMap<>(results);
        boolean changed = false;
        for (TargetResult result : results.values()) {
            if (result.state() != TargetState.INSTALLING) {
                continue;
            }
            String targetId = result.targetId();
            Optional<Path> currentDir = installer.currentReleaseDir(targetId);
            Optional<InstallRecord> currentRecord = stateStore.current(targetId);
            boolean committedToNew = currentRecord.map(r -> r.packageId().equals(result.toPackageId()))
                    .orElse(false)
                    && currentDir.map(p -> p.getFileName().toString().equals(result.toPackageId()))
                    .orElse(false);
            if (committedToNew) {
                // 安装已完整提交，仅日志未更新：直接认领为已安装，不重装。
                fixed.put(targetId, new TargetResult(targetId, TargetState.INSTALLED, null,
                        result.fromPackageId(), result.toPackageId(), result.attempts()));
                changed = true;
            }
            // 未提交：保留 INSTALLING，状态机会安全重试（安装器对相同版本幂等）。
        }
        if (changed) {
            journal = new RolloutJournal(journal.rolloutId(), journal.packageId(),
                    journal.waves(), journal.rollbackOnFailure(), journal.status(),
                    journal.waveIndex(), journal.actor(), journal.createdAt(),
                    System.currentTimeMillis(), journal.failureReason(), fixed);
            persist(journal);
        }
        return journal;
    }

    // ------------------------------------------------------------------
    // journal 持久化辅助
    // ------------------------------------------------------------------

    private RolloutJournal updateResult(RolloutJournal journal, String targetId,
                                        TargetState state, String reason, int attempts) {
        TargetResult old = journal.results().get(targetId);
        Map<String, TargetResult> results = new HashMap<>(journal.results());
        results.put(targetId, new TargetResult(targetId, state, reason,
                old.fromPackageId(), old.toPackageId(), attempts));
        RolloutJournal updated = new RolloutJournal(journal.rolloutId(), journal.packageId(),
                journal.waves(), journal.rollbackOnFailure(), journal.status(),
                journal.waveIndex(), journal.actor(), journal.createdAt(),
                System.currentTimeMillis(), journal.failureReason(), results);
        persist(updated);
        return updated;
    }

    private RolloutJournal withWaveIndex(RolloutJournal journal, int waveIndex) {
        RolloutJournal updated = new RolloutJournal(journal.rolloutId(), journal.packageId(),
                journal.waves(), journal.rollbackOnFailure(), journal.status(),
                waveIndex, journal.actor(), journal.createdAt(),
                System.currentTimeMillis(), journal.failureReason(), journal.results());
        persist(updated);
        return updated;
    }

    private RolloutJournal withStatus(RolloutJournal journal, RolloutStatus status, String reason) {
        RolloutJournal updated = new RolloutJournal(journal.rolloutId(), journal.packageId(),
                journal.waves(), journal.rollbackOnFailure(), status,
                journal.waveIndex(), journal.actor(), journal.createdAt(),
                System.currentTimeMillis(), reason, journal.results());
        persist(updated);
        return updated;
    }

    private void persist(RolloutJournal journal) {
        IoSupport.writeStringAtomic(
                rolloutsDir.resolve(journal.rolloutId() + ".json"), JsonCodec.toJson(journal));
    }

    private String nextRolloutId() {
        long max = 0L;
        try (var files = Files.list(rolloutsDir)) {
            var iterator = files.filter(f -> f.getFileName().toString().endsWith(".json"))
                    .map(f -> f.getFileName().toString())
                    .toList();
            for (String name : iterator) {
                String id = name.substring(1, name.length() - ".json".length());
                try {
                    max = Math.max(max, Long.parseLong(id));
                } catch (NumberFormatException ignored) {
                    // 非数字编号不计入
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("生成分发编号失败", e);
        }
        return String.format("R%06d", max + 1);
    }

    private void validatePlan(RolloutPlan plan) {
        if (plan == null || plan.packageId() == null || plan.packageId().isBlank()) {
            throw new IllegalArgumentException("分发计划缺少 packageId");
        }
        if (plan.waves() == null || plan.waves().isEmpty()) {
            throw new IllegalArgumentException("分发计划至少包含一个波次");
        }
        for (RolloutWave wave : plan.waves()) {
            if (wave.targetIds() == null || wave.targetIds().isEmpty()) {
                throw new IllegalArgumentException("波次不能为空");
            }
        }
    }

    /** 内部信号：兼容性/停用导致的跳过（区别于硬失败）。 */
    private static final class CompatibilitySkip extends RuntimeException {
        CompatibilitySkip(String message) {
            super(message);
        }
    }

    private static final class ReentrantLockHolder {
        private final java.util.concurrent.locks.ReentrantLock lock =
                new java.util.concurrent.locks.ReentrantLock();

        void lock() {
            lock.lock();
        }

        void unlock() {
            lock.unlock();
        }
    }
}
