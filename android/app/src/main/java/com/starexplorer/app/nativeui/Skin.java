package com.starexplorer.app.nativeui;

import android.graphics.Color;

/** Skin tokens and bundled artwork are independent of business rules. */
public enum Skin {
  DINO("dino", "恐龙探险", "星星探险家", "今天也要闪闪发光", "#F7F7FF", "#FFFFFF", "#6C5CFF", "#8B7FFF", "#EEEBFF", "#24274F", "#8185A0", "#E9E9F5"),
  PRINCESS("princess", "公主花园", "星星公主", "每一天，都闪闪发光", "#FCF8FF", "#FFFFFF", "#AE68B5", "#CD97D0", "#F6EBF8", "#24274F", "#8185A0", "#E9E9F5"),
  SPACE("space", "太空小熊", "星际小熊", "小小勇气，探索大宇宙", "#F7F7FF", "#FFFFFF", "#646BD8", "#8D93E6", "#ECEFFE", "#24274F", "#8185A0", "#E9E9F5"),
  OCEAN("ocean", "海底小鲸", "小鲸奇遇记", "每一份努力，都荡起小浪花", "#F7FBFF", "#FFFFFF", "#438DC2", "#6EACD6", "#E7F3FC", "#24274F", "#8185A0", "#E9E9F5"),
  FOREST("forest", "森林小狐", "森林小队长", "带着好奇，发现成长的宝藏", "#F7FBFA", "#FFFFFF", "#548F87", "#7CB2A8", "#E9F5F1", "#24274F", "#8185A0", "#E9E9F5");

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
