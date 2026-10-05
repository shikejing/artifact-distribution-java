package com.github.shikejing.artifactdistributionjava.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 声明式资源清单：描述一个资源包包含哪些资源以及运行环境要求。
 */
public class Manifest {
    private String name;
    private String version;
    private List<ResourceDecl> resources = new ArrayList<>();
    private RuntimeRequirement requirement;

    public Manifest() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public List<ResourceDecl> getResources() {
        return resources;
    }

    public void setResources(List<ResourceDecl> resources) {
        this.resources = resources;
    }

    public RuntimeRequirement getRequirement() {
        return requirement;
    }

    public void setRequirement(RuntimeRequirement requirement) {
        this.requirement = requirement;
    }
}
