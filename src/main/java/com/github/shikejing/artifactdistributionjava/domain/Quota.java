package com.github.shikejing.artifactdistributionjava.domain;

/**
 * 单个目标上的资源占用配额。
 */
public class Quota {
    private int maxBundles = Integer.MAX_VALUE;
    private int maxResources = Integer.MAX_VALUE;
    private long maxBytes = Long.MAX_VALUE;

    public Quota() {
    }

    public Quota(int maxBundles, int maxResources, long maxBytes) {
        this.maxBundles = maxBundles;
        this.maxResources = maxResources;
        this.maxBytes = maxBytes;
    }

    public int getMaxBundles() {
        return maxBundles;
    }

    public void setMaxBundles(int maxBundles) {
        this.maxBundles = maxBundles;
    }

    public int getMaxResources() {
        return maxResources;
    }

    public void setMaxResources(int maxResources) {
        this.maxResources = maxResources;
    }

    public long getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(long maxBytes) {
        this.maxBytes = maxBytes;
    }
}
