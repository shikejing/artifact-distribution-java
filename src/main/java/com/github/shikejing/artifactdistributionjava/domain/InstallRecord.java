package com.github.shikejing.artifactdistributionjava.domain;

/**
 * 目标上一次安装的日志记录（持久化，用于崩溃恢复）。
 */
public class InstallRecord {
    private String recordId;
    private String targetId;
    /** 产品线（资源包名称），同一目标同一产品线任一时刻只有一个已提交版本 */
    private String bundleName;
    private String bundleId;
    /** 本次安装开始前目标上已提交的版本（无则为 null），用于回滚 */
    private String previousBundleId;
    private InstallState state;
    private long updatedAtMillis;

    public InstallRecord() {
    }

    public String getRecordId() {
        return recordId;
    }

    public void setRecordId(String recordId) {
        this.recordId = recordId;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public String getBundleName() {
        return bundleName;
    }

    public void setBundleName(String bundleName) {
        this.bundleName = bundleName;
    }

    public String getBundleId() {
        return bundleId;
    }

    public void setBundleId(String bundleId) {
        this.bundleId = bundleId;
    }

    public String getPreviousBundleId() {
        return previousBundleId;
    }

    public void setPreviousBundleId(String previousBundleId) {
        this.previousBundleId = previousBundleId;
    }

    public InstallState getState() {
        return state;
    }

    public void setState(InstallState state) {
        this.state = state;
    }

    public long getUpdatedAtMillis() {
        return updatedAtMillis;
    }

    public void setUpdatedAtMillis(long updatedAtMillis) {
        this.updatedAtMillis = updatedAtMillis;
    }
}
