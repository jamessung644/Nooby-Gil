package com.scenic.pleos;

/** No network is used: policy must reject unsafe endpoints before opening a connection. */
public final class EndpointPolicyTest {
    private static int checks;
    private static void equal(String want, String got) {
        checks++;
        if (!want.equals(got)) throw new AssertionError("Unexpected normalized endpoint: " + got);
    }
    private static void reject(String input) {
        checks++;
        try { EndpointPolicy.normalizeBaseUrl(input); }
        catch (IllegalArgumentException expected) {
            if (expected.getMessage() == null || expected.getMessage().contains("secret"))
                throw new AssertionError("Endpoint error must be safe");
            return;
        }
        throw new AssertionError("Unsafe endpoint accepted");
    }
    public static void main(String[] args) {
        equal("http://10.0.2.2:4173", EndpointPolicy.normalizeBaseUrl("http://10.0.2.2:4173/"));
        equal("http://127.0.0.1:4173", EndpointPolicy.normalizeBaseUrl("http://127.0.0.1:4173"));
        equal("http://localhost", EndpointPolicy.normalizeBaseUrl("HTTP://LOCALHOST/"));
        equal("https://scenic.example.com:8443", EndpointPolicy.normalizeBaseUrl("https://SCENIC.example.com:8443/"));
        String[] invalid = {null,"","http://example.com","http://192.168.0.8:4173","http://127.1",
            "http://2130706433","http://localhost.evil.example","http://localhost.",
            "http://localhost:0","http://localhost:65536","http://localhost:","http://localhost:-1",
            "http://secret@localhost","https://secret@example.com","https://example.com/path",
            "https://example.com//","https://example.com?secret=abc","https://example.com#secret",
            "https://example.com/%2f","file:///tmp/secret","ftp://localhost","//localhost",
            " http://localhost","http://localhost\n","http://local_host","https://-bad.example",
            "https://bad-.example","https://api.openai.com","https://apis-navi.kakaomobility.com",
            "https://dapi.kakao.com","https://example.com:abc"};
        for (String input : invalid) reject(input);
        System.out.println("EndpointPolicyTest: " + checks + " checks passed");
    }
}
