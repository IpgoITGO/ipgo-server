package com.ipgo.server.auth;

import java.time.Instant;

public record AccountProfile(Long id, String email, Instant createdAt) {
}
