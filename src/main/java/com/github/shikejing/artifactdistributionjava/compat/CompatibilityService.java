package com.github.shikejing.artifactdistributionjava.compat;

import com.github.shikejing.artifactdistributionjava.domain.RuntimeRequirement;
import com.github.shikejing.artifactdistributionjava.domain.Target;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 兼容性约束校验：资源包声明的运行环境要求 vs 目标属性。
 * 目标属性约定：os、arch、runtimeVersion 为内置键，其余键按需匹配 requiredAttributes。
 */
@Service
public class CompatibilityService {

    /**
     * 返回不满足的原因列表（可核对），空列表表示兼容。
     */
    public List<String> check(RuntimeRequirement requirement, Target target) {
        List<String> reasons = new ArrayList<>();
        if (requirement == null) {
            return reasons;
        }
        Map<String, String> attrs = target.getAttributes();

        if (requirement.getOs() != null && !requirement.getOs().isBlank()
                && !requirement.getOs().equalsIgnoreCase(attrs.getOrDefault("os", ""))) {
            reasons.add("os mismatch: required=" + requirement.getOs()
                    + " actual=" + attrs.getOrDefault("os", "<missing>"));
        }
        if (requirement.getArch() != null && !requirement.getArch().isBlank()
                && !requirement.getArch().equalsIgnoreCase(attrs.getOrDefault("arch", ""))) {
            reasons.add("arch mismatch: required=" + requirement.getArch()
                    + " actual=" + attrs.getOrDefault("arch", "<missing>"));
        }
        if (requirement.getMinRuntimeVersion() != null && !requirement.getMinRuntimeVersion().isBlank()) {
            String actual = attrs.get("runtimeVersion");
            if (actual == null || compareVersion(actual, requirement.getMinRuntimeVersion()) < 0) {
                reasons.add("runtimeVersion too low: required>=" + requirement.getMinRuntimeVersion()
                        + " actual=" + (actual == null ? "<missing>" : actual));
            }
        }
        requirement.getRequiredAttributes().forEach((key, expected) -> {
            String actual = attrs.get(key);
            if (!expected.equals(actual)) {
                reasons.add("attribute mismatch: " + key + " required=" + expected
                        + " actual=" + (actual == null ? "<missing>" : actual));
            }
        });
        return reasons;
    }

    /**
     * 点分数字版本比较，返回负数/零/正数。非数字段按字符串比较。
     */
    static int compareVersion(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            String sa = i < pa.length ? pa[i] : "0";
            String sb = i < pb.length ? pb[i] : "0";
            int cmp;
            if (isNumeric(sa) && isNumeric(sb)) {
                cmp = Long.compare(Long.parseLong(sa), Long.parseLong(sb));
            } else {
                cmp = sa.compareTo(sb);
            }
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private static boolean isNumeric(String s) {
        return !s.isEmpty() && s.chars().allMatch(Character::isDigit);
    }
}
