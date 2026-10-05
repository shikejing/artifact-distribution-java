package com.github.shikejing.artifactdistributionjava.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 资源包对目标运行环境的兼容性要求。
 */
public class RuntimeRequirement {
    private String os;
    private String arch;
    private String minRuntimeVersion;
    private Map<String, String> requiredAttributes = new LinkedHashMap<>();

    public RuntimeRequirement() {
    }

    public String getOs() {
        return os;
    }

    public void setOs(String os) {
        this.os = os;
    }

    public String getArch() {
        return arch;
    }

    public void setArch(String arch) {
        this.arch = arch;
    }

    public String getMinRuntimeVersion() {
        return minRuntimeVersion;
    }

    public void setMinRuntimeVersion(String minRuntimeVersion) {
        this.minRuntimeVersion = minRuntimeVersion;
    }

    public Map<String, String> getRequiredAttributes() {
        return requiredAttributes;
    }

    public void setRequiredAttributes(Map<String, String> requiredAttributes) {
        this.requiredAttributes = requiredAttributes;
    }
}
