package com.starexplorer.app.nativeui;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Keeps progress directly after short titles, reserving its space for long titles. */
public final class InlineTaskTitle extends LinearLayout {
  private final TextView title, count;
  private final int gap;

  public InlineTaskTitle(Ui ui, String name, int approved, int limit) {
    super(ui.c);
    setOrientation(HORIZONTAL);
    setGravity(Gravity.TOP);
    gap = ui.dp(6);
    title = ui.text(name, 17, true);
    ui.ellipsis(title, 2);
    count = ui.label(approved + "/" + limit);
    count.setTextSize(12);
    count.setPadding(0, ui.dp(3), 0, 0);
    count.setContentDescription("已完成 " + approved + " 次，每天最多 " + limit + " 次");
    addView(title, ui.lp(-2, -2));
    LinearLayout.LayoutParams params = ui.lp(-2, -2);
    params.leftMargin = gap;
    addView(count, params);
  }

  @Override protected void onMeasure(int width, int height) {
    count.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
    if (View.MeasureSpec.getMode(width) != View.MeasureSpec.UNSPECIFIED) {
      int available = Math.max(1, View.MeasureSpec.getSize(width) - count.getMeasuredWidth() - gap);
      if (title.getMaxWidth() != available) title.setMaxWidth(available);
    }
    super.onMeasure(width, height);
  }
}
