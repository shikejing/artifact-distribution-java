package com.github.shikejing.artifactdistributionjava.quota;

import com.github.shikejing.artifactdistributionjava.domain.BundleInfo;
import com.github.shikejing.artifactdistributionjava.domain.Quota;
import com.github.shikejing.artifactdistributionjava.domain.ResourceDecl;
import com.github.shikejing.artifactdistributionjava.domain.Target;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资源占用追踪与配额校验。
 * 占用口径：目标上每个已提交（COMMITTED）资源包计 1 个包占用，
 * 其每个资源计 1 个资源占用与 content 字节数占用。
 */
@Service
public class QuotaService {

    /**
     * 校验在目标上安装指定资源包是否超出配额；返回可核对的拒绝原因列表，空列表表示允许。
     * 同名包（同 bundleId）重复安装不重复计占用。
     */
    public List<String> checkInstall(Target target, BundleInfo bundle, List<BundleInfo> installedBundles) {
        List<String> reasons = new ArrayList<>();
        Quota quota = target.getQuota() == null ? new Quota() : target.getQuota();

        List<BundleInfo> effective = installedBundles.stream()
                .filter(b -> !b.getBundleId().equals(bundle.getBundleId()))
                .toList();

        int bundlesAfter = effective.size() + 1;
        int resourcesAfter = effective.stream().mapToInt(BundleInfo::getResourceCount).sum()
                + bundle.getResourceCount();
        long bytesAfter = effective.stream().mapToLong(this::bundleContentBytes).sum()
                + bundleContentBytes(bundle);

        if (bundlesAfter > quota.getMaxBundles()) {
            reasons.add("bundle count quota exceeded: max=" + quota.getMaxBundles()
                    + " current=" + effective.size() + " requested=+1");
        }
        if (resourcesAfter > quota.getMaxResources()) {
            reasons.add("resource count quota exceeded: max=" + quota.getMaxResources()
                    + " current=" + (resourcesAfter - bundle.getResourceCount())
                    + " requested=+" + bundle.getResourceCount());
        }
        if (bytesAfter > quota.getMaxBytes()) {
            reasons.add("byte quota exceeded: max=" + quota.getMaxBytes()
                    + " current=" + (bytesAfter - bundleContentBytes(bundle))
                    + " requested=+" + bundleContentBytes(bundle));
        }
        return reasons;
    }

    /**
     * 查询目标当前的占用情况：bundleId -> 该包在目标上的资源占用视图。
     */
    public Map<String, UsageView> usageOf(String targetId, List<BundleInfo> installedBundles) {
        Map<String, UsageView> usage = new LinkedHashMap<>();
        for (BundleInfo b : installedBundles) {
            List<String> names = b.getManifest().getResources().stream()
                    .map(ResourceDecl::getName)
                    .sorted()
                    .toList();
            usage.put(b.getBundleId(),
                    new UsageView(b.getBundleId(), b.getResourceCount(), bundleContentBytes(b), names));
        }
        return usage;
    }

    private long bundleContentBytes(BundleInfo b) {
        if (b.getManifest() == null) {
            return b.getSizeBytes();
        }
        return b.getManifest().getResources().stream()
                .mapToLong(r -> (r.getContent() == null ? "" : r.getContent())
                        .getBytes(StandardCharsets.UTF_8).length)
                .sum();
    }

    /**
     * 单个已安装资源包在某目标上的占用视图。
     */
    public record UsageView(String bundleId, int resourceCount, long bytes, List<String> resourceNames) {
    }
}
