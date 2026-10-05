package com.github.shikejing.artifactdistributionjava.model;

/**
 * 某个目标上的一次已提交安装（进入 history 并可能成为 current）。
 *
 * @param fromPackageId 提交前该目标生效的包 ID（没有则为 null）；
 *                      回滚操作据此精确恢复到“上一个完整版本”。
 */
public record InstallRecord(String targetId,
                            String packageId,
                            String name,
                            String version,
                            long installedAt,
                            long totalSize,
                            String fromPackageId) {

    /** 无前序版本的便捷构造（普通安装的常见情况）。 */
    public InstallRecord(String targetId, String packageId, String name, String version,
                         long installedAt, long totalSize) {
        this(targetId, packageId, name, version, installedAt, totalSize, null);
    }
}
