package com.starexplorer.app.nativeui;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.io.InputStream;

/** Six ImageGen icons share one transparent atlas; cells are sampled without editing the artwork. */
public final class NavigationIconView extends View {

  private static Bitmap atlas;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final Skin skin;
  private final String key;

  public NavigationIconView(Context c, Skin skin, String key) {
    super(c);
    this.skin = skin;
    this.key = key;
    if (atlas == null) try (InputStream in = c.getAssets().open("ui/navigation-atlas.png")) {
      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inSampleSize = 2;
      atlas = BitmapFactory.decodeStream(in, null, options);
    } catch (Exception ignored) {}
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  @Override
  protected void onDraw(Canvas c) {
    int column = key.equals("today") ? 0 : key.equals("wallet") ? 1 : key.equals("shop") ? 2 : -1;
    if (column >= 0 && atlas != null) {
      int cellW = atlas.getWidth() / 3,
        cellH = atlas.getHeight() / 2,
        row = skin == Skin.PRINCESS ? 1 : 0;
      float size = Math.min(getWidth(), getHeight());
      c.drawBitmap(
        atlas,
        new Rect(column * cellW, row * cellH, (column + 1) * cellW, (row + 1) * cellH),
        new RectF(
          (getWidth() - size) / 2,
          (getHeight() - size) / 2,
          (getWidth() + size) / 2,
          (getHeight() + size) / 2
        ),
        paint
      );
      return;
    }
    c.save();
    c.scale(getWidth() / 48f, getHeight() / 48f);
    paint.setColor(skin.soft);
    paint.setStyle(Paint.Style.FILL);
    c.drawRoundRect(5, 5, 43, 43, 12, 12, paint);
    paint.setColor(skin.primary);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(2.4f);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
    if (key.equals("review")) {
      Path p = new Path();
      p.moveTo(14, 24);
      p.lineTo(21, 31);
      p.lineTo(34, 17);
      c.drawPath(p, paint);
    } else if (key.equals("rules")) {
      c.drawRoundRect(14, 11, 34, 37, 3, 3, paint);
      for (int y = 18; y <= 30; y += 6) c.drawLine(19, y, 29, y, paint);
    } else {
      c.drawCircle(24, 24, 9, paint);
      c.drawCircle(24, 24, 3, paint);
    }
    paint.setStyle(Paint.Style.FILL);
    c.restore();
  }
}
