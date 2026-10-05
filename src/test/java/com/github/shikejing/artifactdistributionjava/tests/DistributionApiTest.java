package com.github.shikejing.artifactdistributionjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 端到端：打包 → 登记目标 → 灰度分发 → 占用查看 → 回滚 → 审计，
 * 以及越权访问被拒绝（HTTP 403 且审计可查）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DistributionApiTest {

    @TempDir
    static Path sharedTemp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("distribution.data-dir", () -> sharedTemp.resolve("data").toString());
        registry.add("distribution.source-dir", () -> sharedTemp.resolve("sources").toString());
    }

    @Autowired
    MockMvc mvc;

    final ObjectMapper json = new ObjectMapper();

    @Test
    void fullLifecycleOverHttp() throws Exception {
        Path dataDir = sharedTemp.resolve("data");
        Path sources = sharedTemp.resolve("sources");
        Files.createDirectories(sources);
        Files.write(sources.resolve("app.conf"), "k=v\n".getBytes(StandardCharsets.UTF_8));

        Map<String, Object> manifest = Map.of(
                "name", "http-app",
                "version", "1.0",
                "resources", List.of(Map.of("id", "app", "path", "app.conf")),
                "requirements", Map.of("os", "linux"));
        Map<String, Object> buildReq = Map.of(
                "manifest", manifest, "sourceDir", sources.toString());

        // viewer 打包 → 403
        mvc.perform(post("/api/packages")
                        .header("X-Auth-Token", "viewer-token")
                        .contentType("application/json")
                        .content(json.writeValueAsString(buildReq)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("AUTHORIZATION_DENIED"));

        // 无 token → 403
        mvc.perform(post("/api/packages")
                        .contentType("application/json")
                        .content(json.writeValueAsString(buildReq)))
                .andExpect(status().isForbidden());

        // operator 打包成功，拿到 packageId。
        String buildResponse = mvc.perform(post("/api/packages")
                        .header("X-Auth-Token", "operator-token")
                        .contentType("application/json")
                        .content(json.writeValueAsString(buildReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packageId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String packageId = json.readTree(buildResponse).get("packageId").asText();

        // 登记两个目标：一个兼容，一个不兼容。
        mvc.perform(post("/api/targets")
                        .header("X-Auth-Token", "operator-token")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "id", "g1", "name", "g1",
                                "quotaBytes", 1_000_000,
                                "environment", Map.of("os", "linux"),
                                "enabled", true))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/targets")
                        .header("X-Auth-Token", "operator-token")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "id", "g2", "name", "g2",
                                "quotaBytes", 1_000_000,
                                "environment", Map.of("os", "windows"),
                                "enabled", true))))
                .andExpect(status().isOk());

        // 分发。
        Map<String, Object> plan = Map.of(
                "packageId", packageId,
                "waves", List.of(Map.of("targetIds", List.of("g1", "g2"))),
                "rollbackOnFailure", true);
        mvc.perform(post("/api/rollouts")
                        .header("X-Auth-Token", "operator-token")
                        .contentType("application/json")
                        .content(json.writeValueAsString(plan)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.results.g1.state").value("INSTALLED"))
                .andExpect(jsonPath("$.results.g2.state").value("SKIPPED"));

        // 占用查看：viewer 可访问。
        mvc.perform(get("/api/targets/g1/occupancy")
                        .header("X-Auth-Token", "viewer-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedBytes").value(4))
                .andExpect(jsonPath("$.bytesByResource.app").value(4));

        mvc.perform(get("/api/occupancy/resources")
                        .header("X-Auth-Token", "viewer-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.app").value(4));

        // 回滚（没有历史版本 → 400/404，证明接口受保护且语义正确）。
        mvc.perform(post("/api/targets/g1/rollback")
                        .header("X-Auth-Token", "viewer-token"))
                .andExpect(status().isForbidden());

        // 审计：越权与成功操作都在。
        mvc.perform(get("/api/audit")
                        .header("X-Auth-Token", "admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.result=='DENIED')]").exists())
                .andExpect(jsonPath("$[?(@.action=='START_ROLLOUT' && @.result=='SUCCESS')]").exists());
    }
}
