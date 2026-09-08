package com.starexplorer.app.nativeui;

import android.graphics.Color;

/** Skin tokens and bundled artwork are independent of business rules. */
public enum Skin {
  DINO(
    "dino",
    "恐龙探险",
    "星星探险家",
    "今天也要闪闪发光",
    "#FBF8EC",
    "#FFFDF5",
    "#216848",
    "#2D7E4C",
    "#DAEAD0",
    "#294B35",
    "#898572",
    "#E6DCBD"
  ),
  PRINCESS(
    "princess",
    "公主花园",
    "星星公主",
    "每一天，都闪闪发光",
    "#FFF5F4",
    "#FFFCF9",
    "#B24B73",
    "#DF7599",
    "#FBE0E7",
    "#723C55",
    "#A38389",
    "#F0CBD3"
  );

  public final String key, label, title, subtitle;
  public final int background, surface, primary, accent, soft, ink, muted, border;

  Skin(
    String key,
    String label,
    String title,
    String subtitle,
    String bg,
    String surface,
    String primary,
    String accent,
    String soft,
    String ink,
    String muted,
    String border
  ) {
    this.key = key;
    this.label = label;
    this.title = title;
    this.subtitle = subtitle;
    this.background = Color.parseColor(bg);
    this.surface = Color.parseColor(surface);
    this.primary = Color.parseColor(primary);
    this.accent = Color.parseColor(accent);
    this.soft = Color.parseColor(soft);
    this.ink = Color.parseColor(ink);
    this.muted = Color.parseColor(muted);
    this.border = Color.parseColor(border);
  }

  public static Skin from(String key) {
    return "princess".equals(key) ? PRINCESS : DINO;
  }

  public String hero() {
    return "skins/" + key + "-hero.webp";
  }

  public String atlas() {
    return "skins/" + key + "-tasks.webp";
  }
}
