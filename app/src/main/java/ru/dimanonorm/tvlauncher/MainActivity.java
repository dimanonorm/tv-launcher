package ru.dimanonorm.tvlauncher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Лёгкий главный экран для Android TV.
 * Только ТВ-приложения, свой фон из папки /sdcard/Wallpapers, без рекомендаций и анимированных фонов.
 * Весь интерфейс собран кодом, без библиотек — чтобы занимать минимум памяти.
 */
public class MainActivity extends Activity {

    private static final int COLUMNS = 5;
    private static final int REQ_STORAGE = 1;
    private static final String PREFS = "launcher";
    private static final String KEY_ORDER = "order";
    private static final String KEY_HIDDEN = "hidden";
    private static final String KEY_WALLPAPER = "wallpaper";   // имя файла, "" = авто, "-" = без обоев
    private static final int ACCENT = 0xFFD97757;
    private static final String SCREENSAVER = "@screensaver";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private ImageView wallpaperView;
    private GridView grid;
    private TextView emptyView;
    private final AppAdapter adapter = new AppAdapter();
    private final List<AppEntry> apps = new ArrayList<>();
    private int tileWidth;
    private int selectedPos = -1;
    private View selectedView;
    private int loadGeneration = 0;

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            loadApps();
        }
    };

    static final class AppEntry {
        String pkg;
        ComponentName component;
        CharSequence label;
        Drawable banner;
        boolean isBanner;
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(buildUi());

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(packageReceiver, f);

        loadApps();
        loadWallpaper(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Обои могли смениться в файловом менеджере.
        if (prefs.getBoolean("wallpaper_dirty", false)) {
            prefs.edit().putBoolean("wallpaper_dirty", false).apply();
            loadWallpaper(true);
        }
    }

    private void openFiles() {
        startActivity(new Intent(this, FilesActivity.class));
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(packageReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Нажали Home, уже находясь на главном экране — вернуться к первой плитке.
        if (!apps.isEmpty()) {
            grid.requestFocus();
            grid.setSelection(0);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Главный экран: «Назад» ничего не закрывает.
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (grid.hasFocus() && selectedPos >= 0 && selectedPos < apps.size()) {
                showAppMenu(selectedPos);
            } else {
                showLauncherMenu();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_STORAGE) {
            if (hasStoragePermission()) {
                loadWallpaper(false);
            } else {
                toast("Без доступа к фото обои не загрузятся");
            }
        }
    }

    // ---------------------------------------------------------------- UI

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF101010);

        wallpaperView = new ImageView(this);
        wallpaperView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wallpaperView, new FrameLayout.LayoutParams(-1, -1));

        // Затемнение сверху и снизу, чтобы текст читался на любом фоне.
        View dim = new View(this);
        dim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x99000000, 0x33000000, 0xAA000000}));
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(48);
        column.setPadding(pad, dp(32), pad, dp(16));
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        // --- верхняя панель: часы, дата, кнопки
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        column.addView(top, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        time.setTextColor(Color.WHITE);
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clockBox.addView(time);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d MMMM");
        date.setFormat12Hour("EEEE, d MMMM");
        date.setTextColor(0xCCFFFFFF);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        clockBox.addView(date);
        top.addView(clockBox, new LinearLayout.LayoutParams(0, -2, 1f));

        top.addView(makeButton("Файлы", v -> openFiles()));
        top.addView(new View(this), new LinearLayout.LayoutParams(dp(12), 1));
        top.addView(makeButton("Меню", v -> showLauncherMenu()));
        top.addView(new View(this), new LinearLayout.LayoutParams(dp(12), 1));
        top.addView(makeButton("Настройки", v -> openSystemSettings()));

        // --- сетка приложений
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int spacing = dp(20);
        int gridWidth = dm.widthPixels - pad * 2;
        tileWidth = (gridWidth - spacing * (COLUMNS - 1)) / COLUMNS;

        grid = new GridView(this);
        grid.setNumColumns(COLUMNS);
        grid.setHorizontalSpacing(spacing);
        grid.setVerticalSpacing(dp(24));
        grid.setStretchMode(GridView.NO_STRETCH);
        grid.setColumnWidth(tileWidth);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setClipToPadding(false);
        grid.setClipChildren(false);
        grid.setPadding(0, dp(28), 0, dp(28));
        grid.setVerticalScrollBarEnabled(false);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> launch(pos));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            showAppMenu(pos);
            return true;
        });
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                highlight(view, position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                highlight(null, -1);
            }
        });
        grid.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                highlight(grid.getSelectedView(), grid.getSelectedItemPosition());
            } else {
                highlight(null, -1);
            }
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, 0, 1f);
        glp.topMargin = dp(8);
        column.addView(grid, glp);

        emptyView = new TextView(this);
        emptyView.setText("Загрузка…");
        emptyView.setTextColor(0xCCFFFFFF);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        emptyView.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        root.addView(emptyView, elp);

        return root;
    }

    private TextView makeButton(String text, View.OnClickListener click) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setPadding(dp(20), dp(10), dp(20), dp(10));
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(click);

        GradientDrawable normal = new GradientDrawable();
        normal.setCornerRadius(dp(24));
        normal.setColor(0x33FFFFFF);
        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(dp(24));
        focused.setColor(0xEEFFFFFF);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{}, normal);
        b.setBackground(bg);
        b.setOnFocusChangeListener((v, has) -> ((TextView) v).setTextColor(has ? 0xFF111111 : Color.WHITE));
        return b;
    }

    private void highlight(View view, int position) {
        if (selectedView != null && selectedView != view) {
            setTileFocused(selectedView, false);
        }
        selectedView = view;
        selectedPos = position;
        if (view != null && grid.hasFocus()) {
            setTileFocused(view, true);
        }
    }

    private void setTileFocused(View tile, boolean focused) {
        Object tag = tile.getTag();
        if (!(tag instanceof TileHolder)) return;
        TileHolder h = (TileHolder) tag;
        h.cardBg.setStroke(dp(3), focused ? Color.WHITE : Color.TRANSPARENT);
        h.label.setTextColor(focused ? Color.WHITE : 0xBBFFFFFF);
        float s = focused ? 1.08f : 1f;
        tile.animate().scaleX(s).scaleY(s).setDuration(120).start();
        tile.setElevation(focused ? dp(8) : 0);
    }

    // ---------------------------------------------------------------- apps

    private void loadApps() {
        final int gen = ++loadGeneration;
        final PackageManager pm = getPackageManager();
        final List<String> order = getOrder();
        final Set<String> hidden = new HashSet<>(prefs.getStringSet(KEY_HIDDEN, Collections.emptySet()));
        worker.execute(() -> {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            List<ResolveInfo> list = pm.queryIntentActivities(q, 0);
            Map<String, AppEntry> byPkg = new LinkedHashMap<>();
            for (ResolveInfo ri : list) {
                ActivityInfo ai = ri.activityInfo;
                if (ai == null || getPackageName().equals(ai.packageName)) continue;
                if (hidden.contains(ai.packageName) || byPkg.containsKey(ai.packageName)) continue;
                AppEntry e = new AppEntry();
                e.pkg = ai.packageName;
                e.component = new ComponentName(ai.packageName, ai.name);
                e.label = ri.loadLabel(pm);
                Drawable banner = ai.loadBanner(pm);
                if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
                e.isBanner = banner != null;
                e.banner = banner != null ? banner : ri.loadIcon(pm);
                byPkg.put(e.pkg, e);
            }
            if (!hidden.contains(SCREENSAVER)) {
                AppEntry ss = new AppEntry();
                ss.pkg = SCREENSAVER;
                ss.component = new ComponentName("com.android.systemui", "com.android.systemui.Somnambulator");
                ss.label = "Заставка";
                ss.banner = Icons.screensaverBanner();
                ss.isBanner = true;
                byPkg.put(ss.pkg, ss);
            }
            List<AppEntry> sorted = new ArrayList<>();
            for (String p : order) {
                AppEntry e = byPkg.remove(p);
                if (e != null) sorted.add(e);
            }
            List<AppEntry> rest = new ArrayList<>(byPkg.values());
            Collections.sort(rest, (a, b) -> a.label.toString().compareToIgnoreCase(b.label.toString()));
            sorted.addAll(rest);
            main.post(() -> {
                if (gen != loadGeneration || isDestroyed()) return;
                apps.clear();
                apps.addAll(sorted);
                adapter.notifyDataSetChanged();
                emptyView.setVisibility(apps.isEmpty() ? View.VISIBLE : View.GONE);
                emptyView.setText("Нет ТВ-приложений");
                if (!apps.isEmpty() && !grid.hasFocus() && getCurrentFocus() == null) {
                    grid.requestFocus();
                }
            });
        });
    }

    private List<String> getOrder() {
        String s = prefs.getString(KEY_ORDER, "");
        if (s.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(s.split("\n")));
    }

    private void saveOrder() {
        StringBuilder sb = new StringBuilder();
        for (AppEntry e : apps) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.pkg);
        }
        prefs.edit().putString(KEY_ORDER, sb.toString()).apply();
    }

    private void launch(int pos) {
        if (pos < 0 || pos >= apps.size()) return;
        AppEntry e = apps.get(pos);
        if (SCREENSAVER.equals(e.pkg)) {
            startScreensaver();
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException ex) {
            Intent alt = getPackageManager().getLeanbackLaunchIntentForPackage(e.pkg);
            if (alt == null) alt = getPackageManager().getLaunchIntentForPackage(e.pkg);
            if (alt != null) {
                startActivity(alt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else {
                toast("Не удалось открыть " + e.label);
            }
        }
    }

    private void startScreensaver() {
        try {
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .setClassName("com.android.systemui", "com.android.systemui.Somnambulator")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException ex) {
            try {
                startActivity(new Intent(Settings.ACTION_DREAM_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                toast("Не удалось запустить заставку — открыл её настройки");
            } catch (ActivityNotFoundException ex2) {
                toast("Заставка недоступна на этом телевизоре");
            }
        }
    }

    private void move(int from, int to) {
        if (from < 0 || from >= apps.size()) return;
        to = Math.max(0, Math.min(apps.size() - 1, to));
        if (from == to) return;
        AppEntry e = apps.remove(from);
        apps.add(to, e);
        saveOrder();
        adapter.notifyDataSetChanged();
        final int target = to;
        grid.post(() -> {
            grid.requestFocus();
            grid.setSelection(target);
        });
    }

    private void showAppMenu(final int pos) {
        if (pos < 0 || pos >= apps.size()) return;
        final AppEntry e = apps.get(pos);
        String[] items = {
                "Сдвинуть влево",
                "Сдвинуть вправо",
                "Сдвинуть вверх",
                "Сдвинуть вниз",
                "В начало",
                "Скрыть с главного экрана",
                "О приложении",
                "Удалить приложение"
        };
        dialog().setTitle(e.label)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: move(pos, pos - 1); break;
                        case 1: move(pos, pos + 1); break;
                        case 2: move(pos, pos - COLUMNS); break;
                        case 3: move(pos, pos + COLUMNS); break;
                        case 4: move(pos, 0); break;
                        case 5: hide(e.pkg); break;
                        case 6: openAppInfo(e.pkg); break;
                        case 7: uninstall(e.pkg); break;
                        default: break;
                    }
                })
                .show();
    }

    private void hide(String pkg) {
        Set<String> hidden = new HashSet<>(prefs.getStringSet(KEY_HIDDEN, Collections.emptySet()));
        hidden.add(pkg);
        prefs.edit().putStringSet(KEY_HIDDEN, hidden).apply();
        loadApps();
    }

    private void showHidden() {
        final Set<String> hidden = new HashSet<>(prefs.getStringSet(KEY_HIDDEN, Collections.emptySet()));
        if (hidden.isEmpty()) {
            toast("Скрытых приложений нет");
            return;
        }
        final List<String> pkgs = new ArrayList<>(hidden);
        Collections.sort(pkgs);
        final String[] names = new String[pkgs.size()];
        PackageManager pm = getPackageManager();
        for (int i = 0; i < pkgs.size(); i++) {
            if (SCREENSAVER.equals(pkgs.get(i))) {
                names[i] = "Заставка";
                continue;
            }
            try {
                names[i] = pm.getApplicationLabel(pm.getApplicationInfo(pkgs.get(i), 0)).toString();
            } catch (PackageManager.NameNotFoundException ex) {
                names[i] = pkgs.get(i);
            }
        }
        dialog().setTitle("Вернуть на главный экран")
                .setItems(names, (d, which) -> {
                    hidden.remove(pkgs.get(which));
                    prefs.edit().putStringSet(KEY_HIDDEN, hidden).apply();
                    loadApps();
                })
                .show();
    }

    private void openAppInfo(String pkg) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ex) {
            toast("Экран сведений недоступен");
        }
    }

    @SuppressWarnings("deprecation")
    private void uninstall(String pkg) {
        try {
            startActivity(new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ex) {
            toast("Удаление недоступно");
        }
    }

    private void openSystemSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ex) {
            toast("Настройки недоступны");
        }
    }

    // ---------------------------------------------------------------- launcher menu

    private void showLauncherMenu() {
        String[] items = {
                "Файлы",
                "Выбрать обои",
                "Обои: автоматически (новейшая картинка)",
                "Без обоев",
                "Скрытые приложения",
                "Сбросить порядок плиток",
                "О программе"
        };
        dialog().setTitle("Lite Launcher")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: openFiles(); break;
                        case 1: chooseWallpaper(); break;
                        case 2:
                            prefs.edit().putString(KEY_WALLPAPER, "").apply();
                            loadWallpaper(true);
                            break;
                        case 3:
                            prefs.edit().putString(KEY_WALLPAPER, "-").apply();
                            wallpaperView.setImageDrawable(null);
                            break;
                        case 4: showHidden(); break;
                        case 5:
                            prefs.edit().remove(KEY_ORDER).apply();
                            loadApps();
                            break;
                        case 6: showAbout(); break;
                        default: break;
                    }
                })
                .show();
    }

    private void showAbout() {
        String version = "?";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = pi.versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        dialog().setTitle("Lite Launcher " + version)
                .setMessage("Лёгкий главный экран без рекомендаций и рекламы.\n\n"
                        + "Обои: положите картинку (jpg, png, webp) в папку Wallpapers во внутренней памяти.\n"
                        + "Кнопка «Меню» на пульте или долгое нажатие ОК на плитке — действия с приложением.")
                .setPositiveButton("OK", null)
                .show();
    }

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    // ---------------------------------------------------------------- wallpaper

    private File wallpaperDir() {
        return new File(Environment.getExternalStorageDirectory(), "Wallpapers");
    }

    private String storagePermission() {
        return Build.VERSION.SDK_INT >= 33
                ? "android.permission.READ_MEDIA_IMAGES"
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasStoragePermission() {
        return checkSelfPermission(storagePermission()) == PackageManager.PERMISSION_GRANTED;
    }

    private File[] listWallpapers() {
        File[] files = wallpaperDir().listFiles(f -> {
            if (!f.isFile()) return false;
            String n = f.getName().toLowerCase(Locale.ROOT);
            return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp");
        });
        if (files == null) return new File[0];
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return files;
    }

    private void chooseWallpaper() {
        if (!hasStoragePermission()) {
            requestPermissions(new String[]{storagePermission()}, REQ_STORAGE);
            return;
        }
        final File[] files = listWallpapers();
        if (files.length == 0) {
            toast("Папка Wallpapers пуста или не найдена");
            return;
        }
        String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++) names[i] = files[i].getName();
        dialog().setTitle("Обои")
                .setItems(names, (d, which) -> {
                    prefs.edit().putString(KEY_WALLPAPER, files[which].getName()).apply();
                    loadWallpaper(true);
                })
                .show();
    }

    private void loadWallpaper(boolean userAction) {
        final String choice = prefs.getString(KEY_WALLPAPER, "");
        if ("-".equals(choice)) {
            wallpaperView.setImageDrawable(null);
            return;
        }
        if (!hasStoragePermission()) {
            if (userAction || !prefs.getBoolean("asked", false)) {
                prefs.edit().putBoolean("asked", true).apply();
                requestPermissions(new String[]{storagePermission()}, REQ_STORAGE);
            }
            return;
        }
        final DisplayMetrics dm = getResources().getDisplayMetrics();
        worker.execute(() -> {
            File target = null;
            File[] files = listWallpapers();
            if (!choice.isEmpty()) {
                for (File f : files) if (f.getName().equals(choice)) target = f;
            }
            if (target == null) {
                for (File f : files) {
                    if (target == null || f.lastModified() > target.lastModified()) target = f;
                }
            }
            final Bitmap bmp = target == null ? null : decodeScaled(target, dm.widthPixels, dm.heightPixels);
            main.post(() -> {
                if (isDestroyed()) return;
                wallpaperView.setImageBitmap(bmp);
                if (bmp == null && userAction) toast("Картинка не найдена в папке Wallpapers");
            });
        });
    }

    /** Загружает картинку уменьшенной до размера экрана в формате RGB_565 — экономит память. */
    private static Bitmap decodeScaled(File f, int reqW, int reqH) {
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

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- adapter

    static final class TileHolder {
        ImageView image;
        TextView label;
        GradientDrawable cardBg;
    }

    private final class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return apps.size();
        }

        @Override
        public Object getItem(int position) {
            return apps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return apps.get(position).pkg.hashCode();
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TileHolder h;
            View tile = convertView;
            if (tile == null) {
                LinearLayout box = new LinearLayout(MainActivity.this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setClipChildren(false);

                int cardH = tileWidth * 9 / 16;
                FrameLayout card = new FrameLayout(MainActivity.this);
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(10));
                bg.setColor(0xFF1E1E1E);
                card.setBackground(bg);
                card.setClipToOutline(true);
                // Рамка фокуса рисуется поверх картинки, а не вокруг неё — без полей по бокам.
                GradientDrawable frame = new GradientDrawable();
                frame.setCornerRadius(dp(10));
                frame.setColor(Color.TRANSPARENT);
                frame.setStroke(dp(3), Color.TRANSPARENT);
                card.setForeground(frame);
                ImageView img = new ImageView(MainActivity.this);
                card.addView(img, new FrameLayout.LayoutParams(-1, -1));
                box.addView(card, new LinearLayout.LayoutParams(tileWidth, cardH));

                TextView label = new TextView(MainActivity.this);
                label.setTextColor(0xBBFFFFFF);
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                label.setSingleLine(true);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                label.setGravity(Gravity.CENTER_HORIZONTAL);
                label.setPadding(0, dp(8), 0, 0);
                box.addView(label, new LinearLayout.LayoutParams(-1, -2));

                h = new TileHolder();
                h.image = img;
                h.label = label;
                h.cardBg = frame;
                box.setTag(h);
                tile = box;
            } else {
                h = (TileHolder) tile.getTag();
            }

            AppEntry e = apps.get(position);
            h.image.setImageDrawable(e.banner);
            if (e.isBanner) {
                // Баннер заполняет всю ячейку и чуть приближен, чтобы срезать поля, встроенные в сам баннер.
                h.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                h.image.setPadding(0, 0, 0, 0);
                h.image.setScaleX(1.06f);
                h.image.setScaleY(1.06f);
            } else {
                h.image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                int p = tileWidth / 10;
                h.image.setPadding(p, p, p, p);
                h.image.setScaleX(1f);
                h.image.setScaleY(1f);
            }
            h.label.setText(e.label);

            boolean focused = grid.hasFocus() && position == grid.getSelectedItemPosition();
            h.cardBg.setStroke(dp(3), focused ? Color.WHITE : Color.TRANSPARENT);
            h.label.setTextColor(focused ? Color.WHITE : 0xBBFFFFFF);
            float s = focused ? 1.08f : 1f;
            tile.setScaleX(s);
            tile.setScaleY(s);
            if (focused) selectedView = tile;
            return tile;
        }
    }
}
