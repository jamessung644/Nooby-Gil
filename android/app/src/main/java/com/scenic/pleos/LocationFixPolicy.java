package com.scenic.pleos;

/** Coordinates may be displayed only while the monotonic timestamp is recent. */
public final class LocationFixPolicy {
    private static final long MAX_AGE_NANOS = 30_000_000_000L;
    private LocationFixPolicy() {}

    public static boolean isDisplayable(double latitude, double longitude, boolean hasAccuracy,
            float accuracy, long fixElapsedNanos, long nowElapsedNanos) {
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180
                && (!hasAccuracy || (Float.isFinite(accuracy) && accuracy >= 0))
                && fixElapsedNanos > 0 && nowElapsedNanos >= fixElapsedNanos
                && nowElapsedNanos - fixElapsedNanos <= MAX_AGE_NANOS;
    }
}
