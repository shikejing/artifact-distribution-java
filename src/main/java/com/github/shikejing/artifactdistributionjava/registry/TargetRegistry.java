package com.github.shikejing.artifactdistributionjava.registry;

import com.github.shikejing.artifactdistributionjava.domain.Target;
import com.github.shikejing.artifactdistributionjava.store.StateStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 本地目标登记处：分发目标必须先在此登记，登记信息持久化。
 */
@Service
public class TargetRegistry {

    private final StateStore store;

    public TargetRegistry(StateStore store) {
        this.store = store;
    }

    public Target register(Target target) {
        if (target.getTargetId() == null || target.getTargetId().isBlank()) {
            throw new IllegalArgumentException("targetId is required");
        }
        store.saveTarget(target);
        return target;
    }

    public Optional<Target> find(String targetId) {
        return store.findTarget(targetId);
    }

    public List<Target> list() {
        return store.listTargets();
    }
}
