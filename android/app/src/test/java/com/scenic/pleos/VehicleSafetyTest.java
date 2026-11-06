package com.scenic.pleos;

/** Dependency-free contract tests; runnable with javac and java without Android services. */
public final class VehicleSafetyTest {
    private static int checks;
    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
    public static void main(String[] args) {
        check(Math.abs(VehicleSafety.metresPerSecondToKmh(10f) - 36f) < 0.001f, "m/s converted to km/h");
        check(Math.abs(VehicleSafety.metresPerSecondToKmh(-5f) - 18f) < 0.001f, "reverse speed magnitude");
        check(VehicleSafety.metresPerSecondToKmh(Float.NaN) == null, "NaN rejected");
        check(VehicleSafety.metresPerSecondToKmh(Float.POSITIVE_INFINITY) == null, "infinity rejected");
        check(VehicleSafety.metresPerSecondToKmh(null) == null, "missing speed rejected");
        check(!VehicleSafety.isVideoBlocked(false, false, false, true, null), "ordinary Android usable");
        check(VehicleSafety.isVideoBlocked(true, false, true, false, 0f), "disconnected automotive blocks");
        check(VehicleSafety.isVideoBlocked(true, true, false, false, 0f), "unknown UX blocks at zero speed");
        check(VehicleSafety.isVideoBlocked(true, true, true, true, 0f), "idling UX restriction wins");
        check(VehicleSafety.isVideoBlocked(true, true, true, false, null), "unknown/stale speed blocks");
        check(VehicleSafety.isVideoBlocked(true, true, true, false, 36f), "moving blocks");
        check(VehicleSafety.isVideoBlocked(true, true, true, false, -12f), "reverse motion blocks");
        check(VehicleSafety.isVideoBlocked(true, true, true, false, Float.NaN), "invalid speed blocks");
        check(!VehicleSafety.isVideoBlocked(true, true, true, false, 0f), "known stationary unrestricted permits");
        check(VehicleSafety.isGpsVideoBlocked(false,0,0,true,0.1f), "missing GPS speed blocks");
        check(VehicleSafety.isGpsVideoBlocked(true,0,6000,true,0.1f), "stale stationary GPS blocks");
        check(VehicleSafety.isGpsVideoBlocked(true,0,-1,true,0.1f), "future GPS timestamp blocks");
        check(VehicleSafety.isGpsVideoBlocked(true,0,100,false,0), "unknown GPS speed accuracy blocks");
        check(VehicleSafety.isGpsVideoBlocked(true,0,100,true,1), "uncertain GPS speed blocks");
        check(VehicleSafety.isGpsVideoBlocked(true,2,100,true,0.1f), "GPS movement blocks");
        check(VehicleSafety.isGpsVideoBlocked(true,Float.NaN,100,true,0.1f), "invalid GPS speed blocks");
        check(!VehicleSafety.isGpsVideoBlocked(true,0,100,true,0.1f), "fresh stationary GPS is supplemental evidence");
        System.out.println("VehicleSafetyTest: " + checks + " checks passed");
    }
}
