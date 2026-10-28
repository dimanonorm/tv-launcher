package ru.dimanonorm.tvlauncher;

import android.service.dreams.DreamService;

/** Та же заставка как системная (выбирается в настройках заставки телевизора). */
public class SlideshowDream extends DreamService {

    private SlideshowView view;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        view = new SlideshowView(this);
        setContentView(view);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (view != null) view.start();
    }

    @Override
    public void onDreamingStopped() {
        if (view != null) view.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (view != null) view.release();
        super.onDetachedFromWindow();
    }
}
