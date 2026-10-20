package app.nexus.launcher;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Apple TV style home screen for the XGIMI MoGo 2 (720p, 1 GB-class RAM).
 * Everything is drawn in a 1280x720 design space and scaled to the real screen.
 * Focus is handled here (not by Android's focus search) so D-pad moves are deterministic:
 * top bar (Inputs, Wi-Fi, Settings) / dock (4 apps) / grid (4 columns, scrolls).
 * No blur, no video, no polling: the clock ticks on ACTION_TIME_TICK, Wi-Fi on a network callback.
 */
public class LauncherActivity extends Activity {

    static final float DW = 1280f;
    static final int TOP = 0, DOCK = 1, GRID = 2;

    // design-space geometry
    static final float MARGIN = 64, PILL_H = 44, PILL_GAP = 14, PILL_PAD = 22;
    static final float DOCK_TILE_W = 236, DOCK_TILE_H = 133, DOCK_GAP = 22, DOCK_PAD = 24;
    static final float BAND_Y = 512, BAND_H = 184;
    static final float GRID_TOP = 770, GRID_W = 270, GRID_H = 152, GRID_GX = 24, GRID_PITCH = 214;
    static final float HL_PAD = 10;

    static class App {
        String pkg, label;
        ComponentName cn;
        Drawable banner;
        boolean isIcon;
        int glow = 0;
    }

    static class InputItem {
        String id, label;
        boolean tuner;
    }

    float s;
    FrameLayout root, content;
    GlassView glass;
    GlowView glow;
    CritterView critters;
    TextView greeting, clock, date, bigName;
    final List<TextView> pillTexts = new ArrayList<>();
    final List<View> pillBgs = new ArrayList<>();
    final List<RectF> pillRects = new ArrayList<>();
    final List<App> dock = new ArrayList<>(), grid = new ArrayList<>();
    final List<ImageView> dockViews = new ArrayList<>(), gridViews = new ArrayList<>();
    final List<TextView> gridLabels = new ArrayList<>();
    final List<RectF> dockRects = new ArrayList<>(), gridRects = new ArrayList<>();
    FrameLayout dropdown;
    final List<TextView> ddRows = new ArrayList<>();
    List<InputItem> inputs = new ArrayList<>();
    int zone = DOCK, idx = 0, lastDock = 0, ddIdx = 0;
    boolean ddOpen;
    float contentH = 720;
    String wifiName;
    Typeface outfit;
    SharedPreferences prefs;
    ValueAnimator glassAnim, glowAnim;
    final Handler ui = new Handler(Looper.getMainLooper());
    ConnectivityManager cm;
    ConnectivityManager.NetworkCallback netCb;
    boolean built;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        s = getResources().getDisplayMetrics().widthPixels / DW;
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        try {
            outfit = Typeface.createFromAsset(getAssets(), "Outfit.ttf");
        } catch (Exception e) {
            outfit = Typeface.DEFAULT;
        }
        root = new FrameLayout(this);
        if (getResources().getBoolean(R.bool.critter_theme)) {
            root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF080B26, 0xFF150F3F, 0xFF241556}));
        } else {
            root.setBackgroundColor(Color.BLACK);
        }
        content = new FrameLayout(this);
        root.addView(content, new FrameLayout.LayoutParams(px(DW), px(720)));
        setContentView(root);
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        build();
        zone = prefs.getInt("zone", DOCK);
        idx = prefs.getInt("idx", 0);
        lastDock = prefs.getInt("lastDock", 0);
        clampSelection();
        select(zone, idx, false);

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(pkgReceiver, pf);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(pkgReceiver);
        super.onDestroy();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, f);
        netCb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { ui.post(LauncherActivity.this::refreshWifi); }
            @Override public void onLost(Network n) { ui.post(LauncherActivity.this::refreshWifi); }
        };
        try { cm.registerDefaultNetworkCallback(netCb); } catch (Exception ignored) { }
        refreshTime();
        refreshWifi();
        if (critters != null) critters.start();
    }

    @Override
    protected void onStop() {
        if (critters != null) critters.stop();
        unregisterReceiver(timeReceiver);
        try { cm.unregisterNetworkCallback(netCb); } catch (Exception ignored) { }
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while we are showing: close any dropdown and go back to the dock.
        closeDropdown();
        if (zone != DOCK) select(DOCK, Math.min(lastDock, Math.max(0, dock.size() - 1)), true);
    }

    final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { refreshTime(); }
    };

    final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            ui.removeCallbacks(rebuild);
            ui.postDelayed(rebuild, 700);
        }
    };

    final Runnable rebuild = () -> {
        build();
        clampSelection();
        select(zone, idx, false);
        refreshTime();
        refreshWifi();
    };

    // ---------------------------------------------------------------- build

    int px(float v) { return Math.round(v * s); }

    FrameLayout.LayoutParams lp(float x, float y, float w, float h) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(px(w), px(h));
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.leftMargin = px(x);
        p.topMargin = px(y);
        return p;
    }

    TextView text(float size, int weight, int color) {
        TextView t = new TextView(this);
        t.setTypeface(outfit);
        try { t.setFontVariationSettings("'wght' " + weight); } catch (Exception ignored) { }
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * s);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        return t;
    }

    void build() {
        content.removeAllViews();
        pillTexts.clear(); pillBgs.clear(); pillRects.clear();
        dockViews.clear(); gridViews.clear(); gridLabels.clear();
        dockRects.clear(); gridRects.clear();
        ddOpen = false; dropdown = null; ddRows.clear();
        loadApps();

        int rows = (int) Math.ceil(grid.size() / 4.0);
        contentH = Math.max(720, GRID_TOP + rows * GRID_PITCH + 40);
        content.setLayoutParams(new FrameLayout.LayoutParams(px(DW), px(contentH)));

        // optional wallpaper: drop assets/wallpaper.jpg in before building (see README); black otherwise
        View wall = null;
        try {
            java.io.InputStream in = getAssets().open("wallpaper.jpg");
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeStream(in);
            in.close();
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(bm);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setColorFilter(0xFF8A8A8A, android.graphics.PorterDuff.Mode.MULTIPLY);
            wall = iv;
        } catch (Exception ignored) { }
        if (wall != null) content.addView(wall, lp(0, 0, DW, 720));

        // ambient glow behind the focused dock app's name
        glow = new GlowView(this);
        content.addView(glow, lp(0, 250, DW, 470));

        // dock band
        int n = dock.size();
        float bandW = n * DOCK_TILE_W + Math.max(0, n - 1) * DOCK_GAP + 2 * DOCK_PAD;
        float bandX = (DW - bandW) / 2f;
        View band = new View(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x24FFFFFF);
        bg.setCornerRadius(px(36));
        band.setBackground(bg);
        if (n > 0) content.addView(band, lp(bandX, BAND_Y, bandW, BAND_H));

        // critter theme: stars, grass and little creatures walking along the top of the dock
        critters = null;
        if (getResources().getBoolean(R.bool.critter_theme)) {
            critters = new CritterView(this, s, BAND_Y, bandX, bandX + bandW, getResources().getBoolean(R.bool.critter_pixel_art));
            critters.setGallery(getIntent().getBooleanExtra("gallery", false));
            critters.setDebug(getIntent().getIntExtra("force_action", 0), getIntent().getBooleanExtra("fast", false));
            content.addView(critters, lp(0, 0, DW, 720));
            if (hasWindowFocus() || built) critters.start();
        }

        // top bar pill backgrounds (frosted look: a translucent fill)
        String[] names = {"Inputs", "Wi-Fi", "Settings"};
        for (int i = 0; i < 3; i++) {
            View pb = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setColor(0x2EFFFFFF);
            g.setCornerRadius(px(PILL_H / 2));
            pb.setBackground(g);
            pillBgs.add(pb);
            content.addView(pb, lp(0, 0, 10, PILL_H));
            pillRects.add(new RectF());
        }

        // the one glass highlight
        glass = new GlassView(this);
        content.addView(glass, new FrameLayout.LayoutParams(-1, -1));

        greeting = text(22, 300, 0xB3FFFFFF);
        clock = text(112, 160, 0xFFFFFFFF);
        date = text(24, 300, 0xB3FFFFFF);
        content.addView(greeting, lp(MARGIN, 40, 700, 30));
        content.addView(clock, lp(MARGIN - 4, 68, 700, 128));
        content.addView(date, lp(MARGIN, 204, 700, 32));

        for (int i = 0; i < 3; i++) {
            TextView t = text(20, 400, 0xFFFFFFFF);
            t.setText(i == 1 ? (wifiName == null ? names[1] : names[1] + "  ·  " + wifiName) : names[i]);
            t.setGravity(Gravity.CENTER);
            pillTexts.add(t);
            content.addView(t, lp(0, 0, 10, PILL_H));
        }
        relayoutPills();

        bigName = text(60, 240, 0xFFFFFFFF);
        content.addView(bigName, lp(bandX + 8, 346, 900, 76));
        bigName.setAlpha(0);

        // dock tiles
        for (int i = 0; i < n; i++) {
            float x = bandX + DOCK_PAD + i * (DOCK_TILE_W + DOCK_GAP);
            float y = BAND_Y + (BAND_H - DOCK_TILE_H) / 2f;
            ImageView v = tile(dock.get(i), DOCK_TILE_W, DOCK_TILE_H, 16);
            content.addView(v, lp(x, y, DOCK_TILE_W, DOCK_TILE_H));
            dockViews.add(v);
            dockRects.add(new RectF(px(x), px(y), px(x + DOCK_TILE_W), px(y + DOCK_TILE_H)));
        }

        // grid tiles, 4 columns, names appear under the focused one
        float gx0 = (DW - (4 * GRID_W + 3 * GRID_GX)) / 2f;
        for (int i = 0; i < grid.size(); i++) {
            float x = gx0 + (i % 4) * (GRID_W + GRID_GX);
            float y = GRID_TOP + (i / 4) * GRID_PITCH;
            ImageView v = tile(grid.get(i), GRID_W, GRID_H, 14);
            content.addView(v, lp(x, y, GRID_W, GRID_H));
            gridViews.add(v);
            gridRects.add(new RectF(px(x), px(y), px(x + GRID_W), px(y + GRID_H)));
            TextView lab = text(20, 400, 0xFFFFFFFF);
            lab.setText(grid.get(i).label);
            lab.setGravity(Gravity.CENTER);
            lab.setAlpha(0);
            content.addView(lab, lp(x - 10, y + GRID_H + 14, GRID_W + 20, 28));
            gridLabels.add(lab);
        }
        built = true;
    }

    ImageView tile(App a, float w, float h, float radius) {
        ImageView v = new ImageView(this);
        v.setBackgroundColor(0xFF1C1C1E);
        v.setImageDrawable(a.banner);
        v.setScaleType(a.isIcon ? ImageView.ScaleType.CENTER_INSIDE : ImageView.ScaleType.FIT_XY);
        if (a.isIcon) v.setPadding(px(h * 0.15f), px(h * 0.15f), px(h * 0.15f), px(h * 0.15f));
        final float r = radius * s;
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
        return v;
    }

    void relayoutPills() {
        if (pillTexts.size() < 3) return;
        float[] w = new float[3];
        float total = 0;
        for (int i = 0; i < 3; i++) {
            TextView t = pillTexts.get(i);
            w[i] = t.getPaint().measureText(t.getText().toString()) / s + 2 * PILL_PAD;
            total += w[i];
        }
        total += 2 * PILL_GAP;
        float x = DW - MARGIN - total;
        float y = 40;
        for (int i = 0; i < 3; i++) {
            pillBgs.get(i).setLayoutParams(lp(x, y, w[i], PILL_H));
            pillTexts.get(i).setLayoutParams(lp(x, y, w[i], PILL_H));
            pillRects.get(i).set(px(x), px(y), px(x + w[i]), px(y + PILL_H));
            x += w[i] + PILL_GAP;
        }
    }

    // ---------------------------------------------------------------- data

    void loadApps() {
        dock.clear();
        grid.clear();
        PackageManager pm = getPackageManager();
        Map<String, App> all = new LinkedHashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            boolean leanback = cat.equals(Intent.CATEGORY_LEANBACK_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0);
            for (ResolveInfo ri : ris) {
                String p = ri.activityInfo.packageName;
                if (p.equals(getPackageName()) || all.containsKey(p)) continue;
                // regular (phone) launcher entries only count for dock apps and the listed extras; the grid is TV apps
                if (!leanback && !isDockPackage(p) && !isExtraPackage(p)) continue;
                App a = new App();
                a.pkg = p;
                a.cn = new ComponentName(p, ri.activityInfo.name);
                a.label = String.valueOf(ri.loadLabel(pm));
                Drawable d = null;
                try { d = ri.activityInfo.loadBanner(pm); } catch (Exception ignored) { }
                if (d == null) {
                    try { d = ri.activityInfo.applicationInfo.loadBanner(pm); } catch (Exception ignored) { }
                }
                if (d == null) {
                    d = ri.loadIcon(pm);
                    a.isIcon = true;
                }
                a.banner = d;
                all.put(p, a);
            }
        }
        String[] dockPk = getResources().getStringArray(R.array.dock_packages);
        for (String p : dockPk) {
            App a = all.remove(p);
            if (a != null) dock.add(a);
        }
        final Collator col = Collator.getInstance();
        List<App> rest = new ArrayList<>(all.values());
        Collections.sort(rest, (x, y) -> col.compare(x.label, y.label));
        grid.addAll(rest);
    }

    boolean isExtraPackage(String p) {
        for (String d : getResources().getStringArray(R.array.extra_packages)) if (d.equals(p)) return true;
        return false;
    }

    boolean isDockPackage(String p) {
        for (String d : getResources().getStringArray(R.array.dock_packages)) if (d.equals(p)) return true;
        return false;
    }

    List<InputItem> loadInputs() {
        List<InputItem> out = new ArrayList<>();
        InputItem tuner = null;
        try {
            TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            for (TvInputInfo ti : tim.getTvInputList()) {
                if (ti.getType() == TvInputInfo.TYPE_TUNER) {
                    if (tuner == null) {
                        tuner = new InputItem();
                        tuner.label = "Live TV";
                        tuner.tuner = true;
                        tuner.id = ti.getId();
                    }
                } else if (ti.isPassthroughInput()) {
                    InputItem it = new InputItem();
                    it.id = ti.getId();
                    CharSequence c = ti.loadCustomLabel(this);
                    if (c == null || c.length() == 0) c = ti.loadLabel(this);
                    it.label = String.valueOf(c);
                    out.add(it);
                }
            }
        } catch (Exception ignored) { }
        Collections.sort(out, (x, y) -> x.label.compareToIgnoreCase(y.label));
        if (tuner != null) out.add(tuner);
        return out;
    }

    // ---------------------------------------------------------------- clock, wifi

    void refreshTime() {
        if (clock == null) return;
        Calendar c = Calendar.getInstance();
        int h = c.get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening");
        clock.setText(android.text.format.DateFormat.format("h:mm", c));
        date.setText(android.text.format.DateFormat.format("EEEE, d MMMM", c));
    }

    void refreshWifi() {
        String n = null;
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm.getConnectionInfo();
            String ssid = wi == null ? null : wi.getSSID();
            if (ssid != null && !ssid.contains("unknown ssid") && !ssid.equals("0x")) n = ssid.replace("\"", "");
        } catch (Exception ignored) { }
        wifiName = n;
        if (pillTexts.size() < 3) return;
        pillTexts.get(1).setText(n == null ? "Wi-Fi" : "Wi-Fi  ·  " + n);
        relayoutPills();
        if (zone == TOP) moveGlass(rectOf(zone, idx), false);
    }

    // ---------------------------------------------------------------- selection and visuals

    void clampSelection() {
        if (zone == GRID && grid.isEmpty()) zone = DOCK;
        if (zone == DOCK && dock.isEmpty()) zone = grid.isEmpty() ? TOP : GRID;
        int max = zone == TOP ? 2 : zone == DOCK ? dock.size() - 1 : grid.size() - 1;
        idx = Math.max(0, Math.min(idx, Math.max(0, max)));
        lastDock = Math.max(0, Math.min(lastDock, Math.max(0, dock.size() - 1)));
    }

    RectF rectOf(int z, int i) {
        RectF r = z == TOP ? pillRects.get(i) : z == DOCK ? dockRects.get(i) : gridRects.get(i);
        float pad = px(z == TOP ? 6 : HL_PAD);
        return new RectF(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad);
    }

    View viewOf(int z, int i) {
        return z == TOP ? pillTexts.get(i) : z == DOCK ? dockViews.get(i) : gridViews.get(i);
    }

    void select(int z, int i, boolean animate) {
        if (!built) return;
        zone = z;
        idx = i;
        if (z == DOCK) lastDock = i;
        prefs.edit().putInt("zone", z == TOP ? DOCK : z).putInt("idx", z == TOP ? lastDock : i).putInt("lastDock", lastDock).apply();

        long dur = animate ? 180 : 0;
        for (int j = 0; j < dockViews.size(); j++) scaleTo(dockViews.get(j), zone == DOCK && j == idx ? 1.07f : 1f, dur);
        for (int j = 0; j < gridViews.size(); j++) {
            boolean f = zone == GRID && j == idx;
            scaleTo(gridViews.get(j), f ? 1.07f : 1f, dur);
            gridLabels.get(j).animate().alpha(f ? 1f : 0f).setDuration(dur).start();
        }

        // big name and ambient glow follow the focused dock app
        if (zone == DOCK && idx < dock.size()) {
            App a = dock.get(idx);
            bigName.setText(a.label);
            if (a.glow == 0) a.glow = sampleColor(a.banner);
            bigName.animate().alpha(1f).setDuration(dur).start();
            glow.show(a.glow, animate);
        } else {
            bigName.animate().alpha(0f).setDuration(dur).start();
            glow.hide(animate);
        }

        moveGlass(rectOf(zone, idx), animate);
        if (critters != null) critters.setActive(zone != GRID);

        float scroll = 0;
        if (zone == GRID) {
            scroll = GRID_TOP + (idx / 4) * GRID_PITCH - 130;
            scroll = Math.max(0, Math.min(scroll, contentH - 720));
        }
        content.animate().translationY(-scroll * s).setDuration(animate ? 320 : 0)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    void scaleTo(View v, float t, long dur) {
        if (Math.abs(v.getScaleX() - t) < 0.001f) return;
        v.animate().scaleX(t).scaleY(t).setDuration(dur).start();
    }

    void moveGlass(RectF to, boolean animate) {
        if (glassAnim != null) glassAnim.cancel();
        final RectF from = new RectF(glass.r);
        final RectF dst = new RectF(to);
        final float rad = (zone == TOP ? PILL_H / 2f + 6 : 24) * s;
        if (!animate || from.isEmpty()) {
            glass.set(dst, rad);
            return;
        }
        glassAnim = ValueAnimator.ofFloat(0f, 1f);
        glassAnim.setDuration(380);
        glassAnim.setInterpolator(new OvershootInterpolator(1.5f));   // the spring
        glassAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            glass.set(new RectF(lerp(from.left, dst.left, f), lerp(from.top, dst.top, f),
                    lerp(from.right, dst.right, f), lerp(from.bottom, dst.bottom, f)), rad);
        });
        glassAnim.start();
    }

    static float lerp(float a, float b, float f) { return a + (b - a) * f; }

    int sampleColor(Drawable d) {
        try {
            Drawable c = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
            Bitmap bm = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bm);
            cv.drawColor(0xFF1C1C1E);
            c.setBounds(0, 0, 32, 18);
            c.draw(cv);
            float[] hsv = new float[3];
            double sr = 0, sg = 0, sb = 0, sw = 0;
            for (int y = 0; y < 18; y++) {
                for (int x = 0; x < 32; x++) {
                    int p = bm.getPixel(x, y);
                    Color.colorToHSV(p, hsv);
                    double w = hsv[1] * hsv[2] * hsv[2];   // favour vivid, bright pixels
                    sr += Color.red(p) * w; sg += Color.green(p) * w; sb += Color.blue(p) * w; sw += w;
                }
            }
            bm.recycle();
            if (sw < 0.5) return 0xFF5B6CFF;
            int col = Color.rgb((int) (sr / sw), (int) (sg / sw), (int) (sb / sw));
            Color.colorToHSV(col, hsv);
            hsv[1] = Math.max(hsv[1], 0.55f);
            hsv[2] = Math.max(hsv[2], 0.9f);
            return Color.HSVToColor(hsv);
        } catch (Exception e) {
            return 0xFF5B6CFF;
        }
    }

    // ---------------------------------------------------------------- dropdown

    void openDropdown() {
        inputs = loadInputs();
        closeDropdown();
        ddOpen = true;
        ddIdx = 0;
        RectF pr = pillRects.get(0);
        float rowH = 52, pad = 10;
        float w = Math.max(240, pr.width() / s);
        int rows = Math.max(1, inputs.size());
        float h = rows * rowH + 2 * pad;
        dropdown = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xE62A2A2E);
        g.setStroke(Math.max(1, px(1)), 0x33FFFFFF);
        g.setCornerRadius(px(22));
        dropdown.setBackground(g);
        content.addView(dropdown, lp(pr.left / s, pr.bottom / s + 12, w, h));
        ddRows.clear();
        for (int i = 0; i < rows; i++) {
            TextView t = text(21, 400, 0xFFFFFFFF);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(px(22), 0, px(22), 0);
            t.setText(inputs.isEmpty() ? "No inputs found" : inputs.get(i).label);
            dropdown.addView(t, lp(pad, pad + i * rowH, w - 2 * pad, rowH));
            ddRows.add(t);
        }
        glass.setVisibility(View.INVISIBLE);
        paintDropdown();
    }

    void paintDropdown() {
        for (int i = 0; i < ddRows.size(); i++) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(i == ddIdx ? 0x40FFFFFF : 0x00000000);
            g.setCornerRadius(px(14));
            ddRows.get(i).setBackground(g);
        }
    }

    void closeDropdown() {
        if (dropdown != null) {
            content.removeView(dropdown);
            dropdown = null;
        }
        ddOpen = false;
        if (glass != null) glass.setVisibility(View.VISIBLE);
    }

    void switchInput(InputItem it) {
        try {
            Intent i = it.tuner
                    ? new Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI)
                    : new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(it.id));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent ev) {
        if (!built) return super.onKeyDown(keyCode, ev);
        if (ddOpen) return dropdownKey(keyCode);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: move(0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(0, 1); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: move(-1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (ev.getRepeatCount() == 0) activate();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (zone != DOCK && !dock.isEmpty()) select(DOCK, Math.min(lastDock, dock.size() - 1), true);
                return true;   // a launcher never exits
            default:
                return super.onKeyDown(keyCode, ev);
        }
    }

    boolean dropdownKey(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: ddIdx = Math.max(0, ddIdx - 1); paintDropdown(); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: ddIdx = Math.min(ddRows.size() - 1, ddIdx + 1); paintDropdown(); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (!inputs.isEmpty()) {
                    InputItem it = inputs.get(ddIdx);
                    closeDropdown();
                    switchInput(it);
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                closeDropdown();
                return true;
            default:
                return true;
        }
    }

    int nearest(List<RectF> rs, float cx) {
        int best = 0;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < rs.size(); i++) {
            float d = Math.abs(rs.get(i).centerX() - cx);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    void move(int dx, int dy) {
        int z = zone, i = idx;
        if (zone == TOP) {
            if (dx != 0) i = Math.max(0, Math.min(2, idx + dx));          // ends stay put
            else if (dy > 0 && !dock.isEmpty()) { z = DOCK; i = nearest(dockRects, pillRects.get(idx).centerX()); }
            else if (dy > 0 && !grid.isEmpty()) { z = GRID; i = Math.min(3, grid.size() - 1); }
        } else if (zone == DOCK) {
            if (dx != 0) i = Math.max(0, Math.min(dock.size() - 1, idx + dx));
            else if (dy < 0) { z = TOP; i = nearest(pillRects, dockRects.get(idx).centerX()); }
            else if (dy > 0 && !grid.isEmpty()) { z = GRID; i = Math.min(idx, grid.size() - 1); }
        } else {
            int row = idx / 4, col = idx % 4, last = (grid.size() - 1) / 4;
            if (dx < 0) { if (col > 0) i = idx - 1; }
            else if (dx > 0) { if (col < 3 && idx + 1 < grid.size()) i = idx + 1; }
            else if (dy < 0) {
                if (row == 0) { if (!dock.isEmpty()) { z = DOCK; i = Math.min(col, dock.size() - 1); } else { z = TOP; i = 0; } }
                else i = idx - 4;
            } else if (dy > 0) {
                if (row < last) i = Math.min(idx + 4, grid.size() - 1);    // exactly one row; short last row clamps
            }
        }
        if (z != zone || i != idx) select(z, i, true);
    }

    void activate() {
        if (zone == TOP) {
            if (idx == 0) openDropdown();
            else startSettings(idx == 1 ? Settings.ACTION_WIFI_SETTINGS : Settings.ACTION_SETTINGS);
            return;
        }
        App a = zone == DOCK ? dock.get(idx) : grid.get(idx);
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setComponent(a.cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(a.pkg);
                if (i != null) startActivity(i);
            } catch (Exception ignored) { }
        }
    }

    void startSettings(String action) {
        try {
            startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception ignored) { }
        }
    }

    // ---------------------------------------------------------------- custom views

    /** The one translucent highlight that glides between tiles. */
    static class GlassView extends View {
        final RectF r = new RectF();
        float rad;
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

        GlassView(Context c) {
            super(c);
            fill.setColor(0x38FFFFFF);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(1f, c.getResources().getDisplayMetrics().widthPixels / 1280f * 1.5f));
            stroke.setColor(0x66FFFFFF);
        }

        void set(RectF to, float radius) {
            r.set(to);
            rad = radius;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            if (r.isEmpty()) return;
            c.drawRoundRect(r, rad, rad, fill);
            c.drawRoundRect(r, rad, rad, stroke);
        }
    }

    /** Soft coloured ambient light behind the dock app name. */
    static class GlowView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int color = 0;
        ValueAnimator anim;

        GlowView(Context c) { super(c); setAlpha(0); }

        void apply(int col) {
            color = col;
            float h = getHeight();
            if (h == 0) return;
            float rad = h * 0.48f;   // fades to nothing inside the view, so no hard edge
            p.setShader(new RadialGradient(0, 0, rad,
                    (col & 0xFFFFFF) | 0x70000000, col & 0xFFFFFF, Shader.TileMode.CLAMP));
            invalidate();
        }

        void show(int col, boolean animate) {
            if (anim != null) anim.cancel();
            if (!animate || color == 0 || getAlpha() < 0.05f) {
                apply(col);
                animate().alpha(1f).setDuration(animate ? 300 : 0).start();
                return;
            }
            final int from = color;
            anim = ValueAnimator.ofObject(new ArgbEvaluator(), from, col);
            anim.setDuration(350);
            anim.addUpdateListener(a -> apply((int) a.getAnimatedValue()));
            anim.start();
            animate().alpha(1f).setDuration(300).start();
        }

        void hide(boolean animate) {
            if (anim != null) anim.cancel();
            animate().alpha(0f).setDuration(animate ? 300 : 0).start();
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            if (color != 0) apply(color);
        }

        @Override protected void onDraw(Canvas c) {
            if (p.getShader() == null) return;
            float h = getHeight();
            c.save();
            c.translate(getWidth() * 0.34f, h * 0.5f);
            c.scale(1.7f, 1f);   // wide ellipse
            c.drawCircle(0, 0, h * 0.48f, p);
            c.restore();
        }
    }
}
