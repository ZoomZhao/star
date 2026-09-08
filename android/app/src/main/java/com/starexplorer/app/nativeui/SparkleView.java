package com.starexplorer.app.nativeui;

import android.animation.*;
import android.content.Context;
import android.graphics.*;
import android.os.Build;
import android.view.*;
import android.view.animation.LinearInterpolator;
import android.widget.*;

/** A short, touch-through celebration on a layer that survives dashboard refreshes. */
public final class SparkleView extends FrameLayout {

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path particle = new Path();
  private final Skin skin;
  private final float density;
  private final LinearLayout badge;
  private final StarView star;
  private float progress;
  private ValueAnimator animator;
  private final Runnable finish = this::remove;

  public SparkleView(Context context, Skin skin, String title, String subtitle) {
    super(context);
    this.skin = skin;
    density = getResources().getDisplayMetrics().density;
    setTag("completion-celebration");
    setClickable(false);
    setClipChildren(false);
    setWillNotDraw(false);
    Ui ui = new Ui(context, skin);
    badge = ui.col();
    badge.setGravity(Gravity.CENTER);
    ui.pad(badge, 20);
    badge.setBackground(ui.bg(skin.surface, 28, true));
    badge.setElevation(ui.dp(12));
    badge.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    badge.setContentDescription(title + "。" + subtitle);
    badge.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
    star = new StarView(context, skin);
    badge.addView(star, ui.lp(100, 100));
    TextView heading = ui.text(title, 25, true);
    heading.setGravity(Gravity.CENTER);
    badge.addView(heading, ui.lp(-1, -2));
    ui.gap(badge, 10);
    TextView copy = ui.text(subtitle, 16, false);
    copy.setGravity(Gravity.CENTER);
    badge.addView(copy, ui.lp(-1, -2));
    LayoutParams params = new LayoutParams(ui.dp(330), -2, Gravity.CENTER);
    params.leftMargin = params.rightMargin = ui.dp(20);
    addView(badge, params);
    badge.setAlpha(0);
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    LayoutParams params = (LayoutParams) badge.getLayoutParams();
    int width = Math.min(Math.round(330 * density), Math.max(1, w - Math.round(40 * density)));
    if (params.width != width) {
      params.width = width;
      badge.setLayoutParams(params);
    }
  }

  public static boolean motionEnabled() {
    return Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled();
  }

  public void play() {
    if (!motionEnabled()) {
      progress = .6f;
      badge.setAlpha(1);
      postDelayed(finish, 2000);
      return;
    }
    performHapticFeedback(HapticFeedbackConstants.CONFIRM);
    animator = ValueAnimator.ofFloat(0, 1);
    animator.setInterpolator(new LinearInterpolator());
    animator.setDuration(2600);
    animator.addUpdateListener(a -> {
      progress = (float) a.getAnimatedValue();
      float enter = Math.min(1, progress / .24f);
      float pop = 1 + (float) (Math.pow(1 - enter, 3) * (2.7f * enter - 1));
      float alpha = Math.min(1, progress / .12f) * Math.min(1, (1 - progress) / .16f);
      badge.setAlpha(alpha);
      badge.setScaleX(.78f + .22f * pop);
      badge.setScaleY(.78f + .22f * pop);
      badge.setTranslationY(
        (1 - enter) * 28 * density - Math.max(0, progress - .84f) * 90 * density
      );
      star.setRotation((float) Math.sin(enter * Math.PI * 3) * (1 - enter) * 18);
      invalidate();
    });
    animator.addListener(
      new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
          remove();
        }
      }
    );
    animator.start();
  }

  private void remove() {
    if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
  }

  @Override
  protected void onDetachedFromWindow() {
    removeCallbacks(finish);
    if (animator != null) {
      animator.removeAllListeners();
      animator.cancel();
      animator.removeAllUpdateListeners();
    }
    super.onDetachedFromWindow();
  }

  private void drawStar(
    Canvas c,
    float x,
    float y,
    float radius,
    float rotation,
    int color,
    int alpha
  ) {
    particle.reset();
    for (int j = 0; j < 10; j++) {
      double angle = (j * Math.PI) / 5 - Math.PI / 2 + rotation;
      float r = j % 2 == 0 ? radius : radius * .46f;
      float sx = x + (float) Math.cos(angle) * r,
        sy = y + (float) Math.sin(angle) * r;
      if (j == 0) particle.moveTo(sx, sy);
      else particle.lineTo(sx, sy);
    }
    particle.close();
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(color);
    paint.setAlpha(alpha);
    c.drawPath(particle, paint);
  }

  @Override
  protected void onDraw(Canvas c) {
    super.onDraw(c);
    if (!motionEnabled()) return;
    float cx = getWidth() * .5f,
      cy = getHeight() * .5f;
    float fade = Math.min(1, (1 - progress) / .2f);
    float range = Math.min(getWidth(), getHeight()) * .46f;
    // Expanding rings behind the badge; no full-screen flash or blocking dim layer.
    for (int i = 0; i < 2; i++) {
      float t = Math.max(0, Math.min(1, (progress - i * .09f) / .6f));
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth((1 - t) * 5 * density);
      paint.setColor(0xFFFFD666);
      paint.setAlpha((int) ((1 - t) * 160));
      c.drawCircle(cx, cy, (.12f + t * .9f) * range, paint);
    }
    paint.setStyle(Paint.Style.FILL);
    // Staggered stars sweep from the lower corners along quadratic arcs into the badge.
    for (int i = 0; i < 10; i++) {
      float t = Math.max(0, Math.min(1, (progress - i * .012f) / .34f));
      if (t <= 0 || t >= 1) continue;
      float side = i % 2 == 0 ? -1 : 1;
      float sx = cx + side * range,
        sy = cy + range * .7f;
      float bx = cx + side * range * .9f,
        by = cy - range * .95f;
      float x = (1 - t) * (1 - t) * sx + 2 * (1 - t) * t * bx + t * t * cx;
      float y = (1 - t) * (1 - t) * sy + 2 * (1 - t) * t * by + t * t * (cy - 70 * density);
      drawStar(
        c,
        x,
        y,
        (9 + (i % 3) * 3) * density,
        t * 5,
        0xFFFFC735,
        (int) (255 * Math.sin(t * Math.PI))
      );
    }
    // Stars and skin-colored confetti burst outward and drift down.
    float burst = Math.max(0, (progress - .16f) / .84f);
    for (int i = 0; i < 42; i++) {
      float angle = (float) (i * 2.39996);
      float spread = (float) (1 - Math.pow(1 - burst, 3));
      float distance = (.25f + spread * (.6f + (i % 5) * .08f)) * range;
      float x = cx + (float) Math.cos(angle) * distance;
      float y = cy + (float) Math.sin(angle) * distance + burst * burst * 65 * density;
      int alpha = (int) (Math.min(1, burst * 8) * fade * 235);
      int color = i % 3 == 0 ? skin.accent : i % 3 == 1 ? 0xFFFFC43C : 0xFFFFE59B;
      if (i % 3 != 0) drawStar(
        c,
        x,
        y,
        (5 + (i % 4) * 2) * density,
        angle + burst * 3,
        color,
        alpha
      );
      else {
        paint.setColor(color);
        paint.setAlpha(alpha);
        c.save();
        c.rotate(burst * 240 + i * 17, x, y);
        c.drawRoundRect(
          x - 3 * density,
          y - 7 * density,
          x + 3 * density,
          y + 7 * density,
          2 * density,
          2 * density,
          paint
        );
        c.restore();
      }
    }
  }
}
