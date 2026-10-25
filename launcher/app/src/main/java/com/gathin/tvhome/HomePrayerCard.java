package com.gathin.tvhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The prayer times under the clock on Home, as a card in the screensaver's style: dark green glass
 * with a gold rim, the Hijri date by a crescent, each prayer's name in gold above its time in a large
 * serif, the next prayer framed in gold, and how long until it.
 */
final class HomePrayerCard {
    static final int GOLD = 0xFFE9CB7E;
    private static final int GOLD_LIGHT = 0xFFFBE7B0;
    private static final int GOLD_DIM = 0xCCE9CB7E;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int WHITE_SOFT = 0xE6FFFFFF;

    private final LinearLayout root;
    private final TextView head;
    private final android.graphics.drawable.Drawable moon;
    private final LinearLayout[] cells = new LinearLayout[PrayerTimes.NAMES.length];
    private final TextView[] names = new TextView[cells.length], times = new TextView[cells.length];
    private final float density;

    HomePrayerCard(LinearLayout root) {
        this.root = root;
        Context c = root.getContext();
        density = c.getResources().getDisplayMetrics().density;
        Typeface outfit = c.getResources().getFont(R.font.outfit);
        Typeface serifBold = Typeface.create(Typeface.SERIF, Typeface.BOLD);
        root.removeAllViews();

        // The date (after a gold crescent) above the countdown: one two-line text, so its width is
        // always that of its longest line and neither line is ever clipped.
        head = text(c, outfit, 16, WHITE_SOFT);
        head.setSingleLine(false);
        head.setMaxLines(2);
        head.setLineSpacing(dp(7), 1f);
        moon = c.getDrawable(R.drawable.ic_crescent).mutate();
        moon.setTint(GOLD);
        moon.setBounds(0, 0, dp(20), dp(20));
        root.addView(head);

        // A gold rule, then one cell per prayer.
        View rule = new View(c);
        rule.setBackgroundColor(0x80E9CB7E);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(dp(1), dp(48));
        rl.leftMargin = dp(18);
        rl.rightMargin = dp(8);
        root.addView(rule, rl);

        for (int i = 0; i < cells.length; i++) {
            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(dp(12), dp(6), dp(12), dp(7));
            TextView name = text(c, outfit, 15, GOLD_DIM);
            name.setLetterSpacing(0.06f);
            name.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView at = text(c, serifBold, 26, WHITE);
            at.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            al.topMargin = dp(5);
            cell.addView(name);
            cell.addView(at, al);
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            pl.leftMargin = dp(3);
            root.addView(cell, pl);
            cells[i] = cell;
            names[i] = name;
            times[i] = at;
        }
    }

    /** Fills the card: {@code next} is the index of the next prayer, -1 after Isya (tomorrow's Subuh). */
    void bind(String[] prayerNames, int[] minutes, String hijriText, int next, String countdownText, boolean is24) {
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
        if (!hijriText.isEmpty()) {
            b.append("\u00a0 ");
            b.setSpan(new android.text.style.ImageSpan(moon, android.text.style.DynamicDrawableSpan.ALIGN_BASELINE),
                    0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int from = b.length();
            b.append(hijriText);
            b.setSpan(new android.text.style.ForegroundColorSpan(GOLD), from, b.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.setSpan(new android.text.style.AbsoluteSizeSpan(18, true), from, b.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.append("\n");
        }
        b.append(countdownText);
        head.setText(b);
        for (int i = 0; i < cells.length; i++) {
            boolean isNext = i == next;
            names[i].setText(prayerNames[i]);
            times[i].setText(PrayerTimes.format(minutes[i], is24));
            if (isNext) cells[i].setBackgroundResource(R.drawable.prayer_cell_next);
            else cells[i].setBackground(null);
            names[i].setTextColor(isNext ? GOLD : GOLD_DIM);
            times[i].setTextColor(isNext ? GOLD_LIGHT : WHITE);
        }
        StringBuilder say = new StringBuilder(hijriText);
        for (int i = 0; i < cells.length; i++) {
            say.append(", ").append(prayerNames[i]).append(' ').append(times[i].getText());
        }
        root.setContentDescription(say.append(", ").append(countdownText));
    }

    private TextView text(Context c, Typeface face, float sp, int color) {
        TextView t = new TextView(c);
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setShadowLayer(6 * density, 0, 1 * density, 0x99000000);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
