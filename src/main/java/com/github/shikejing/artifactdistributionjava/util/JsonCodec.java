package com.github.shikejing.artifactdistributionjava.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 确定性 JSON 编解码。
 *
 * <p>输出规则（保证同内容永远得到相同字节）：
 * <ul>
 *     <li>紧凑输出，无缩进、无多余空格；</li>
 *     <li>对象字段按名字字典序输出，Map 键排序；</li>
 *     <li>不写入任何时间戳或版本标记；</li>
 *     <li>行尾固定为 '\n'。</li>
 * </ul>
 */
public final class JsonCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(MapperFeatureHolder.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonCodec() {
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value) + "\n";
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    public static byte[] toJsonBytes(Object value) {
        return toJson(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 反序列化失败: " + e.getMessage(), e);
        }
    }

    public static <T> T fromJson(String json, TypeReference<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 反序列化失败: " + e.getMessage(), e);
        }
    }

    /** 间接持有枚举，避免静态初始化写法怪异。 */
    private static final class MapperFeatureHolder {
        private static final com.fasterxml.jackson.databind.MapperFeature SORT_PROPERTIES_ALPHABETICALLY =
                com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY;
    }
}
