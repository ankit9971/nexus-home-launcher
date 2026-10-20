package app.nexus.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * The launcher's critter theme: twinkling stars, a tuft-grass shelf along the top of the dock, and twelve little original
 * creatures that wander along the shelf one at a time: walk on, do one or two things (nap, hop, wave, look around, spin,
 * chase a ball, sit), walk off, a different one next time.
 * Everything is drawn in code in a 1280x720 design space (no images). It only animates while shown and started, and the
 * frame rate drops to a slow twinkle while nobody is on the shelf. With {@code pixel} on, each creature is drawn small
 * without smoothing and scaled up, for a chunky pixel-art look.
 */
public class CritterView extends View {

    static final int WAIT = 0, IN = 1, ACT = 2, OUT = 3;
    static final int A_IDLE = 0, A_SLEEP = 1, A_HOP = 2, A_WAVE = 3, A_LOOK = 4, A_SPIN = 5, A_BALL = 6, A_SIT = 7;
    static final int SPECIES = 12;
    static final float SPEED = 66f;   // design px per second
    static final float K = 1.3f;      // creature size in smooth mode

    final float s, shelfY, left, right;
    final boolean pixel;
    final Random rnd = new Random();
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint bmpPaint = new Paint();
    final Path path = new Path();
    final RectF rect = new RectF();
    final RectF dst = new RectF();
    final float[] sx = new float[64], sy = new float[64], sr = new float[64], sph = new float[64], ssp = new float[64];
    final float[] gx = new float[110], gh = new float[110];
    final Bitmap[] pool = new Bitmap[SPECIES];   // one small bitmap per slot (the gallery draws twelve at once; normally only slot 0 is used)
    final Canvas[] poolC = new Canvas[SPECIES];
    Bitmap pxBmp;
    Canvas pxCanvas;
    int slot;

    boolean running, active = true, gallery, fast;
    int forceAction;
    long t0 = SystemClock.uptimeMillis(), last, until, actionStart, actionLen;
    int state = WAIT, species, lastSpecies = -1, action, actionsLeft;
    float x, dir = 1, stopAt, ballCx;

    CritterView(Context c, float scale, float shelfY, float left, float right, boolean pixel) {
        super(c);
        this.s = scale;
        this.shelfY = shelfY;
        this.left = left;
        this.right = right;
        this.pixel = pixel;
        bmpPaint.setFilterBitmap(false);
        for (int i = 0; i < sx.length; i++) {
            sx[i] = rnd.nextFloat() * 1280f;
            sy[i] = rnd.nextFloat() * 470f;
            sr[i] = 0.7f + rnd.nextFloat() * 1.5f;
            sph[i] = rnd.nextFloat() * 6.28f;
            ssp[i] = 0.5f + rnd.nextFloat() * 1.6f;
        }
        for (int i = 0; i < gx.length; i++) {
            gx[i] = i * (1280f / gx.length) + rnd.nextFloat() * 6f;
            gh[i] = 4f + rnd.nextFloat() * 8f;
        }
        until = 3500;
        setWillNotDraw(false);
    }

    // ------------------------------------------------------------------ lifecycle

    final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long ms = SystemClock.uptimeMillis() - t0;
            update(ms);
            invalidate();
            postDelayed(this, state == WAIT && !gallery ? 300 : 33);
        }
    };

    void start() {
        if (running) return;
        running = true;
        last = SystemClock.uptimeMillis() - t0;
        post(tick);
    }

    void stop() {
        running = false;
        removeCallbacks(tick);
    }

    /** Off while the app grid is scrolled into view (nothing to see here, so nothing to draw). */
    void setActive(boolean on) {
        if (active == on) return;
        active = on;
        setVisibility(on ? VISIBLE : INVISIBLE);
    }

    /** A test view: all twelve in a row, standing still (shown when the launcher is started with the extra "gallery"). */
    void setGallery(boolean on) { gallery = on; }

    /** Test switches: always do this action (1-7), and shorten the waits between visits. */
    void setDebug(int forceAction, boolean fast) {
        this.forceAction = forceAction;
        this.fast = fast;
        if (fast) until = 1200;
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------------ the little story

    void update(long ms) {
        float dt = Math.min(0.1f, (ms - last) / 1000f);
        last = ms;
        if (gallery) return;
        switch (state) {
            case WAIT:
                if (ms >= until) {
                    do { species = rnd.nextInt(SPECIES); } while (species == lastSpecies);
                    lastSpecies = species;
                    dir = rnd.nextBoolean() ? 1 : -1;
                    x = dir > 0 ? -60 : 1340;
                    stopAt = left + 100 + rnd.nextFloat() * (right - left - 200);
                    actionsLeft = 1 + rnd.nextInt(2);
                    state = IN;
                }
                break;
            case IN:
                x += dir * SPEED * dt;
                if (dir > 0 ? x >= stopAt : x <= stopAt) beginAction(ms);
                break;
            case ACT:
                if (action == A_BALL) {
                    float d = ballX(ms) - x;
                    if (Math.abs(d) > 8) {
                        dir = d > 0 ? 1 : -1;
                        x += dir * SPEED * 1.15f * dt;
                    }
                }
                if (ms - actionStart > actionLen) {
                    if (--actionsLeft > 0) beginAction(ms);
                    else state = OUT;
                }
                break;
            case OUT:
                x += dir * SPEED * dt;
                if (x < -70 || x > 1350) {
                    state = WAIT;
                    until = ms + (fast ? 1500 : 14000 + rnd.nextInt(36000));
                }
                break;
        }
    }

    void beginAction(long ms) {
        state = ACT;
        action = forceAction > 0 ? forceAction : 1 + rnd.nextInt(7);
        actionStart = ms;
        ballCx = Math.max(left + 90, Math.min(right - 90, x));
        switch (action) {
            case A_SLEEP: actionLen = 6500 + rnd.nextInt(3500); break;
            case A_BALL: actionLen = 6000 + rnd.nextInt(3000); break;
            case A_SPIN: actionLen = 2000 + rnd.nextInt(1200); break;
            default: actionLen = 2600 + rnd.nextInt(2200);
        }
    }

    float ballX(long ms) { return ballCx + (float) Math.sin((ms - actionStart) / 1000f * 1.5f) * 75f; }

    // ------------------------------------------------------------------ drawing

    @Override protected void onDraw(Canvas c) {
        long ms = SystemClock.uptimeMillis() - t0;
        float t = ms / 1000f;
        c.save();
        c.scale(s, s);
        stars(c, t);
        grass(c);
        if (gallery) {
            int st = state, sp = species, ac = action;
            float ox = x, od = dir;
            for (int i = 0; i < SPECIES; i++) {
                species = i;
                slot = i;
                x = 70 + i * 100;
                dir = 1;
                state = ACT;
                action = forceAction > 0 && forceAction != A_BALL ? forceAction : A_IDLE;
                actionStart = 0;
                critter(c, t, ms);
            }
            state = st; species = sp; action = ac; x = ox; dir = od; slot = 0;
        } else if (state != WAIT) {
            critter(c, t, ms);
            if (state == ACT && action == A_BALL) ball(c, t, ms);
        }
        c.restore();
    }

    void stars(Canvas c, float t) {
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < sx.length; i++) {
            float a = 0.18f + 0.32f * (0.5f + 0.5f * (float) Math.sin(t * ssp[i] + sph[i]));
            p.setColor(((int) (a * 255) << 24) | 0xE8EEFF);
            c.drawCircle(sx[i], sy[i], sr[i], p);
        }
    }

    void grass(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x6638B56A);
        for (int i = 0; i < gx.length; i++) {
            float x0 = gx[i], h = gh[i];
            path.reset();
            path.moveTo(x0 - 3, shelfY + 2);
            path.quadTo(x0 - 2, shelfY - h * 0.6f, x0 - 5, shelfY - h);
            path.quadTo(x0 + 1, shelfY - h * 0.55f, x0 + 1, shelfY + 2);
            path.close();
            c.drawPath(path, p);
            path.reset();
            path.moveTo(x0 + 1, shelfY + 2);
            path.quadTo(x0 + 3, shelfY - h * 0.7f, x0 + 6, shelfY - h * 1.15f);
            path.quadTo(x0 + 5, shelfY - h * 0.4f, x0 + 5, shelfY + 2);
            path.close();
            c.drawPath(path, p);
        }
        p.setColor(0x4438B56A);
        rect.set(0, shelfY, 1280, shelfY + 3);
        c.drawRect(rect, p);
    }

    /** A plain striped beach ball for the chase game. */
    void ball(Canvas c, float t, long ms) {
        float bx = ballX(ms);
        float by = shelfY - 8 - Math.abs((float) Math.sin((ms - actionStart) / 1000f * 3f)) * 15f;
        oval(c, bx, by, 8, 8, 0xFFFFD95A);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF4FC3E8);
        rect.set(bx - 8, by - 8, bx + 8, by + 8);
        c.drawArc(rect, -30 + (t * 140) % 360, 60, true, p);
        p.setColor(0xFFFF8FA3);
        c.drawArc(rect, 150 + (t * 140) % 360, 60, true, p);
    }

    /** A flat oval (small details, shadows, pixel mode). */
    void flat(Canvas c, float cx, float cy, float rx, float ry, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        p.setColor(color);
        rect.set(cx - rx, cy - ry, cx + rx, cy + ry);
        c.drawOval(rect, p);
    }

    static int mix(int color, int to, float amt) {
        int a = color >>> 24;
        int r = (int) (((color >> 16) & 255) * (1 - amt) + ((to >> 16) & 255) * amt);
        int g = (int) (((color >> 8) & 255) * (1 - amt) + ((to >> 8) & 255) * amt);
        int b = (int) ((color & 255) * (1 - amt) + (to & 255) * amt);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** An oval. In smooth mode the bigger ones get a soft light-to-dark gradient and a thin darker outline (the high-definition look). */
    void oval(Canvas c, float cx, float cy, float rx, float ry, int color) {
        if (pixel || rx < 9 || (color >>> 24) < 0xF0) {
            flat(c, cx, cy, rx, ry, color);
            return;
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF000000);   // a shader is multiplied by the paint's alpha: start every shaded fill opaque
        p.setShader(new RadialGradient(cx - rx * 0.35f, cy - ry * 0.5f, Math.max(rx, ry) * 1.3f,
                new int[]{mix(color, 0xFFFFFFFF, 0.32f), color, mix(color, 0xFF000000, 0.2f)},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        rect.set(cx - rx, cy - ry, cx + rx, cy + ry);
        c.drawOval(rect, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.3f);
        p.setColor((mix(color, 0xFF000000, 0.35f) & 0xFFFFFF) | 0xAA000000);
        c.drawOval(rect, p);
        p.setStyle(Paint.Style.FILL);
    }

    void tri(Canvas c, float x1, float y1, float x2, float y2, float x3, float y3, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        path.reset();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        path.lineTo(x3, y3);
        path.close();
        c.drawPath(path, p);
    }

    void critter(Canvas c, float t, long ms) {
        boolean chasing = state == ACT && action == A_BALL && Math.abs(ballX(ms) - x) > 8;
        boolean walking = state == IN || state == OUT || chasing;
        float phase = t * 11f;
        float hop = 0, squash = 1, spin = 0;
        boolean asleep = state == ACT && action == A_SLEEP;
        boolean sit = state == ACT && action == A_SIT;
        if (state == ACT && action == A_HOP) {
            float k = (ms - actionStart) / 330f;
            float u = Math.abs((float) Math.sin(k * Math.PI));
            hop = -u * 24;
            squash = 1 + (1 - u) * -0.08f;
        }
        if (state == ACT && action == A_SPIN) {
            float k = (ms - actionStart) / 800f;
            spin = (k % 1f) * 360f;
            hop = -Math.abs((float) Math.sin(k * Math.PI)) * 8;
        }
        if (chasing) hop = -Math.abs((float) Math.sin(phase * 0.55f)) * 9;
        if (sit) squash = 0.86f;
        float bob = walking ? (float) Math.abs(Math.sin(phase)) * -2.5f
                : (asleep || sit ? (float) Math.sin(t * 1.6f) * -1.0f : (float) Math.sin(t * 2.4f) * -1f);

        Canvas g;
        if (pixel) {
            if (pool[slot] == null) {
                pool[slot] = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
                poolC[slot] = new Canvas(pool[slot]);
            }
            pxBmp = pool[slot];
            pxCanvas = poolC[slot];
            pxBmp.eraseColor(0);
            g = pxCanvas;
            g.save();
            g.translate(32, 56);
            g.scale(0.5f * dir, 0.5f);
            p.setAntiAlias(false);
        } else {
            g = c;
            // a soft shadow on the grass that shrinks when it hops
            float lift = Math.min(1f, Math.max(0f, -hop / 30f));
            flat(c, x, shelfY + 1.5f, 24 * K * (1 - 0.3f * lift), 4.2f * (1 - 0.3f * lift), 0x40000000);
            g.save();
            g.translate(x, shelfY);
            g.scale(dir * K, K);
        }
        g.translate(0, hop + bob);
        g.scale(1, squash);
        if (spin != 0) g.rotate(spin, 0, -20);

        float look = state == ACT && action == A_LOOK ? (float) Math.sin(t * 2.2f) * 3.4f : 0;
        boolean blink = ((int) (t * 1000) % (sit ? 5200 : 3300)) < 150;
        boolean closed = asleep || blink;
        boolean waving = state == ACT && action == A_WAVE;
        float wave = waving ? (float) Math.sin(t * 9f) : 0;
        float l1 = walking ? Math.max(0, (float) Math.sin(phase)) * 4f : 0;
        float l2 = walking ? Math.max(0, (float) Math.sin(phase + Math.PI)) * 4f : 0;
        if (sit) { l1 = 0; l2 = 0; }

        switch (species) {
            case 0: mochi(g, t, closed, look, waving, wave, l1, l2); break;
            case 1: sprout(g, t, closed, look, waving, wave, l1, l2); break;
            case 2: ember(g, t, closed, look, waving, wave, l1, l2); break;
            case 3: drift(g, t, closed, look, waving, wave); break;
            case 4: blip(g, t, closed, look, waving, wave, l1, l2); break;
            case 5: moss(g, t, closed, look, waving, wave, l1, l2); break;
            case 6: zing(g, t, closed, look, waving, wave, l1, l2); break;
            case 7: plum(g, t, closed, look, waving, wave, l1, l2); break;
            case 8: pounce(g, t, closed, look, waving, wave, l1, l2); break;
            case 9: bloom(g, t, closed, look, waving, wave, l1, l2); break;
            case 10: frost(g, t, closed, look, waving, wave, l1, l2); break;
            default: twirl(g, t, closed, look, waving, wave); break;
        }
        g.restore();
        if (pixel) {
            p.setAntiAlias(true);
            dst.set(x - 64, shelfY - 112, x + 64, shelfY + 16);
            c.drawBitmap(pxBmp, null, dst, bmpPaint);
        }
        if (asleep) zzz(c, t);
    }

    void zzz(Canvas c, float t) {
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 3; i++) {
            float u = ((t * 0.5f) + i / 3f) % 1f;
            p.setColor(((int) ((1 - u) * 200) << 24) | 0xDDE6FF);
            p.setTextSize(11 + u * 9);
            p.setFakeBoldText(true);
            c.drawText("z", x + dir * ((pixel ? 16 : 16 * K) + u * 14), shelfY - (pixel ? 46 : 46 * K) - u * 30, p);
        }
        p.setFakeBoldText(false);
    }

    void eyes(Canvas c, float lx, float rx, float y, boolean closed, float look) {
        if (closed) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(0xFF2B2140);
            for (float ex : new float[]{lx, rx}) {
                rect.set(ex - 3.6f, y - 3, ex + 3.6f, y + 3);
                c.drawArc(rect, 20, 140, false, p);
            }
            p.setStyle(Paint.Style.FILL);
        } else {
            for (float ex : new float[]{lx, rx}) {
                oval(c, ex + look, y, 3.2f, 4.4f, 0xFF2B2140);
                oval(c, ex + look + 1f, y - 1.6f, 1.2f, 1.2f, 0xFFFFFFFF);
                oval(c, ex + look - 1f, y + 1.9f, 0.7f, 0.7f, 0xCCFFFFFF);
            }
        }
    }

    void mouth(Canvas c, float cx, float y) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.6f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(0xFF2B2140);
        rect.set(cx - 3, y - 2, cx + 3, y + 3);
        c.drawArc(rect, 20, 140, false, p);
        p.setStyle(Paint.Style.FILL);
    }

    void cheeks(Canvas c, float dx, float y, int color) {
        oval(c, -dx, y, 3.4f, 2.5f, color);
        oval(c, dx, y, 3.4f, 2.5f, color);
    }

    /** A little arm that waves (drawn when the critter waves, otherwise tucked in). */
    void arm(Canvas c, float cx, float cy, int color, boolean waving, float wave) {
        c.save();
        c.translate(cx, cy);
        c.rotate(waving ? -50 + wave * 28 : -10);
        oval(c, 0, -5, 3.6f, 7f, color);
        c.restore();
    }

    // --- 0. the dumpling: round, pink, two soft ears
    void mochi(Canvas c, float t, boolean closed, float look, boolean waving, float wave, float l1, float l2) {
        oval(c, -9, -2 - l1, 6.5f, 3.6f, 0xFFF59AB8);
        oval(c, 9, -2 - l2, 6.5f, 3.6f, 0xFFF59AB8);
        c.save(); c.rotate(-16, -12, -36); oval(c, -12, -38, 6f, 10f, 0xFFFFC6DA); oval(c, -12, -37, 3f, 6f, 0xFFFF93B7); c.restore();
        c.save(); c.rotate(16, 12, -36); oval(c, 12, -38, 6f, 10f, 0xFFFFC6DA); oval(c, 12, -37, 3f, 6f, 0xFFFF93B7); c.restore();
        oval(c, 0, -19, 22f, 18.5f, 0xFFFFC6DA);
        oval(c, 0, -14, 12f, 9f, 0xFFFFE6F0);
        arm(c, 17, -18, 0xFFFFC6DA, waving, wave);
        eyes(c, -8, 8, -22, closed, look);
        cheeks(c, 15, -15, 0x88FF7FA8);
        mouth(c, 0, -17);
    }

    // --- 1. the sprout: a green bean with a two-leaf tuft that sways
    void sprout(Canvas c, float t, boolean closed, float look, boolean waving, float wave, float l1, float l2) {
        oval(c, -8, -2 - l1, 6f, 3.4f, 0xFF5BC878);
        oval(c, 8, -2 - l2, 6f, 3.4f, 0xFF5BC878);
        oval(c, 0, -21, 19f, 20f, 0xFF92E6A7);
        oval(c, 0, -16, 11f, 10f, 0xFFD4F7DC);
        float sway = (float) Math.sin(t * 2.6f) * 3f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF3DBE63);
        path.reset();
        path.moveTo(0, -39);
        path.quadTo(-6 + sway, -52, -17 + sway, -50);
        path.quadTo(-13 + sway, -40, 0, -39);
        c.drawPath(path, p);
        path.reset();
        path.moveTo(0, -39);
        path.quadTo(6 + sway, -54, 16 + sway, -53);
        path.quadTo(12 + sway, -42, 0, -39);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        p.setColor(0xFF2FA354);
        c.drawLine(0, -38, sway * 0.5f, -45, p);
        p.setStyle(Paint.Style.FILL);
        arm(c, 15, -19, 0xFF92E6A7, waving, wave);
        eyes(c, -7, 7, -24, closed, look);
        cheeks(c, 13, -17, 0x88FF8FA8);
        mouth(c, 0, -18);
    }

    // --- 2. the kit: an orange fox kit with a flame-tipped tail
    void ember(Canvas c, float t, boolean closed, float look, boolean waving, float wave, float l1, float l2) {
        float wag = (float) Math.sin(t * 5f) * 6f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFF9B54);
        path.reset();
        path.moveTo(-14, -14);
        path.quadTo(-32, -10 + wag, -34, -28 + wag);
        path.quadTo(-22, -22 + wag * 0.4f, -12, -24);
        path.close();
        c.drawPath(path, p);
        oval(c, -36, -32 + wag, 6f, 9f, 0xFFFFD34D);
        oval(c, -36, -30 + wag, 3f, 5f, 0xFFFF6B3D);
        oval(c, -8, -2 - l1, 6f, 3.4f, 0xFFE5803D);
        oval(c, 9, -2 - l2, 6f, 3.4f, 0xFFE5803D);
        tri(c, -15, -30, -9, -47, -2, -32, 0xFFFF9B54);
        tri(c, 15, -30, 9, -47, 2, -32, 0xFFFF9B54);
        tri(c, -12, -31, -9, -41, -5, -32, 0xFFFFD3A8);
        tri(c, 12, -31, 9, -41, 5, -32, 0xFFFFD3A8);
        oval(c, 0, -18, 20f, 16.5f, 0xFFFF9B54);
        oval(c, 3, -13, 11f, 8f, 0xFFFFE0B8);
        arm(c, 16, -17, 0xFFFF9B54, waving, wave);
        eyes(c, -7, 9, -22, closed, look);
        oval(c, 15, -17, 2.4f, 1.8f, 0xFF2B2140);
    }

    // --- 3. the jelly: floats a little above the shelf, trailing soft tentacles
    void drift(Canvas c, float t, boolean closed, float look, boolean waving, float wave) {
        float fl = -12 + (float) Math.sin(t * 2f) * 3f;
        c.save();
        c.translate(0, fl);
        for (int i = -1; i <= 1; i++) {
            float sw = (float) Math.sin(t * 4f + i) * 3f;
            oval(c, i * 12 + sw, -17, 4.8f, 10f, 0xFF8AD9E6);
        }
        oval(c, 0, -32, 23f, 18f, 0xFFA5EBF2);
        oval(c, -7, -39, 8f, 4.5f, 0x66FFFFFF);
        arm(c, 20, -30, 0xFFA5EBF2, waving, wave);
        eyes(c, -7, 7, -34, closed, look);
        cheeks(c, 13, -28, 0x88FF9DB5);
        mouth(c, 0, -29);
        c.restore();
        p.setColor(0x88FFFFFF);
        c.drawCircle(-26, -44 + fl, 1.8f, p);
        c.drawCircle(27, -22 + fl, 1.4f, p);
    }

    // --- 4. the droplet: a sky-blue drop with a little head fin and a curled tail
    void blip(Canvas c, float t, boolean closed, float look, boolean waving, float wave, float l1, float l2) {
        oval(c, -8, -2 - l1, 6.5f, 3.2f, 0xFF3FA6E8);
        oval(c, 9, -2 - l2, 6.5f, 3.2f, 0xFF3FA6E8);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(0xFF3FA6E8);
        rect.set(-34, -24 + (float) Math.sin(t * 4f) * 2f, -18, -8);
        c.drawArc(rect, 40, 260, false, p);
        p.setStyle(Paint.Style.FILL);
        tri(c, -4, -35, 0, -52, 6, -35, 0xFF3FA6E8);
        oval(c, 0, -19, 19.5f, 18f, 0xFF6EC8FF);
        oval(c, 0, -14, 11f, 10f, 0xFFD8F1FF);
        oval(c, -8, -28, 5f, 3f, 0x66FFFFFF);
        arm(c, 16, -18, 0xFF6EC8FF, waving, wave);
        eyes(c, -7, 8, -22, closed, look);
        cheeks(c, 14, -16, 0x88FF8FB5);
        mouth(c, 0, -17);
    }

    // --- 5. the pebble: a grey stone with a patch of moss and a tiny flower
    void moss(Canvas c, float t, boolean closed, float look, boolean waving, float wave, float l1, float l2) {
        oval(c, -9, -2 - l1, 6f, 3.2f, 0xFF7C8598);
        oval(c, 9, -2 - l2, 6f, 3.2f, 0xFF7C8598);
        oval(c, -15, -31, 5.5f, 4.5f, 0xFFAEB6C4);
        oval(c, 15, -31, 5.5f, 4.5f, 0xFFAEB6C4);
        oval(c, 0, -17, 22f, 17f, 0xFFAEB6C4);
        oval(c, 7, -11, 11f, 7f, 0xFF98A1B2);
        oval(c, -3, -30, 15f, 6.5f, 0xFF6BC46B);
        oval(c, -9, -33, 6f, 3.5f, 0xFF8ADB8A);
        oval(c, 8, -34, 2.8f, 2.8f, 0xFFFFE066);
        oval(c, 8, -34, 1.1f, 1.1f, 0xFFFF9B54);
        arm(c, 18, -16, 0xFFAEB6C4, waving, wave);
        eyes(c, -8, 8, -20, closed, look);
        mouth(c, 0, -14);
    }

    // --- 6. the firefly: a teal bug with a glowing tail, feelers and humming wings
    void zing(Canvas c, float t, boolean closed, float look, boolean waving, float wave, float l1, float l2) {
        float glow = 0.55f + 0.45f * (float) Math.sin(t * 3.2f);
        oval(c, -19, -15, 15 + glow * 4, 12 + glow * 4, ((int) (glow * 70) << 24) | 0xFFE66B);
        oval(c, -18, -14, 9f, 7.5f, 0xFFFFE66B);
        oval(c, -7, -2 - l1, 5f, 3f, 0xFF3FA79A);
        oval(c, 8, -2 - l2, 5f, 3f, 0xFF3FA79A);
        float flap = (float) Math.sin(t * 34f) * 14f;
        c.save(); c.rotate(-24 + flap, -4, -30); oval(c, -10, -40, 5f, 11f, 0xAAFFFFFF); c.restore();
        c.save(); c.rotate(24 - flap, 4, -30); oval(c, 10, -40, 5f, 11f, 0xAAFFFFFF); c.restore();
        oval(c, 0, -17, 17f, 14.5f, 0xFF7BE0C4);
        oval(c, 3, -13, 9f, 7f, 0xFFC9F7EA);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.8f);
        p.setColor(0xFF3FA79A);
        c.drawLine(-4, -30, -9, -42, p);
        c.drawLine(5, -30, 10, -42, p);
        p.setStyle(Paint.Style.FILL);
        oval(c, -9, -43, 2.4f, 2.4f, 0xFFFFE66B);
        oval(c, 10, -43, 2.4f, 2.4f, 0xFFFFE66B);
        arm(c, 15, -16, 0xFF7BE0C4, waving, wave);
        eyes(c, -6, 8, -20, closed, look);
        mouth(c, 1, -14);
    }

    // --- 7. the owlet: a plum-purple owlet with ear tufts and a chevron belly
    void plum(Canvas c, float t, boolean closed, float look, boolean waving, float wave, float l1, float l2) {
        oval(c, -7, -2 - l1, 6f, 3f, 0xFFFFA94D);
        oval(c, 7, -2 - l2, 6f, 3f, 0xFFFFA94D);
        tri(c, -17, -33, -15, -49, -6, -37, 0xFF8F63D9);
        tri(c, 17, -33, 15, -49, 6, -37, 0xFF8F63D9);
        oval(c, 0, -20, 19f, 20f, 0xFFB58CF0);
        oval(c, 0, -14, 12f, 12f, 0xFFE9D8FF);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.6f);
        p.setColor(0xFFB58CF0);
        for (int i = 0; i < 3; i++) {
            rect.set(-5, -19 + i * 5, 5, -13 + i * 5);
            c.drawArc(rect, 20, 140, false, p);
        }
        p.setStyle(Paint.Style.FILL);
        c.save(); c.rotate(-18, -19, -18); oval(c, -19, -17, 5.5f, 11f, 0xFF9C70E3); c.restore();
        c.save(); c.rotate(waving ? -60 + wave * 25 : 18, 19, -18); oval(c, 19, -17, 5.5f, 11f, 0xFF9C70E3); c.restore();
        oval(c, -7.5f, -26, 6.5f, 6.5f, 0xFFFFFFFF);
        oval(c, 7.5f, -26, 6.5f, 6.5f, 0xFFFFFFFF);
        if (closed) {
            eyes(c, -7.5f, 7.5f, -26, true, 0);
        } else {
            oval(c, -7.5f + look, -26, 3.3f, 3.8f, 0xFF2B2140);
            oval(c, 7.5f + look, -26, 3.3f, 3.8f, 0xFF2B2140);
            oval(c, -6.5f + look, -27.5f, 1.1f, 1.1f, 0xFFFFFFFF);
            oval(c, 8.5f + look, -27.5f, 1.1f, 1.1f, 0xFFFFFFFF);
        }
        tri(c, -2.5f, -20, 2.5f, -20, 0, -15.5f, 0xFFFFA94D);
    }

    // --- 8. the kitten: lavender, with a striped tail that swishes
    void pounce(Canvas c, float t, boolean closed, float look, boolean waving, float wave, float l1, float l2) {
        float sw = (float) Math.sin(t * 3.6f) * 5f;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(6f);
        p.setColor(0xFFC9B8FF);
        path.reset();
        path.moveTo(-14, -12);
        path.quadTo(-34 + sw, -12, -30 + sw, -34);
        c.drawPath(path, p);
        p.setStrokeWidth(6.4f);
        p.setColor(0xFF9D86E6);
        for (int i = 1; i <= 3; i++) {
            float u = 0.22f * i + 0.12f;
            float px = (1 - u) * (1 - u) * -14 + 2 * (1 - u) * u * (-34 + sw) + u * u * (-30 + sw);
            float py = (1 - u) * (1 - u) * -12 + 2 * (1 - u) * u * -12 + u * u * -34;
            c.drawPoint(px, py, p);
        }
        p.setStyle(Paint.Style.FILL);
        oval(c, -8, -2 - l1, 6f, 3.2f, 0xFFA690EE);
        oval(c, 9, -2 - l2, 6f, 3.2f, 0xFFA690EE);
        tri(c, -16, -28, -13, -46, -3, -31, 0xFFC9B8FF);
        tri(c, 16, -28, 13, -46, 3, -31, 0xFFC9B8FF);
        tri(c, -13, -30, -12, -41, -6, -32, 0xFFFFB6D1);
        tri(c, 13, -30, 12, -41, 6, -32, 0xFFFFB6D1);
        oval(c, 0, -17, 19.5f, 15.5f, 0xFFC9B8FF);
        oval(c, 0, -12, 11f, 8f, 0xFFF0EAFF);
        arm(c, 16, -16, 0xFFC9B8FF, waving, wave);
        eyes(c, -7, 8, -21, closed, look);
        tri(c, -1.8f, -17, 1.8f, -17, 0, -14.6f, 0xFFFF8FB0);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.2f);
        p.setColor(0x88FFFFFF);
        c.drawLine(-16, -15, -25, -17, p);
        c.drawLine(-16, -12, -25, -11, p);
        c.drawLine(16, -15, 25, -17, p);
        c.drawLine(16, -12, 25, -11, p);
        p.setStyle(Paint.Style.FILL);
    }

    // --- 9. the bud: a flower with a petal crown and a smiling yellow face
    void bloom(Canvas c, float t, boolean closed, float look, boolean waving, float wave, float l1, float l2) {
        oval(c, -6, -2 - l1, 5f, 3f, 0xFF4FC06B);
        oval(c, 6, -2 - l2, 5f, 3f, 0xFF4FC06B);
        oval(c, 0, -13, 12.5f, 12f, 0xFF7ED68A);
        c.save(); c.rotate(-30, -12, -14); oval(c, -14, -14, 4.5f, 8f, 0xFF4FC06B); c.restore();
        c.save(); c.rotate(waving ? -60 + wave * 28 : 30, 12, -14); oval(c, 14, -14, 4.5f, 8f, 0xFF4FC06B); c.restore();
        float sway = (float) Math.sin(t * 2f) * 4f;
        c.save();
        c.rotate(sway, 0, -25);
        for (int i = 0; i < 6; i++) {
            c.save();
            c.rotate(i * 60, 0, -36);
            oval(c, 0, -47, 6.5f, 9.5f, i % 2 == 0 ? 0xFFFF9FC8 : 0xFFFFB8D8);
            c.restore();
        }
        oval(c, 0, -36, 11.5f, 11.5f, 0xFFFFD76A);
        eyes(c, -4.5f, 4.5f, -37, closed, look * 0.6f);
        cheeks(c, 8, -32, 0x88FF8FA8);
        mouth(c, 0, -34);
        c.restore();
    }

    // --- 10. the waddler: an ice-blue penguin-like critter in a pink scarf
    void frost(Canvas c, float t, boolean closed, float look, boolean waving, float wave, float l1, float l2) {
        oval(c, -7, -2 - l1, 7f, 3f, 0xFFFFA94D);
        oval(c, 8, -2 - l2, 7f, 3f, 0xFFFFA94D);
        oval(c, 0, -20, 17f, 20f, 0xFF5B7DE8);
        oval(c, 1, -16, 11.5f, 14f, 0xFFF1F6FF);
        c.save(); c.rotate(waving ? -60 + wave * 28 : 20, 16, -22); oval(c, 17, -20, 4.5f, 11f, 0xFF4767CF); c.restore();
        c.save(); c.rotate(-20, -16, -22); oval(c, -17, -20, 4.5f, 11f, 0xFF4767CF); c.restore();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4.5f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(0xFFFF8FA3);
        rect.set(-11, -34, 11, -22);
        c.drawArc(rect, 10, 160, false, p);
        c.drawLine(6, -26, 9, -17, p);
        p.setStyle(Paint.Style.FILL);
        eyes(c, -6, 8, -28, closed, look);
        tri(c, 2, -24, 11, -23, 4, -20, 0xFFFFA94D);
    }

    // --- 11. the crawler: a mint snail with a spiral shell and eye stalks
    void twirl(Canvas c, float t, boolean closed, float look, boolean waving, float wave) {
        oval(c, -3, -7, 21f, 7.5f, 0xFFFFE0B8);
        oval(c, 14, -14, 8.5f, 9f, 0xFFFFE0B8);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.8f);
        p.setColor(0xFFE8B98A);
        float st = (float) Math.sin(t * 3f) * 2f;
        c.drawLine(11, -21, 10 + st, -31, p);
        c.drawLine(17, -21, 19 + st, -31, p);
        p.setStyle(Paint.Style.FILL);
        oval(c, 10 + st, -32, 2.6f, 2.6f, 0xFF2B2140);
        oval(c, 19 + st, -32, 2.6f, 2.6f, 0xFF2B2140);
        oval(c, -6, -22, 16f, 15f, 0xFFFF9F7A);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(0xFFE0745A);
        path.reset();
        path.moveTo(-6, -22);
        path.cubicTo(-2, -28, 4, -22, -2, -17);
        path.cubicTo(-10, -12, -17, -22, -11, -29);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
        if (closed) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.6f);
            p.setColor(0xFF2B2140);
            rect.set(12, -17, 18, -12);
            c.drawArc(rect, 20, 140, false, p);
            p.setStyle(Paint.Style.FILL);
        }
        cheeks(c, 0, -90, 0);
        mouth(c, 16, -10);
    }
}
