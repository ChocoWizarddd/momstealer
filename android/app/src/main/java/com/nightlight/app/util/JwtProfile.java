package com.nightlight.app.util;

/**
 * Minimal JWT payload reader for Firebase/Google ID tokens — display
 * purposes only (username keying, profile name/photo). Signature
 * verification stays server-side; this never authenticates anything.
 *
 * <p>Pure Java (hand-rolled base64url + string extraction) so it is
 * unit-testable on the JVM without Android dependencies.
 */
public final class JwtProfile {

    public final String sub;
    public final String name;
    public final String picture;
    public final String email;

    private JwtProfile(String sub, String name, String picture, String email) {
        this.sub = sub;
        this.name = name;
        this.picture = picture;
        this.email = email;
    }

    /** Returns null when the token is not a parseable JWT. */
    public static JwtProfile parse(String idToken) {
        if (idToken == null) {
            return null;
        }
        String[] parts = idToken.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        String json;
        try {
            json = new String(decodeBase64Url(parts[1]), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!json.trim().startsWith("{")) {
            return null;
        }
        return new JwtProfile(
                stringField(json, "user_id", stringField(json, "sub", null)),
                stringField(json, "name", null),
                stringField(json, "picture", null),
                stringField(json, "email", null));
    }

    static byte[] decodeBase64Url(String input) {
        String s = input.replace('-', '+').replace('_', '/');
        int pad = (4 - (s.length() % 4)) % 4;
        StringBuilder sb = new StringBuilder(s);
        for (int i = 0; i < pad; i++) {
            sb.append('=');
        }
        return java.util.Base64.getDecoder().decode(sb.toString());
    }

    /** Extracts a top-level "key": "string value" pair, honoring escapes. */
    static String stringField(String json, String key, String fallback) {
        String needle = "\"" + key + "\"";
        int keyAt = json.indexOf(needle);
        while (keyAt >= 0) {
            int i = keyAt + needle.length();
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
                i++;
            }
            if (i < json.length() && json.charAt(i) == ':') {
                i++;
                while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
                    i++;
                }
                if (i < json.length() && json.charAt(i) == '"') {
                    StringBuilder out = new StringBuilder();
                    i++;
                    while (i < json.length()) {
                        char c = json.charAt(i);
                        if (c == '\\' && i + 1 < json.length()) {
                            char next = json.charAt(i + 1);
                            switch (next) {
                                case 'n':
                                    out.append('\n');
                                    break;
                                case 't':
                                    out.append('\t');
                                    break;
                                case 'r':
                                    out.append('\r');
                                    break;
                                case 'u':
                                    if (i + 5 < json.length()) {
                                        try {
                                            out.append((char) Integer.parseInt(json.substring(i + 2, i + 6), 16));
                                            i += 4;
                                        } catch (NumberFormatException e) {
                                            out.append(next);
                                        }
                                    }
                                    break;
                                default:
                                    out.append(next);
                                    break;
                            }
                            i += 2;
                        } else if (c == '"') {
                            return out.toString();
                        } else {
                            out.append(c);
                            i++;
                        }
                    }
                    return out.toString();
                }
                return fallback;
            }
            keyAt = json.indexOf(needle, keyAt + 1);
        }
        return fallback;
    }
}
