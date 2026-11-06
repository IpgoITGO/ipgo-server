package com.ipgo.server.service;

import com.ipgo.server.dto.response.ReceiptCorrection.Source;
import com.ipgo.server.entity.UploadFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 수요 관측 flag와 입고 검산을 분리한다. GET은 저장된 정제 사실만 읽는다. */
@Component
public class ReceiptCorrectionSources {
    private final JdbcTemplate jdbc;
    public ReceiptCorrectionSources(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String fingerprint(boolean present,long quantity,boolean valid) {
        return hash(present+":"+quantity+":"+valid);
    }
    static boolean balanced(long opening,long inbound,long outbound,long adjustment,long closing) {
        return java.math.BigInteger.valueOf(opening).add(java.math.BigInteger.valueOf(inbound))
                .subtract(java.math.BigInteger.valueOf(outbound)).add(java.math.BigInteger.valueOf(adjustment))
                .equals(java.math.BigInteger.valueOf(closing));
    }
    public Source read(UploadFile upload,long productId,java.time.LocalDate day) {
        var rows=jdbc.query("SELECT opening_qty,inbound_qty,outbound_qty,adjustment_qty,closing_qty FROM product_daily_stocks WHERE upload_file_id=? AND product_id=? AND stock_date=?",
                (rs,n)->new long[]{rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5)},upload.getId(),productId,day);
        boolean present=!rows.isEmpty(); long qty=present?rows.getFirst()[1]:0;
        boolean valid=present;
        if(present) {
            long[] r=rows.getFirst();
            var report=upload.getValidationReport();
            valid=report!=null && report.validation()!=null && report.validation().excludedSkus().stream().noneMatch(e->e.productId()==productId);
            valid &= balanced(r[0],r[1],r[2],r[3],r[4]);
            var lotQty=jdbc.queryForObject("SELECT COALESCE(SUM(CAST(d.inbound_qty AS DECIMAL(38,0))),0) FROM daily_stock_records d JOIN inventory_units u ON u.id=d.inventory_unit_id WHERE d.upload_file_id=? AND u.product_id=? AND d.stock_date=?",java.math.BigDecimal.class,upload.getId(),productId,day);
            valid &= lotQty.compareTo(java.math.BigDecimal.valueOf(qty))==0;
            valid &= jdbc.queryForObject("SELECT COUNT(*) FROM daily_stock_records d JOIN inventory_units u ON u.id=d.inventory_unit_id WHERE d.upload_file_id=? AND u.product_id=? AND CAST(d.opening_qty AS DECIMAL(38,0))+d.inbound_qty-d.outbound_qty+d.adjustment_qty<>d.closing_qty",Long.class,upload.getId(),productId)==0;
        }
        return new Source(upload.getId(),upload.getRevisionNo(),present,qty,valid,fingerprint(present,qty,valid));
    }
}
