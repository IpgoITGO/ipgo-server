package com.ipgo.server.config;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class ApiDocsConfiguration {
    private static final String BEARER_AUTH = "bearerAuth";
    private static final Set<String> PUBLIC_API = Set.of(
            "GET /api/auth/csrf", "GET /api/auth/email-availability",
            "POST /api/auth/signup", "POST /api/auth/login", "POST /api/auth/refresh", "POST /api/auth/logout",
            "POST /api/auth/email-verifications", "POST /api/auth/email-verifications/confirm");
    private static final Map<String, String> TAGS = Map.of(
            "auth", "로그인·계정", "products", "상품", "uploads", "파일 업로드",
            "dashboard", "대시보드", "replenishment-settings", "입고 조건", "planned-receipts", "기존 발주");

    @Bean
    OpenAPI developmentApi() {
        return new OpenAPI()
                .info(new Info().title("입고 개발 API").version("v1")
                        .description("개발자가 API 목록과 입력·응답 형식을 확인하는 문서입니다. "
                                + "현재 HTTP 주소에서는 연결만 확인하고, 실제 계정 정보·토큰 입력과 로그인 전체 검증은 HTTPS 전환 후 진행하세요. "
                                + "로그인은 form 형식이며 쿠키를 사용하는 인증 요청에는 먼저 /api/auth/csrf에서 받은 확인 토큰이 필요합니다."))
                .servers(List.of(new Server().url("/")))
                .components(new Components().addSecuritySchemes(BEARER_AUTH,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                                .description("HTTPS 연결에서 받은 액세스 토큰을 입력합니다. 로그인·갱신용 쿠키는 별도로 처리합니다.")));
    }

    @Bean
    OpenApiCustomizer documentApiAccess() {
        return api -> api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
            String endpoint = method + " " + path;
            operation.setSecurity(PUBLIC_API.contains(endpoint)
                    ? List.of() : List.of(new SecurityRequirement().addList(BEARER_AUTH)));
            if (PUBLIC_API.contains(endpoint) && method == io.swagger.v3.oas.models.PathItem.HttpMethod.POST) {
                operation.addParametersItem(new Parameter().name("X-XSRF-TOKEN").in("header").required(true)
                        .description("먼저 GET /api/auth/csrf에서 받은 token 값을 입력합니다. HTTPS에서 쿠키와 함께 보냅니다.")
                        .schema(new StringSchema()));
            }
            String[] segments = path.split("/");
            if (segments.length > 2 && TAGS.containsKey(segments[2])) {
                operation.setTags(List.of(TAGS.get(segments[2])));
            }
        }));
    }
}
