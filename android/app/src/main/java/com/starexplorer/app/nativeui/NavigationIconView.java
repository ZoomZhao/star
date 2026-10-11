package com.starexplorer.app.nativeui;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Bundled Lucide icons, shared with the web interface (ISC). */
public final class NavigationIconView extends View {
  private static final Map<String, Bitmap> icons = new HashMap<>();
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final Bitmap icon;

  public NavigationIconView(Context c, Skin skin, String key) {
    super(c);
    String name = key.equals("today") ? "Home" : key.equals("review") ? "ClipboardCheck"
      : key.equals("wallet") ? "Wallet" : key.equals("shop") ? "Heart"
      : key.equals("rules") ? "BookOpen" : key.equals("cards") ? "ScanLine"
      : key.equals("admin") ? "Users" : key.equals("more") ? "MoreHorizontal" : key;
    if (!icons.containsKey(name)) try (InputStream in = c.getAssets().open("ui/v2/" + name + ".png")) {
      icons.put(name, BitmapFactory.decodeStream(in));
    } catch (Exception ignored) {}
    icon = icons.get(name);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  @Override protected void onDraw(Canvas c) {
    if (icon == null) return;
    float size = Math.min(getWidth(), getHeight());
    c.drawBitmap(icon, null, new RectF((getWidth()-size)/2, (getHeight()-size)/2,
      (getWidth()+size)/2, (getHeight()+size)/2), paint);
  }
}
