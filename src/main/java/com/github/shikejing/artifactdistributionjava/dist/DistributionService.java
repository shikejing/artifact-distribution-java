package com.github.shikejing.artifactdistributionjava.dist;

import com.github.shikejing.artifactdistributionjava.compat.CompatibilityService;
import com.github.shikejing.artifactdistributionjava.domain.BundleInfo;
import com.github.shikejing.artifactdistributionjava.domain.InstallRecord;
import com.github.shikejing.artifactdistributionjava.domain.InstallState;
import com.github.shikejing.artifactdistributionjava.domain.Target;
import com.github.shikejing.artifactdistributionjava.pack.PackageBuilder;
import com.github.shikejing.artifactdistributionjava.quota.QuotaService;
import com.github.shikejing.artifactdistributionjava.registry.TargetRegistry;
import com.github.shikejing.artifactdistributionjava.security.AuditLog;
import com.github.shikejing.artifactdistributionjava.store.StateStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 分级（分批灰度）分发与回滚。
 *
 * 语义：
 *  - 目标按 batchSize 分批推进；任一批次内目标应用失败即停止推进，
 *    并把本次推进已安装的目标回滚到上一个完整版本；
 *  - 兼容性不满足的目标被跳过（原因可查），不影响其他目标；
 *  - 配额超限拒绝安装，视为失败并触发停止与回滚；
 *  - 每个目标上的安装以 PENDING -> COMMITTED 日志提交；同一目标同一产品线下
 *    任一时刻只有一个已提交的完整版本，崩溃恢复时回收未提交的 PENDING 记录；
 *  - 同一目标可同时安装多个不同产品线的资源包，占用按全部已提交包累计。
 */
@Service
public class DistributionService {

    private final StateStore store;
    private final TargetRegistry registry;
    private final CompatibilityService compatibility;
    private final QuotaService quota;
    private final PackageBuilder packageBuilder;
    private final AuditLog audit;

    /** 每个目标一把锁，保证分发与回滚并发时目标级原子切换 */
    private final Map<String, Object> targetLocks = new ConcurrentHashMap<>();

    /** 测试/演练钩子：模拟指定目标应用失败 */
    private volatile Predicate<String> applyFailurePredicate = t -> false;
    /** 测试/演练钩子：模拟提交前进程崩溃（留下 PENDING 记录） */
    private volatile Predicate<String> crashBeforeCommitPredicate = t -> false;

    public DistributionService(StateStore store, TargetRegistry registry,
                               CompatibilityService compatibility, QuotaService quota,
                               PackageBuilder packageBuilder, AuditLog audit) {
        this.store = store;
        this.registry = registry;
        this.compatibility = compatibility;
        this.quota = quota;
        this.packageBuilder = packageBuilder;
        this.audit = audit;
    }

    public void setApplyFailurePredicate(Predicate<String> predicate) {
        this.applyFailurePredicate = predicate;
    }

    public void setCrashBeforeCommitPredicate(Predicate<String> predicate) {
        this.crashBeforeCommitPredicate = predicate;
    }

    /**
     * 按批次灰度分发资源包到目标列表。任一目标失败即停止推进并回滚到上一个完整版本。
     */
    public RolloutResult distribute(String actor, String bundleId, List<String> targetIds, int batchSize) {
        BundleInfo bundle = store.findBundle(bundleId)
                .orElseThrow(() -> new IllegalArgumentException("bundle not found: " + bundleId));
        byte[] bytes = store.loadBundleBytes(bundleId)
                .orElseThrow(() -> new IllegalStateException("bundle bytes missing: " + bundleId));
        if (!packageBuilder.verify(bytes, bundle.getSha256())) {
            throw new IllegalStateException("bundle integrity check failed: " + bundleId);
        }
        for (String tid : targetIds) {
            if (registry.find(tid).isEmpty()) {
                throw new IllegalArgumentException("target not registered: " + tid);
            }
        }
        int size = Math.max(1, batchSize);
        List<TargetOutcome> outcomes = new ArrayList<>();
        List<String> installedInRollout = new ArrayList<>();
        boolean rolledBack = false;

        outer:
        for (int i = 0; i < targetIds.size(); i += size) {
            List<String> batch = targetIds.subList(i, Math.min(i + size, targetIds.size()));
            for (String tid : batch) {
                TargetOutcome outcome = installOne(actor, tid, bundle);
                outcomes.add(outcome);
                if ("INSTALLED".equals(outcome.status())) {
                    installedInRollout.add(tid);
                } else if ("FAILED".equals(outcome.status())) {
                    audit.record(actor, "ROLLOUT_HALT",
                            "bundle=" + bundleId + " failedAt=" + tid + " reason=" + outcome.reason(), true);
                    for (String done : installedInRollout) {
                        rollbackTarget(actor, done, bundle.getName(), "rollout failure at " + tid, true);
                    }
                    rolledBack = !installedInRollout.isEmpty();
                    break outer;
                }
                // SKIPPED: 兼容性不满足，不影响其他目标继续推进
            }
        }
        audit.record(actor, "DISTRIBUTE",
                "bundle=" + bundleId + " targets=" + targetIds + " rolledBack=" + rolledBack, true);
        return new RolloutResult(bundleId, outcomes, rolledBack);
    }

    /**
     * 在单个目标上安装（目标锁内原子完成）。
     */
    private TargetOutcome installOne(String actor, String targetId, BundleInfo bundle) {
        Target target = registry.find(targetId).orElseThrow();
        List<String> incompat = compatibility.check(bundle.getManifest().getRequirement(), target);
        if (!incompat.isEmpty()) {
            String reason = String.join("; ", incompat);
            audit.record(actor, "SKIP", "target=" + targetId + " bundle=" + bundle.getBundleId()
                    + " reason=" + reason, true);
            return new TargetOutcome(targetId, "SKIPPED", reason);
        }
        synchronized (lockOf(targetId)) {
            String current = currentVersionOf(targetId, bundle.getName()).orElse(null);
            if (bundle.getBundleId().equals(current)) {
                return new TargetOutcome(targetId, "INSTALLED", "already at this version");
            }
            List<BundleInfo> installed = installedBundlesOf(targetId);
            List<String> quotaReasons = quota.checkInstall(target, bundle, installed);
            if (!quotaReasons.isEmpty()) {
                String reason = String.join("; ", quotaReasons);
                audit.record(actor, "QUOTA_REJECT", "target=" + targetId + " bundle="
                        + bundle.getBundleId() + " reason=" + reason, false);
                return new TargetOutcome(targetId, "FAILED", reason);
            }
            if (applyFailurePredicate.test(targetId)) {
                InstallRecord failed = newRecord(targetId, bundle, current);
                failed.setState(InstallState.FAILED);
                store.saveInstallRecord(failed);
                return new TargetOutcome(targetId, "FAILED", "simulated apply failure");
            }

            // PENDING -> COMMITTED 两阶段日志提交
            InstallRecord record = newRecord(targetId, bundle, current);
            record.setState(InstallState.PENDING);
            store.saveInstallRecord(record);

            if (crashBeforeCommitPredicate.test(targetId)) {
                throw new SimulatedCrashException("simulated crash before commit on " + targetId);
            }

            // 先提交新版本，再淘汰旧版本：任何时刻目标都存在已提交的完整版本
            record.setState(InstallState.COMMITTED);
            record.setUpdatedAtMillis(now());
            store.saveInstallRecord(record);
            if (current != null) {
                markCommittedSuperseded(targetId, bundle.getName(), record.getRecordId());
            }
            audit.record(actor, "INSTALL", "target=" + targetId + " bundle=" + bundle.getBundleId()
                    + " previous=" + current, true);
            return new TargetOutcome(targetId, "INSTALLED", null);
        }
    }

    /**
     * 把指定目标上指定产品线的安装回滚到上一个完整版本。
     */
    public RollbackResult rollback(String actor, String targetId, String bundleName) {
        return rollbackTarget(actor, targetId, bundleName, "explicit rollback", false);
    }

    private RollbackResult rollbackTarget(String actor, String targetId, String bundleName, String why,
                                          boolean allowUninstall) {
        synchronized (lockOf(targetId)) {
            Optional<InstallRecord> currentOpt = latestCommittedRecord(targetId, bundleName);
            if (currentOpt.isEmpty()) {
                return new RollbackResult(targetId, bundleName, null, null, false,
                        "no committed version of " + bundleName + " on target");
            }
            InstallRecord currentRecord = currentOpt.get();
            String current = currentRecord.getBundleId();
            String previous = currentRecord.getPreviousBundleId();

            if (previous == null) {
                if (!allowUninstall) {
                    // 显式回滚且没有上一个完整版本时拒绝，保持当前已提交版本不变
                    audit.record(actor, "ROLLBACK_REJECT", "target=" + targetId + " name=" + bundleName
                            + " from=" + current + " reason=no previous complete version", false);
                    return new RollbackResult(targetId, bundleName, current, null, false,
                            "no previous complete version to roll back to");
                }
                // 推进失败路径：目标在推进前没有版本，卸载本次安装以回到推进前状态
                markCommittedSuperseded(targetId, bundleName, null);
                audit.record(actor, "ROLLBACK", "target=" + targetId + " name=" + bundleName
                        + " from=" + current + " to=<none> why=" + why, true);
                return new RollbackResult(targetId, bundleName, current, null, true,
                        "rolled back to pre-rollout state (no version)");
            }
            // 先提交回滚目标版本，再淘汰当前版本
            InstallRecord record = newRecord(targetId, bundleName, previous, current);
            record.setState(InstallState.COMMITTED);
            store.saveInstallRecord(record);
            markCommittedSuperseded(targetId, bundleName, record.getRecordId());
            audit.record(actor, "ROLLBACK", "target=" + targetId + " name=" + bundleName
                    + " from=" + current + " to=" + previous + " why=" + why, true);
            return new RollbackResult(targetId, bundleName, current, previous, true, "rolled back");
        }
    }

    /**
     * 进程重启后恢复：回收未提交的 PENDING 安装（标记 FAILED），
     * 已提交的安装保持不变，保证目标停留在某个完整版本且不重复安装。
     */
    public void recoverAfterRestart() {
        for (InstallRecord r : store.listInstallRecords()) {
            if (r.getState() == InstallState.PENDING) {
                r.setState(InstallState.FAILED);
                r.setUpdatedAtMillis(now());
                store.saveInstallRecord(r);
                audit.record("system", "RECOVER", "recycled pending install record=" + r.getRecordId()
                        + " target=" + r.getTargetId() + " bundle=" + r.getBundleId(), true);
            }
        }
    }

    /**
     * 查询每个目标当前提交的完整版本：targetId -> (产品线 -> bundleId)。
     */
    public Map<String, Map<String, String>> currentVersions() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (Target t : registry.list()) {
            Map<String, String> byName = new LinkedHashMap<>();
            for (InstallRecord r : store.installRecordsOf(t.getTargetId())) {
                if (r.getState() == InstallState.COMMITTED
                        && latestCommittedRecord(t.getTargetId(), r.getBundleName())
                        .map(InstallRecord::getRecordId).orElse("").equals(r.getRecordId())) {
                    byName.put(r.getBundleName(), r.getBundleId());
                }
            }
            if (!byName.isEmpty()) {
                out.put(t.getTargetId(), byName);
            }
        }
        return out;
    }

    /**
     * 查询目标上所有已提交的资源包（用于占用追踪）。
     */
    public List<BundleInfo> installedBundlesOf(String targetId) {
        List<BundleInfo> out = new ArrayList<>();
        for (String name : committedNamesOf(targetId)) {
            latestCommittedRecord(targetId, name)
                    .flatMap(r -> store.findBundle(r.getBundleId()))
                    .ifPresent(out::add);
        }
        return out;
    }

    public Optional<String> currentVersionOf(String targetId, String bundleName) {
        return latestCommittedRecord(targetId, bundleName).map(InstallRecord::getBundleId);
    }

    private List<String> committedNamesOf(String targetId) {
        return store.installRecordsOf(targetId).stream()
                .filter(r -> r.getState() == InstallState.COMMITTED)
                .map(InstallRecord::getBundleName)
                .distinct()
                .toList();
    }

    private Optional<InstallRecord> latestCommittedRecord(String targetId, String bundleName) {
        return store.installRecordsOf(targetId).stream()
                .filter(r -> r.getState() == InstallState.COMMITTED
                        && r.getBundleName().equals(bundleName))
                .max(Comparator.comparingLong(InstallRecord::getUpdatedAtMillis)
                        .thenComparing(InstallRecord::getRecordId));
    }

    private void markCommittedSuperseded(String targetId, String bundleName, String exceptRecordId) {
        for (InstallRecord r : store.installRecordsOf(targetId)) {
            if (r.getBundleName().equals(bundleName) && r.getState() == InstallState.COMMITTED
                    && !r.getRecordId().equals(exceptRecordId)) {
                r.setState(InstallState.SUPERSEDED);
                r.setUpdatedAtMillis(now());
                store.saveInstallRecord(r);
            }
        }
    }

    private InstallRecord newRecord(String targetId, BundleInfo bundle, String previousBundleId) {
        return newRecord(targetId, bundle.getName(), bundle.getBundleId(), previousBundleId);
    }

    private InstallRecord newRecord(String targetId, String bundleName, String bundleId,
                                    String previousBundleId) {
        InstallRecord r = new InstallRecord();
        r.setRecordId(UUID.randomUUID().toString());
        r.setTargetId(targetId);
        r.setBundleName(bundleName);
        r.setBundleId(bundleId);
        r.setPreviousBundleId(previousBundleId);
        r.setUpdatedAtMillis(now());
        return r;
    }

    private long now() {
        return System.currentTimeMillis();
    }

    private Object lockOf(String targetId) {
        return targetLocks.computeIfAbsent(targetId, k -> new Object());
    }

    public record TargetOutcome(String targetId, String status, String reason) {
    }

    public record RolloutResult(String bundleId, List<TargetOutcome> outcomes, boolean rolledBack) {
    }

    public record RollbackResult(String targetId, String bundleName, String fromBundleId, String toBundleId,
                                 boolean success, String reason) {
    }

    /**
     * 模拟进程崩溃（测试钩子触发）。
     */
    public static class SimulatedCrashException extends RuntimeException {
        public SimulatedCrashException(String message) {
            super(message);
        }
    }
}
