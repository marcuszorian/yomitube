package org.schabi.newpipe.mining;

/**
 * Estimates where Media3's SubtitleView draws bottom-centred caption text, so a long press can tell
 * "on the subtitle" from "elsewhere". SubtitleView doesn't expose text bounds, so this re-derives
 * them from the text size and bottom padding the player configures, then adds slack because the
 * text can be small. Pure logic, no Android dependencies.
 */
public final class SubtitleHitArea {

    /** Line height relative to text size (font ascent+descent plus outline). */
    static final float LINE_HEIGHT = 1.35f;
    /** Fraction of the view width text may use before wrapping. */
    static final float MAX_WIDTH_FRACTION = 0.9f;

    private SubtitleHitArea() {
    }

    /**
     * @param x            touch x relative to the subtitle view
     * @param y            touch y relative to the subtitle view
     * @param width        subtitle view width
     * @param height       subtitle view height
     * @param textSizePx   caption text size in px
     * @param bottomPx     gap between the view's bottom and the last text line
     * @param text         the caption text currently shown (lines separated by '\n')
     * @param slackPx      extra margin added on every side
     */
    public static boolean contains(float x, float y, int width, int height, float textSizePx,
                                   float bottomPx, String text, float slackPx) {
        if (text == null || text.trim().isEmpty() || width <= 0 || height <= 0 || textSizePx <= 0) {
            return false;
        }
        float available = width * MAX_WIDTH_FRACTION;
        int lines = 0;
        float widest = 0;
        for (String line : text.split("\n")) {
            float w = estimateWidth(line, textSizePx);
            int wrapped = Math.max(1, (int) Math.ceil(w / available));
            lines += wrapped;
            widest = Math.max(widest, Math.min(w, available));
        }
        float blockHeight = lines * textSizePx * LINE_HEIGHT;
        float bottom = height - bottomPx;
        float top = bottom - blockHeight;
        float left = (width - widest) / 2f;
        float right = left + widest;
        return x >= left - slackPx && x <= right + slackPx
                && y >= top - slackPx && y <= bottom + slackPx;
    }

    /** Full-width (CJK, kana, full-width forms) ≈ 1 em; everything else ≈ 0.55 em. */
    static float estimateWidth(String line, float textSizePx) {
        float em = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            em += isWide(c) ? 1f : 0.55f;
        }
        return em * textSizePx;
    }

    static boolean isWide(char c) {
        return (c >= 0x3000 && c <= 0x9FFF) || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0xFF00 && c <= 0xFF60)
                || (c >= 0xAC00 && c <= 0xD7AF);
    }
}
