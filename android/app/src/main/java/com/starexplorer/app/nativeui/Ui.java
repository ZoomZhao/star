package com.starexplorer.app.nativeui;

import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;

public final class Ui {

  public final Context c;
  public final Skin s;

  public Ui(Context c, Skin s) {
    this.c = c;
    this.s = s;
  }

  public int dp(float n) {
    return Math.round(n * c.getResources().getDisplayMetrics().density);
  }

  public LinearLayout col() {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(1);
    return l;
  }

  public LinearLayout row() {
    LinearLayout l = new LinearLayout(c);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  public void pad(View v, int n) {
    v.setPadding(dp(n), dp(n), dp(n), dp(n));
  }

  public GradientDrawable bg(int color, int radius, boolean border) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    if (border) d.setStroke(dp(1), s.border);
    return d;
  }

  public TextView text(String value, int size, boolean bold) {
    TextView t = new TextView(c);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(s.ink);
    t.setIncludeFontPadding(false);
    t.setTypeface(IslandTypeface.get(c, bold));
    return t;
  }

  public TextView label(String value) {
    TextView t = text(value, 13, false);
    t.setTextColor(s.muted);
    return t;
  }

  public TextView button(String title, boolean primary, Runnable action) {
    TextView b = text(title, 15, true);
    b.setGravity(Gravity.CENTER);
    b.setMinHeight(dp(48));
    b.setPadding(dp(14), dp(8), dp(14), dp(8));
    b.setTextColor(primary ? android.graphics.Color.WHITE : s.ink);
    b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x226C5CFF), bg(primary ? s.primary : s.surface, 12, !primary), null));
    String icon = actionIcon(title);
    if (icon != null) try (java.io.InputStream in = c.getAssets().open("ui/v2/" + icon + ".png")) {
      Drawable d = Drawable.createFromStream(in, icon);
      d.setBounds(0, 0, dp(18), dp(18));
      d.setTint(primary ? android.graphics.Color.WHITE : s.primary);
      b.setCompoundDrawables(d, null, null, null);
      b.setCompoundDrawablePadding(dp(7));
    } catch (java.io.IOException ignored) {}
    b.setClickable(true);
    b.setFocusable(true);
    b.setOnClickListener(v -> {
      v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
      action.run();
    });
    b.setAccessibilityDelegate(
      new View.AccessibilityDelegate() {
        @Override
        public void onInitializeAccessibilityNodeInfo(
          View host,
          android.view.accessibility.AccessibilityNodeInfo info
        ) {
          super.onInitializeAccessibilityNodeInfo(host, info);
          info.setClassName("android.widget.Button");
        }
      }
    );
    return b;
  }

  private String actionIcon(String text) {
    if (text.contains("下载")) return "Download";
    if (text.contains("恢复备份")) return "Upload";
    if (text.contains("家庭")) return "Building2";
    if (text.contains("分配账号")) return "UserPlus";
    if (text.contains("密码")) return "KeyRound";
    if (text.contains("编辑") || text.contains("修改")) return "Pencil";
    if (text.contains("检测")) return "ScanLine";
    if (text.contains("卡")) return "Nfc";
    if (text.contains("退出")) return "LogOut";
    if (text.contains("刷新") || text.contains("重试") || text.contains("重新连接")) return "RefreshCw";
    if (text.contains("皮肤") || text.contains("换装")) return "Palette";
    if (text.equals("设置")) return "Settings";
    if (text.startsWith("星星 ") || text.contains("账本")) return "Star";
    if (text.contains("添加") || text.contains("新增")) return "Plus";
    if (text.contains("退回")) return "X";
    if (text.contains("发放") || text.contains("发星") || text.contains("提交")) return "Send";
    if (text.equals("‹")) return null;
    if (text.equals("›")) return null;
    return null;
  }

  public LinearLayout iconBox(String name, int size) {
    LinearLayout box = row();
    box.setGravity(Gravity.CENTER);
    box.setBackground(bg(s.soft, 12, false));
    box.addView(new NavigationIconView(c, s, name), lp(size, size));
    return box;
  }

  public LinearLayout card() {
    LinearLayout l = col();
    pad(l, 16);
    l.setBackground(bg(s.surface, 22, true));
    return l;
  }

  public LinearLayout.LayoutParams lp(int w, int h) {
    return new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h));
  }

  public void gap(LinearLayout l, int size) {
    View v = new View(c);
    l.addView(v, l.getOrientation() == 1 ? lp(1, size) : lp(size, 1));
  }

  public void grow(LinearLayout l, View v) {
    l.addView(
      v,
      new LinearLayout.LayoutParams(
        l.getOrientation() == 1 ? -1 : 0,
        l.getOrientation() == 1 ? 0 : -1,
        1
      )
    );
  }

  public void add(LinearLayout l, View v, int h) {
    l.addView(v, lp(-1, h));
  }

  public void line(LinearLayout l, String text) {
    TextView t = text(text, 16, false);
    t.setLineSpacing(dp(4), 1);
    add(l, t, -2);
    gap(l, 12);
  }

  public EditText input(LinearLayout l, String label, String value, int type) {
    add(l, label(label), -2);
    gap(l, 6);
    EditText e = new EditText(c);
    e.setTextSize(16);
    e.setTypeface(IslandTypeface.get(c, false));
    e.setTextColor(s.ink);
    e.setInputType(type);
    e.setText(value);
    e.setSingleLine((type & android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
    pad(e, 12);
    e.setBackground(bg(s.surface, 12, true));
    add(l, e, -2);
    gap(l, 14);
    return e;
  }

  public Spinner select(LinearLayout l, String label, String[] options, int chosen) {
    add(l, label(label), -2);
    Spinner sp = new Spinner(c);
    ArrayAdapter<String> a = new ArrayAdapter<>(
      c,
      android.R.layout.simple_spinner_dropdown_item,
      options
    );
    sp.setAdapter(a);
    sp.setSelection(Math.max(0, chosen));
    add(l, sp, 52);
    gap(l, 10);
    return sp;
  }

  public ScrollView scroll(View content) {
    ScrollView s = new ScrollView(c);
    s.setFillViewport(false);
    s.setClipToPadding(false);
    s.addView(content);
    return s;
  }

  public void ellipsis(TextView t, int lines) {
    t.setMaxLines(lines);
    t.setEllipsize(TextUtils.TruncateAt.END);
  }
}
