package com.scenic.pleos;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Accept only an app backend origin, never a path, credential or upstream API URL. */
public final class EndpointPolicy {
    private EndpointPolicy() {}

    public static String normalizeBaseUrl(String value) {
        if (value == null || value.length() < 1 || value.length() > 2048) throw invalid();
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i)) || Character.isISOControl(value.charAt(i))) throw invalid();
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) throw invalid();
            scheme = scheme.toLowerCase(Locale.ROOT);
            host = host.toLowerCase(Locale.ROOT);
            String path = uri.getRawPath();
            if (path != null && !path.isEmpty() && !path.equals("/")) throw invalid();
            if (!validHost(host)) throw invalid();
            int port = uri.getPort();
            if (port == 0 || port > 65535 || port < -1) throw invalid();
            String authority = host + (port == -1 ? "" : ":" + port);
            if (!authority.equalsIgnoreCase(uri.getRawAuthority())) throw invalid();
            if (scheme.equals("http")) {
                if (!host.equals("10.0.2.2") && !host.equals("127.0.0.1") && !host.equals("localhost")) throw invalid();
            } else if (!scheme.equals("https")) throw invalid();
            // The Android app calls the user's backend. Provider credentials remain server-side.
            if (host.equals("openai.com") || host.endsWith(".openai.com")
                    || host.equals("kakaomobility.com") || host.endsWith(".kakaomobility.com")
                    || host.equals("dapi.kakao.com")) throw invalid();
            return scheme + "://" + authority;
        } catch (URISyntaxException e) {
            throw invalid();
        }
    }

    private static boolean validHost(String host) {
        if (host.length() > 253 || host.endsWith(".")) return false;
        // URI already validates bracketed IPv6 literals; allow them only with HTTPS.
        if (host.startsWith("[") && host.endsWith("]")) return host.indexOf('%') < 0;
        String[] labels = host.split("\\.", -1);
        for (String label : labels) {
            if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) return false;
        }
        return true;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("서버 주소는 로컬 HTTP 주소 또는 HTTPS 서버의 기본 주소로 입력해 주세요. 경로·인증정보·검색어는 사용할 수 없습니다.");
    }
}
