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
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public BillingLedger(JdbcTemplate jdbc, PlatformTransactionManager manager) { this.jdbc=jdbc; tx=new TransactionTemplate(manager); }
    public <T> T transaction(Supplier<T> action) { return tx.execute(s->action.get()); }
    public void lock(long user) {
        if (list("select id from users where id=? for update",user).isEmpty()) throw BillingException.forbidden();
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
