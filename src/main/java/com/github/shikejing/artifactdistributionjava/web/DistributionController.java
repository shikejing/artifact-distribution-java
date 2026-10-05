package com.github.shikejing.artifactdistributionjava.web;

import com.github.shikejing.artifactdistributionjava.model.Action;
import com.github.shikejing.artifactdistributionjava.model.AuditEntry;
import com.github.shikejing.artifactdistributionjava.model.InstallRecord;
import com.github.shikejing.artifactdistributionjava.model.Manifest;
import com.github.shikejing.artifactdistributionjava.model.OccupancyView;
import com.github.shikejing.artifactdistributionjava.model.PackageMeta;
import com.github.shikejing.artifactdistributionjava.model.RolloutJournal;
import com.github.shikejing.artifactdistributionjava.model.RolloutPlan;
import com.github.shikejing.artifactdistributionjava.model.Target;
import com.github.shikejing.artifactdistributionjava.security.AuthService;
import com.github.shikejing.artifactdistributionjava.service.AuditLog;
import com.github.shikejing.artifactdistributionjava.service.OccupancyTracker;
import com.github.shikejing.artifactdistributionjava.service.PackageBuilder;
import com.github.shikejing.artifactdistributionjava.service.PackageStore;
import com.github.shikejing.artifactdistributionjava.service.RollbackService;
import com.github.shikejing.artifactdistributionjava.service.RolloutService;
import com.github.shikejing.artifactdistributionjava.service.TargetRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 打包 / 登记 / 分发 / 回滚 / 占用查看 / 审计的 REST 入口。
 *
 * <p>所有接口都要求 {@code X-Auth-Token} 头，由 {@link AuthService} 统一做
 * 认证、授权与越权审计。查看类操作使用 VIEW 权限，写操作使用对应写权限。
 */
@RestController
@RequestMapping("/api")
public class DistributionController {

    private final AuthService authService;
    private final PackageBuilder packageBuilder;
    private final PackageStore packageStore;
    private final TargetRegistry targetRegistry;
    private final RolloutService rolloutService;
    private final RollbackService rollbackService;
    private final OccupancyTracker occupancyTracker;
    private final AuditLog auditLog;

    public DistributionController(AuthService authService,
                                  PackageBuilder packageBuilder,
                                  PackageStore packageStore,
                                  TargetRegistry targetRegistry,
                                  RolloutService rolloutService,
                                  RollbackService rollbackService,
                                  OccupancyTracker occupancyTracker,
                                  AuditLog auditLog) {
        this.authService = authService;
        this.packageBuilder = packageBuilder;
        this.packageStore = packageStore;
        this.targetRegistry = targetRegistry;
        this.rolloutService = rolloutService;
        this.rollbackService = rollbackService;
        this.occupancyTracker = occupancyTracker;
        this.auditLog = auditLog;
    }

    /** 打包请求：声明式清单 + 可选的本地来源目录。 */
    public record BuildRequest(Manifest manifest, String sourceDir) {
    }

    @PostMapping("/packages")
    public PackageMeta buildPackage(@RequestHeader("X-Auth-Token") String token,
                                    @RequestBody BuildRequest request) {
        var user = authService.require(token, Action.BUILD_PACKAGE);
        if (request == null || request.manifest() == null) {
            throw new IllegalArgumentException("请求体必须包含 manifest");
        }
        PackageMeta meta = packageBuilder.build(request.manifest(),
                request.sourceDir() == null ? null : Path.of(request.sourceDir()));
        authService.audit(user, Action.BUILD_PACKAGE, meta.packageId(), "SUCCESS",
                "name=" + meta.name() + ", version=" + meta.version()
                        + ", resources=" + meta.entries().size());
        return meta;
    }

    @GetMapping("/packages")
    public List<PackageMeta> listPackages(@RequestHeader("X-Auth-Token") String token) {
        authService.require(token, Action.VIEW);
        return packageStore.list();
    }

    @GetMapping("/packages/{id}")
    public PackageMeta getPackage(@RequestHeader("X-Auth-Token") String token,
                                  @PathVariable String id) {
        authService.require(token, Action.VIEW);
        return packageStore.verifyAndLoadMeta(id);
    }

    @PostMapping("/targets")
    public Target registerTarget(@RequestHeader("X-Auth-Token") String token,
                                 @RequestBody Target target) {
        var user = authService.require(token, Action.REGISTER_TARGET);
        Target registered = targetRegistry.register(target);
        authService.audit(user, Action.REGISTER_TARGET, registered.id(), "SUCCESS",
                "quotaBytes=" + registered.quotaBytes() + ", env=" + registered.environment());
        return registered;
    }

    @GetMapping("/targets")
    public List<Target> listTargets(@RequestHeader("X-Auth-Token") String token) {
        authService.require(token, Action.VIEW);
        return targetRegistry.list();
    }

    @PostMapping("/rollouts")
    public RolloutJournal startRollout(@RequestHeader("X-Auth-Token") String token,
                                       @RequestBody RolloutPlan plan) {
        var user = authService.require(token, Action.START_ROLLOUT);
        return rolloutService.startAndRun(user, plan);
    }

    @PostMapping("/rollouts/{id}/resume")
    public RolloutJournal resumeRollout(@RequestHeader("X-Auth-Token") String token,
                                        @PathVariable String id) {
        var user = authService.require(token, Action.START_ROLLOUT);
        return rolloutService.resume(id);
    }

    @GetMapping("/rollouts/{id}")
    public RolloutJournal getRollout(@RequestHeader("X-Auth-Token") String token,
                                     @PathVariable String id) {
        authService.require(token, Action.VIEW);
        return rolloutService.get(id);
    }

    @GetMapping("/rollouts")
    public List<RolloutJournal> listRollouts(@RequestHeader("X-Auth-Token") String token) {
        authService.require(token, Action.VIEW);
        return rolloutService.list();
    }

    @PostMapping("/targets/{id}/rollback")
    public InstallRecord rollback(@RequestHeader("X-Auth-Token") String token,
                                  @PathVariable String id) {
        var user = authService.require(token, Action.ROLLBACK);
        return rollbackService.rollbackTarget(user, id);
    }

    @GetMapping("/targets/{id}/occupancy")
    public OccupancyView occupancy(@RequestHeader("X-Auth-Token") String token,
                                   @PathVariable String id) {
        authService.require(token, Action.VIEW);
        return occupancyTracker.targetOccupancy(id);
    }

    @GetMapping("/occupancy/packages")
    public Map<String, Long> occupancyByPackage(@RequestHeader("X-Auth-Token") String token) {
        authService.require(token, Action.VIEW);
        return occupancyTracker.occupancyByPackage();
    }

    @GetMapping("/occupancy/resources")
    public Map<String, Long> occupancyByResource(@RequestHeader("X-Auth-Token") String token) {
        authService.require(token, Action.VIEW);
        return occupancyTracker.occupancyByResource();
    }

    @GetMapping("/audit")
    public List<AuditEntry> audit(@RequestHeader("X-Auth-Token") String token) {
        var user = authService.require(token, Action.VIEW);
        authService.audit(user, Action.VIEW, "audit", "SUCCESS", "查看审计日志");
        return auditLog.readAll();
    }
}
