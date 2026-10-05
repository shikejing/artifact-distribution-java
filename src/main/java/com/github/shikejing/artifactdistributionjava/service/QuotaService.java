package com.github.shikejing.artifactdistributionjava.service;

import com.github.shikejing.artifactdistributionjava.exception.QuotaExceededException;
import com.github.shikejing.artifactdistributionjava.model.InstallRecord;
import com.github.shikejing.artifactdistributionjava.model.PackageMeta;
import com.github.shikejing.artifactdistributionjava.model.Target;

import java.util.Optional;

/**
 * 配额校验。
 *
 * <p>占用模型：每个目标上<b>当前生效的是一个完整版本</b>，同一资源包升级时
 * 旧版本被新版本整体替换，因此计费口径为：
 * <pre>
 *   安装后占用 = 当前占用 - 同包当前版本大小(若有) + 待安装包大小
 * </pre>
 * 即旧版本空间会被释放。拒绝原因包含目标配额、当前占用、释放量、需求量，可逐项核对。
 */
public class QuotaService {

    private final StateStore stateStore;
    private final PackageStore packageStore;

    public QuotaService(StateStore stateStore, PackageStore packageStore) {
        this.stateStore = stateStore;
        this.packageStore = packageStore;
    }

    /**
     * 校验目标是否有足够配额安装该包，不足时抛出 {@link QuotaExceededException}。
     */
    public void checkQuota(Target target, PackageMeta meta) {
        long used = currentlyUsed(target.id());
        long replaceable = currentSizeOfPackage(target.id(), meta.name());
        long projected = used - replaceable + meta.contentSize();

        if (projected > target.quotaBytes()) {
            long shortage = projected - target.quotaBytes();
            throw new QuotaExceededException(String.format(
                    "目标[%s]配额不足被拒绝安装: 配额=%d字节, 当前占用=%d字节, "
                            + "同包旧版本可释放=%d字节, 待安装包[%s/%s]需求=%d字节, "
                            + "安装后预计占用=%d字节, 超出=%d字节",
                    target.id(), target.quotaBytes(), used, replaceable,
                    meta.packageId(), meta.name() + ":" + meta.version(),
                    meta.contentSize(), projected, shortage));
        }
    }

    /** 目标当前已提交版本的总占用。 */
    public long currentlyUsed(String targetId) {
        return stateStore.current(targetId).map(InstallRecord::totalSize).orElse(0L);
    }

    private long currentSizeOfPackage(String targetId, String packageName) {
        Optional<InstallRecord> current = stateStore.current(targetId);
        if (current.isEmpty() || !current.get().name().equals(packageName)) {
            return 0L;
        }
        return current.get().totalSize();
    }
}
