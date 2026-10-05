package com.github.shikejing.artifactdistributionjava.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地登记的分发目标。
 */
public class Target {
    private String targetId;
    private Map<String, String> attributes = new LinkedHashMap<>();
    private Quota quota = new Quota();

    public Target() {
    }

    public Target(String targetId) {
        this.targetId = targetId;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, String> attributes) {
        this.attributes = attributes;
    }

    public Quota getQuota() {
        return quota;
    }

    public void setQuota(Quota quota) {
        this.quota = quota;
    }
}
