package ru.dimanonorm.tvlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Слайд-шоу для заставки: картинки из папки Wallpapers (или Pictures), плавная смена,
 * часы, которые медленно смещаются, чтобы не выжигать экран.
 */
final class SlideshowView extends FrameLayout {

    private static final long SLIDE_MS = 15_000;
    private static final long CLOCK_MOVE_MS = 60_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final ImageView[] views = new ImageView[2];
    private final LinearLayout clockBox;
    private final List<File> images = new ArrayList<>();
    private int front = 0;
    private int index = 0;
    private boolean running;

    private final Runnable nextSlide = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            showNext();
            if (images.size() > 1) main.postDelayed(this, SLIDE_MS);
        }
    };

    private final Runnable moveClock = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            int w = getWidth() - clockBox.getWidth();
            int h = getHeight() - clockBox.getHeight();
            if (w > 0 && h > 0) {
                clockBox.animate()
                        .x(w * (0.05f + random.nextFloat() * 0.9f))
                        .y(h * (0.10f + random.nextFloat() * 0.8f))
                        .setDuration(2000).start();
            }
            main.postDelayed(this, CLOCK_MOVE_MS);
        }
    };

    SlideshowView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        for (int i = 0; i < 2; i++) {
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setAlpha(0f);
            addView(iv, new LayoutParams(-1, -1));
            views[i] = iv;
        }

        clockBox = new LinearLayout(c);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockBox.setGravity(Gravity.END);
        clockBox.setPadding(dp(24), dp(16), dp(24), dp(16));
        TextClock time = new TextClock(c);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        time.setTextColor(0xEEFFFFFF);
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 64);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setShadowLayer(dp(6), 0, 0, 0xAA000000);
        clockBox.addView(time);
        TextClock date = new TextClock(c);
        date.setFormat24Hour("EEEE, d MMMM");
        date.setFormat12Hour("EEEE, d MMMM");
        date.setTextColor(0xCCFFFFFF);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        date.setShadowLayer(dp(4), 0, 0, 0xAA000000);
        clockBox.addView(date);
        LayoutParams clp = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        clp.rightMargin = dp(48);
        clp.bottomMargin = dp(48);
        addView(clockBox, clp);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    void start() {
        running = true;
        images.clear();
        File base = Environment.getExternalStorageDirectory();
        collect(new File(base, "Wallpapers"));
        if (images.isEmpty()) collect(new File(base, Environment.DIRECTORY_PICTURES));
        Collections.shuffle(images, random);
        index = 0;
        main.post(nextSlide);
        main.postDelayed(moveClock, CLOCK_MOVE_MS);
    }

    void stop() {
        running = false;
        main.removeCallbacks(nextSlide);
        main.removeCallbacks(moveClock);
        for (ImageView v : views) v.setImageDrawable(null);
    }

    void release() {
        stop();
        worker.shutdownNow();
    }

    private void collect(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile() && FilesActivity.isImage(FilesActivity.extension(f))) images.add(f);
        }
    }

    private void showNext() {
        if (images.isEmpty()) return;
        final File f = images.get(index % images.size());
        index++;
        final DisplayMetrics dm = getResources().getDisplayMetrics();
        worker.execute(() -> {
            final Bitmap b = FilesActivity.decodeScaled(f, dm.widthPixels, dm.heightPixels);
            main.post(() -> {
                if (!running || b == null) return;
                ImageView in = views[1 - front];
                ImageView out = views[front];
                in.setImageBitmap(b);
                in.setScaleX(1f);
                in.setScaleY(1f);
                in.animate().alpha(1f).setDuration(1500).start();
                // лёгкое медленное приближение (отдельным аниматором, чтобы не замедлить появление)
                android.animation.ObjectAnimator zoom = android.animation.ObjectAnimator.ofPropertyValuesHolder(in,
                        android.animation.PropertyValuesHolder.ofFloat(SCALE_X, 1f, 1.06f),
                        android.animation.PropertyValuesHolder.ofFloat(SCALE_Y, 1f, 1.06f));
                zoom.setDuration(SLIDE_MS + 2000);
                zoom.start();
                out.animate().alpha(0f).setDuration(1500).withEndAction(() -> {
                    if (running && out.getAlpha() == 0f) out.setImageDrawable(null);
                }).start();
                front = 1 - front;
            });
        });
    }
}
