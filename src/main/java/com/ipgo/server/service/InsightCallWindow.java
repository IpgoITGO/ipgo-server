package com.ipgo.server.service;

import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** A deployment window is checked again immediately before every outgoing request. */
@Component
public class InsightCallWindow {
    private final Instant startsAt;
    private final Instant expiresAt;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public InsightCallWindow(@Value("${ipgo.insight.demo-starts-at:}") String starts,
                             @Value("${ipgo.insight.demo-expires-at:}") String expires) {
        this(starts, expires, Clock.systemUTC());
    }
    InsightCallWindow(String starts, String expires, Clock clock) {
        this.clock = clock;
        startsAt = starts.isBlank() ? Instant.MIN : Instant.parse(starts);
        expiresAt = expires.isBlank() ? Instant.MAX : Instant.parse(expires);
        if (!startsAt.isBefore(expiresAt)) throw new IllegalArgumentException("Invalid insight window");
    }
    boolean open() { var now = clock.instant(); return !now.isBefore(startsAt) && now.isBefore(expiresAt); }
    boolean limited() { return !expiresAt.equals(Instant.MAX); }
    Instant startsAt() { return startsAt; }
}
