package com.github.shikejing.artifactdistributionjava.service;

import com.github.shikejing.artifactdistributionjava.exception.NotFoundException;
import com.github.shikejing.artifactdistributionjava.exception.RolloutException;
import com.github.shikejing.artifactdistributionjava.model.InstallRecord;
import com.github.shikejing.artifactdistributionjava.model.PackageMeta;
import com.github.shikejing.artifactdistributionjava.model.Target;
import com.github.shikejing.artifactdistributionjava.model.User;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 手动回滚服务。
 *
 * <p>把目标整体恢复到“上一个已提交的完整版本”：读取 state 历史中倒数第二条
 * （倒数第一条是当前版本）；若历史不足两条但存在更早已提交版本，也依次回退。
 *
 * <p>与分发并发安全：整个“查找上一版本 → 切回/重装 → 提交”过程持有该目标的
 * {@link TargetLocks}，因此并发回滚期间该目标始终只安装某个完整版本，
 * 不会出现资源级混合。
 */
public class RollbackService {

    private final StateStore stateStore;
    private final Installer installer;
    private final PackageStore packageStore;
    private final TargetRegistry targetRegistry;
    private final TargetLocks targetLocks;
    private final AuditLog auditLog;

    public RollbackService(Path dataDir,
                           StateStore stateStore,
                           Installer installer,
                           PackageStore packageStore,
                           TargetRegistry targetRegistry,
                           TargetLocks targetLocks,
                           AuditLog auditLog) {
        this.stateStore = stateStore;
        this.installer = installer;
        this.packageStore = packageStore;
        this.targetRegistry = targetRegistry;
        this.targetLocks = targetLocks;
        this.auditLog = auditLog;
    }

    /**
     * 回滚指定目标到上一个已提交版本。
     *
     * @return 回滚后生效的安装记录
     */
    public InstallRecord rollbackTarget(User actor, String targetId) {
        Target target = targetRegistry.get(targetId);
        return targetLocks.withLock(targetId, () -> {
            Optional<InstallRecord> current = stateStore.current(targetId);
            if (current.isEmpty()) {
                throw new RolloutException("目标 " + targetId + " 当前没有已安装版本，无法回滚");
            }

            // 精确取“当前这次提交之前生效的版本”（记录在 current.fromPackageId）。
            String currentId = current.get().packageId();
            String previousId = current.get().fromPackageId();
            if (previousId == null || previousId.isBlank()) {
                throw new NotFoundException(
                        "目标 " + targetId + " 没有可回滚的历史版本（当前=" + currentId + "）");
            }

            PackageMeta meta = packageStore.verifyAndLoadMeta(previousId);

            InstallRecord record;
            if (installer.activate(targetId, previousId).isPresent()) {
                // 旧版本目录仍在本地：原子切回指针即可。
                record = new InstallRecord(targetId, meta.packageId(), meta.name(),
                        meta.version(), System.currentTimeMillis(), meta.contentSize());
            } else {
                // 旧版本目录已被清理：用已校验包完整重装该版本（仍是整体切换）。
                record = installer.install(target, meta);
            }
            stateStore.commitRollback(record);
            auditLog.record(actor.username(), "ROLLBACK_TARGET", targetId, "SUCCESS",
                    "回滚: " + currentId + " -> " + previousId);
            return record;
        });
    }
}
