package com.starexplorer.app.nativeui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.util.LruCache;
import android.view.*;
import android.view.animation.LinearInterpolator;
import java.io.InputStream;

/** A separately composited mascot: only the character moves, never its scene or controls. */
public final class CompanionView extends View {
  private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
    protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
  };
  private final Skin skin;
  private final Bitmap mascot;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final Path shape = new Path();
  private ValueAnimator animator;
  private float progress = 1;
  private int action = -1;
  private boolean greeting;
  private final Runnable clearGreeting = () -> { greeting = false; invalidate(); };

  public CompanionView(Context context, Skin skin) {
    super(context);
    this.skin = skin;
    Bitmap loaded = CACHE.get(skin.key);
    if (loaded == null) try (InputStream in = context.getAssets().open("companions/" + skin.key + ".webp")) {
      loaded = BitmapFactory.decodeStream(in);
      if (loaded != null) CACHE.put(skin.key, loaded);
    } catch (Exception ignored) {}
    mascot = loaded;
    setTag("interactive-companion");
    setContentDescription("和" + skin.title + "打招呼，点击看伙伴动起来");
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    setFocusable(true);
    setOnClickListener(v -> react());
  }

  @Override public CharSequence getAccessibilityClassName() { return "android.widget.Button"; }

  private void react() {
    stopMotion();
    action = (action + 1) % 3;
    greeting = true;
    announceForAccessibility(new String[] {"嘿，我们一起加油！", "收到你的鼓励啦！", "今天也要闪闪发光！"}[action]);
    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    removeCallbacks(clearGreeting);
    postDelayed(clearGreeting, 1800);
    if (!SparkleView.motionEnabled()) { invalidate(); return; }
    animator = ValueAnimator.ofFloat(0, 1);
    animator.setDuration((action == 2 ? 1200 : 950) + (skin == Skin.OCEAN ? 180 : 0));
    animator.setInterpolator(new LinearInterpolator());
    animator.addUpdateListener(a -> { progress = (float) a.getAnimatedValue(); invalidate(); });
    animator.start();
  }

  private void stopMotion() {
    if (animator != null) { animator.cancel(); animator.removeAllUpdateListeners(); animator = null; }
    progress = 1;
    invalidate();
  }

  @Override protected void onWindowVisibilityChanged(int visibility) {
    super.onWindowVisibilityChanged(visibility);
    if (visibility != VISIBLE) { stopMotion(); removeCallbacks(clearGreeting); greeting = false; }
  }
  @Override protected void onDetachedFromWindow() {
    stopMotion(); removeCallbacks(clearGreeting); greeting = false;
    super.onDetachedFromWindow();
  }

  private void star(Canvas canvas, float x, float y, float radius, int color) {
    shape.reset();
    for (int i = 0; i < 10; i++) {
      double angle = -Math.PI / 2 + i * Math.PI / 5;
      float r = i % 2 == 0 ? radius : radius * .46f;
      float px = x + (float)Math.cos(angle) * r, py = y + (float)Math.sin(angle) * r;
      if (i == 0) shape.moveTo(px, py); else shape.lineTo(px, py);
    }
    shape.close(); paint.setColor(color); canvas.drawPath(shape, paint);
  }

  @Override protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float w = getWidth(), h = getHeight(), size = Math.min(w, h);
    if (w == 0 || h == 0) return;
    paint.setShader(new LinearGradient(0, 0, w, h, skin.soft, skin.surface, Shader.TileMode.CLAMP));
    canvas.drawRoundRect(0, 0, w, h, size * .07f, size * .07f, paint); paint.setShader(null);
    // Fixed scene decorations: rings, bubbles or leafy hills around a warm halo.
    paint.setColor(skin.surface); canvas.drawCircle(w * .5f, h * .44f, size * .42f, paint);
    paint.setColor(skin.soft); canvas.drawOval(-w * .2f, h * .78f, w * 1.2f, h * 1.16f, paint);
    for (int i = 0; i < 7; i++) {
      float x = w * (.10f + (i % 3) * .39f), y = h * (.12f + (i / 3) * .28f);
      if (skin == Skin.OCEAN) {
        paint.setColor(skin.accent); paint.setAlpha(75); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2);
        canvas.drawCircle(x, y, size * (.016f + i * .002f), paint); paint.setStyle(Paint.Style.FILL); paint.setAlpha(255);
      } else if (skin == Skin.FOREST || skin == Skin.DINO) {
        paint.setColor(skin.accent); paint.setAlpha(75); canvas.save(); canvas.rotate(i * 39, x, y);
        canvas.drawOval(x-size*.012f,y-size*.025f,x+size*.012f,y+size*.025f,paint);canvas.restore();paint.setAlpha(255);
      } else star(canvas,x,y,size*.018f,0xFFEDC56B);
    }
    float wave = (float)Math.sin(progress * Math.PI * 4) * (1-progress);
    float hop = (float)Math.sin(progress * Math.PI);
    float rotation = action == 0 ? wave * (skin == Skin.OCEAN ? 13 : 9) : action == 2 ? wave * 17 : wave * 3;
    float lift = action == 1 ? Math.abs((float)Math.sin(progress*Math.PI*2))*(1-progress)*h*.12f : hop*h*.025f;
    float squash = action == 1 ? wave*.055f : wave*.025f;
    if (mascot != null) {
      float scale = Math.min(w*.92f/mascot.getWidth(),h*.82f/mascot.getHeight());
      float bw=mascot.getWidth()*scale,bh=mascot.getHeight()*scale,cx=w*.5f,cy=h*.46f;
      canvas.save();
      float drift = skin == Skin.SPACE || skin == Skin.OCEAN ? wave * w * .045f : 0;
      canvas.translate(drift,-lift);canvas.rotate(rotation,cx,cy+bh*.30f);
      canvas.scale(1+squash,1-squash,cx,cy+bh*.35f);
      canvas.drawBitmap(mascot,null,new RectF(cx-bw/2,cy-bh/2,cx+bw/2,cy+bh/2),paint);canvas.restore();
    }
    if (progress < 1) for (int i=0;i<8;i++) {
      double a=i*Math.PI/4;
      float r=size*(.23f+progress*.21f);
      int alpha=Math.round((1-progress)*220);
      star(canvas,w*.5f+(float)Math.cos(a)*r,h*.43f+(float)Math.sin(a)*r,size*.021f,(alpha<<24)|0xEDBA4D);
    }
    paint.setColor(skin.primary);paint.setTextAlign(Paint.Align.CENTER);
    paint.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));paint.setTextSize(Math.min(16*getResources().getDisplayMetrics().scaledDensity,w/14));
    canvas.drawText(greeting ? new String[]{"嘿，我们一起加油！","收到你的鼓励啦！","今天也要闪闪发光！"}[Math.max(0,action)] : "点点我，看我动起来",w*.5f,h*.94f,paint);
  }
}
