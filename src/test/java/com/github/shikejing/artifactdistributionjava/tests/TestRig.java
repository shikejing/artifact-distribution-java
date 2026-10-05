package com.github.shikejing.artifactdistributionjava.tests;

import com.github.shikejing.artifactdistributionjava.compat.CompatibilityService;
import com.github.shikejing.artifactdistributionjava.dist.DistributionService;
import com.github.shikejing.artifactdistributionjava.domain.Manifest;
import com.github.shikejing.artifactdistributionjava.domain.ResourceDecl;
import com.github.shikejing.artifactdistributionjava.domain.RuntimeRequirement;
import com.github.shikejing.artifactdistributionjava.domain.Target;
import com.github.shikejing.artifactdistributionjava.pack.PackageBuilder;
import com.github.shikejing.artifactdistributionjava.quota.QuotaService;
import com.github.shikejing.artifactdistributionjava.registry.TargetRegistry;
import com.github.shikejing.artifactdistributionjava.security.AuditLog;
import com.github.shikejing.artifactdistributionjava.store.StateStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 测试基建：在一组临时目录上手工组装服务，模拟进程重启时重建整组实例。
 */
class TestRig {

    final StateStore store;
    final TargetRegistry registry;
    final PackageBuilder builder;
    final DistributionService distribution;
    final AuditLog audit;
    final QuotaService quota;

    TestRig(Path dataDir) {
        this.store = new StateStore(dataDir.toString());
        this.store.init();
        this.registry = new TargetRegistry(store);
        this.builder = new PackageBuilder();
        this.audit = new AuditLog(store);
        this.quota = new QuotaService();
        this.distribution = new DistributionService(store, registry, new CompatibilityService(),
                quota, builder, audit);
    }

    static Manifest manifest(String name, String version, ResourceDecl... resources) {
        Manifest m = new Manifest();
        m.setName(name);
        m.setVersion(version);
        m.setResources(List.of(resources));
        return m;
    }

    static ResourceDecl resource(String name, String content) {
        return new ResourceDecl(name, "file", content);
    }

    static Target target(String id) {
        Target t = new Target(id);
        t.setAttributes(Map.of("os", "linux", "arch", "amd64", "runtimeVersion", "2.0"));
        return t;
    }

    static RuntimeRequirement requirement(String os, String arch, String minRuntimeVersion) {
        RuntimeRequirement r = new RuntimeRequirement();
        r.setOs(os);
        r.setArch(arch);
        r.setMinRuntimeVersion(minRuntimeVersion);
        return r;
    }
}
