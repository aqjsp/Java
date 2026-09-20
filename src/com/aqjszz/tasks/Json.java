package com.aqjszz.tasks;

final class Json {
    private Json() {}

    static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            switch (cp) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (cp < 0x20) {
                        b.append("\\u%04x".formatted(cp));
                    } else {
                        b.appendCodePoint(cp);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    static String obj(String... kv) {
        if ((kv.length & 1) != 0) {
            throw new IllegalArgumentException("odd kv");
        }
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) {
                b.append(',');
            }
            b.append(quote(kv[i])).append(':').append(kv[i + 1]);
        }
        return b.append('}').toString();
    }

    /** Only understands {"payload":"..."} with \\ and \" escapes. */
    static String payloadOf(String body) {
        String key = "\"payload\"";
        int k = body.indexOf(key);
        if (k < 0) {
            throw new IllegalArgumentException("missing payload");
        }
        int colon = body.indexOf(':', k + key.length());
        int q1 = body.indexOf('"', colon + 1);
        if (colon < 0 || q1 < 0) {
            throw new IllegalArgumentException("bad payload");
        }
        StringBuilder out = new StringBuilder();
        for (int i = q1 + 1; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\') {
                if (i + 1 >= body.length()) {
                    throw new IllegalArgumentException("bad escape");
                }
                out.append(body.charAt(++i));
            } else if (c == '"') {
                return out.toString();
            } else {
                out.append(c);
            }
        }
        throw new IllegalArgumentException("unterminated payload");
    }
}
