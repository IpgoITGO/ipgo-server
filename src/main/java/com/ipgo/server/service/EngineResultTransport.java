package com.ipgo.server.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Decode before locking/writing. Neither exceptions nor logs contain result contents. */
final class EngineResultTransport {
    static final int MAX_WIRE_BYTES = 900_000;
    static final int MAX_DECODED_BYTES = 16 * 1024 * 1024;
    private EngineResultTransport() { }

    static JsonNode decode(String body, JsonMapper mapper) {
        require(body != null && body.length() <= MAX_WIRE_BYTES
                && body.getBytes(StandardCharsets.UTF_8).length <= MAX_WIRE_BYTES);
        JsonNode envelope = mapper.readTree(body);
        require(envelope != null && envelope.isObject());
        if ("1".equals(envelope.path("contractVersion").asText())) return envelope;
        require("2".equals(envelope.path("contractVersion").asText())
                && envelope.size() == 5 && "gzip+base64".equals(envelope.path("encoding").asText())
                && envelope.path("payload").isString() && envelope.path("sha256").isString()
                && envelope.path("uncompressedBytes").isIntegralNumber()
                && envelope.path("uncompressedBytes").canConvertToInt());
        long expected = envelope.path("uncompressedBytes").asLong();
        require(expected > 0 && expected <= MAX_DECODED_BYTES);
        try {
            byte[] compressed = Base64.getDecoder().decode(envelope.get("payload").asText());
            byte[] raw;
            try (var gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                raw = gzip.readNBytes((int) expected + 1);
            }
            require(raw.length == expected);
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
            require(digest.equals(envelope.get("sha256").asText()));
            JsonNode decoded = mapper.readTree(raw);
            require(decoded != null && decoded.isObject() && "1".equals(decoded.path("contractVersion").asText()));
            return decoded;
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalArgumentException("Invalid engine compression");
        }
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Invalid engine compression");
    }
}
