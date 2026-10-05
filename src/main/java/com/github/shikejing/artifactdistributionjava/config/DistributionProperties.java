package com.github.shikejing.artifactdistributionjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 分发系统配置项（前缀 distribution.*）。
 */
@ConfigurationProperties(prefix = "distribution")
public class DistributionProperties {

    /** 本地数据根目录：目标登记、安装状态、审计、包仓库均存于此。 */
    private String dataDir = "data/distribution";

    /** 打包来源根目录（声明式资源的相对路径基于它解析），默认仅作回退。 */
    private String sourceDir = "data/sources";

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }
    public String getSourceDir() { return sourceDir; }
    public void setSourceDir(String sourceDir) { this.sourceDir = sourceDir; }
}
