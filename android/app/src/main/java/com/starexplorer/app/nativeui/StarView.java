package com.starexplorer.app.nativeui;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Resolution-independent gold stars, with real approval counts driving collected states. */
public final class StarView extends View {

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Skin skin;
  private final int mode, earned, pending, total;
  private final float density;

  public StarView(Context context, Skin skin) {
    this(context, skin, 0, 0, 0, 0);
  }

  public StarView(Context context, Skin skin, int mode, int earned, int pending, int total) {
    super(context);
    this.skin = skin;
    this.mode = mode;
    this.earned = earned;
    this.pending = pending;
    this.total = total;
    density = getResources().getDisplayMetrics().density;
    if (mode == 0) setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    else {
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
      setContentDescription(
        mode == 1
          ? "已通过 " + earned + " / " + total + " 次，待确认 " + pending + " 次"
          : "当日已获得 " + earned + " 星，上限 " + total + " 星"
      );
    }
  }

  private Path path(float cx, float cy, float radius) {
    Path path = new Path();
    for (int i = 0; i < 10; i++) {
      double angle = -Math.PI / 2 + (i * Math.PI) / 5;
      float r = i % 2 == 0 ? radius : radius * .48f;
      float x = cx + (float) Math.cos(angle) * r,
        y = cy + (float) Math.sin(angle) * r;
      if (i == 0) path.moveTo(x, y);
      else path.lineTo(x, y);
    }
    path.close();
    return path;
  }

  private void star(Canvas canvas, float cx, float cy, float radius, int state) {
    Path outline = path(cx, cy, radius);
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(state == 1 ? 0x33B66E12 : 0x12B98C48);
    canvas.save();
    canvas.translate(0, density * 2);
    canvas.drawPath(outline, paint);
    canvas.restore();
    paint.setAlpha(255);
    paint.setShader(
      new LinearGradient(
        cx,
        cy - radius,
        cx,
        cy + radius,
        state == 1
          ? new int[] { 0xFFFFE66D, 0xFFFFC329, 0xFFEE9914 }
          : new int[] { 0xFFFFFCED, 0xFFF1E7C8 },
        null,
        Shader.TileMode.CLAMP
      )
    );
    canvas.drawPath(outline, paint);
    paint.setShader(null);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeJoin(Paint.Join.ROUND);
    paint.setStrokeWidth(density * 1.3f);
    paint.setColor(state == 1 ? 0xFFC78C26 : 0xFFCEBC8D);
    canvas.drawPath(outline, paint);
    paint.setStrokeWidth(density);
    paint.setColor(state == 1 ? 0xFFFFF9C9 : 0xFFFFFEF7);
    canvas.drawPath(path(cx, cy, radius * .81f), paint);
    paint.setStyle(Paint.Style.FILL);
    if (state == 1) {
      paint.setColor(0xFFFFFCE0);
      canvas.drawOval(
        cx - radius * .26f,
        cy - radius * .45f,
        cx - radius * .08f,
        cy - radius * .12f,
        paint
      );
    } else if (state == 2) {
      paint.setColor(skin.primary);
      canvas.drawCircle(cx, cy, radius * .25f, paint);
      paint.setColor(Color.WHITE);
      paint.setStrokeWidth(density);
      canvas.drawLine(cx, cy, cx, cy - radius * .16f, paint);
      canvas.drawLine(cx, cy, cx + radius * .12f, cy, paint);
    }
  }

  private void label(Canvas canvas, String text, float x, float y, float size) {
    paint.setColor(skin.ink);
    paint.setTextSize(size * getResources().getDisplayMetrics().scaledDensity);
    paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    paint.setTextAlign(Paint.Align.CENTER);
    canvas.drawText(text, x, y, paint);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float w = getWidth(),
      h = getHeight();
    if (mode == 0) {
      star(canvas, w / 2, h / 2, Math.min(w, h) * .44f, 1);
      return;
    }
    if (mode == 1) {
      int slots = total <= 5 ? Math.max(1, total) : 1;
      float step = Math.min(34 * density, w / slots),
        radius = Math.min(15 * density, step * .45f);
      for (int i = 0; i < slots; i++) star(
        canvas,
        step * (i + .5f),
        17 * density,
        radius,
        total > 5
          ? earned > 0
            ? 1
            : pending > 0
              ? 2
              : 0
          : i < earned
            ? 1
            : i < earned + pending
              ? 2
              : 0
      );
      label(
        canvas,
        earned + "/" + total + " 次" + (pending > 0 ? " · 待审 " + pending : ""),
        w / 2,
        h - 2 * density,
        11
      );
      return;
    }
    float step = w / 4;
    paint.setColor(0xFFE7D7AC);
    paint.setStrokeWidth(3 * density);
    canvas.drawLine(step / 2, 25 * density, w - step / 2, 25 * density, paint);
    for (int i = 0; i < 4; i++) {
      int threshold = (int) Math.ceil((total * (i + 1)) / 4.0);
      star(
        canvas,
        step * (i + .5f),
        25 * density,
        21 * density,
        total > 0 && earned >= threshold ? 1 : 0
      );
      label(canvas, total > 0 ? threshold + " 星" : "—", step * (i + .5f), 66 * density, 12);
    }
  }
}
