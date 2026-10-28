package ru.dimanonorm.tvlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.MimeTypeMap;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Файловый менеджер для пульта: внутренняя память и флешки, открытие файлов,
 * установка APK, просмотр картинок, копирование, перемещение, переименование, удаление.
 */
public class FilesActivity extends Activity {

    private static final int ACCENT = 0xFFD97757;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault());

    private SharedPreferences prefs;
    private File current;
    private File currentRoot;
    private final List<File> entries = new ArrayList<>();
    private final List<File> clip = new ArrayList<>();
    private boolean clipCut;
    private boolean showHidden;

    private LinearLayout sidebar;
    private TextView pathView;
    private TextView pasteButton;
    private ListView list;
    private TextView emptyView;
    private final EntryAdapter adapter = new EntryAdapter();

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        setContentView(buildUi());
        File start = Environment.getExternalStorageDirectory();
        currentRoot = start;
        open(start);
        if (!hasAllFilesAccess()) {
            askAllFilesAccess();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        buildSidebar();
        if (current != null) refresh();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        File parent = current == null ? null : current.getParentFile();
        if (parent != null && currentRoot != null && !current.equals(currentRoot) && parent.canRead()) {
            open(parent);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            int pos = list.getSelectedItemPosition();
            if (list.hasFocus() && pos >= 0 && pos < entries.size()) {
                showItemMenu(entries.get(pos));
            } else {
                showFolderMenu();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------------------------------------------------------- permission

    private boolean hasAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void askAllFilesAccess() {
        if (Build.VERSION.SDK_INT < 30) {
            requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            return;
        }
        dialog().setTitle("Нужен доступ к файлам")
                .setMessage("Чтобы видеть и изменять все файлы, разрешите доступ.\n\n"
                        + "Если на телевизоре нет такого экрана, выполните на компьютере:\n"
                        + "adb shell appops set " + getPackageName() + " MANAGE_EXTERNAL_STORAGE allow")
                .setPositiveButton("Открыть настройки", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (ActivityNotFoundException ex) {
                        toast("На этом телевизоре экрана нет — используйте команду adb");
                    }
                })
                .setNegativeButton("Позже", null)
                .show();
    }

    // ---------------------------------------------------------------- UI

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(0xFF121212);

        ScrollView sideScroll = new ScrollView(this);
        sideScroll.setBackgroundColor(0xFF1A1A1A);
        sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setPadding(dp(16), dp(28), dp(16), dp(16));
        sideScroll.addView(sidebar, new FrameLayout.LayoutParams(-1, -2));
        root.addView(sideScroll, new LinearLayout.LayoutParams(dp(260), -1));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(32), dp(24), dp(40), dp(16));
        root.addView(content, new LinearLayout.LayoutParams(0, -1, 1f));

        pathView = new TextView(this);
        pathView.setTextColor(0xCCFFFFFF);
        pathView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        pathView.setSingleLine(true);
        pathView.setEllipsize(android.text.TextUtils.TruncateAt.START);
        content.addView(pathView, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setPadding(0, dp(12), 0, dp(12));
        content.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
        toolbar.addView(button("⬆ Вверх", v -> onBackPressed()));
        toolbar.addView(spacer());
        toolbar.addView(button("＋ Папка", v -> createFolder()));
        toolbar.addView(spacer());
        pasteButton = button("Вставить", v -> paste());
        pasteButton.setVisibility(View.GONE);
        toolbar.addView(pasteButton);
        toolbar.addView(spacer());
        toolbar.addView(button("Ещё", v -> showFolderMenu()));

        FrameLayout listBox = new FrameLayout(this);
        content.addView(listBox, new LinearLayout.LayoutParams(-1, 0, 1f));

        list = new ListView(this);
        GradientDrawable sel = new GradientDrawable();
        sel.setCornerRadius(dp(8));
        sel.setColor(0x33FFFFFF);
        sel.setStroke(dp(2), ACCENT);
        list.setSelector(sel);
        list.setDrawSelectorOnTop(false);
        list.setDivider(new ColorDrawable(0x14FFFFFF));
        list.setDividerHeight(1);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> openEntry(entries.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            showItemMenu(entries.get(pos));
            return true;
        });
        listBox.addView(list, new FrameLayout.LayoutParams(-1, -1));

        emptyView = new TextView(this);
        emptyView.setTextColor(0x99FFFFFF);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        emptyView.setGravity(Gravity.CENTER);
        listBox.addView(emptyView, new FrameLayout.LayoutParams(-1, -1));

        return root;
    }

    private View spacer() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(10), 1));
        return v;
    }

    private TextView button(String text, View.OnClickListener click) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setPadding(dp(18), dp(9), dp(18), dp(9));
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(click);
        b.setBackground(pill());
        b.setOnFocusChangeListener((v, has) -> ((TextView) v).setTextColor(has ? 0xFF111111 : Color.WHITE));
        return b;
    }

    private StateListDrawable pill() {
        GradientDrawable normal = new GradientDrawable();
        normal.setCornerRadius(dp(22));
        normal.setColor(0x26FFFFFF);
        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(dp(22));
        focused.setColor(0xEEFFFFFF);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{}, normal);
        return bg;
    }

    private void buildSidebar() {
        sidebar.removeAllViews();
        TextView title = new TextView(this);
        title.setText("Файлы");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setPadding(dp(8), 0, 0, dp(20));
        sidebar.addView(title);

        File internal = Environment.getExternalStorageDirectory();
        addPlace("Внутренняя память", internal, internal);
        addPlace("Загрузки", new File(internal, Environment.DIRECTORY_DOWNLOADS), internal);
        addPlace("Изображения", new File(internal, Environment.DIRECTORY_PICTURES), internal);
        addPlace("Видео", new File(internal, Environment.DIRECTORY_MOVIES), internal);
        addPlace("Музыка", new File(internal, Environment.DIRECTORY_MUSIC), internal);
        addPlace("Обои", new File(internal, "Wallpapers"), internal);

        for (Volume v : removableVolumes()) {
            addPlace("USB: " + v.label, v.dir, v.dir);
        }
    }

    private void addPlace(String label, File dir, File root) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setFocusable(true);
        b.setClickable(true);
        b.setBackground(pill());
        b.setOnFocusChangeListener((v, has) -> ((TextView) v).setTextColor(has ? 0xFF111111 : Color.WHITE));
        b.setOnClickListener(v -> {
            if (!dir.exists()) dir.mkdirs();
            currentRoot = root;
            open(dir);
            list.requestFocus();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(6);
        sidebar.addView(b, lp);
    }

    static final class Volume {
        String label;
        File dir;
    }

    /** Флешки и карты памяти. */
    private List<Volume> removableVolumes() {
        List<Volume> out = new ArrayList<>();
        StorageManager sm = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
        List<String> seen = new ArrayList<>();
        if (sm != null) {
            for (StorageVolume sv : sm.getStorageVolumes()) {
                if (sv.isPrimary()) continue;
                File dir = null;
                if (Build.VERSION.SDK_INT >= 30) {
                    dir = sv.getDirectory();
                } else if (sv.getUuid() != null) {
                    dir = new File("/storage/" + sv.getUuid());
                }
                if (dir == null || !dir.exists()) continue;
                Volume v = new Volume();
                v.label = sv.getDescription(this);
                v.dir = dir;
                out.add(v);
                seen.add(dir.getAbsolutePath());
            }
        }
        // На некоторых прошивках тома не попадают в список — подстраховка через /storage.
        File[] dirs = new File("/storage").listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                String n = d.getName();
                if (n.equals("emulated") || n.equals("self") || seen.contains(d.getAbsolutePath())) continue;
                if (!d.isDirectory() || !d.canRead()) continue;
                Volume v = new Volume();
                v.label = "Флешка " + n;
                v.dir = d;
                out.add(v);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- navigation

    private void open(File dir) {
        current = dir;
        refresh();
        list.setSelection(0);
    }

    private void refresh() {
        final File dir = current;
        pathView.setText(dir.getAbsolutePath());
        pasteButton.setVisibility(clip.isEmpty() ? View.GONE : View.VISIBLE);
        pasteButton.setText((clipCut ? "Переместить сюда (" : "Вставить (") + clip.size() + ")");
        File[] files = dir.listFiles();
        entries.clear();
        if (files != null) {
            for (File f : files) {
                if (!showHidden && f.getName().startsWith(".")) continue;
                entries.add(f);
            }
        }
        Collections.sort(entries, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        adapter.notifyDataSetChanged();
        if (files == null) {
            emptyView.setText(hasAllFilesAccess() ? "Нет доступа к папке" : "Нет доступа к файлам.\nОткройте «Ещё → Доступ к файлам».");
            emptyView.setVisibility(View.VISIBLE);
        } else if (entries.isEmpty()) {
            emptyView.setText("Папка пуста");
            emptyView.setVisibility(View.VISIBLE);
        } else {
            emptyView.setVisibility(View.GONE);
        }
    }

    private void openEntry(File f) {
        if (f.isDirectory()) {
            if (f.canRead()) {
                open(f);
            } else {
                toast("Нет доступа");
            }
            return;
        }
        String ext = extension(f);
        if (isImage(ext)) {
            showImage(f);
            return;
        }
        Uri uri = FilesProvider.uriFor(this, f);
        String mime = mimeOf(f);
        if ("application/vnd.android.package-archive".equals(mime)) {
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                dialog().setTitle("Установка приложений")
                        .setMessage("Разрешите лаунчеру устанавливать приложения.\n\n"
                                + "Если экрана разрешения нет, выполните на компьютере:\n"
                                + "adb shell appops set " + getPackageName() + " REQUEST_INSTALL_PACKAGES allow")
                        .setPositiveButton("Открыть настройки", (d, w) -> {
                            try {
                                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:" + getPackageName())));
                            } catch (ActivityNotFoundException ex) {
                                toast("Экрана нет — используйте команду adb");
                            }
                        })
                        .setNegativeButton("Отмена", null)
                        .show();
                return;
            }
        }
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException ex) {
            toast("Нет приложения, которое умеет открывать такие файлы");
        }
    }

    // ---------------------------------------------------------------- menus

    private void showItemMenu(final File f) {
        final boolean image = !f.isDirectory() && isImage(extension(f));
        List<String> items = new ArrayList<>(Arrays.asList(
                "Открыть", "Копировать", "Вырезать", "Переименовать", "Удалить", "Свойства"));
        if (!f.isDirectory()) items.add(1, "Открыть с помощью…");
        if (image) items.add("Сделать обоями");
        final String[] arr = items.toArray(new String[0]);
        dialog().setTitle(f.getName())
                .setItems(arr, (d, which) -> {
                    switch (arr[which]) {
                        case "Открыть": openEntry(f); break;
                        case "Открыть с помощью…": openWith(f); break;
                        case "Копировать": setClip(f, false); break;
                        case "Вырезать": setClip(f, true); break;
                        case "Переименовать": rename(f); break;
                        case "Удалить": confirmDelete(f); break;
                        case "Свойства": showProperties(f); break;
                        case "Сделать обоями": setWallpaper(f); break;
                        default: break;
                    }
                })
                .show();
    }

    private void showFolderMenu() {
        List<String> items = new ArrayList<>(Arrays.asList(
                "Новая папка",
                showHidden ? "Не показывать скрытые файлы" : "Показывать скрытые файлы",
                "Обновить"));
        if (!clip.isEmpty()) {
            items.add(0, clipCut ? "Переместить сюда" : "Вставить сюда");
            items.add("Очистить буфер");
        }
        if (!hasAllFilesAccess()) items.add("Доступ к файлам");
        final String[] arr = items.toArray(new String[0]);
        dialog().setTitle(current.getName().isEmpty() ? current.getAbsolutePath() : current.getName())
                .setItems(arr, (d, which) -> {
                    String s = arr[which];
                    if (s.equals("Новая папка")) createFolder();
                    else if (s.startsWith("Показывать") || s.startsWith("Не показывать")) {
                        showHidden = !showHidden;
                        refresh();
                    } else if (s.equals("Обновить")) refresh();
                    else if (s.equals("Вставить сюда") || s.equals("Переместить сюда")) paste();
                    else if (s.equals("Очистить буфер")) {
                        clip.clear();
                        refresh();
                    } else if (s.equals("Доступ к файлам")) askAllFilesAccess();
                })
                .show();
    }

    private void openWith(File f) {
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(FilesProvider.uriFor(this, f), mimeOf(f))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(i, "Открыть с помощью").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ex) {
            toast("Нет подходящих приложений");
        }
    }

    private void setClip(File f, boolean cut) {
        clip.clear();
        clip.add(f);
        clipCut = cut;
        toast((cut ? "Вырезано: " : "Скопировано: ") + f.getName() + ". Откройте нужную папку и нажмите «Вставить».");
        refresh();
    }

    // ---------------------------------------------------------------- file operations

    private void createFolder() {
        final EditText input = textInput("Новая папка");
        dialog().setTitle("Новая папка")
                .setView(wrap(input))
                .setPositiveButton("Создать", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!validName(name)) return;
                    File dir = new File(current, name);
                    if (dir.exists()) toast("Такая папка уже есть");
                    else if (!dir.mkdirs()) toast("Не удалось создать папку");
                    refresh();
                })
                .setNegativeButton("Отмена", null)
                .show();
        input.requestFocus();
    }

    private void rename(final File f) {
        final EditText input = textInput(f.getName());
        dialog().setTitle("Переименовать")
                .setView(wrap(input))
                .setPositiveButton("OK", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!validName(name) || name.equals(f.getName())) return;
                    File target = new File(f.getParentFile(), name);
                    if (target.exists()) toast("Файл с таким именем уже есть");
                    else if (!f.renameTo(target)) toast("Не удалось переименовать");
                    refresh();
                })
                .setNegativeButton("Отмена", null)
                .show();
        input.requestFocus();
    }

    private boolean validName(String name) {
        if (name.isEmpty() || name.contains("/") || name.equals(".") || name.equals("..")) {
            toast("Недопустимое имя");
            return false;
        }
        return true;
    }

    private void confirmDelete(final File f) {
        dialog().setTitle("Удалить?")
                .setMessage(f.getName() + (f.isDirectory() ? "\n\nПапка будет удалена вместе со всем содержимым." : ""))
                .setPositiveButton("Удалить", (d, w) -> runTask("Удаление…", () -> {
                    if (!deleteRecursive(f)) throw new IOException("Не все файлы удалось удалить");
                }))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void paste() {
        if (clip.isEmpty()) return;
        final List<File> sources = new ArrayList<>(clip);
        final boolean cut = clipCut;
        final File dest = current;
        for (File s : sources) {
            if (dest.getAbsolutePath().startsWith(s.getAbsolutePath() + "/") || dest.equals(s)) {
                toast("Нельзя вставить папку саму в себя");
                return;
            }
        }
        runTask(cut ? "Перемещение…" : "Копирование…", () -> {
            for (File s : sources) {
                File target = uniqueTarget(dest, s.getName());
                if (cut) {
                    if (!s.renameTo(target)) {
                        copyRecursive(s, target);
                        if (!deleteRecursive(s)) throw new IOException("Скопировано, но исходник не удалён");
                    }
                } else {
                    copyRecursive(s, target);
                }
            }
            if (cut) main.post(clip::clear);
        });
    }

    private File uniqueTarget(File dir, String name) {
        File t = new File(dir, name);
        if (!t.exists()) return t;
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 1; ; i++) {
            t = new File(dir, base + " (" + i + ")" + ext);
            if (!t.exists()) return t;
        }
    }

    private static void copyRecursive(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) throw new IOException("Не удалось создать " + dst.getName());
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyRecursive(k, new File(dst, k.getName()));
        } else {
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Отменено");
                    out.write(buf, 0, n);
                }
            }
            dst.setLastModified(src.lastModified());
        }
    }

    private static boolean deleteRecursive(File f) {
        boolean ok = true;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) ok &= deleteRecursive(k);
        }
        return f.delete() && ok;
    }

    interface IoTask {
        void run() throws IOException;
    }

    private void runTask(String title, IoTask task) {
        final AlertDialog progress = dialog().setTitle(title).setMessage("Подождите…").setCancelable(false).show();
        worker.execute(() -> {
            String error = null;
            try {
                task.run();
            } catch (IOException | SecurityException ex) {
                error = ex.getMessage();
            }
            final String err = error;
            main.post(() -> {
                if (isDestroyed()) return;
                progress.dismiss();
                if (err != null) toast("Ошибка: " + err);
                else toast("Готово");
                refresh();
            });
        });
    }

    private void showProperties(File f) {
        StringBuilder sb = new StringBuilder();
        sb.append("Путь: ").append(f.getAbsolutePath()).append("\n\n");
        if (f.isDirectory()) {
            String[] kids = f.list();
            sb.append("Объектов внутри: ").append(kids == null ? "?" : kids.length).append("\n");
        } else {
            sb.append("Размер: ").append(humanSize(f.length())).append("\n");
            sb.append("Тип: ").append(mimeOf(f)).append("\n");
        }
        sb.append("Изменён: ").append(dateFmt.format(new Date(f.lastModified())));
        dialog().setTitle(f.getName()).setMessage(sb.toString()).setPositiveButton("OK", null).show();
    }

    private void setWallpaper(File f) {
        File dir = new File(Environment.getExternalStorageDirectory(), "Wallpapers");
        runTask("Установка обоев…", () -> {
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Не удалось создать папку Wallpapers");
            File target = f;
            if (!dir.equals(f.getParentFile())) {
                target = new File(dir, f.getName());
                if (!target.exists()) copyRecursive(f, target);
            }
            prefs.edit().putString("wallpaper", target.getName()).putBoolean("wallpaper_dirty", true).apply();
        });
    }

    // ---------------------------------------------------------------- image viewer

    private void showImage(File f) {
        final List<File> images = new ArrayList<>();
        for (File e : entries) if (!e.isDirectory() && isImage(extension(e))) images.add(e);
        final int[] index = {Math.max(0, images.indexOf(f))};

        final Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(Color.BLACK);
        final ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        final TextView caption = new TextView(this);
        caption.setTextColor(0xDDFFFFFF);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        caption.setBackgroundColor(0x88000000);
        caption.setPadding(dp(16), dp(8), dp(16), dp(8));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        clp.bottomMargin = dp(24);
        box.addView(caption, clp);
        d.setContentView(box);

        final DisplayMetrics dm = getResources().getDisplayMetrics();
        final Runnable show = new Runnable() {
            @Override
            public void run() {
                final File cur = images.get(index[0]);
                caption.setText(cur.getName() + "   " + (index[0] + 1) + "/" + images.size()
                        + "   ◀ ▶ листать · ОК — меню");
                worker.execute(() -> {
                    Bitmap b = decodeScaled(cur, dm.widthPixels, dm.heightPixels);
                    main.post(() -> iv.setImageBitmap(b));
                });
            }
        };
        d.setOnKeyListener((dlg, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && index[0] < images.size() - 1) {
                index[0]++;
                show.run();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && index[0] > 0) {
                index[0]--;
                show.run();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_MENU) {
                final File cur = images.get(index[0]);
                dialog().setTitle(cur.getName())
                        .setItems(new String[]{"Сделать обоями", "Свойства", "Закрыть просмотр"}, (x, w) -> {
                            if (w == 0) setWallpaper(cur);
                            else if (w == 1) showProperties(cur);
                            else d.dismiss();
                        })
                        .show();
                return true;
            }
            return false;
        });
        d.setOnDismissListener(x -> iv.setImageDrawable(null));
        d.show();
        show.run();
    }

    static Bitmap decodeScaled(File f, int reqW, int reqH) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= reqW && o.outHeight / (sample * 2) >= reqH) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.RGB_565;
        try {
            return BitmapFactory.decodeFile(f.getAbsolutePath(), d);
        } catch (OutOfMemoryError err) {
            return null;
        }
    }

    // ---------------------------------------------------------------- helpers

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    private EditText textInput(String initial) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setText(initial);
        e.selectAll();
        return e;
    }

    private View wrap(View v) {
        FrameLayout f = new FrameLayout(this);
        f.setPadding(dp(24), dp(8), dp(24), 0);
        f.addView(v);
        return f;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    static String extension(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static boolean isImage(String ext) {
        return ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png") || ext.equals("webp")
                || ext.equals("gif") || ext.equals("bmp");
    }

    static String mimeOf(File f) {
        String ext = extension(f);
        if (ext.equals("apk")) return "application/vnd.android.package-archive";
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (m != null) return m;
        if (ext.equals("mkv")) return "video/x-matroska";
        return "*/*";
    }

    static String humanSize(long b) {
        if (b < 1024) return b + " Б";
        double v = b / 1024.0;
        String[] u = {"КБ", "МБ", "ГБ", "ТБ"};
        int i = 0;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.getDefault(), v < 10 ? "%.1f %s" : "%.0f %s", v, u[i]);
    }

    // ---------------------------------------------------------------- adapter

    static final class RowHolder {
        ImageView icon;
        TextView name;
        TextView info;
    }

    private final class EntryAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            RowHolder h;
            View row = convertView;
            if (row == null) {
                LinearLayout r = new LinearLayout(FilesActivity.this);
                r.setOrientation(LinearLayout.HORIZONTAL);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setPadding(dp(14), dp(12), dp(14), dp(12));
                h = new RowHolder();
                h.icon = new ImageView(FilesActivity.this);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(32), dp(32));
                ilp.rightMargin = dp(14);
                r.addView(h.icon, ilp);
                h.name = new TextView(FilesActivity.this);
                h.name.setTextColor(Color.WHITE);
                h.name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
                h.name.setSingleLine(true);
                h.name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                r.addView(h.name, new LinearLayout.LayoutParams(0, -2, 1f));
                h.info = new TextView(FilesActivity.this);
                h.info.setTextColor(0x99FFFFFF);
                h.info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                h.info.setGravity(Gravity.END);
                r.addView(h.info, new LinearLayout.LayoutParams(dp(260), -2));
                r.setTag(h);
                row = r;
            } else {
                h = (RowHolder) row.getTag();
            }
            File f = entries.get(position);
            h.icon.setImageDrawable(Icons.file(f));
            h.name.setText(f.getName());
            String date = dateFmt.format(new Date(f.lastModified()));
            if (f.isDirectory()) {
                h.info.setText(date);
            } else {
                h.info.setText(humanSize(f.length()) + "   " + date);
            }
            return row;
        }
    }
}
