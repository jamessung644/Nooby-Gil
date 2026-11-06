package com.scenic.pleos;

/** Pure policy helpers shared by the optional AAOS adapter and its offline tests. */
public final class VehicleSafety {
    private VehicleSafety() {}

    /** VehiclePropertyIds.PERF_VEHICLE_SPEED uses signed metres per second. */
    public static Float metresPerSecondToKmh(Number metresPerSecond) {
        if (metresPerSecond == null) return null;
        float value = metresPerSecond.floatValue();
        if (Float.isNaN(value) || Float.isInfinite(value)) return null;
        float result = Math.abs(value) * 3.6f;
        return Float.isInfinite(result) ? null : result;
    }

    /** GPS is supplemental; missing, stale, or imprecise speed never proves a stop. */
    public static boolean isGpsVideoBlocked(boolean hasSpeed, float speedMps, long ageMillis,
            boolean hasSpeedAccuracy, float speedAccuracyMps) {
        return !hasSpeed || !hasSpeedAccuracy || ageMillis<0 || ageMillis>5000
                || Float.isNaN(speedMps) || Float.isInfinite(speedMps)
                || Float.isNaN(speedAccuracyMps) || Float.isInfinite(speedAccuracyMps)
                || speedAccuracyMps<0 || speedAccuracyMps>0.5f || Math.abs(speedMps)>0.05f;
    }

    /**
     * Vehicle UX restrictions are authoritative; a missing/stale sensor never means parked.
     * The small speed tolerance only accommodates zero-speed sensor noise. A zero speed
     * alone cannot permit video: the UX restriction stream must also explicitly allow it.
     */
    public static boolean isVideoBlocked(boolean automotive, boolean connected,
            boolean uxKnown, boolean uxRestricted, Float speedKmh) {
        if (!automotive) return false;
        return !connected || !uxKnown || uxRestricted || speedKmh == null
                || Float.isNaN(speedKmh) || Float.isInfinite(speedKmh)
                || Math.abs(speedKmh) > 0.1f;
    }
}
