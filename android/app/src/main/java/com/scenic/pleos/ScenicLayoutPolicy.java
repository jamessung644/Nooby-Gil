package com.scenic.pleos;

/** Layout decisions in safe-area dp, independent of display pixels and system bars. */
public final class ScenicLayoutPolicy {
    private ScenicLayoutPolicy() {}

    public static boolean useTwoColumns(int availableWidthDp,int availableHeightDp) {
        return availableWidthDp>=900&&availableHeightDp>=420;
    }

    public static boolean pinRouteActions(int availableHeightDp,float fontScale) {
        return availableHeightDp>=560&&fontScale<=1.3f;
    }

    public static int mapHeight(int availableWidthDp,int availableHeightDp,boolean twoColumns) {
        if(twoColumns)return clamp(availableHeightDp-350,240,540);
        return clamp(Math.round((availableWidthDp-40)*.62f),240,360);
    }

    public static int previewHeight(int availableHeightDp) {
        return clamp(availableHeightDp/3,160,280);
    }

    private static int clamp(int value,int minimum,int maximum) {
        return Math.max(minimum,Math.min(maximum,value));
    }
}
