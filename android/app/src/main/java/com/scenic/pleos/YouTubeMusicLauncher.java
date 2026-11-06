package com.scenic.pleos;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.regex.Pattern;

/** Opens YouTube Music after a caller's explicit click and vehicle-safety check. */
public final class YouTubeMusicLauncher {
    private static final String PACKAGE = "com.google.android.apps.youtube.music";
    private static final String HOME = "https://music.youtube.com/";
    private static final Pattern PLAYLIST_URL = Pattern.compile("https://music\\.youtube\\.com/playlist\\?list=PL[A-Za-z0-9_-]{16,100}");

    /** An accepted navigation request is not a guarantee of playback or sign-in. */
    public enum Result { APP, WEB, UNAVAILABLE }

    enum Target { APP_SEARCH, APP_HOME, WEB }
    interface Launcher {
        boolean start(Target target, String url);
    }

    private YouTubeMusicLauncher() {}

    public static Result open(final Activity activity, String query) {
        if (activity == null) return Result.UNAVAILABLE;
        return open(activityLauncher(activity), query);
    }

    /** Opens the chosen playlist, preserving its identity through every fallback. */
    public static Result openPlaylist(final Activity activity, String playlistUrl) {
        if (activity == null) return Result.UNAVAILABLE;
        return openPlaylist(activityLauncher(activity), playlistUrl);
    }

    public static boolean isPlaylistUrl(String url) {
        return url != null && PLAYLIST_URL.matcher(url).matches();
    }

    private static Launcher activityLauncher(final Activity activity) {
        return new Launcher() {
            @Override public boolean start(Target target, String url) {
                final Intent intent;
                if (target == Target.APP_HOME) {
                    intent = activity.getPackageManager().getLaunchIntentForPackage(PACKAGE);
                    if (intent == null) return false;
                } else {
                    intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    intent.addCategory(Intent.CATEGORY_BROWSABLE);
                    if (target == Target.APP_SEARCH) intent.setPackage(PACKAGE);
                }
                // Try directly: resolveActivity can hide handlers under package visibility rules.
                activity.startActivity(intent);
                return true;
            }
        };
    }

    static Result openPlaylist(Launcher launcher, String playlistUrl) {
        if (launcher == null || !isPlaylistUrl(playlistUrl)) return Result.UNAVAILABLE;
        // APP_SEARCH names the existing package-specific ACTION_VIEW target. A playlist
        // must never fall back to the app's home screen, which would discard selection.
        for (Target target : new Target[] {Target.APP_SEARCH, Target.WEB}) {
            try {
                if (launcher.start(target, playlistUrl)) return target == Target.WEB ? Result.WEB : Result.APP;
            } catch (ActivityNotFoundException | SecurityException unavailable) {
                // Keep the exact playlist URL for the next handler or the caller's WebView.
            }
        }
        return Result.UNAVAILABLE;
    }

    static Result open(Launcher launcher, String query) {
        String url = searchUrl(query);
        for (Target target : Target.values()) {
            try {
                if (launcher.start(target, url)) return target == Target.WEB ? Result.WEB : Result.APP;
            } catch (ActivityNotFoundException | SecurityException unavailable) {
                // A missing/disabled handler or vehicle policy may reject this destination.
            }
        }
        return Result.UNAVAILABLE;
    }

    /** The input is only a search term; it can never replace the fixed HTTPS destination. */
    static String searchUrl(String query) {
        if (query == null || query.trim().isEmpty()) return HOME;
        try {
            return HOME + "search?q=" + URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError("UTF-8 is required by Java", impossible);
        }
    }
}
