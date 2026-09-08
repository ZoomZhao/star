package com.starexplorer.app.nativeui;

/** Pure layout math keeps the landscape dashboard bounded by available height. */
public final class LayoutPolicy {

  private LayoutPolicy() {}

  public static boolean landscape(int width, int height) {
    return width > height && width >= 600;
  }

  public static int tasksPerPage(int width, int height, float fontScale) {
    if (landscape(width, height)) return height < 510 || fontScale > 1.25f ? 2 : 4;
    return width >= 600 ? 4 : 2;
  }

  public static int pageCount(int count, int pageSize) {
    return Math.max(1, (count + pageSize - 1) / pageSize);
  }

  public static int page(int requested, int count, int pageSize) {
    return Math.max(0, Math.min(requested, pageCount(count, pageSize) - 1));
  }
}
