package ru.dimanonorm.tvlauncher;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import java.io.File;

/** Плоские 2D-иконки, нарисованные кодом — без эмодзи и картинок. */
final class Icons {

    private Icons() {
    }

    enum Kind { FOLDER, IMAGE, VIDEO, AUDIO, APK, ARCHIVE, DOC, FILE }

    static Kind kindOf(File f) {
        if (f.isDirectory()) return Kind.FOLDER;
        String e = FilesActivity.extension(f);
        if (FilesActivity.isImage(e)) return Kind.IMAGE;
        switch (e) {
            case "mp4": case "mkv": case "avi": case "mov": case "webm": case "ts": case "m4v": return Kind.VIDEO;
            case "mp3": case "flac": case "wav": case "ogg": case "m4a": case "aac": return Kind.AUDIO;
            case "apk": return Kind.APK;
            case "zip": case "rar": case "7z": case "tar": case "gz": return Kind.ARCHIVE;
            case "pdf": case "txt": case "doc": case "docx": case "xls": case "xlsx": return Kind.DOC;
            default: return Kind.FILE;
        }
    }

    static Drawable file(File f) {
        return new FileIcon(kindOf(f));
    }

    static Drawable screensaverBanner() {
        return new ScreensaverBanner();
    }

    // ------------------------------------------------------------ base

    private abstract static class Base extends Drawable {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override
        public void setAlpha(int alpha) {
            p.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            p.setColorFilter(cf);
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // ------------------------------------------------------------ files

    private static final class FileIcon extends Base {
        private final Kind kind;

        FileIcon(Kind kind) {
            this.kind = kind;
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            float x = b.left + (b.width() - s) / 2f;
            float y = b.top + (b.height() - s) / 2f;
            if (kind == Kind.FOLDER) drawFolder(c, x, y, s);
            else drawPage(c, x, y, s);
        }

        private void drawFolder(Canvas c, float x, float y, float s) {
            float r = s * 0.08f;
            // язычок
            p.setColor(0xFFD9A330);
            c.drawRoundRect(new RectF(x + s * 0.08f, y + s * 0.18f, x + s * 0.48f, y + s * 0.40f), r, r, p);
            // задняя стенка
            p.setColor(0xFFE3AE3A);
            c.drawRoundRect(new RectF(x + s * 0.08f, y + s * 0.26f, x + s * 0.92f, y + s * 0.82f), r, r, p);
            // передняя стенка
            p.setColor(0xFFF2C14E);
            c.drawRoundRect(new RectF(x + s * 0.08f, y + s * 0.36f, x + s * 0.92f, y + s * 0.82f), r, r, p);
        }

        private void drawPage(Canvas c, float x, float y, float s) {
            float left = x + s * 0.20f, right = x + s * 0.80f;
            float top = y + s * 0.10f, bottom = y + s * 0.90f;
            float fold = s * 0.18f;
            Path page = new Path();
            page.moveTo(left, top);
            page.lineTo(right - fold, top);
            page.lineTo(right, top + fold);
            page.lineTo(right, bottom);
            page.lineTo(left, bottom);
            page.close();
            p.setColor(0xFFE8E8E8);
            c.drawPath(page, p);
            Path corner = new Path();
            corner.moveTo(right - fold, top);
            corner.lineTo(right - fold, top + fold);
            corner.lineTo(right, top + fold);
            corner.close();
            p.setColor(0xFFB8B8B8);
            c.drawPath(corner, p);

            String tag;
            int color;
            switch (kind) {
                case IMAGE: tag = "IMG"; color = 0xFF3FA66B; break;
                case VIDEO: tag = "VID"; color = 0xFFD95757; break;
                case AUDIO: tag = "MP3"; color = 0xFF8E6BD9; break;
                case APK: tag = "APK"; color = 0xFF4CAF50; break;
                case ARCHIVE: tag = "ZIP"; color = 0xFFB0864A; break;
                case DOC: tag = "DOC"; color = 0xFF4A86D9; break;
                default: tag = ""; color = 0xFF8A8A8A; break;
            }
            RectF badge = new RectF(x + s * 0.10f, y + s * 0.54f, x + s * 0.74f, y + s * 0.78f);
            p.setColor(color);
            c.drawRoundRect(badge, s * 0.04f, s * 0.04f, p);
            if (!tag.isEmpty()) {
                p.setColor(0xFFFFFFFF);
                p.setTypeface(Typeface.DEFAULT_BOLD);
                p.setTextSize(s * 0.17f);
                p.setTextAlign(Paint.Align.CENTER);
                Paint.FontMetrics fm = p.getFontMetrics();
                c.drawText(tag, badge.centerX(), badge.centerY() - (fm.ascent + fm.descent) / 2f, p);
            }
        }
    }

    // ------------------------------------------------------------ screensaver tile

    private static final class ScreensaverBanner extends Base {
        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float w = b.width(), h = b.height();
            p.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                    0xFF141E3C, 0xFF3B2560, Shader.TileMode.CLAMP));
            c.drawRect(b, p);
            p.setShader(null);

            // звёзды
            p.setColor(0xCCFFFFFF);
            float[][] stars = {{0.12f, 0.2f}, {0.25f, 0.72f}, {0.82f, 0.18f}, {0.9f, 0.6f}, {0.7f, 0.82f}, {0.4f, 0.15f}};
            for (float[] st : stars) c.drawCircle(b.left + w * st[0], b.top + h * st[1], h * 0.018f, p);

            // полумесяц
            float cx = b.left + w * 0.5f, cy = b.top + h * 0.40f, r = h * 0.20f;
            Path moon = new Path();
            moon.addCircle(cx, cy, r, Path.Direction.CW);
            Path cut = new Path();
            cut.addCircle(cx + r * 0.45f, cy - r * 0.25f, r * 0.85f, Path.Direction.CW);
            moon.op(cut, Path.Op.DIFFERENCE);
            p.setColor(0xFFF2D27A);
            c.drawPath(moon, p);

            p.setColor(0xFFFFFFFF);
            p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            p.setTextSize(h * 0.16f);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText("Заставка", cx, b.top + h * 0.86f, p);
        }
    }
}
