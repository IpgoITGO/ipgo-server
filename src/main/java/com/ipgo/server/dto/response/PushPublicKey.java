package com.ipgo.server.dto.response;

// #190 브라우저 등록(pushManager.subscribe)의 applicationServerKey로 쓰는 VAPID 공개키(base64url)
public record PushPublicKey(String publicKey) {
}
