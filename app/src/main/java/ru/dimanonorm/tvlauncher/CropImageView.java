package ru.dimanonorm.tvlauncher;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

/**
 * Картинка, которая заполняет ячейку целиком (center-crop) с небольшим приближением.
 * Масштаб считается внутри самой ячейки, поэтому картинка никогда не вылезает за рамку.
 */
final class CropImageView extends ImageView {

    private float zoom = 1f;
    private final Matrix matrix = new Matrix();

    CropImageView(Context c) {
        super(c);
        setScaleType(ScaleType.MATRIX);
    }

    void setZoom(float zoom) {
        this.zoom = zoom;
        update();
    }

    @Override
    public void setImageDrawable(Drawable d) {
        super.setImageDrawable(d);
        update();
    }

    @Override
    protected boolean setFrame(int l, int t, int r, int b) {
        boolean changed = super.setFrame(l, t, r, b);
        update();
        return changed;
    }

    private void update() {
        Drawable d = getDrawable();
        int vw = getWidth() - getPaddingLeft() - getPaddingRight();
        int vh = getHeight() - getPaddingTop() - getPaddingBottom();
        if (d == null || vw <= 0 || vh <= 0) return;
        int dw = d.getIntrinsicWidth();
        int dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) {
            // Векторные/без размера — растянуть на всю ячейку.
            matrix.reset();
            setImageMatrix(matrix);
            return;
        }
        // zoom > 0 — заполнить ячейку (crop) с приближением; zoom <= 0 — вписать целиком (для иконок).
        float scale = zoom > 0
                ? Math.max(vw / (float) dw, vh / (float) dh) * zoom
                : Math.min(vw / (float) dw, vh / (float) dh);
        float dx = (vw - dw * scale) / 2f;
        float dy = (vh - dh * scale) / 2f;
        matrix.setScale(scale, scale);
        matrix.postTranslate(Math.round(dx), Math.round(dy));   // отступы ImageView учитывает сам
        setImageMatrix(matrix);
    }
}
