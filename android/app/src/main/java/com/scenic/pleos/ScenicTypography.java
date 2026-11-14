package com.scenic.pleos;

import android.content.Context;
import android.graphics.Typeface;
import android.util.SparseArray;

/** Bundled Pleos system UI typeface, licensed under SIL OFL 1.1. */
final class ScenicTypography {
    private static Typeface base;
    private static final SparseArray<Typeface> weights = new SparseArray<>();
    private ScenicTypography() {}
    /** A small, consistent readability lift; Android still applies the user's font scale. */
    static int size(int baseSp) { return baseSp + 2; }
    static synchronized Typeface font(Context context, int weight) {
        if (base == null) base = Typeface.createFromAsset(context.getAssets(), "fonts/AstaSans.ttf");
        Typeface face = weights.get(weight);
        if (face == null) { face = Typeface.create(base, weight, false); weights.put(weight, face); }
        return face;
    }
}
