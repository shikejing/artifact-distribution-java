package com.github.shikejing.artifactdistributionjava.service;

import com.github.shikejing.artifactdistributionjava.model.PackageMeta;
import com.github.shikejing.artifactdistributionjava.model.Target;

import java.util.Map;
import java.util.Optional;

/**
 * 运行环境兼容性校验。
 *
 * <p>资源包在 requirements 中声明环境要求，目标在 environment 中登记自身能力。
 * 支持的约束形式：
 * <ul>
 *   <li>{@code key=minVersion} 形式，若 key 以 ".min" 结尾则按语义化/数字版本做最低版本比较；</li>
 *   <li>普通 {@code key=value}：目标环境必须存在同名键且取值完全相等；</li>
 *   <li>目标缺少对应键即视为不满足。</li>
 * </ul>
 * 任一约束不满足时返回可读原因；跳过不影响其他目标推进。
 */
public class CompatibilityChecker {

    /**
     * @return 不兼容时返回原因；兼容时返回 empty
     */
    public Optional<String> check(PackageMeta meta, Target target) {
        Map<String, String> requirements = meta.requirements();
        if (requirements == null || requirements.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> env = target.environment();
        for (Map.Entry<String, String> requirement : requirements.entrySet()) {
            String key = requirement.getKey();
            String required = requirement.getValue();

            if (key.endsWith(".min")) {
                String baseKey = key.substring(0, key.length() - ".min".length());
                String actual = env.get(baseKey);
                if (actual == null) {
                    return Optional.of(String.format(
                            "目标[%s]缺少环境属性[%s]，资源包要求最低版本 %s",
                            target.id(), baseKey, required));
                }
                if (compareVersions(actual, required) < 0) {
                    return Optional.of(String.format(
                            "目标[%s]环境[%s]=%s 低于资源包要求的最低版本 %s",
                            target.id(), baseKey, actual, required));
                }
            } else {
                String actual = env.get(key);
                if (actual == null) {
                    return Optional.of(String.format(
                            "目标[%s]缺少环境属性[%s]（要求值: %s）", target.id(), key, required));
                }
                if (!actual.equals(required)) {
                    return Optional.of(String.format(
                            "目标[%s]环境[%s]=%s 与资源包要求 %s 不匹配",
                            target.id(), key, actual, required));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 比较两个点分版本（数值段优先，非数值段按字符串兜底）。
     *
     * @return 负数/0/正数，分别表示 left 小于/等于/大于 right
     */
    static int compareVersions(String left, String right) {
        String[] ls = left.trim().split("\\.");
        String[] rs = right.trim().split("\\.");
        int length = Math.max(ls.length, rs.length);
        for (int i = 0; i < length; i++) {
            String l = i < ls.length ? ls[i] : "0";
            String r = i < rs.length ? rs[i] : "0";
            int cmp;
            try {
                cmp = Long.compare(Long.parseLong(l.trim()), Long.parseLong(r.trim()));
            } catch (NumberFormatException e) {
                cmp = l.compareTo(r);
            }
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }
}
