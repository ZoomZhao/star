package com.starexplorer.app.nativeui;

import android.content.Context;
import android.graphics.*;
import android.util.LruCache;
import android.view.View;
import java.io.InputStream;

/** Cached, native bitmap rendering. All illustration assets are bundled for instant display. */
public final class ArtView extends View {

  private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(
    24 * 1024 * 1024
  ) {
    protected int sizeOf(String k, Bitmap b) {
      return b.getByteCount();
    }
  };
  private final Bitmap bitmap;
  private final Rect source;
  private final Paint paint = new Paint(3);
  private final Path clip = new Path();

  public ArtView(Context c, String asset) {
    this(c, asset, 1, 1, 0);
  }

  public ArtView(Context c, String asset, int columns, int rows, int index) {
    super(c);
    Bitmap b = CACHE.get(asset);
    if (b == null) {
      try (InputStream in = c.getAssets().open(asset)) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 2;
        b = BitmapFactory.decodeStream(in, null, o);
        if (b != null) CACHE.put(asset, b);
      } catch (Exception ignored) {}
    }
    bitmap = b;
    int cellW = b == null ? 1 : b.getWidth() / columns;
    int cellH = b == null ? 1 : b.getHeight() / rows;
    source = new Rect(
      (index % columns) * cellW,
      (index / columns) * cellH,
      ((index % columns) + 1) * cellW,
      (index / columns + 1) * cellH
    );
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  protected void onDraw(Canvas c) {
    super.onDraw(c);
    if (bitmap == null) return;
    float w = getWidth(),
      h = getHeight(),
      scale = Math.max(w / source.width(), h / source.height());
    float bw = source.width() * scale,
      bh = source.height() * scale;
    clip.reset();
    clip.addRoundRect(
      0,
      0,
      w,
      h,
      18 * getResources().getDisplayMetrics().density,
      18 * getResources().getDisplayMetrics().density,
      Path.Direction.CW
    );
    c.save();
    c.clipPath(clip);
    c.drawBitmap(
      bitmap,
      source,
      new RectF((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2),
      paint
    );
    c.restore();
  }
}
