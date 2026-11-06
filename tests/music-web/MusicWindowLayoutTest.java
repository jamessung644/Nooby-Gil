package com.scenic.pleos;

/** Window containment across landscape, portrait, split windows, and density changes. */
public final class MusicWindowLayoutTest {
    private static int checks;
    private static void check(boolean result, String message) {
        checks++;
        if (!result) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        for (int width : new int[] {1, 80, 240, 320, 480, 720, 1280, 2160, 3840}) {
            for (int height : new int[] {1, 80, 240, 320, 480, 720, 1080, 2160}) {
                for (float density : new float[] {.75f, 1f, 1.5f, 2f, 3f}) {
                    for (boolean minimized : new boolean[] {false, true}) {
                        MusicWindowLayout bounds = MusicWindowLayout.calculate(width, height, density, minimized);
                        check(bounds.width > 0 && bounds.height > 0, "The WebView must retain positive bounds");
                        check(bounds.width + bounds.margin * 2 <= width, "Horizontal bounds must fit");
                        check(bounds.height + bounds.margin * 2 <= height, "Vertical bounds must fit");
                    }
                }
            }
        }
        MusicWindowLayout expanded = MusicWindowLayout.calculate(1280, 720, 1f, false);
        MusicWindowLayout compact = MusicWindowLayout.calculate(1280, 720, 1f, true);
        check(expanded.width >= 1024 && expanded.width <= 1152, "Normal width should use 80–90% of app area");
        check(expanded.height >= 576 && expanded.height <= 648, "Normal height should use 80–90% of app area");
        check(compact.width == 440 && compact.height == 300 && compact.margin == 16, "Landscape compact window");
        check(compact.width < expanded.width && compact.height < expanded.height, "Minimize must uncover SCENIC");
        MusicWindowLayout tiny = MusicWindowLayout.calculate(0, -1, Float.NaN, true);
        check(tiny.width == 1 && tiny.height == 1, "Unmeasured input must remain valid");
        System.out.println("MusicWindowLayoutTest: " + checks + " checks passed");
    }
}
