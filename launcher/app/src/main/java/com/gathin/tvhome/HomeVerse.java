package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A verse of the Quran on Home (res/raw/ayat.json: the Arabic, its Indonesian translation and where
 * it is from), in a glass card at the right above the dock, changing every five minutes. It sits in
 * the space between the prayer card and the dock; the Arabic gets smaller until the verse fits, and
 * a verse is never cut: when even the smallest size does not fit, the translation goes, then the card.
 */
final class HomeVerse {
    private static final String TAG = "TvHome.Verse";
    static final long EVERY_MS = 5 * 60_000L;
    private static final float[] ARABIC_SP = {26, 24, 22, 20, 19, 18, 17, 16, 15, 14};

    private final LinearLayout box;
    private final TextView arabic, meaning, ref;
    private final List<String[]> verses = new ArrayList<>();
    private final float density;
    private final int width;
    private int index;
    private float top = -1, bottom = -1;

    private final Runnable next = new Runnable() {
        @Override
        public void run() {
            if (verses.size() > 1) {
                box.animate().cancel();
                box.animate().alpha(0f).setDuration(400).withEndAction(() -> {
                    index = (index + 1) % verses.size();
                    show();
                    box.animate().alpha(1f).setDuration(600).start();
                }).start();
            }
            box.postDelayed(this, EVERY_MS);
        }
    };

    HomeVerse(FrameLayout parent, int insertAt, int widthPx) {
        Context c = parent.getContext();
        density = c.getResources().getDisplayMetrics().density;
        width = widthPx;
        Typeface outfit = c.getResources().getFont(R.font.outfit);

        box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.prayer_card);
        box.setPadding(dp(18), dp(11), dp(18), dp(11));
        box.setVisibility(View.GONE);
        box.setFocusable(false);

        arabic = new TextView(c);
        arabic.setTextColor(0xFFF6E7BE);
        arabic.setTextDirection(View.TEXT_DIRECTION_RTL);
        arabic.setGravity(Gravity.END);
        arabic.setLineSpacing(0, 1.2f);
        arabic.setShadowLayer(6 * density, 0, density, 0x99000000);
        box.addView(arabic, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        meaning = new TextView(c);
        meaning.setTypeface(outfit);
        meaning.setTextColor(0xE6FFFFFF);
        meaning.setLineSpacing(dp(2), 1f);
        meaning.setShadowLayer(6 * density, 0, density, 0x99000000);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ml.topMargin = dp(6);
        box.addView(meaning, ml);

        ref = new TextView(c);
        ref.setTypeface(outfit);
        ref.setTextColor(HomePrayerCard.GOLD);
        ref.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        ref.setLetterSpacing(0.04f);
        ref.setGravity(Gravity.END);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rl.topMargin = dp(4);
        box.addView(ref, rl);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(widthPx, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        lp.rightMargin = dp(56);
        parent.addView(box, insertAt, lp);

        load(c);
        if (verses.isEmpty()) return;
        index = new Random().nextInt(verses.size());
        show();
        box.postDelayed(next, EVERY_MS);
    }

    /** The space the card may use, in its parent's coordinates: from {@code topPx} down to {@code bottomPx}. */
    void place(float bottomPx, float topPx) {
        if (verses.isEmpty() || bottomPx <= 0) return;
        if (Math.abs(bottomPx - bottom) < 1 && Math.abs(topPx - top) < 1 && box.getVisibility() == View.VISIBLE) return;
        bottom = bottomPx;
        top = topPx;
        fit();
    }

    void stop() {
        box.removeCallbacks(next);
        box.animate().cancel();
    }

    private void show() {
        String[] v = verses.get(index);
        arabic.setText(v[0]);
        meaning.setText(v[1]);
        ref.setText("QS. " + v[2]);
        fit();
    }

    /** Sizes the verse to the space it has and hangs the card from the bottom of that space. */
    private void fit() {
        if (bottom <= 0) return;
        float room = bottom - top;
        meaning.setVisibility(View.VISIBLE);
        int h = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (float sp : ARABIC_SP) {
                arabic.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
                meaning.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(13, Math.min(16, Math.round(sp * 0.62f))));
                h = measure();
                if (h <= room) break;
            }
            if (h <= room) break;
            meaning.setVisibility(View.GONE);   // the Arabic alone, rather than a verse cut short
        }
        if (h > room) {
            box.setVisibility(View.GONE);
            return;
        }
        box.setY(bottom - h);
        box.setVisibility(View.VISIBLE);
    }

    private int measure() {
        box.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return box.getMeasuredHeight();
    }

    private void load(Context c) {
        try (InputStream in = c.getResources().openRawResource(R.raw.ayat);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            JSONArray a = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8)).getJSONArray("ayat");
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                verses.add(new String[]{o.getString("ar"), o.getString("tr"), o.getString("ref")});
            }
        } catch (Exception e) {
            Log.w(TAG, "no verses: " + e.getMessage());
        }
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
