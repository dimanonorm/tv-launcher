package ru.dimanonorm.tvlauncher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Отдаёт файлы другим приложениям (видеоплеер, установщик APK) только на чтение
 * и только по явному разрешению, которое выдаётся при открытии файла.
 */
public class FilesProvider extends ContentProvider {

    static String authority(Context c) {
        return c.getPackageName() + ".files";
    }

    static Uri uriFor(Context c, File f) {
        return new Uri.Builder()
                .scheme("content")
                .authority(authority(c))
                .encodedPath("/f" + Uri.encode(f.getAbsolutePath(), "/"))
                .build();
    }

    private static File fileFor(Uri uri) throws FileNotFoundException {
        String path = uri.getPath();
        if (path == null || !path.startsWith("/f/")) throw new FileNotFoundException("bad uri");
        File f = new File(path.substring(2));
        try {
            f = f.getCanonicalFile();
        } catch (java.io.IOException e) {
            throw new FileNotFoundException("bad path");
        }
        String p = f.getAbsolutePath();
        if (!p.startsWith("/storage/") && !p.startsWith("/sdcard/")) {
            throw new FileNotFoundException("outside storage");
        }
        if (!f.isFile()) throw new FileNotFoundException("not a file");
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && mode.contains("w")) throw new FileNotFoundException("read only");
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        try {
            return FilesActivity.mimeOf(fileFor(uri));
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = fileFor(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }
}
