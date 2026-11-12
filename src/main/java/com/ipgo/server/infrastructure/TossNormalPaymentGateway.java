package com.ipgo.server.infrastructure;

import com.ipgo.server.config.NormalPaymentProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 토스 테스트 상점 전용. 요청/응답/키를 로그에 남기지 않는다. */
@Component
public class TossNormalPaymentGateway implements NormalPaymentGateway {
    private static final String ORIGIN="https://api.tosspayments.com";
    // 나머지 오류는 승인 여부를 보수적으로 UNKNOWN으로 두고 조회한다.
    private static final Set<String> DECLINES=Set.of("REJECT_CARD_COMPANY","EXCEED_MAX_CARD_INSTALLMENT_PLAN",
            "INVALID_CARD_NUMBER","INVALID_CARD_EXPIRATION","NOT_AVAILABLE_PAYMENT","EXCEED_MAX_DAILY_PAYMENT_COUNT",
            "EXCEED_MAX_PAYMENT_AMOUNT","INVALID_STOPPED_CARD","INVALID_CARD_LOST_OR_STOLEN",
            "CARD_PROCESSING_ERROR","NOT_SUPPORTED_INSTALLMENT_PLAN_CARD_OR_MERCHANT");
    private final NormalPaymentProperties properties;
    private final JsonMapper mapper;
    private final HttpClient client;
    @org.springframework.beans.factory.annotation.Autowired
    public TossNormalPaymentGateway(NormalPaymentProperties p,JsonMapper mapper) {
        this(p,mapper,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build());
    }
    TossNormalPaymentGateway(NormalPaymentProperties p,JsonMapper mapper,HttpClient client) { properties=p; this.mapper=mapper; this.client=client; }
    @Override public BillingGateway.Payment confirm(String paymentKey,String order,long amount,String key) {
        return payment(send("POST","/v1/payments/confirm",Map.of("paymentKey",paymentKey,"orderId",order,"amount",amount),key));
    }
    @Override public BillingGateway.Payment lookup(String paymentKey) {
        return payment(send("GET","/v1/payments/"+segment(paymentKey),null,null));
    }
    @Override public BillingGateway.Payment cancel(String paymentKey,String reason,String key) {
        return payment(send("POST","/v1/payments/"+segment(paymentKey)+"/cancel",Map.of("cancelReason",reason),key));
    }
    private BillingGateway.Payment payment(JsonNode n) {
        try {
            String approved=text(n,"approvedAt");
            java.time.Instant approvedAt=approved.isBlank()?null:java.time.OffsetDateTime.parse(approved).toInstant();
            JsonNode cancels=n.path("cancels");
            String canceled=cancels.isArray() && cancels.size()>0?text(cancels.get(cancels.size()-1),"canceledAt"):"";
            java.time.Instant canceledAt=canceled.isBlank()?null:java.time.OffsetDateTime.parse(canceled).toInstant();
            return new BillingGateway.Payment(text(n,"paymentKey"),text(n,"orderId"),n.path("totalAmount").asLong(-1),text(n,"currency"),text(n,"status"),text(n,"mId"),approvedAt,n.path("balanceAmount").asLong(-1),canceledAt);
        } catch(java.time.DateTimeException e) { throw new BillingGateway.Failure(false,"INVALID_PROVIDER_RESPONSE"); }
    }
    private JsonNode send(String method,String path,Object body,String key) {
        if(!properties.enabled() || !properties.secretKey().startsWith("test_gsk_"))
            throw new BillingGateway.Failure(false,"BILLING_DISABLED");
        try {
            var b=HttpRequest.newBuilder(URI.create(ORIGIN+path)).timeout(Duration.ofSeconds(75))
                    .header("Authorization","Basic "+Base64.getEncoder().encodeToString((properties.secretKey()+":").getBytes(StandardCharsets.UTF_8)))
                    .header("Content-Type","application/json");
            if(key!=null) b.header("Idempotency-Key",key);
            b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            var r=client.send(b.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if(r.body().length()>1_000_000) throw new BillingGateway.Failure(false,"PROVIDER_RESPONSE_TOO_LARGE");
            JsonNode n=r.body().isBlank()?mapper.createObjectNode():mapper.readTree(r.body());
            if(r.statusCode()<200 || r.statusCode()>=300) {
                String code=text(n,"code");
                throw new BillingGateway.Failure(r.statusCode()>=400 && r.statusCode()<500 && DECLINES.contains(code),"PAYMENT_REJECTED");
            }
            return n;
        } catch(BillingGateway.Failure e) { throw e; }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new BillingGateway.Failure(false,"PROVIDER_INTERRUPTED"); }
        catch(Exception e) { throw new BillingGateway.Failure(false,"PROVIDER_UNAVAILABLE"); }
    }
    private static String text(JsonNode n,String key) { return n.path(key).asText(""); }
    private static String segment(String s) { return java.net.URLEncoder.encode(s,StandardCharsets.UTF_8).replace("+","%20"); }
}
