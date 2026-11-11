package com.ipgo.server.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// #173 아이디 찾기. 화면은 "123-45-67890"처럼 하이픈을 넣어 보낼 수 있어 서버가 숫자만 남긴다.
public record FindIdRequest(@NotBlank @Size(max = 20) String businessNumber) {
}
