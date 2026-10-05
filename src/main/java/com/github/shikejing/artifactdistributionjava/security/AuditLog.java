package com.github.shikejing.artifactdistributionjava.security;

import com.github.shikejing.artifactdistributionjava.store.StateStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 全流程审计日志（持久化，含越权拒绝记录）。
 */
@Component
public class AuditLog {

    private final StateStore store;

    public AuditLog(StateStore store) {
        this.store = store;
    }

    public void record(String actor, String action, String detail, boolean allowed) {
        store.appendAudit(actor == null ? "<anonymous>" : actor, action, detail, allowed);
    }

    public List<String> entries() {
        return store.listAuditLines();
    }
}
