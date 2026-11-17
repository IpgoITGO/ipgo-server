package com.ipgo.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// #244 QA 계정 목록이 켜져 있으면 서버 시작 로그에 경고를 남긴다. 회원 ID 개수만 남기고 ID · 이메일은 남기지 않는다.
@Configuration
@EnableConfigurationProperties(QaAccessProperties.class)
public class QaAccessConfig {
    private static final Logger log = LoggerFactory.getLogger(QaAccessConfig.class);

    public QaAccessConfig(QaAccessProperties properties) {
        int count = properties.fullAccessUserIds().size();
        if (count > 0)
            log.warn("QA full access is enabled for {} user id(s); billing entitlements are bypassed for them. Dev only, never set IPGO_QA_FULL_ACCESS_USER_IDS in production.", count);
    }
}
