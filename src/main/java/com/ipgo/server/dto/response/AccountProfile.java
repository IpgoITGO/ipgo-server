package com.ipgo.server.dto.response;

public record AccountProfile(Long userId, String email, String managerName, String companyName) {
}
