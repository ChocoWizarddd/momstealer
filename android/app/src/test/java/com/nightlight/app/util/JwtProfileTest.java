package com.nightlight.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class JwtProfileTest {

    private static String token(String payloadJson) {
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "header." + payload + ".signature";
    }

    @Test
    public void parsesFirebaseClaims() {
        JwtProfile profile = JwtProfile.parse(token(
                "{\"sub\":\"abc123\",\"name\":\"Ashu Pathak\",\"picture\":\"https://example.com/p.jpg\",\"email\":\"ashu@example.com\"}"));
        assertEquals("abc123", profile.sub);
        assertEquals("Ashu Pathak", profile.name);
        assertEquals("https://example.com/p.jpg", profile.picture);
        assertEquals("ashu@example.com", profile.email);
    }

    @Test
    public void prefersUserIdOverSub() {
        JwtProfile profile = JwtProfile.parse(token("{\"user_id\":\"uid9\",\"sub\":\"abc\"}"));
        assertEquals("uid9", profile.sub);
    }

    @Test
    public void rejectsGarbage() {
        assertNull(JwtProfile.parse(null));
        assertNull(JwtProfile.parse(""));
        assertNull(JwtProfile.parse("not-a-jwt"));
        assertNull(JwtProfile.parse("header.!!!.sig"));
    }

    @Test
    public void handlesEscapes() {
        JwtProfile profile = JwtProfile.parse(token("{\"name\":\"O\\\"Neil\\nJr\"}"));
        assertEquals("O\"Neil\nJr", profile.name);
    }
}
