package com.starexplorer.app.nativeui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontStyle;
import android.os.Build;

/** Offline Nunito + Noto Sans SC, using the same licensed fonts as animal-island-ui. */
public final class IslandTypeface {
  private static Typeface regular, bold;
  private IslandTypeface() {}

  public static synchronized Typeface get(Context c, boolean heading) {
    if (regular == null) {
      try {
        if (Build.VERSION.SDK_INT >= 29) {
          FontFamily latin = new FontFamily.Builder(new Font.Builder(c.getAssets(), "fonts/nunito-latin-500.ttf").setWeight(500).build())
            .addFont(new Font.Builder(c.getAssets(), "fonts/nunito-latin-700.ttf").setWeight(700).build())
            .addFont(new Font.Builder(c.getAssets(), "fonts/nunito-latin-900.ttf").setWeight(900).build()).build();
          FontFamily chinese = new FontFamily.Builder(new Font.Builder(c.getAssets(), "fonts/noto-sans-sc-chinese-simplified-500.ttf").setWeight(500).build())
            .addFont(new Font.Builder(c.getAssets(), "fonts/noto-sans-sc-chinese-simplified-700.ttf").setWeight(700).build()).build();
          regular = new Typeface.CustomFallbackBuilder(latin).addCustomFallback(chinese).setSystemFallback("sans-serif")
            .setStyle(new FontStyle(500, FontStyle.FONT_SLANT_UPRIGHT)).build();
          bold = new Typeface.CustomFallbackBuilder(latin).addCustomFallback(chinese).setSystemFallback("sans-serif")
            .setStyle(new FontStyle(700, FontStyle.FONT_SLANT_UPRIGHT)).build();
        } else {
          regular = Typeface.createFromAsset(c.getAssets(), "fonts/noto-sans-sc-chinese-simplified-500.ttf");
          bold = Typeface.createFromAsset(c.getAssets(), "fonts/noto-sans-sc-chinese-simplified-700.ttf");
        }
      } catch (Exception e) {
        regular = Typeface.create("sans-serif-rounded", Typeface.NORMAL);
        bold = Typeface.create("sans-serif-rounded", Typeface.BOLD);
      }
    }
    return heading ? bold : regular;
  }
}
