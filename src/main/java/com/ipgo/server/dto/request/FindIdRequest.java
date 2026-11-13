package com.ipgo.server.dto.request;

import jakarta.validation.constraints.Size;

// #206 아이디 찾기: 사업자등록번호 + 담당자 이름.
// 사업자등록번호는 하나로 정확히 맞고, 담당자 이름은 쇼핑몰에 공개되지 않아 함께 쓴다.
// 프론트가 바뀌는 동안에는 이전 방식(회사명 + 담당자 이름, #185)도 받는다. businessRegNo가 있으면 그쪽을 쓴다.
public record FindIdRequest(@Size(max = 20) String businessRegNo, @Size(max = 50) String companyName,
        @Size(max = 50) String managerName) {
}
