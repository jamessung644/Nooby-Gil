package com.scenic.pleos;

/** Navigation boundary checks, without a browser, device, or network. */
public final class MusicWebPolicyTest {
    private static int checks;
    private static void check(boolean expected, boolean actual, String value) {
        checks++;
        if (expected != actual) throw new AssertionError("Unexpected URL policy for " + value);
    }
    public static void main(String[] args) {
        for (String url : new String[] {
                "https://music.youtube.com/", "https://music.youtube.com/search?q=city%20pop",
                "https://music.youtube.com/watch?v=sample", "https://www.youtube.com/",
                "https://m.youtube.com/", "https://youtube.com/",
                "https://consent.youtube.com/m?continue=https%3A%2F%2Fmusic.youtube.com",
                "https://consent.google.com/m", "HTTPS://MUSIC.YOUTUBE.COM:443/search?q=pop" }) {
            check(true, MusicWebPolicy.allowsNavigation(url), url);
        }
        for (String url : new String[] {
                null, "", "music.youtube.com", "/watch?v=sample", "//music.youtube.com/",
                "http://music.youtube.com/", "http://127.0.0.1/", "https://music.youtube.com:8443/",
                "https://music.youtube.com.evil.example/", "https://evil.example/music.youtube.com",
                "https://music.youtube.com@evil.example/", "https://evil@music.youtube.com/",
                "https://music.youtube.com./", "https://music%2eyoutube.com/", "https://music.youtube.com\\@evil.example/",
                "https://accounts.google.com/ServiceLogin", "https://accounts.youtube.com/",
                "https://support.google.com/", "https://youtube.com.evil.example/",
                "javascript:alert(1)", "file:///data/local/tmp/example.html", "content://private/secret",
                "intent://watch#Intent;scheme=https;end", "data:text/html,test", "https://music.youtube.com/\n" }) {
            check(false, MusicWebPolicy.allowsNavigation(url), String.valueOf(url));
        }
        for (String url : new String[] {"https://accounts.google.com/ServiceLogin", "https://ACCOUNTS.YOUTUBE.COM/", "https://accounts.google.com:443/o/oauth2/auth"})
            check(true, MusicWebPolicy.isAuthentication(url), url);
        for (String url : new String[] {"https://accounts.google.com.evil.example/", "https://music.youtube.com/?next=accounts.google.com", null})
            check(false, MusicWebPolicy.isAuthentication(url), String.valueOf(url));
        for (String url : new String[] {"https://i.ytimg.com/image.jpg", "https://rr1---sn.googlevideo.com/videoplayback", "https://www.gstatic.com/script.js"})
            check(true, MusicWebPolicy.allowsResource(url), url);
        for (String url : new String[] {"http://127.0.0.1/private", "https://accounts.google.com/ServiceLogin", "https://accounts.youtube.com/", "file:///data/local/tmp/a", "https://a@cdn.example/file"})
            check(false, MusicWebPolicy.allowsResource(url), url);
        System.out.println("MusicWebPolicyTest: " + checks + " checks passed");
    }
}
