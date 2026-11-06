package com.scenic.pleos;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Pure URL policy for the parked, signed-out official music website preview. */
final class MusicWebPolicy {
    private MusicWebPolicy() {}
    static boolean allowsNavigation(String url) {
        String host = secureHost(url);
        return "music.youtube.com".equals(host) || "www.youtube.com".equals(host)
                || "m.youtube.com".equals(host) || "youtube.com".equals(host)
                || "consent.youtube.com".equals(host) || "consent.google.com".equals(host);
    }
    static boolean isAuthentication(String url) {
        String host = secureHost(url);
        return "accounts.google.com".equals(host) || "accounts.youtube.com".equals(host);
    }
    static boolean allowsResource(String url) {
        return secureHost(url) != null && !isAuthentication(url);
    }
    static String secureHost(String url) {
        if (url == null) return null;
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) return null;
            return uri.getHost().toLowerCase(Locale.ROOT);
        } catch (URISyntaxException invalid) { return null; }
    }
}
