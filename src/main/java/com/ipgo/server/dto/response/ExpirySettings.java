package com.ipgo.server.dto.response;

// #168 유통기한 임박 기준(일). 셀러가 바꾸지 않았으면 기본 30일이다.
public record ExpirySettings(int imminentDays) {
}
