package com.ipgo.server.auth;

public record AccountProfile(Long userId, String email, String managerName, String companyName) {
}
