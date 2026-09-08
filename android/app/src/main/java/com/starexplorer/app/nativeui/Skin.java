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
  ),
  SPACE("space", "太空小熊", "星际小熊", "小小勇气，探索大宇宙", "#F4F2FC", "#FDFCFF", "#6252A0", "#9A8ACB", "#E6E0F8", "#3B365A", "#807993", "#D7CEEB"),
  OCEAN("ocean", "海底小鲸", "小鲸奇遇记", "每一份努力，都荡起小浪花", "#EDF8FA", "#FBFEFE", "#237789", "#5AB7BC", "#D1EEF0", "#28525D", "#71949B", "#BCE1E5"),
  FOREST("forest", "森林小狐", "森林小队长", "带着好奇，发现成长的宝藏", "#FBF4EA", "#FFFCF6", "#936039", "#D69752", "#F4E3C8", "#5D4736", "#988575", "#E5D2B8");

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
    for (Skin skin : values()) if (skin.key.equals(key)) return skin;
    return DINO;
  }

  public String hero() {
    return "skins/" + key + "-hero.webp";
  }

  public String atlas() {
    return "skins/" + (this == PRINCESS ? "princess" : "dino") + "-tasks.webp";
  }
}
