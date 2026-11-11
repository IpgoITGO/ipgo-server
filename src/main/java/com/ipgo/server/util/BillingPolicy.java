package com.ipgo.server.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.Duration;
import java.util.List;
import com.ipgo.server.dto.response.BillingViews.Plan;
import com.ipgo.server.exception.BillingException;

public final class BillingPolicy {
    public static final String VERSION="test-2026-11-500-v2";
    public static final String CONSENT="monthly-2026-11-v1";
    public static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    private BillingPolicy() { }
    public static List<Plan> plans() { return List.of(new Plan("trial",VERSION,0,"KRW","MONTH",300,100,true,14,CONSENT),
            new Plan("seller",VERSION,500,"KRW","MONTH",300,100,true,0,CONSENT),
            new Plan("business",VERSION,129000,"KRW","MONTH",2000,null,true,0,CONSENT)); }
    public static Plan plan(String id) { return plans().stream().filter(p->p.planId().equals(id)).findFirst().orElseThrow(BillingException::invalid); }
    public static Instant boundary(Instant anchor, int month) { return anchor.atZone(SEOUL).plusMonths(month).toInstant(); }
    public static Instant grace(Instant end) { return end.plus(Duration.ofDays(3)); }
}
