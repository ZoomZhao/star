package com.starexplorer.app.nativeui;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import org.json.*;

/** Native seven-day chart; both income and spending use the same numeric scale. */
public final class WeekChartView extends View {

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final JSONArray week;
  private final Skin skin;

  public WeekChartView(Context c, Skin skin, JSONArray week) {
    super(c);
    this.skin = skin;
    this.week = week;
    StringBuilder description = new StringBuilder("最近七天。");
    for (int i = 0; i < week.length(); i++) {
      JSONObject day = week.optJSONObject(i);
      if (day != null) description
        .append(day.optString("date"))
        .append("，获得 ")
        .append(day.optInt("earned"))
        .append(" 星，使用 ")
        .append(day.optInt("spent"))
        .append(" 星。");
    }
    setContentDescription(description);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
  }

  @Override
  protected void onDraw(Canvas c) {
    float d = getResources().getDisplayMetrics().density;
    float baseline = getHeight() - 24 * d,
      top = 24 * d;
    float step = getWidth() / (float) Math.max(1, week.length());
    int max = 1;
    for (int i = 0; i < week.length(); i++) {
      JSONObject day = week.optJSONObject(i);
      if (day != null) max = Math.max(max, Math.max(day.optInt("earned"), day.optInt("spent")));
    }
    paint.setColor(skin.border);
    paint.setStrokeWidth(d * .5f);
    for (int j = 0; j < 3; j++) {
      float y = top + ((baseline - top) * j) / 2;
      c.drawLine(0, y, getWidth(), y, paint);
    }
    for (int i = 0; i < week.length(); i++) {
      JSONObject day = week.optJSONObject(i);
      if (day == null) continue;
      float center = step * (i + .5f),
        bw = Math.min(9 * d, step * .21f);
      for (int k = 0; k < 2; k++) {
        int n = day.optInt(k == 0 ? "earned" : "spent");
        float x = center + (k == 0 ? -bw - 2 * d : 2 * d);
        float height = (Math.max(0, baseline - top) * n) / max;
        paint.setColor(k == 0 ? 0xFF87AF65 : 0xFFE795AA);
        if (n > 0) c.drawRoundRect(x, baseline - height, x + bw, baseline, 3 * d, 3 * d, paint);
        paint.setTextSize(10 * d);
        paint.setTextAlign(Paint.Align.CENTER);
        if (n > 0) c.drawText("" + n, x + bw / 2, baseline - height - 4 * d, paint);
      }
      paint.setColor(skin.muted);
      paint.setTextSize(10 * d);
      paint.setTextAlign(Paint.Align.CENTER);
      String date = day.optString("date");
      c.drawText(
        date.length() >= 10 ? date.substring(5) : date,
        center,
        getHeight() - 4 * d,
        paint
      );
    }
  }
}
