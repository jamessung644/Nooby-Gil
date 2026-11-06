package com.scenic.pleos;

/** Pure Java coverage of the location marker's freshness and coordinate boundary. */
public final class LocationFixPolicyTest {
    private static int checks;
    private static final long NOW = 60_000_000_000L;
    private static void expect(boolean wanted, double lat, double lon, boolean hasAccuracy,
            float accuracy, long fixTime, long now, String label) {
        checks++;
        boolean actual = LocationFixPolicy.isDisplayable(lat, lon, hasAccuracy, accuracy, fixTime, now);
        if (wanted != actual) throw new AssertionError(label + ": expected " + wanted + ", got " + actual);
    }
    public static void main(String[] args) {
        expect(true,37.51,126.99,true,5,NOW,NOW,"fresh accurate fix");
        expect(true,37.51,126.99,true,0,NOW,NOW,"zero accuracy is valid");
        expect(true,37.51,126.99,false,Float.NaN,NOW,NOW,"absent accuracy is allowed");
        expect(true,37.51,126.99,false,-1,NOW,NOW,"unused accuracy is ignored");
        expect(true,-90,-180,true,100,NOW,NOW,"lower coordinate boundaries");
        expect(true,90,180,true,100,NOW,NOW,"upper coordinate boundaries");
        expect(false,90.0001,127,true,5,NOW,NOW,"latitude too high");
        expect(false,-90.0001,127,true,5,NOW,NOW,"latitude too low");
        expect(false,37,180.0001,true,5,NOW,NOW,"longitude too high");
        expect(false,37,-180.0001,true,5,NOW,NOW,"longitude too low");
        expect(false,Double.NaN,127,true,5,NOW,NOW,"NaN latitude");
        expect(false,37,Double.NaN,true,5,NOW,NOW,"NaN longitude");
        expect(false,Double.POSITIVE_INFINITY,127,true,5,NOW,NOW,"infinite latitude");
        expect(false,37,Double.NEGATIVE_INFINITY,true,5,NOW,NOW,"infinite longitude");
        expect(false,37,127,true,-0.01f,NOW,NOW,"negative accuracy");
        expect(false,37,127,true,Float.NaN,NOW,NOW,"NaN accuracy");
        expect(false,37,127,true,Float.POSITIVE_INFINITY,NOW,NOW,"infinite accuracy");
        expect(true,37,127,true,5,NOW-30_000_000_000L,NOW,"exactly thirty seconds old");
        expect(false,37,127,true,5,NOW-30_000_000_001L,NOW,"one nanosecond too old");
        expect(false,37,127,true,5,NOW+1,NOW,"future fix");
        expect(false,37,127,true,5,0,NOW,"unset monotonic timestamp");
        expect(false,37,127,true,5,-1,NOW,"negative fix timestamp");
        expect(false,37,127,true,5,1,-1,"negative current timestamp");
        expect(false,37,127,true,5,1,Long.MAX_VALUE,"large elapsed age cannot wrap");
        expect(true,37,127,true,5,Long.MAX_VALUE-30_000_000_000L,Long.MAX_VALUE,"large timestamps at boundary");
        System.out.println("LocationFixPolicyTest: " + checks + " checks passed");
    }
}
