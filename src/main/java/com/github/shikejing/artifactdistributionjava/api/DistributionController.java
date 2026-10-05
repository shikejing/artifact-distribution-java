package com.github.shikejing.artifactdistributionjava.api;

import com.github.shikejing.artifactdistributionjava.dist.DistributionService;
import com.github.shikejing.artifactdistributionjava.domain.BundleInfo;
import com.github.shikejing.artifactdistributionjava.domain.Manifest;
import com.github.shikejing.artifactdistributionjava.domain.Target;
import com.github.shikejing.artifactdistributionjava.pack.PackageBuilder;
import com.github.shikejing.artifactdistributionjava.quota.QuotaService;
import com.github.shikejing.artifactdistributionjava.registry.TargetRegistry;
import com.github.shikejing.artifactdistributionjava.security.AuditLog;
import com.github.shikejing.artifactdistributionjava.security.AuthService;
import com.github.shikejing.artifactdistributionjava.security.Permission;
import com.github.shikejing.artifactdistributionjava.store.StateStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 打包 / 分发 / 回滚 / 查询的 REST 入口。所有操作经权限校验并写审计日志。
 */
@RestController
@RequestMapping("/api")
public class DistributionController {

    private final AuthService auth;
    private final AuditLog audit;
    private final TargetRegistry registry;
    private final PackageBuilder packageBuilder;
    private final StateStore store;
    private final DistributionService distribution;
    private final QuotaService quota;

    public DistributionController(AuthService auth, AuditLog audit, TargetRegistry registry,
                                  PackageBuilder packageBuilder, StateStore store,
                                  DistributionService distribution, QuotaService quota) {
        this.auth = auth;
        this.audit = audit;
        this.registry = registry;
        this.packageBuilder = packageBuilder;
        this.store = store;
        this.distribution = distribution;
        this.quota = quota;
    }

    @PostMapping("/targets")
    public Target registerTarget(@RequestHeader(value = "Authorization", required = false) String token,
                                 @RequestBody Target target) {
        String actor = authorize(token, Permission.REGISTER_TARGET, "register target " + target.getTargetId());
        Target saved = registry.register(target);
        audit.record(actor, "REGISTER_TARGET", "target=" + target.getTargetId(), true);
        return saved;
    }

    @GetMapping("/targets")
    public List<Target> listTargets(@RequestHeader(value = "Authorization", required = false) String token) {
        authorize(token, Permission.VIEW, "list targets");
        return registry.list();
    }

    @PostMapping("/bundles")
    public BundleInfo buildBundle(@RequestHeader(value = "Authorization", required = false) String token,
                                  @RequestBody Manifest manifest) {
        String actor = authorize(token, Permission.PACK, "pack " + manifest.getName());
        BundleInfo info = packageBuilder.buildInfo(manifest);
        byte[] bytes = packageBuilder.build(manifest);
        store.saveBundle(info, bytes);
        audit.record(actor, "PACK", "bundle=" + info.getBundleId() + " sha256=" + info.getSha256(), true);
        return info;
    }

    @GetMapping("/bundles")
    public List<BundleInfo> listBundles(@RequestHeader(value = "Authorization", required = false) String token) {
        authorize(token, Permission.VIEW, "list bundles");
        return store.listBundles();
    }

    @PostMapping("/distributions")
    public DistributionService.RolloutResult distribute(
            @RequestHeader(value = "Authorization", required = false) String token,
            @RequestBody DistributeRequest request) {
        String actor = authorize(token, Permission.DISTRIBUTE, "distribute " + request.bundleId());
        return distribution.distribute(actor, request.bundleId(), request.targetIds(), request.batchSize());
    }

    @PostMapping("/rollbacks")
    public DistributionService.RollbackResult rollback(
            @RequestHeader(value = "Authorization", required = false) String token,
            @RequestBody RollbackRequest request) {
        String actor = authorize(token, Permission.ROLLBACK,
                "rollback " + request.targetId() + "/" + request.bundleName());
        return distribution.rollback(actor, request.targetId(), request.bundleName());
    }

    @GetMapping("/versions")
    public Map<String, Map<String, String>> currentVersions(
            @RequestHeader(value = "Authorization", required = false) String token) {
        authorize(token, Permission.VIEW, "view versions");
        return distribution.currentVersions();
    }

    @GetMapping("/usage/{targetId}")
    public Map<String, QuotaService.UsageView> usage(
            @RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable String targetId) {
        authorize(token, Permission.VIEW, "view usage of " + targetId);
        return quota.usageOf(targetId, distribution.installedBundlesOf(targetId));
    }

    @GetMapping("/audit")
    public List<String> audit(@RequestHeader(value = "Authorization", required = false) String token) {
        authorize(token, Permission.VIEW, "view audit log");
        return audit.entries();
    }

    private String authorize(String token, Permission permission, String detail) {
        try {
            return auth.require(token, permission);
        } catch (SecurityException e) {
            audit.record(auth.resolveActor(token), permission.name(), detail + " -> " + e.getMessage(), false);
            throw e;
        }
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, String>> forbidden(SecurityException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    public record DistributeRequest(String bundleId, List<String> targetIds, int batchSize) {
    }

    public record RollbackRequest(String targetId, String bundleName) {
    }
}
