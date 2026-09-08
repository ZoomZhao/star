package com.starexplorer.app.nativeui;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

/** Decorative keepsake, deliberately not a daily target or quota meter. */
public final class JourneyView extends FrameLayout {

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path path = new Path();
  private final float density;

  public JourneyView(Context c, Skin skin) {
    super(c);
    Ui ui = new Ui(c, skin);
    density = c.getResources().getDisplayMetrics().density;
    setWillNotDraw(false);
    GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {
      skin.surface,
      skin == Skin.PRINCESS ? 0xFFFFF1DF : 0xFFFFF4D5,
    });
    bg.setCornerRadius(ui.dp(22));
    bg.setStroke(ui.dp(1), 0xFFECD6AB);
    setBackground(bg);
    LinearLayout content = ui.col();
    content.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    content.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), 0);
    FrameLayout medallion = new FrameLayout(c);
    medallion.setBackground(ui.bg(0xFFFFF0BC, 24, true));
    medallion.addView(new StarView(c, skin), new FrameLayout.LayoutParams(-1, -1));
    content.addView(medallion, ui.lp(44, 44));
    ui.gap(content, 7);
    TextView title = ui.text("星星收集之旅", 18, true);
    title.setTextColor(skin.primary);
    title.setGravity(Gravity.CENTER);
    content.addView(title);
    ui.gap(content, 7);
    TextView note = ui.text("每一份努力，都值得被看见", 12, false);
    note.setGravity(Gravity.CENTER);
    content.addView(note, ui.lp(-1, -2));
    addView(content, new LayoutParams(-1, -1));
  }

  private void star(Canvas c, float x, float y, float r) {
    path.reset();
    for (int i = 0; i < 10; i++) {
      double a = -Math.PI / 2 + (i * Math.PI) / 5;
      float rr = i % 2 == 0 ? r : r * .45f;
      float xx = x + (float) Math.cos(a) * rr,
        yy = y + (float) Math.sin(a) * rr;
      if (i == 0) path.moveTo(xx, yy);
      else path.lineTo(xx, yy);
    }
    path.close();
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(0xFFFFD577);
    c.drawPath(path, paint);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(density * .7f);
    paint.setColor(0xFFDCB45F);
    c.drawPath(path, paint);
  }

  @Override
  protected void onDraw(Canvas c) {
    super.onDraw(c);
    float w = getWidth(),
      h = getHeight();
    paint.setColor(0xFFF1D9AC);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(density * .8f);
    c.drawRoundRect(
      5 * density,
      5 * density,
      w - 5 * density,
      h - 5 * density,
      18 * density,
      18 * density,
      paint
    );
    float y = h - 39 * density;
    path.reset();
    path.moveTo(23 * density, y);
    path.cubicTo(w * .26f, h + 2 * density, w * .74f, h + 2 * density, w - 23 * density, y);
    c.drawPath(path, paint);
    for (int i = 0; i < 7; i++) {
      float t = i / 6f,
        x = 23 * density + (w - 46 * density) * t;
      float sy = y + 25 * density * (float) Math.sin(t * Math.PI);
      star(c, x, sy, (i % 3 == 0 ? 5 : 2.5f) * density);
    }
  }
}
