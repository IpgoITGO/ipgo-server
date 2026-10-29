package com.ipgo.server.auth;

public record AccessTokenResponse(String accessToken, String tokenType, long expiresIn) { }
