package com.scenic.pleos;

/** Pixel bounds for the music window, always contained by its available app area. */
public final class MusicWindowLayout {
    public final int width, height, margin;

    private MusicWindowLayout(int width, int height, int margin) {
        this.width = width;
        this.height = height;
        this.margin = margin;
    }

    public static MusicWindowLayout calculate(int availableWidth, int availableHeight,
                                               float density, boolean minimized) {
        int width = Math.max(1, availableWidth), height = Math.max(1, availableHeight);
        float scale = density > 0 && !Float.isInfinite(density) ? density : 1f;
        int margin = Math.min(Math.round(16 * scale), Math.min(width, height) / 8);
        int maxWidth = Math.max(1, width - margin * 2);
        int maxHeight = Math.max(1, height - margin * 2);
        int targetWidth = minimized
                ? Math.max(Math.round(280 * scale), Math.min(Math.round(440 * scale), Math.round(width * .46f)))
                : Math.round(width * .88f);
        int targetHeight = minimized
                ? Math.max(Math.round(200 * scale), Math.min(Math.round(300 * scale), Math.round(height * .48f)))
                : Math.round(height * .86f);
        return new MusicWindowLayout(Math.max(1, Math.min(maxWidth, targetWidth)),
                Math.max(1, Math.min(maxHeight, targetHeight)), margin);
    }

}
