package com.ipgo.server.config;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// #244 dev QA 계정의 요금제 제한 풀기. dev 전용이고 운영에는 넣지 않는다.
// - full-access-user-ids(IPGO_QA_FULL_ACCESS_USER_IDS): 쉼표로 구분한 회원 ID. 기본은 비어 있어 지금과 같다.
// - 목록의 회원은 구독 상태와 상관없이 권한(entitlements)만 모두 허용한다. 결제 · 구독 기록은 바꾸지 않는다.
// - 숫자가 아니거나 1보다 작은 값은 서버 시작에서 막는다.
@ConfigurationProperties("ipgo.qa")
public record QaAccessProperties(@DefaultValue Set<Long> fullAccessUserIds) {
    public QaAccessProperties {
        if (fullAccessUserIds == null) fullAccessUserIds = Set.of();
        for (Long id : fullAccessUserIds)
            if (id == null || id < 1) throw new IllegalStateException("IPGO_QA_FULL_ACCESS_USER_IDS must list positive user ids");
        fullAccessUserIds = Set.copyOf(fullAccessUserIds);
    }

    public boolean fullAccess(long userId) {
        return fullAccessUserIds.contains(userId);
    }
}
