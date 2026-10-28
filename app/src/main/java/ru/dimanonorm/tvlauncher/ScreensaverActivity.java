package ru.dimanonorm.tvlauncher;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;

/** Заставка, запускаемая с плитки: слайд-шоу обоев, любая кнопка пульта — выход. */
public class ScreensaverActivity extends Activity {

    private SlideshowView view;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        view = new SlideshowView(this);
        setContentView(view);
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.start();
    }

    @Override
    protected void onPause() {
        view.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        view.release();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        finish();
        return true;
    }
}
