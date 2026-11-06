package com.ipgo.server.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.ipgo.server.exception.BillingException;

/** JDBC 원장은 짧은 transaction/사용자 row lock으로 직렬화하며 PG 호출은 transaction 밖에서 한다. */
@Repository
public class BillingLedger {
    private static final java.util.concurrent.locks.ReentrantLock[] TEST_KEY_LOCKS=
            java.util.stream.IntStream.range(0,64).mapToObj(i->new java.util.concurrent.locks.ReentrantLock())
                    .toArray(java.util.concurrent.locks.ReentrantLock[]::new);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public BillingLedger(JdbcTemplate jdbc, PlatformTransactionManager manager) { this.jdbc=jdbc; tx=new TransactionTemplate(manager); }
    public <T> T transaction(Supplier<T> action) { return tx.execute(s->action.get()); }
    public void lock(long user) {
        if (list("select id from users where id=? for update",user).isEmpty()) throw BillingException.forbidden();
    }
    /** 등록/삭제 경합을 막는다. 사용자 row도 잠그는 경로는 user→fingerprint 순서를 지킨다. */
    public void lockFingerprint(String fingerprint) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Billing key lock requires transaction");
        String product=jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)c->c.getMetaData().getDatabaseProductName());
        if("PostgreSQL".equals(product)) {
            long key=Long.parseUnsignedLong(fingerprint.substring(0,16),16);
            jdbc.query("select pg_advisory_xact_lock(?)",ps->ps.setLong(1,key),rs->{});
        } else if("H2".equals(product)) {
            // H2는 한 JVM의 검증용이다. PostgreSQL의 여러 인스턴스 간 보장은 실제 advisory lock으로 검사한다.
            var lock=TEST_KEY_LOCKS[fingerprint.hashCode() & 63]; lock.lock();
            try {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override public void afterCompletion(int status) { lock.unlock(); }
                        });
            } catch(RuntimeException e) { lock.unlock(); throw e; }
        } else throw new IllegalStateException("Unsupported billing database");
    }
    public int update(String sql,Object... args) { return jdbc.update(sql,args); }
    public List<Row> list(String sql,Object... args) { return jdbc.queryForList(sql,args).stream().map(Row::new).toList(); }
    public Row one(String sql,Object... args) { var l=list(sql,args); if(l.isEmpty()) throw new BillingException(404,"BILLING_NOT_FOUND","결제 정보를 찾을 수 없습니다."); return l.getFirst(); }
    public static Timestamp at(Instant time) { return time==null?null:Timestamp.from(time); }
    public record Row(Map<String,Object> values) {
        public String text(String name) { Object v=values.get(name); return v==null?null:v.toString(); }
        public long number(String name) { return ((Number)values.get(name)).longValue(); }
        public boolean flag(String name) { return Boolean.TRUE.equals(values.get(name)); }
        public Instant time(String name) {
            Object v=values.get(name); if(v==null) return null;
            if(v instanceof Timestamp t) return t.toInstant();
            if(v instanceof OffsetDateTime t) return t.toInstant();
            return (Instant)v;
        }
        @Override public String toString() { return "BillingRow[redacted]"; }
    }
}
