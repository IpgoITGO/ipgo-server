package com.ipgo.server.dto.response;

public record AccessTokenResponse(String accessToken, String tokenType, long expiresIn) { }
