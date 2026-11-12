package com.ipgo.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ipgo.push.delivery")
public record PushDeliveryProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("") String privateKey, @DefaultValue("https://ipgo-dev.iwhya.kr") String subject) {
    // record의 기본 toString은 비밀키를 포함하므로 출력하지 않는다.
    @Override public String toString() { return "PushDeliveryProperties[enabled=" + enabled + "]"; }
}
