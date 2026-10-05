package com.github.shikejing.artifactdistributionjava.domain;

/**
 * 目标上某次安装的日志状态。只有 COMMITTED 代表完整生效的版本。
 */
public enum InstallState {
    /** 已开始安装但未提交（进程重启后需回收） */
    PENDING,
    /** 已完整提交，目标当前运行该版本 */
    COMMITTED,
    /** 安装失败，已清理 */
    FAILED,
    /** 曾提交但已被回滚替换 */
    SUPERSEDED
}
