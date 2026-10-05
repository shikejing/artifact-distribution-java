package com.github.shikejing.artifactdistributionjava.service;

import com.github.shikejing.artifactdistributionjava.model.InstallRecord;
import com.github.shikejing.artifactdistributionjava.model.OccupancyView;
import com.github.shikejing.artifactdistributionjava.model.PackageMeta;
import com.github.shikejing.artifactdistributionjava.model.Target;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 资源占用追踪。
 *
 * <p>口径：占用只统计每个目标<b>当前已提交</b>的完整版本（暂存中的内容不计费，
 * 失败安装不留痕迹）。提供：
 * <ul>
 *   <li>单目标：总占用/配额、按包占用、按资源占用；</li>
 *   <li>全局：每个资源包在所有目标上的占用合计（每目标一份完整内容）；</li>
 *   <li>全局：每个资源在所有目标上的占用合计。</li>
 * </ul>
 */
public class OccupancyTracker {

    private final StateStore stateStore;
    private final PackageStore packageStore;
    private final TargetRegistry targetRegistry;

    public OccupancyTracker(StateStore stateStore, PackageStore packageStore,
                            TargetRegistry targetRegistry) {
        this.stateStore = stateStore;
        this.packageStore = packageStore;
        this.targetRegistry = targetRegistry;
    }

    /** 单个目标的占用视图。 */
    public OccupancyView targetOccupancy(String targetId) {
        Target target = targetRegistry.get(targetId);
        Map<String, Long> byPackage = new TreeMap<>();
        Map<String, Long> byResource = new TreeMap<>();
        long used = 0L;
        if (stateStore.current(targetId).isPresent()) {
            InstallRecord record = stateStore.current(targetId).get();
            PackageMeta meta = packageStore.loadMeta(record.packageId());
            used = meta.contentSize();
            byPackage.put(label(meta), used);
            for (PackageMeta.ResourceEntry entry : meta.entries()) {
                byResource.merge(entry.resourceId(), entry.size(), Long::sum);
            }
        }
        return new OccupancyView(used, target.quotaBytes(), byPackage, byResource);
    }

    /** 每个资源包在所有目标上的占用合计（key = packageId/name:version）。 */
    public Map<String, Long> occupancyByPackage() {
        Map<String, Long> result = new TreeMap<>();
        for (InstallRecord record : stateStore.allCurrent()) {
            PackageMeta meta = packageStore.loadMeta(record.packageId());
            result.merge(label(meta), meta.contentSize(), Long::sum);
        }
        return result;
    }

    /** 每个资源在所有目标上的占用合计（key = resourceId）。 */
    public Map<String, Long> occupancyByResource() {
        Map<String, Long> result = new TreeMap<>();
        for (InstallRecord record : stateStore.allCurrent()) {
            PackageMeta meta = packageStore.loadMeta(record.packageId());
            for (PackageMeta.ResourceEntry entry : meta.entries()) {
                result.merge(entry.resourceId(), entry.size(), Long::sum);
            }
        }
        return result;
    }

    /** 某资源包当前已安装到哪些目标。 */
    public List<String> installedTargetIds(String packageId) {
        return stateStore.allCurrent().stream()
                .filter(r -> r.packageId().equals(packageId))
                .map(InstallRecord::targetId)
                .sorted()
                .toList();
    }

    public PackageMeta metaOf(String packageId) {
        return packageStore.loadMeta(packageId);
    }

    private static String label(PackageMeta meta) {
        return meta.packageId() + "/" + meta.name() + ":" + meta.version();
    }
}
