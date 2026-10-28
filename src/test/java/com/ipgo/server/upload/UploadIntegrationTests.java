package com.ipgo.server.upload;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.auth.UserAccountRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class UploadIntegrationTests extends DatabaseIntegrationTest {
    @TempDir static Path root;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired UserAccountRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Value("${local.server.port}") int port;

    @DynamicPropertySource
    static void uploadProperties(DynamicPropertyRegistry registry) {
        registry.add("ipgo.upload.directory", () -> root.toString());
    }

    @Test
    void requiresAuthenticationAndCsrf() throws Exception {
        var file = new MockMultipartFile("file", "stock.xlsx", "application/octet-stream", UploadTestFiles.workbook());
        mvc.perform(multipart("/api/uploads").file(file).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/uploads").file(file).with(user("777"))).andExpect(status().isForbidden());
        assertThat(Files.exists(root.resolve("777"))).isFalse();
    }

    @Test
    void bindsOwnerToSessionAndReturnsOnlyPublicReceipt() throws Exception {
        var result = mvc.perform(multipart("/api/uploads")
                        .file(new MockMultipartFile("file", "stock.xlsx", "application/octet-stream", UploadTestFiles.workbook()))
                        .param("userId", "999").with(user("888")).with(csrf()))
                .andExpect(status().isCreated()).andExpect(jsonPath("status").value("STORED"))
                .andExpect(jsonPath("uploadId").isString()).andExpect(jsonPath("sha256").isString())
                .andExpect(jsonPath("storedAt").isString()).andExpect(jsonPath("size").isNumber())
                .andExpect(jsonPath("storageKey").doesNotExist()).andReturn();
        var receipt = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(Files.exists(root.resolve("888").resolve(receipt.get("uploadId").stringValue()).resolve("original.xlsx"))).isTrue();
        assertThat(Files.exists(root.resolve("999"))).isFalse();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(root.toString());
    }

    @Test
    void returnsJsonForMissingInvalidAndOversizeFiles() throws Exception {
        mvc.perform(multipart("/api/uploads").with(user("123")).with(csrf()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_INPUT"));
        mvc.perform(multipart("/api/uploads").file(new MockMultipartFile("file", "file.csv", "text/csv", new byte[] {1}))
                        .with(user("123")).with(csrf()))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("code").value("INVALID_FILE_TYPE"));
        mvc.perform(multipart("/api/uploads").file(new MockMultipartFile("file", "file.xlsx", "application/octet-stream",
                                new byte[10 * 1024 * 1024 + 1])).with(user("123")).with(csrf()))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("code").value("FILE_TOO_LARGE"));
    }

    @Test
    void realHttpSessionUploadsOriginalAndContainerRejectsOversizeFile() throws Exception {
        String email = "upload-" + UUID.randomUUID() + "@example.test";
        // 업로드 테스트는 가입 절차와 독립적인 기존 회원을 사용한다.
        jdbc.update("INSERT INTO users (email, password_hash) VALUES (?, ?)", email,
                passwordEncoder.encode("test-upload-password-123"));
        var account = users.findByEmail(email).orElseThrow();
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).connectTimeout(Duration.ofSeconds(5)).build()) {
            JsonNode token = mapper.readTree(send(client, HttpRequest.newBuilder(uri("/api/auth/csrf")).GET()).body());
            var login = send(client, HttpRequest.newBuilder(uri("/api/auth/login"))
                    .header(token.get("headerName").stringValue(), token.get("token").stringValue())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("email=" + email + "&password=test-upload-password-123")));
            assertThat(login.statusCode()).isEqualTo(204);
            token = mapper.readTree(send(client, HttpRequest.newBuilder(uri("/api/auth/csrf")).GET()).body());
            byte[] original = UploadTestFiles.workbook();
            var uploaded = send(client, uploadRequest(original, token));
            assertThat(uploaded.statusCode()).isEqualTo(201);
            String id = mapper.readTree(uploaded.body()).get("uploadId").stringValue();
            assertThat(Files.readAllBytes(root.resolve(account.getId().toString()).resolve(id).resolve("original.xlsx"))).isEqualTo(original);
            var oversized = send(client, uploadRequest(new byte[10 * 1024 * 1024 + 1], token));
            assertThat(oversized.statusCode()).isEqualTo(413);
            assertThat(mapper.readTree(oversized.body()).get("code").stringValue()).isEqualTo("FILE_TOO_LARGE");
        }
    }

    private HttpRequest.Builder uploadRequest(byte[] bytes, JsonNode token) {
        String boundary = "ipgo-test-boundary";
        byte[] before = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"stock.xlsx\"\r\n"
                + "Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] after = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        return HttpRequest.newBuilder(uri("/api/uploads"))
                .header(token.get("headerName").stringValue(), token.get("token").stringValue())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(before),
                        HttpRequest.BodyPublishers.ofByteArray(bytes), HttpRequest.BodyPublishers.ofByteArray(after)));
    }

    private HttpResponse<String> send(HttpClient client, HttpRequest.Builder request) throws Exception {
        return client.send(request.timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }
}
