package com.ipgo.server.service;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EngineResultTransportTests {
    final JsonMapper mapper = JsonMapper.builder().build();
    String wrap(String raw) throws Exception {
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        var buffer = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(buffer)) { gzip.write(bytes); }
        return mapper.writeValueAsString(java.util.Map.of("contractVersion", "2", "encoding", "gzip+base64",
                "uncompressedBytes", bytes.length, "sha256", HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)), "payload", Base64.getEncoder().encodeToString(buffer.toByteArray())));
    }
    @Test void plainAndCompressedHaveIdenticalContents() throws Exception {
        String raw = "{\"contractVersion\":\"1\",\"jobId\":\"job\",\"result\":\"" + "가".repeat(400_000) + "\"}";
        assertEquals(mapper.readTree(raw), EngineResultTransport.decode(wrap(raw), mapper));
        assertEquals(mapper.readTree("{\"contractVersion\":\"1\"}"),
                EngineResultTransport.decode("{\"contractVersion\":\"1\"}", mapper));
    }
    @Test void corruptHashWrongLengthNestedOrOversizedFail() throws Exception {
        String body = wrap("{\"contractVersion\":\"1\"}");
        var node = (tools.jackson.databind.node.ObjectNode) mapper.readTree(body);
        node.put("sha256", "wrong");
        assertThrows(IllegalArgumentException.class, () -> EngineResultTransport.decode(node.toString(), mapper));
        node.put("uncompressedBytes", EngineResultTransport.MAX_DECODED_BYTES + 1);
        assertThrows(IllegalArgumentException.class, () -> EngineResultTransport.decode(node.toString(), mapper));
        assertThrows(IllegalArgumentException.class, () -> EngineResultTransport.decode(wrap("{\"contractVersion\":\"2\"}"), mapper));
        assertThrows(IllegalArgumentException.class, () -> EngineResultTransport.decode(" ".repeat(900_001), mapper));
        var enormous = (tools.jackson.databind.node.ObjectNode) mapper.readTree(body);
        enormous.put("uncompressedBytes", new java.math.BigInteger("18446744073709551640"));
        assertThrows(IllegalArgumentException.class, () -> EngineResultTransport.decode(enormous.toString(), mapper));
        var corrupt = (tools.jackson.databind.node.ObjectNode) mapper.readTree(body);
        corrupt.put("payload", "invalid_base64!");
        assertThrows(IllegalArgumentException.class, () -> EngineResultTransport.decode(corrupt.toString(), mapper));

        var length = (tools.jackson.databind.node.ObjectNode) mapper.readTree(body);
        length.put("uncompressedBytes", 1);
        assertThrows(IllegalArgumentException.class, () -> EngineResultTransport.decode(length.toString(), mapper));
    }
}
