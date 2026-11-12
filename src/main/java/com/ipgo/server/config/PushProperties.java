package com.ipgo.server.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// #190 Web Push 설정.
// - publicKey: 브라우저 등록에 쓰는 VAPID 공개키(base64url). 비어 있으면 알림 등록을 쓸 수 없다(503).
// - allowedHosts: 구독 주소(endpoint)로 받을 브라우저 회사 푸시 서버. 서버가 이 주소로 직접 요청을 보내므로
//   아무 주소나 받으면 내부망 요청(SSRF)에 쓰일 수 있어 알려진 푸시 서버만 받는다. 앞에 점이 있으면 하위 도메인까지 허용한다.
@ConfigurationProperties("ipgo.push")
public record PushProperties(@DefaultValue("") String publicKey,
        @DefaultValue({"fcm.googleapis.com", ".push.services.mozilla.com", ".notify.windows.com", ".push.apple.com"})
        List<String> allowedHosts) {
}
