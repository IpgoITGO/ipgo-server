package com.ipgo.server.integration;

import com.ipgo.server.DatabaseIntegrationTest;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = { "springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true" })
@AutoConfigureMockMvc
class ApiDocsIntegrationTests extends DatabaseIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;

    @Test
    void receiptCorrectionRequestResponseAndPageModelsStayDistinct() throws Exception {
        var document = mapper.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        var preview = document.path("paths").path("/api/receipt-corrections/{reviewId}/preview").path("post");
        var request = preview.path("requestBody").path("content").properties().iterator().next().getValue().path("schema");
        var response = preview.path("responses").path("200").path("content").properties().iterator().next().getValue().path("schema");
        assertThat(request.path("$ref").asText()).isNotEqualTo(response.path("$ref").asText());
        var requestModel = document.path("components").path("schemas").path(request.path("$ref").asText().replace("#/components/schemas/", ""));
        var responseModel = document.path("components").path("schemas").path(response.path("$ref").asText().replace("#/components/schemas/", ""));
        assertThat(requestModel.path("properties").has("expectedVersion")).isTrue();
        assertThat(requestModel.path("properties").has("receiptIds")).isTrue();
        assertThat(responseModel.path("properties").has("before")).isTrue();
        assertThat(responseModel.path("properties").has("after")).isTrue();
        var pageRef = document.path("paths").path("/api/receipt-corrections").path("get").path("responses").path("200")
                .path("content").properties().iterator().next().getValue().path("schema").path("$ref").asText();
        var page = document.path("components").path("schemas").path(pageRef.replace("#/components/schemas/", ""));
        assertThat(page.path("properties").has("nextCursor")).isTrue();
    }

    @Test
    void apiDocumentDescribesRealRoutesAndTheirAuthentication() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi", startsWith("3.")))
                .andExpect(jsonPath("$.info.title").value("입고 개발 API"))
                .andExpect(jsonPath("$.servers[0].url").value("/"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/api/auth/csrf'].get.security").isEmpty())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.security").isEmpty())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.requestBody.content['application/x-www-form-urlencoded']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.parameters[?(@.name == 'X-XSRF-TOKEN')].in").value(hasItem("header")))
                .andExpect(jsonPath("$.paths['/api/auth/me'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/products'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/actuator/health']").doesNotExist());
    }

    @Test
    void swaggerPageAssetsAndConfigAreAvailableWithoutLogin() throws Exception {
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"));
        mvc.perform(get("/swagger-ui/swagger-ui-bundle.js")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/v3/api-docs"))
                .andExpect(jsonPath("$.persistAuthorization").value(false));
    }

    @Test
    void openingDocumentationDoesNotOpenBusinessOrWriteEndpoints() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/products")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login")).andExpect(status().isForbidden());
        mvc.perform(post("/v3/api-docs")).andExpect(status().isUnauthorized());
    }
}
