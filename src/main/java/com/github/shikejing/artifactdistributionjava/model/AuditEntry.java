package com.github.shikejing.artifactdistributionjava.model;

/**
 * 审计日志条目（append-only）。
 */
public record AuditEntry(long timestamp,
                        String actor,
                        String action,
                        String target,
                        String result,
                        String detail) {
}
