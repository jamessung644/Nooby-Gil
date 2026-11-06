package com.scenic.pleos;

import android.content.ActivityNotFoundException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Offline boundary checks; no external app or Android UI is started. */
public final class YouTubeMusicLauncherTest {
    private static int checks;
    private static void equal(Object expected, Object actual, String label) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }
    private static void verifyUrl(String query) throws Exception {
        URI uri = new URI(YouTubeMusicLauncher.searchUrl(query));
        equal("https", uri.getScheme(), "HTTPS only");
        equal("music.youtube.com", uri.getRawAuthority(), "fixed official destination");
        equal("/search", uri.getPath(), "search page, never a watch URL");
        equal(query.trim(), URLDecoder.decode(uri.getRawQuery().substring(2), "UTF-8"), "query round trip");
        equal(true, uri.getRawQuery().startsWith("q=") && !uri.getRawQuery().contains("&"), "one search parameter");
        equal(true, uri.getFragment() == null, "query cannot introduce a fragment");
    }
    private static final class FakeLauncher implements YouTubeMusicLauncher.Launcher {
        final List<YouTubeMusicLauncher.Target> calls = new ArrayList<>();
        final YouTubeMusicLauncher.Target accepted;
        final boolean denied;
        String firstUrl;
        FakeLauncher(YouTubeMusicLauncher.Target accepted, boolean denied) { this.accepted = accepted; this.denied = denied; }
        public boolean start(YouTubeMusicLauncher.Target target, String url) {
            calls.add(target);
            if (firstUrl == null) firstUrl = url;
            equal(firstUrl, url, "all fallbacks preserve the scene search");
            if (target == accepted) return true;
            if (denied) throw new SecurityException("handler denied");
            return false;
        }
    }
    private static void verifyFallback(YouTubeMusicLauncher.Target accepted, boolean denied,
            YouTubeMusicLauncher.Result expected, YouTubeMusicLauncher.Target... calls) {
        FakeLauncher launcher = new FakeLauncher(accepted, denied);
        equal(expected, YouTubeMusicLauncher.open(launcher, "해안 드라이브"), "navigation outcome");
        equal(Arrays.asList(calls), launcher.calls, "ordered fallback stops after success");
    }
    private static void verifyPlaylistFallback(String playlistUrl, YouTubeMusicLauncher.Target accepted, boolean denied,
            YouTubeMusicLauncher.Result expected, YouTubeMusicLauncher.Target... calls) {
        FakeLauncher launcher = new FakeLauncher(accepted, denied);
        equal(expected, YouTubeMusicLauncher.openPlaylist(launcher, playlistUrl), "playlist navigation outcome");
        equal(Arrays.asList(calls), launcher.calls, "playlist fallback never tries app home");
        equal(playlistUrl, launcher.firstUrl, "playlist selection is sent exactly without search or autoplay changes");
    }
    private static void verifyPlaylists() {
        String canonical = "https://music.youtube.com/playlist?list=PL_0123456789abc-DEFG";
        for (String valid : new String[] {canonical,
                "https://music.youtube.com/playlist?list=PL0123456789abcdef",
                "https://music.youtube.com/playlist?list=PL" + new String(new char[100]).replace('\0', 'a')}) {
            equal(true, YouTubeMusicLauncher.isPlaylistUrl(valid), "canonical playlist URL accepted");
            verifyPlaylistFallback(valid, YouTubeMusicLauncher.Target.APP_SEARCH, false, YouTubeMusicLauncher.Result.APP,
                    YouTubeMusicLauncher.Target.APP_SEARCH);
        }
        for (String invalid : new String[] {null, "", "PL_0123456789abc-DEFG", " " + canonical, canonical + "\n",
                canonical + "&autoplay=1", canonical + "#part", canonical + "&list=PL0123456789abcdef",
                canonical.replace("https://", "http://"), canonical.replace("music.youtube.com", "music.youtube.com.evil.example"),
                canonical.replace("music.youtube.com", "evil@music.youtube.com"), canonical.replace("music.youtube.com", "music.youtube.com:443"),
                canonical.replace("playlist?", "search?"), canonical.replace("list=PL", "list=RD"),
                canonical.replace("list=PL", "list=%50%4C"), canonical.replace("playlist?list=", "playlist/?list="),
                "https://music.youtube.com/", "https://music.youtube.com/playlist?list=PL0123456789abcde",
                "https://music.youtube.com/playlist?list=PL" + new String(new char[101]).replace('\0', 'a')}) {
            equal(false, YouTubeMusicLauncher.isPlaylistUrl(invalid), "noncanonical playlist URL rejected");
            FakeLauncher launcher = new FakeLauncher(YouTubeMusicLauncher.Target.APP_SEARCH, false);
            equal(YouTubeMusicLauncher.Result.UNAVAILABLE, YouTubeMusicLauncher.openPlaylist(launcher, invalid), "invalid playlist is not opened");
            equal(0, launcher.calls.size(), "invalid playlist never calls a handler");
        }
        verifyPlaylistFallback(canonical, YouTubeMusicLauncher.Target.WEB, false, YouTubeMusicLauncher.Result.WEB,
                YouTubeMusicLauncher.Target.APP_SEARCH, YouTubeMusicLauncher.Target.WEB);
        verifyPlaylistFallback(canonical, YouTubeMusicLauncher.Target.APP_HOME, false, YouTubeMusicLauncher.Result.UNAVAILABLE,
                YouTubeMusicLauncher.Target.APP_SEARCH, YouTubeMusicLauncher.Target.WEB);
        verifyPlaylistFallback(canonical, YouTubeMusicLauncher.Target.WEB, true, YouTubeMusicLauncher.Result.WEB,
                YouTubeMusicLauncher.Target.APP_SEARCH, YouTubeMusicLauncher.Target.WEB);
        verifyPlaylistFallback(canonical, null, true, YouTubeMusicLauncher.Result.UNAVAILABLE,
                YouTubeMusicLauncher.Target.APP_SEARCH, YouTubeMusicLauncher.Target.WEB);
        final List<YouTubeMusicLauncher.Target> missingCalls = new ArrayList<>();
        equal(YouTubeMusicLauncher.Result.UNAVAILABLE, YouTubeMusicLauncher.openPlaylist((target, url) -> {
            missingCalls.add(target);
            equal(canonical, url, "missing activities still receive the exact playlist URL");
            throw new ActivityNotFoundException("no installed handler");
        }, canonical), "absent handlers return unavailable for WebView fallback");
        equal(Arrays.asList(YouTubeMusicLauncher.Target.APP_SEARCH, YouTubeMusicLauncher.Target.WEB), missingCalls,
                "missing activities never discard the playlist by opening home");
        equal(YouTubeMusicLauncher.Result.UNAVAILABLE, YouTubeMusicLauncher.openPlaylist((YouTubeMusicLauncher.Launcher) null, canonical), "null launcher is unavailable");
    }
    public static void main(String[] args) throws Exception {
        verifyPlaylists();
        for (String query : new String[] { "해안 드라이브 신나는 팝 플레이리스트", "city pop + jazz & chill", "https://evil.example/watch?v=x&autoplay=1#part", "a/b?c=d%2F\n🚗", "  lakeside folk  " }) verifyUrl(query);
        equal("https://music.youtube.com/", YouTubeMusicLauncher.searchUrl(null), "null opens home");
        equal("https://music.youtube.com/", YouTubeMusicLauncher.searchUrl(" \t\n"), "blank opens home");
        equal("https://music.youtube.com/search?q=a%20b%2Bc%26d%23e", YouTubeMusicLauncher.searchUrl("a b+c&d#e"), "reserved characters cannot alter URL");
        verifyFallback(YouTubeMusicLauncher.Target.APP_SEARCH, false, YouTubeMusicLauncher.Result.APP, YouTubeMusicLauncher.Target.APP_SEARCH);
        verifyFallback(YouTubeMusicLauncher.Target.APP_HOME, false, YouTubeMusicLauncher.Result.APP, YouTubeMusicLauncher.Target.APP_SEARCH, YouTubeMusicLauncher.Target.APP_HOME);
        verifyFallback(YouTubeMusicLauncher.Target.WEB, false, YouTubeMusicLauncher.Result.WEB, YouTubeMusicLauncher.Target.APP_SEARCH, YouTubeMusicLauncher.Target.APP_HOME, YouTubeMusicLauncher.Target.WEB);
        verifyFallback(null, false, YouTubeMusicLauncher.Result.UNAVAILABLE, YouTubeMusicLauncher.Target.APP_SEARCH, YouTubeMusicLauncher.Target.APP_HOME, YouTubeMusicLauncher.Target.WEB);
        verifyFallback(YouTubeMusicLauncher.Target.WEB, true, YouTubeMusicLauncher.Result.WEB, YouTubeMusicLauncher.Target.APP_SEARCH, YouTubeMusicLauncher.Target.APP_HOME, YouTubeMusicLauncher.Target.WEB);
        verifyFallback(null, true, YouTubeMusicLauncher.Result.UNAVAILABLE, YouTubeMusicLauncher.Target.APP_SEARCH, YouTubeMusicLauncher.Target.APP_HOME, YouTubeMusicLauncher.Target.WEB);
        final List<YouTubeMusicLauncher.Target> missingCalls = new ArrayList<>();
        equal(YouTubeMusicLauncher.Result.UNAVAILABLE, YouTubeMusicLauncher.open((target, url) -> {
            missingCalls.add(target);
            throw new ActivityNotFoundException("no installed handler");
        }, "city pop"), "missing Android activities produce an honest unavailable result");
        equal(Arrays.asList(YouTubeMusicLauncher.Target.values()), missingCalls, "missing Android activities try every fallback");
        System.out.println("YouTubeMusicLauncherTest: " + checks + " checks passed");
    }
}
