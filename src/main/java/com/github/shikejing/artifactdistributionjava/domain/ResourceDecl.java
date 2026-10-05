package com.github.shikejing.artifactdistributionjava.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 声明式资源清单中的单个资源声明。
 */
public class ResourceDecl {
    private String name;
    private String type;
    private String content;
    private Map<String, String> metadata = new LinkedHashMap<>();

    public ResourceDecl() {
    }

    public ResourceDecl(String name, String type, String content) {
        this.name = name;
        this.type = type;
        this.content = content;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }
}
