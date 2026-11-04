package com.ipgo.server.util;

import java.util.Locale;

public final class EmailNormalizer {
    private EmailNormalizer() { }

    public static String normalize(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }
}
