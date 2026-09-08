package com.starexplorer.app;

import static com.starexplorer.app.nativeui.StarApi.json;

import android.app.DatePickerDialog;
import android.content.*;
import android.content.res.Configuration;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.*;
import com.starexplorer.app.nativeui.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Android Views application: navigation, forms, artwork and networking are all native. */
public class MainActivity extends AppCompatActivity {

  private static final String[] SUBJECTS = { "chinese", "math", "english", "sports", "other" },
    LABELS = { "语文", "数学", "英语", "体育", "其他" };
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private StarApi api;
  private Ui ui;
  private Skin skin;
  private FrameLayout root, effects;
  private ScrollView taskScroller;
  private final Map<String, Integer> regionOffsets = new HashMap<>();
  private JSONObject user, data, accounts;
  private JSONArray children = new JSONArray(),
    templates = new JSONArray();
  private String loginName = "",
    loginPassword = "";
  private String childId = "",
    date = today(),
    page = "today",
    subject = "chinese",
    rewardFilter = "all",
    error = "";
  private int taskScrollY = 0,
    requestVersion = 0;
  private boolean busy = false,
    initialized = false,
    wide = false,
    resumed = false,
    loading = false;
  private int contentW = 0,
    contentH = 0;
  private AlertDialog activeDialog;
  private byte[] pendingBackup;
  private final Runnable poll = new Runnable() {
    public void run() {
      if (resumed && user != null && !busy && activeDialog == null) refresh(false);
      handler.postDelayed(this, 20000);
    }
  };

  private interface Work {
    Object run() throws Exception;
  }

  private interface Done {
    void run(Object result) throws Exception;
  }

  public static String today() {
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
    return f.format(new Date());
  }

  private String shift(String d, int n) {
    try {
      SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
      f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
      Calendar c = Calendar.getInstance();
      c.setTime(f.parse(d));
      c.add(Calendar.DATE, n);
      return f.format(c.getTime());
    } catch (Exception e) {
      return today();
    }
  }

  private JSONArray array(JSONObject o, String key) {
    return o == null
      ? new JSONArray()
      : o.optJSONArray(key) == null
        ? new JSONArray()
        : o.optJSONArray(key);
  }

  private JSONObject obj(JSONArray a, int n) {
    JSONObject v = a.optJSONObject(n);
    return v == null ? new JSONObject() : v;
  }

  private boolean parent() {
    return user != null && !user.optString("role").equals("child");
  }

  private boolean admin() {
    return user != null && user.optString("role").equals("admin");
  }

  private String childPath() {
    return "/api/children/" + childId;
  }

  private int subjectIndex(String key) {
    for (int i = 0; i < 5; i++) if (SUBJECTS[i].equals(key)) return i;
    return 4;
  }

  private String subjectName(JSONObject t) {
    return LABELS[subjectIndex(t.optString("subject"))];
  }

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    getWindow().setStatusBarColor(Color.TRANSPARENT);
    getWindow().setNavigationBarColor(Color.TRANSPARENT);
    WindowCompat.getInsetsController(
      getWindow(),
      getWindow().getDecorView()
    ).setAppearanceLightStatusBars(true);
    WindowCompat.getInsetsController(
      getWindow(),
      getWindow().getDecorView()
    ).setAppearanceLightNavigationBars(true);
    setRequestedOrientation(getPreferences(0).getInt("lockedOrientation", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED));
    skin = Skin.from(getPreferences(0).getString("skin", "dino"));
    String base = BuildConfig.API_BASE_URL;
    String test = getIntent().getStringExtra("test_api");
    if (BuildConfig.DEBUG && test != null && test.matches("http://127\\.0\\.0\\.1:[0-9]+")) base =
      test;
    api = new StarApi(this, base);
    if (b != null) {
      date = b.getString("date", date);
      page = b.getString("page", page);
      subject = b.getString("subject", subject);
      if (subject.equals("all")) subject = "chinese";
      taskScrollY = b.getInt("taskScrollY");
      childId = b.getString("childId", "");
    }
    root = new FrameLayout(this);
    FrameLayout stage = new FrameLayout(this);
    stage.addView(root, new FrameLayout.LayoutParams(-1, -1));
    effects = new FrameLayout(this);
    effects.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    stage.addView(effects, new FrameLayout.LayoutParams(-1, -1));
    setContentView(stage);
    ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
      Insets i = insets.getInsets(
        WindowInsetsCompat.Type.systemBars() |
          WindowInsetsCompat.Type.displayCutout() |
          WindowInsetsCompat.Type.ime()
      );
      v.setPadding(i.left, i.top, i.right, i.bottom);
      return insets;
    });
    root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
      int w = root.getWidth() - root.getPaddingLeft() - root.getPaddingRight(),
        h = root.getHeight() - root.getPaddingTop() - root.getPaddingBottom();
      if (w != contentW || h != contentH) {
        boolean widthChanged = w != contentW;
        contentW = w;
        contentH = h;
        if (activeDialog != null) resizeDialog(activeDialog);
        if (widthChanged || !(getCurrentFocus() instanceof EditText)) render();
      }
    });
    render();
    if (api.hasSession()) resumeSession();
  }

  @Override
  protected void onSaveInstanceState(Bundle b) {
    super.onSaveInstanceState(b);
    b.putString("date", date);
    b.putString("page", page);
    b.putString("subject", subject);
    b.putInt("taskScrollY", taskScrollY);
    b.putString("childId", childId);
  }

  @Override
  public void onConfigurationChanged(Configuration c) {
    super.onConfigurationChanged(c);
    if (activeDialog != null) resizeDialog(activeDialog);
    render();
  }

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    handler.removeCallbacks(poll);
    handler.postDelayed(poll, 20000);
    if (user != null && !busy && activeDialog == null) refresh(false);
  }

  @Override
  protected void onPause() {
    super.onPause();
    resumed = false;
    handler.removeCallbacks(poll);
    if (effects != null) effects.removeAllViews();
  }

  @Override
  protected void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    worker.shutdownNow();
    super.onDestroy();
  }

  private void async(Work work, Done done, boolean mutating) {
    if (mutating && busy) return;
    if (mutating) busy = true;
    AlertDialog operationDialog = mutating ? activeDialog : null;
    android.widget.Button action =
      operationDialog == null ? null : operationDialog.getButton(AlertDialog.BUTTON_POSITIVE);
    CharSequence actionTitle = action == null ? null : action.getText();
    if (operationDialog != null) {
      operationDialog.setCancelable(false);
      operationDialog.getWindow().getDecorView().findViewWithTag("dialog-close").setEnabled(false);
    }
    if (action != null) {
      action.setEnabled(false);
      action.setText("处理中…");
    }
    worker.execute(() -> {
      Object value = null;
      Exception failure = null;
      try {
        value = work.run();
      } catch (Exception e) {
        failure = e;
      }
      final Object result = value;
      final Exception err = failure;
      handler.post(() -> {
        if (isDestroyed()) return;
        if (mutating) busy = false;
        if (operationDialog != null && operationDialog.isShowing()) {
          operationDialog.setCancelable(true);
          operationDialog.getWindow().getDecorView().findViewWithTag("dialog-close").setEnabled(true);
        }
        if (action != null) {
          action.setEnabled(true);
          action.setText(actionTitle);
        }
        if (err != null) {
          if (err instanceof StarApi.ApiException && ((StarApi.ApiException) err).status == 401) {
            logoutLocal();
          }
          error = err.getMessage() == null ? "操作未完成，请重试" : err.getMessage();
          loading = false;
          if (activeDialog != null) toast(error);
          else render();
        } else try {
          done.run(result);
        } catch (Exception e) {
          error = "数据读取失败，请重试";
          loading = false;
          render();
        }
      });
    });
  }

  private void resumeSession() {
    loading = true;
    render();
    async(
      () -> api.get("/api/me"),
      r -> {
        user = (JSONObject) r;
        loadInitial();
      },
      false
    );
  }

  private void loadInitial() {
    loading = true;
    render();
    async(
      () -> new Object[] { api.get("/api/children"), api.get("/api/task-templates") },
      r -> {
        Object[] a = (Object[]) r;
        children = (JSONArray) a[0];
        templates = (JSONArray) a[1];
        initialized = true;
        error = "";
        boolean found = false;
        for (int i = 0; i < children.length(); i++) if (
          obj(children, i).optString("id").equals(childId)
        ) found = true;
        if (!found) childId = children.length() > 0 ? obj(children, 0).optString("id") : "";
        loading = false;
        if (admin()) page = "admin";
        refresh(true);
      },
      false
    );
  }

  private void refresh(boolean show) {
    if (user == null) return;
    if (!initialized) {
      loadInitial();
      return;
    }
    if (admin() && page.equals("admin")) {
      async(
        () -> api.get("/api/admin/accounts"),
        r -> {
          accounts = (JSONObject) r;
          loading = false;
          error = "";
          render();
        },
        false
      );
      return;
    }
    if (childId.isEmpty()) {
      loading = false;
      render();
      return;
    }
    if (data != null && date.equals(data.optString("today")) && !date.equals(today())) {
      date = today();
      taskScrollY = 0;
    }
    int v = ++requestVersion;
    String p = childPath() + "/dashboard?date=" + date;
    if (show) {
      loading = true;
      render();
    }
    async(
      () -> api.get(p),
      r -> {
        if (v != requestVersion) return;
        JSONObject previous = data;
        data = (JSONObject) r;
        loading = false;
        error = "";
        if (
          previous != null &&
          !parent() &&
          data.optJSONObject("wallet").optInt("balance") >
            previous.optJSONObject("wallet").optInt("balance")
        ) toast("收到新的星星啦！你的努力被看见了 ★");
        render();
      },
      false
    );
  }

  private void mutation(String path, String method, JSONObject body, boolean idem, String message) {
    async(
      () -> api.call(path, method, body, idem),
      r -> {
        closeDialog();
        boolean starAction =
          path.endsWith("/submit") ||
          (path.startsWith("/api/submissions/") &&
            path.endsWith("/review") &&
            body.optBoolean("approve"));
        if (!starAction || !resumed) toast(message);
        refresh(false);
        if (path.endsWith("/submit")) {
          celebrate(
            parent() ? "星星点亮啦！" : "任务已提交！",
            parent() ? "努力被看见，星星已到账" : "等待家长确认，星星就会点亮"
          );
        } else if (
          path.startsWith("/api/submissions/") &&
          path.endsWith("/review") &&
          body.optBoolean("approve")
        ) {
          celebrate("星星点亮啦！", "努力被看见，星星已到账");
        }
      },
      true
    );
  }

  private void celebrate(String title, String subtitle) {
    if (!resumed || isFinishing()) return;
    effects.removeAllViews();
    SparkleView stars = new SparkleView(this, skin, title, subtitle);
    effects.addView(stars, new FrameLayout.LayoutParams(-1, -1));
    stars.play();
  }

  private void toast(String text) {
    Toast.makeText(this, text, Toast.LENGTH_LONG).show();
  }

  private void closeDialog() {
    if (activeDialog != null) {
      activeDialog.dismiss();
      activeDialog = null;
    }
  }

  private int widthDp() {
    return Math.round(contentW / getResources().getDisplayMetrics().density);
  }

  private int heightDp() {
    return Math.round(contentH / getResources().getDisplayMetrics().density);
  }

  private boolean compact() {
    return contentH > 0 && contentH / getResources().getDisplayMetrics().density < 510;
  }

  private void render() {
    ui = new Ui(this, skin);
    wide = LayoutPolicy.landscape(
      Math.round(contentW / getResources().getDisplayMetrics().density),
      Math.round(contentH / getResources().getDisplayMetrics().density)
    );
    root.setBackgroundColor(skin.background);
    root.removeAllViews();
    if (user == null) {
      login();
      return;
    }
    LinearLayout shell = ui.col();
    ui.pad(shell, compact() ? 8 : wide ? 16 : 10);
    root.addView(shell, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout header = ui.row();
    TextView title = ui.text(
      (skin == Skin.PRINCESS ? "✧  " : "✦  ") +
        (parent() ? (admin() ? "管理中心" : "家长空间") : user.optString("name") + "的星星日"),
      wide ? 23 : 19,
      true
    );
    ui.ellipsis(title, 2);
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    boolean roomyHeader = wide && widthDp() >= 900;
    boolean dailyPage = !admin() && page.equals("today") && data != null;
    if (roomyHeader && dailyPage) header.addView(earnedBadge(), ui.lp(-2, 48));
    else if (roomyHeader && (page.equals("wallet") || page.equals("shop"))) {
      LinearLayout pageTitle = ui.row();
      pageTitle.addView(new NavigationIconView(this, skin, page), ui.lp(40, 40));
      ui.gap(pageTitle, 8);
      pageTitle.addView(ui.text(page.equals("wallet") ? "星星宝库" : "心愿小铺", 22, true));
      header.addView(pageTitle, ui.lp(-2, 48));
    }
    LinearLayout controls = ui.row();
    controls.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
    controls.addView(ui.button("换装", false, this::skins));
    ui.gap(controls, 8);
    controls.addView(ui.button("设置", false, this::settings));
    if (data != null) {
      ui.gap(controls, 8);
      LinearLayout balance = ui.row();
      balance.setPadding(ui.dp(10), 0, ui.dp(14), 0);
      balance.setBackground(ui.bg(0xFFFFF4D6, 24, true));
      balance.addView(new StarView(this, skin), ui.lp(32, 40));
      ui.gap(balance, 5);
      if (wide && widthDp() >= 1000) {
        balance.addView(ui.text("可用星星", 13, false));
        ui.gap(balance, 6);
      }
      balance.addView(ui.text("" + data.optJSONObject("wallet").optInt("balance"), 23, true));
      balance.setContentDescription(
        "可用星星 " + data.optJSONObject("wallet").optInt("balance") + "，打开星星宝库"
      );
      balance.setFocusable(true);
      balance.setOnClickListener(v -> navigate("wallet"));
      controls.addView(balance, ui.lp(-2, 48));
    }
    header.addView(
      controls,
      new LinearLayout.LayoutParams(
        roomyHeader && !admin() ? 0 : -2,
        -2,
        roomyHeader && !admin() ? 1 : 0
      )
    );
    ui.add(shell, header, compact() ? 52 : 56);
    ui.gap(shell, compact() ? 6 : 12);
    if (!error.isEmpty()) {
      TextView err = ui.button(error + " · 重试", false, () -> refresh(true));
      ui.add(shell, err, 48);
      ui.gap(shell, 8);
    }
    LinearLayout body = wide ? ui.row() : ui.col();
    ui.grow(shell, body);
    LinearLayout nav = navigation();
    if (wide) {
      body.addView(nav, ui.lp(96, -1));
      ui.gap(body, 16);
    }
    LinearLayout main = ui.col();
    ui.grow(body, main);
    if (parent() && !admin() && children.length() > 1) {
      String[] names = new String[children.length()];
      int chosen = 0;
      for (int i = 0; i < names.length; i++) {
        names[i] = obj(children, i).optString("name");
        if (obj(children, i).optString("id").equals(childId)) chosen = i;
      }
      Spinner sp = ui.select(main, "小朋友", names, chosen);
      sp.setOnItemSelectedListener(
        new android.widget.AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(android.widget.AdapterView<?> a) {}

          public void onItemSelected(
            android.widget.AdapterView<?> a,
            View v,
            int position,
            long id
          ) {
            String next = obj(children, position).optString("id");
            if (!childId.equals(next)) {
              childId = next;
              data = null;
              taskScrollY = 0;
              refresh(true);
            }
          }
        }
      );
    }
    if (admin() && page.equals("admin")) adminScreen(main);
    else if (loading) {
      ui.grow(main, center("正在寻找今天的星星…"));
    } else if (data == null) {
      ui.grow(main, center(childId.isEmpty() ? "还没有分配小朋友，请联系管理员" : "星星岛准备中…"));
      main.addView(ui.button("刷新", true, () -> refresh(true)));
    } else switch (page) {
      case "wallet":
        wallet(main);
        break;
      case "shop":
        shop(main);
        break;
      case "review":
        reviews(main);
        break;
      case "rules":
        rules(main);
        break;
      default:
        daily(main);
    }
    if (!wide) {
      ui.gap(shell, 8);
      ui.add(shell, nav, 64);
    }
  }

  private TextView center(String value) {
    TextView t = ui.text(value, 18, true);
    t.setGravity(Gravity.CENTER);
    return t;
  }

  private void navigate(String next) {
    page = next;
    error = "";
    render();
    if (next.equals("admin")) refresh(false);
  }

  private LinearLayout navigation() {
    LinearLayout nav = wide ? ui.col() : ui.row();
    String[][] items = admin()
      ? new String[][] { { "admin", "⚙", "管理" } }
      : parent()
        ? new String[][] {
            { "today", "✦", "任务" },
            { "review", "✓", "审核" },
            { "rules", "▤", "规则" },
            { "wallet", "★", "星星" },
            { "shop", "♧", "小铺" },
          }
        : new String[][] {
            { "today", "✦", "今日任务" },
            { "wallet", "★", "星星宝库" },
            { "shop", "♧", "心愿小铺" },
          };
    if (wide) {
      nav.setBackground(ui.bg(skin.surface, 24, true));
      ui.pad(nav, 6);
      if (!compact()) {
        FrameLayout seal = new FrameLayout(this);
        StarView emblem = new StarView(this, skin);
        emblem.setBackground(ui.bg(0xFFFFF2CD, 26, true));
        seal.addView(emblem, new FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), Gravity.CENTER));
        ui.add(nav, seal, 68);
        ui.gap(nav, 10);
      }
    }
    for (String[] n : items) {
      FrameLayout item = new FrameLayout(this);
      item.setSelected(page.equals(n[0]));
      item.setBackground(
        new android.graphics.drawable.RippleDrawable(
          android.content.res.ColorStateList.valueOf(0x22444444),
          ui.bg(page.equals(n[0]) ? skin.soft : Color.TRANSPARENT, 18, page.equals(n[0])),
          null
        )
      );
      LinearLayout content = ui.col();
      content.setGravity(Gravity.CENTER);
      int iconSize = wide ? (parent() || compact() ? 40 : 60) : 28;
      content.addView(new NavigationIconView(this, skin, n[0]), ui.lp(iconSize, iconSize));
      ui.gap(content, wide ? 4 : 2);
      TextView label = ui.text(n[2], wide ? 13 : 12, page.equals(n[0]));
      label.setTextColor(page.equals(n[0]) ? skin.primary : skin.ink);
      label.setGravity(Gravity.CENTER);
      content.addView(label);
      item.addView(content, new FrameLayout.LayoutParams(-1, -1));
      if (wide && page.equals(n[0])) {
        View mark = new View(this);
        mark.setBackground(ui.bg(skin.accent, 2, false));
        FrameLayout.LayoutParams pos = new FrameLayout.LayoutParams(
          ui.dp(3),
          ui.dp(26),
          Gravity.LEFT | Gravity.CENTER_VERTICAL
        );
        pos.leftMargin = ui.dp(2);
        item.addView(mark, pos);
      }
      item.setContentDescription(n[2]);
      item.setFocusable(true);
      item.setOnClickListener(v -> {
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        navigate(n[0]);
      });
      item.setAccessibilityDelegate(
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
      if (wide) {
        ui.add(nav, item, compact() ? 58 : parent() ? 78 : 104);
        ui.gap(nav, compact() ? 4 : 12);
      } else {
        nav.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
        ui.gap(nav, 4);
      }
    }
    return nav;
  }

  private android.text.TextWatcher watch(Pick pick) {
    return new android.text.TextWatcher() {
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

      public void onTextChanged(CharSequence s, int start, int before, int count) {
        pick.set(s.toString());
      }

      public void afterTextChanged(android.text.Editable e) {}
    };
  }

  private void login() {
    LinearLayout outer = wide ? ui.row() : ui.col();
    ui.pad(outer, wide ? 32 : 20);
    root.addView(outer, new FrameLayout.LayoutParams(-1, -1));
    if (wide) {
      outer.addView(new ArtView(this, skin.hero()), new LinearLayout.LayoutParams(0, -1, 1));
      ui.gap(outer, 32);
    }
    LinearLayout form = ui.card();
    ui.line(form, skin.title);
    ui.add(form, ui.text("欢迎回到星星岛", 28, true), -2);
    ui.gap(form, 12);
    ui.line(form, "每一次小小的坚持，都值得一颗星星。");
    if (loading) {
      ui.line(form, "正在恢复登录状态…");
    } else if (api.hasSession()) {
      ui.line(form, error.isEmpty() ? "暂时连接不上服务器，登录信息已保留。" : error);
      form.addView(ui.button("重新连接", true, this::resumeSession));
    } else {
      EditText name = ui.input(form, "账号", loginName, InputType.TYPE_CLASS_TEXT),
        password = ui.input(
          form,
          "密码",
          loginPassword,
          InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );
      name.setContentDescription("账号");
      password.setContentDescription("密码");
      name.addTextChangedListener(watch(value -> loginName = value));
      password.addTextChangedListener(watch(value -> loginPassword = value));
      if (!error.isEmpty()) ui.line(form, error);
      TextView login = ui.button("进入星星岛", true, () -> {
        if (busy) return;
        String u = name.getText().toString().trim(),
          p = password.getText().toString();
        if (u.isEmpty() || p.isEmpty()) {
          toast("请填写账号和密码");
          return;
        }
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(
          password.getWindowToken(),
          0
        );
        async(
          () -> {
            JSONObject r = (JSONObject) api.call(
              "/api/login",
              "POST",
              json("username", u, "password", p),
              false
            );
            api.authenticate(r.getString("token"));
            return r.getJSONObject("user");
          },
          r -> {
            user = (JSONObject) r;
            loginPassword = "";
            error = "";
            loadInitial();
          },
          true
        );
      });
      ui.add(form, login, 52);
      ui.gap(form, 16);
      ui.line(form, "已开启记住登录 · 账号由管理员预先分配");
    }
    ui.gap(form, 12);
    form.addView(ui.button("选择皮肤", false, this::skins));
    ScrollView scroll = ui.scroll(form);
    if (wide) {
      LinearLayout wrap = ui.col();
      wrap.setGravity(Gravity.CENTER);
      wrap.addView(scroll, ui.lp(-1, -2));
      outer.addView(wrap, new LinearLayout.LayoutParams(0, -1, 1));
    } else {
      outer.setGravity(Gravity.CENTER);
      outer.addView(scroll, ui.lp(-1, -2));
    }
  }

  private int sum(JSONArray a, String field) {
    int n = 0;
    for (int i = 0; i < a.length(); i++) n += obj(a, i).optInt(field);
    return n;
  }

  private LinearLayout hero() {
    LinearLayout hero = ui.col();
    hero.setBackground(ui.bg(skin.surface, 24, true));
    ui.pad(hero, 12);
    ui.add(hero, ui.text(skin.title, 23, true), -2);
    ui.gap(hero, 8);
    ui.add(hero, ui.label("✦ " + skin.subtitle + " ✦"), -2);
    ui.gap(hero, 12);
    CompanionView art = new CompanionView(this, skin);
    ui.grow(hero, art);
    ui.gap(hero, 10);
    ui.add(hero, new JourneyView(this, skin), 148);
    return hero;
  }

  private LinearLayout earnedBadge() {
    int earned = data.optInt("dayEarned");
    LinearLayout badge = ui.row();
    badge.setTag("daily-earned");
    badge.setGravity(Gravity.CENTER);
    badge.setPadding(ui.dp(12), 0, ui.dp(16), 0);
    badge.setBackground(ui.bg(0xFFFFEBC0, 24, true));
    badge.addView(new StarView(this, skin), ui.lp(34, 42));
    ui.gap(badge, 7);
    badge.addView(
      ui.text(date.equals(data.optString("today")) ? "今日已获得" : "当日已获得", 14, false)
    );
    ui.gap(badge, 8);
    badge.addView(ui.text(earned + "", 27, true));
    ui.gap(badge, 5);
    badge.addView(ui.text("星", 14, false));
    return badge;
  }

  private void daily(LinearLayout main) {
    LinearLayout layout = wide ? ui.row() : ui.col();
    ui.grow(main, layout);
    if (wide && heightDp() > 520) {
      layout.addView(hero(), new LinearLayout.LayoutParams(0, -1, .32f));
      ui.gap(layout, 16);
    }
    LinearLayout tasks = ui.col();
    tasks.setTag("daily-panel");
    layout.addView(
      tasks,
      new LinearLayout.LayoutParams(wide ? 0 : -1, wide ? -1 : 0, wide ? .68f : 1)
    );
    if (!wide || widthDp() < 900) {
      ui.add(tasks, earnedBadge(), 48);
      ui.gap(tasks, 8);
    }
    dateStrip(tasks);
    subjectStrip(tasks);
    ui.gap(tasks, 6);
    JSONArray selected = new JSONArray();
    for (int i = 0; i < array(data, "tasks").length(); i++) {
      JSONObject task = obj(array(data, "tasks"), i);
      if (task.optString("subject", "other").equals(subject)) selected.put(
        task
      );
    }
    ScrollView scroll = new ScrollView(this);
    taskScroller = scroll;
    scroll.setTag("task-scroll");
    scroll.setFillViewport(true);
    scroll.setVerticalScrollBarEnabled(true);
    scroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
    scroll.setClipToPadding(false);
    scroll.setPadding(0, 0, ui.dp(5), 0);
    LinearLayout grid = ui.col();
    grid.setTag("task-grid");
    int cols = wide || widthDp() >= 600 ? 2 : 1;
    int cardHeight = Math.round(190 * Math.max(1, getResources().getConfiguration().fontScale));
    if (selected.length() == 0) ui.add(grid, center("这个科目还没有安排任务"), 180);
    for (int start = 0; start < selected.length(); start += cols) {
      LinearLayout row = ui.row();
      for (int c = 0; c < cols; c++) {
        if (start + c < selected.length()) row.addView(
          taskCard(obj(selected, start + c)),
          new LinearLayout.LayoutParams(0, -1, 1)
        );
        else row.addView(new View(this), new LinearLayout.LayoutParams(0, -1, 1));
        if (c < cols - 1) ui.gap(row, 12);
      }
      ui.add(grid, row, cardHeight);
      if (start + cols < selected.length()) ui.gap(grid, 12);
    }
    scroll.addView(grid, new ScrollView.LayoutParams(-1, -2));
    ui.grow(tasks, scroll);
    final int restoreY = taskScrollY;
    scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
      if (taskScroller == scroll) taskScrollY = y;
    });
    scroll.post(() -> {
      if (taskScroller == scroll) scroll.scrollTo(0, restoreY);
    });
  }

  private void dateStrip(LinearLayout box) {
    LinearLayout row = ui.row();
    row.setTag("date-strip");
    if (wide && !compact()) {
      TextView heading = ui.text("今日任务", 19, true);
      row.addView(heading);
      ui.gap(row, 14);
    }

    row.addView(
      ui.button("‹", false, () -> {
        date = shift(date, -1);
        taskScrollY = 0;
        refresh(true);
      })
    );
    ui.gap(row, 8);
    TextView d = ui.button(date + (date.equals(today()) ? " · 今天" : ""), false, () ->
      chooseDate(date, next -> {
        date = next;
        taskScrollY = 0;
        refresh(true);
      })
    );
    row.addView(d, new LinearLayout.LayoutParams(0, ui.dp(48), 1));
    ui.gap(row, 8);
    row.addView(
      ui.button("›", false, () -> {
        date = shift(date, 1);
        taskScrollY = 0;
        refresh(true);
      })
    );
    ui.gap(row, 8);
    row.addView(
      ui.button("今天", false, () -> {
        date = today();
        taskScrollY = 0;
        refresh(true);
      })
    );
    if (parent()) {
      ui.gap(row, 8);
      row.addView(ui.button("＋", true, () -> ruleForm(null, false)));
    }
    ui.add(box, row, compact() ? 48 : 56);
  }

  private interface Pick {
    void set(String value);
  }

  private void chooseDate(String initial, Pick pick) {
    String[] p = initial.split("-");
    new DatePickerDialog(
      this,
      (v, y, m, d) -> pick.set(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)),
      Integer.parseInt(p[0]),
      Integer.parseInt(p[1]) - 1,
      Integer.parseInt(p[2])
    ).show();
  }

  private void subjectStrip(LinearLayout box) {
    LinearLayout row = ui.row();
    row.setTag("subject-strip");
    String[] keys = SUBJECTS, labels = LABELS;
    for (int i = 0; i < keys.length; i++) {
      String key = keys[i];
      TextView b = ui.button(labels[i], subject.equals(key), () -> {
        subject = key;
        taskScrollY = 0;
        render();
      });
      b.setTextSize(14);
      b.setPadding(0, ui.dp(6), 0, ui.dp(6));
      row.addView(b, new LinearLayout.LayoutParams(0, ui.dp(48), 1));
      if (i < keys.length - 1) ui.gap(row, 5);
    }
    ui.add(box, row, 54);
  }

  private LinearLayout taskCard(JSONObject t) {
    LinearLayout card = ui.card();
    ui.pad(card, compact() ? 12 : 16);
    LinearLayout top = ui.row();
    int h = heightDp();
    ArtView image = new ArtView(this, "subjects/" + t.optString("subject", "other") + ".webp");
    top.addView(image, ui.lp(h < 510 ? 48 : h < 560 ? 62 : 82, h < 510 ? 48 : h < 560 ? 62 : 82));
    ui.gap(top, 12);
    LinearLayout copy = ui.col();

    TextView title = ui.text(t.optString("title"), 18, true);
    ui.ellipsis(title, compact() ? 1 : 2);
    title.setOnClickListener(v -> taskDetail(t));
    ui.add(copy, title, -2);
    ui.gap(copy, 10);
    ui.add(copy, ui.label(subjectName(t) + " · 每次 " + t.optInt("stars") + " 星"), -2);
    ui.gap(copy, 8);
    ui.add(copy, ui.label("每天最多 " + t.optInt("daily_limit") + " 次 · 家长可调整发放"), -2);
    top.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
    ui.grow(card, top);
    ui.gap(card, compact() ? 4 : 8);
    LinearLayout bottom = ui.row();
    int approved = t.optInt("approved"),
      pending = t.optInt("pending"),
      limit = t.optInt("daily_limit");
    StarView progress = new StarView(this, skin, 1, approved, pending, limit);
    bottom.addView(progress, new LinearLayout.LayoutParams(0, ui.dp(48), 1));
    ui.gap(bottom, 6);
    boolean available =
      approved + pending < limit &&
      date.compareTo(data.optString("today")) <= 0 &&
      (parent() || date.equals(data.optString("today")));
    TextView done = ui.button(
      approved >= limit
        ? "完成啦 ✓"
        : pending + approved >= limit
          ? "等待确认"
          : parent()
            ? "代为完成"
            : "我完成啦",
      available,
      () -> taskDetail(t)
    );
    done.setEnabled(!busy);
    done.setOnTouchListener((v, event) -> {
      if (SparkleView.motionEnabled()) {
        boolean down = event.getActionMasked() == MotionEvent.ACTION_DOWN;
        if (
          down ||
          event.getActionMasked() == MotionEvent.ACTION_UP ||
          event.getActionMasked() == MotionEvent.ACTION_CANCEL
        ) {
          v.animate()
            .scaleX(down ? .93f : 1)
            .scaleY(down ? .93f : 1)
            .setDuration(down ? 90 : 220)
            .setInterpolator(new android.view.animation.OvershootInterpolator(2));
        }
      }
      return false;
    });
    done.setTag("task-action");
    bottom.addView(done);
    ui.add(card, bottom, 48);
    card.setOnClickListener(v -> taskDetail(t));
    return card;
  }

  private AlertDialog dialog(String title, LinearLayout content, String action, Runnable onAction) {
    closeDialog();
    ScrollView scroll = ui.scroll(content);
    LinearLayout heading = ui.row();
    heading.setGravity(Gravity.TOP);
    ui.pad(heading, 16);
    TextView titleView = ui.text(title, 21, true);
    titleView.setPadding(0, ui.dp(12), ui.dp(12), 0);
    heading.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));
    TextView close = ui.button("×", false, () -> { if (!busy) closeDialog(); });
    close.setTextSize(26);
    close.setContentDescription("关闭");
    close.setTag("dialog-close");
    heading.addView(close, ui.lp(48, 48));
    AlertDialog.Builder b = new AlertDialog.Builder(this)
      .setCustomTitle(heading)
      .setView(scroll);
    if (action != null) b.setPositiveButton(action, null);
    AlertDialog d = b.create();
    activeDialog = d;
    d.setOnDismissListener(v -> {
      if (activeDialog == d) activeDialog = null;
    });
    d.setOnShowListener(v -> {
      resizeDialog(d);
      if (action != null) {
        android.widget.Button confirm = d.getButton(AlertDialog.BUTTON_POSITIVE);
        TextView style = ui.button(action, true, () -> {});
        confirm.setBackground(style.getBackground());
        confirm.setTextColor(style.getTextColors());
        confirm.setTypeface(style.getTypeface());
        confirm.setTextSize(16);
        confirm.setAllCaps(false);
        confirm.setMinHeight(ui.dp(52));
        confirm.setPadding(ui.dp(24), ui.dp(12), ui.dp(24), ui.dp(12));
        confirm.setOnClickListener(x -> {
          if (!busy) onAction.run();
        });
      }
    });
    d.show();
    return d;
  }

  private void resizeDialog(AlertDialog d) {
    if (d.getWindow() == null) return;
    d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    d.getWindow().setLayout(
      Math.min(ui.dp(560), Math.max(ui.dp(280), contentW - ui.dp(32))),
      WindowManager.LayoutParams.WRAP_CONTENT
    );
  }

  private LinearLayout form() {
    LinearLayout f = ui.col();
    ui.pad(f, 22);
    return f;
  }

  private void taskDetail(JSONObject t) {
    LinearLayout f = form();
    if (t.optInt("approved") + t.optInt("pending") < t.optInt("daily_limit")) {
      LinearLayout intro = ui.row();
      ui.pad(intro, 12);
      intro.setBackground(ui.bg(skin.soft, 20, false));
      StarView star = new StarView(this, skin);
      intro.addView(star, ui.lp(70, 78));
      ui.gap(intro, 12);
      LinearLayout message = ui.col();
      ui.add(message, ui.text("又完成一次挑战！", 20, true), -2);
      ui.gap(message, 8);
      ui.add(
        message,
        ui.text(parent() ? "确认后，为孩子点亮星星" : "提交后，等待家长确认", 14, false),
        -2
      );
      intro.addView(message, new LinearLayout.LayoutParams(0, -2, 1));
      ui.add(f, intro, -2);
      ui.gap(f, 18);
      star.addOnAttachStateChangeListener(
        new View.OnAttachStateChangeListener() {
          public void onViewAttachedToWindow(View v) {
            if (!SparkleView.motionEnabled()) return;
            v.setScaleX(.45f);
            v.setScaleY(.45f);
            v.setRotation(-22);
            v.animate()
              .scaleX(1)
              .scaleY(1)
              .rotation(0)
              .setDuration(620)
              .setInterpolator(new android.view.animation.OvershootInterpolator(3));
          }

          public void onViewDetachedFromWindow(View v) {
            v.animate().cancel();
          }
        }
      );
    }
    ui.line(
      f,
      subjectName(t) + " · " + t.optString("date") + " · 规则 v" + t.optInt("rule_version")
    );
    ui.line(f, t.optString("description"));
    ui.line(
      f,
      "每次 " +
        t.optInt("stars") +
        " 星，每天最多 " +
        t.optInt("daily_limit") +
        " 次。已通过 " +
        t.optInt("approved") +
        " 次，待确认 " +
        t.optInt("pending") +
        " 次。"
    );
    EditText[] award = parent() ? awardFields(f, t.optInt("stars"), t.optInt("daily_limit") - t.optInt("approved") - t.optInt("pending")) : null;
    ui.line(f, "家长可调整本次每次星星和完成次数");
    EditText note = parent() ? ui.input(
      f,
      "想告诉家长的话（选填）",
      "",
      InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
    ) : null;
    for (int i = 0; i < array(t, "submissions").length(); i++) {
      JSONObject s = obj(array(t, "submissions"), i);
      ui.line(
        f,
        status(s.optString("status")) +
          " · " +
          s.optString("note") +
          " " +
          s.optString("review_note")
      );
    }
    if (parent() && array(t, "submissions").length() == 0 && date.compareTo(today()) >= 0) {
      f.addView(ui.button("编辑这一天的规则", false, () -> ruleForm(t, true)));
    }
    boolean can =
      t.optInt("approved") + t.optInt("pending") < t.optInt("daily_limit") &&
      date.compareTo(data.optString("today")) <= 0 &&
      (parent() || date.equals(data.optString("today")));
    dialog(
      t.optString("title"),
      f,
      can ? (parent() ? "完成并发星星" : "提交给家长确认") : null,
      () ->
        mutation(
          "/api/tasks/" + t.optString("id") + "/submit",
          "POST",
          awardBody(json("note", note == null ? "" : note.getText().toString()), award),
          true,
          parent() ? "已发放星星 ★" : "已提交！等家长确认后就能收到星星啦"
        )
    );
  }

  private String status(String s) {
    return s.equals("approved") ? "已通过" : s.equals("rejected") ? "已退回" : "等待确认";
  }

  private void skins() {
    LinearLayout f = form();
    ui.line(f, "选一个喜欢的世界，星星和任务会一起保留。");
    for (Skin choice : Skin.values()) {
      LinearLayout row = ui.row();
      row.addView(new ArtView(this, choice.hero()), ui.lp(130, 160));
      ui.gap(row, 18);
      LinearLayout c = ui.col();
      ui.line(c, choice.label);
      c.addView(
        ui.button(choice == skin ? "正在使用" : "穿上这套皮肤", choice == skin, () -> {
          skin = choice;
          getPreferences(0).edit().putString("skin", choice.key).apply();
          closeDialog();
          render();
          toast("欢迎来到" + choice.label + "！");
        })
      );
      row.addView(c, new LinearLayout.LayoutParams(0, -2, 1));
      ui.add(f, row, 170);
      ui.gap(f, 12);
    }
    dialog("我的换装间", f, null, null);
  }

  private void settings() {
    LinearLayout f = form();
    ui.line(f, user.optString("name") + " · " + user.optString("username"));
    ui.line(f, "登录状态已安全保存，关闭应用或重启平板后会自动进入。");
    androidx.appcompat.widget.SwitchCompat orientationLock = new androidx.appcompat.widget.SwitchCompat(this);
    orientationLock.setText("锁定当前方向");
    orientationLock.setTextColor(skin.ink);
    orientationLock.setMinHeight(ui.dp(48));
    orientationLock.setChecked(getPreferences(0).getInt("lockedOrientation", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    orientationLock.setOnCheckedChangeListener((button, checked) -> {
      int orientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
      if (checked) {
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        boolean reverse = rotation == Surface.ROTATION_180 ||
          rotation == (landscape ? Surface.ROTATION_270 : Surface.ROTATION_90);
        orientation = landscape
          ? (reverse ? ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
          : (reverse ? ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
      }
      getPreferences(0).edit().putInt("lockedOrientation", orientation).apply();
      setRequestedOrientation(orientation);
    });
    f.addView(orientationLock);
    ui.line(f, "开启后保持当前横屏或竖屏，关闭后跟随系统旋转设置。");
    f.addView(ui.button("更换皮肤", false, this::skins));
    ui.gap(f, 12);
    f.addView(
      ui.button("修改密码", false, () -> {
        LinearLayout p = form();
        EditText old = ui.input(p, "当前密码", "", 129),
          next = ui.input(p, "新密码（至少 8 位）", "", 129);
        dialog("修改密码", p, "保存", () -> {
          if (next.length() < 8) {
            toast("新密码至少 8 位");
            return;
          }
          async(
            () ->
              api.call(
                "/api/password",
                "POST",
                json("current", old.getText().toString(), "password", next.getText().toString()),
                false
              ),
            r -> {
              logoutLocal();
              toast("密码已修改，请重新登录");
            },
            true
          );
        });
      })
    );
    ui.gap(f, 12);
    f.addView(
      ui.button("刷新数据", false, () -> {
        closeDialog();
        refresh(true);
      })
    );
    ui.gap(f, 12);
    f.addView(
      ui.button("退出并切换账号", false, () -> {
        LinearLayout c = form();
        ui.line(c, "退出后需要重新输入账号和密码。");
        dialog("切换账号", c, "退出登录", () ->
          async(() -> api.call("/api/logout", "POST", json(), false), r -> logoutLocal(), true)
        );
      })
    );
    dialog("账号设置", f, null, null);
  }

  private void logoutLocal() {
    api.forget();
    requestVersion++;
    user = null;
    initialized = false;
    data = null;
    accounts = null;
    children = new JSONArray();
    templates = new JSONArray();
    regionOffsets.clear();
    childId = "";
    date = today();
    page = "today";
    subject = "chinese";
    taskScrollY = 0;
    error = "";
    closeDialog();
    render();
  }

  private void title(LinearLayout main, String title, Runnable add) {
    LinearLayout h = ui.row();
    h.addView(ui.text(title, 25, true), new LinearLayout.LayoutParams(0, -2, 1));
    if (add != null) h.addView(ui.button("＋ 添加", true, add));
    ui.add(main, h, 58);
    ui.gap(main, 10);
  }

  private ScrollView region(LinearLayout contents, String kind) {
    ScrollView scroll = ui.scroll(contents);
    scroll.setTag(kind + "-scroll");
    scroll.setFillViewport(true);
    scroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
    String key = kind + ":" + childId + (kind.equals("shop") ? ":" + rewardFilter : "");
    int y = regionOffsets.getOrDefault(key, 0);
    scroll.setOnScrollChangeListener((v, x, now, oldX, oldY) -> {
      if (v.isAttachedToWindow()) regionOffsets.put(key, now);
    });
    scroll.post(() -> {
      if (scroll.isAttachedToWindow()) scroll.scrollTo(0, y);
    });
    return scroll;
  }

  private void wallet(LinearLayout main) {
    if (parent()) title(main, "我的星星宝库", this::entryForm);
    JSONObject w = data.optJSONObject("wallet");
    LinearLayout content = wide ? ui.row() : ui.col();
    ui.grow(main, content);
    LinearLayout treasury = ui.col();
    LinearLayout balance = ui.card();
    balance.setBackground(ui.bg(0xFFFFF4DF, 24, true));
    LinearLayout hero = ui.row();
    hero.addView(
      new NavigationIconView(this, skin, "wallet"),
      ui.lp(wide ? 108 : 66, wide ? 108 : 66)
    );
    ui.gap(hero, 14);
    LinearLayout amount = ui.col();
    ui.add(amount, ui.label("可用星星"), -2);
    ui.gap(amount, 8);
    ui.add(amount, ui.text("" + w.optInt("balance"), wide ? 44 : 32, true), -2);
    hero.addView(amount, new LinearLayout.LayoutParams(0, -2, 1));
    ui.add(balance, hero, wide ? 108 : 66);
    ui.gap(balance, 12);
    LinearLayout totals = ui.row();
    for (String[] field : new String[][] { { "累计获得", "earned" }, { "累计使用", "spent" } }) {
      LinearLayout stat = ui.col();
      stat.setGravity(Gravity.CENTER);
      ui.add(stat, centerSmall(field[0]), -2);
      ui.gap(stat, 5);
      TextView value = ui.text("" + w.optInt(field[1]), 24, true);
      value.setGravity(Gravity.CENTER);
      ui.add(stat, value, -2);
      totals.addView(stat, new LinearLayout.LayoutParams(0, -2, 1));
    }
    ui.add(balance, totals, 54);
    ui.gap(balance, 14);
    ui.add(balance, ui.button("去心愿小铺", true, () -> navigate("shop")), 48);
    ui.add(treasury, balance, -2);
    if (wide) {
      ui.gap(treasury, 12);
      LinearLayout chart = ui.card();
      LinearLayout heading = ui.row();
      heading.addView(ui.text("最近七天", 18, true), new LinearLayout.LayoutParams(0, -2, 1));
      TextView income = ui.text("● 获得", 11, false);
      income.setTextColor(0xFF6F9C50);
      TextView spending = ui.text("● 使用", 11, false);
      spending.setTextColor(0xFFD97D97);
      heading.addView(income);
      ui.gap(heading, 8);
      heading.addView(spending);
      ui.add(chart, heading, 28);
      ui.grow(chart, new WeekChartView(this, skin, array(data, "week")));
      ui.grow(treasury, chart);
      content.addView(treasury, new LinearLayout.LayoutParams(0, -1, .34f));
      ui.gap(content, 16);
    } else {
      ui.add(content, treasury, -2);
      ui.gap(content, 12);
    }
    LinearLayout list = ui.card();
    ui.add(list, ui.text("星星收支记录", 20, true), -2);
    ui.gap(list, 12);
    LinearLayout rows = ui.col();
    JSONArray ledger = array(data, "ledger");
    if (ledger.length() == 0) ui.line(rows, "还没有星星记录，从今天的小任务开始吧。");
    for (int i = 0; i < ledger.length(); i++) {
      JSONObject e = obj(ledger, i);
      LinearLayout row = ui.col();
      ui.pad(row, 10);
      LinearLayout entry = ui.row();
      StarView icon = new StarView(this, skin);
      entry.addView(icon, ui.lp(32, 40));
      ui.gap(entry, 12);
      LinearLayout copy = ui.col();
      TextView entryTitle = ui.text(e.optString("title"), 17, true);
      ui.ellipsis(entryTitle, 2);
      ui.add(copy, entryTitle, -2);
      ui.gap(copy, 8);
      TextView detail = ui.label(e.optString("date") + "  " + e.optString("note"));
      ui.ellipsis(detail, 2);
      ui.add(copy, detail, -2);
      entry.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
      ui.gap(entry, 16);
      TextView delta = ui.text((e.optInt("amount") > 0 ? "+" : "") + e.optInt("amount"), 24, true);
      delta.setTextColor(e.optInt("amount") > 0 ? 0xFF528047 : 0xFFC75D7E);
      entry.addView(delta);
      ui.add(row, entry, -2);
      ui.gap(row, 12);
      if (parent() && e.isNull("reversed_by") && !e.optString("kind").equals("reversal")) {
        row.addView(
          ui.button("撤销这笔记录", false, () -> {
            LinearLayout f = form();
            ui.line(f, e.optString("title") + " · " + e.optInt("amount") + " 星");
            EditText note = ui.input(f, "撤销原因", "", 1);
            dialog("撤销记录", f, "确认撤销", () -> {
              if (note.length() == 0) {
                toast("请填写撤销原因");
                return;
              }
              mutation(
                "/api/ledger/" + e.optString("id") + "/reverse",
                "POST",
                json("note", note.getText().toString()),
                false,
                "已撤销记录"
              );
            });
          })
        );
      }
      ui.add(rows, row, -2);
      View divider = new View(this);
      divider.setBackgroundColor(skin.border);
      ui.add(rows, divider, 1);
      ui.gap(rows, 8);
    }
    ui.grow(list, region(rows, "ledger"));
    content.addView(
      list,
      new LinearLayout.LayoutParams(wide ? 0 : -1, wide ? -1 : 0, wide ? .65f : 1)
    );
  }

  private TextView centerSmall(String label) {
    TextView text = ui.label(label);
    text.setGravity(Gravity.CENTER);
    return text;
  }

  private void entryForm() {
    LinearLayout f = form();
    Spinner kind = ui.select(f, "记录类型", new String[] { "消费星星", "额外奖励", "扣除星星" }, 0);
    EditText title = ui.input(f, "原因", "", 1),
      amount = ui.input(f, "星星数量", "1", 2),
      note = ui.input(
        f,
        "补充说明",
        "",
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
      );
    dialog("记一笔星星", f, "保存", () -> {
      int n = number(amount, 1, 100000);
      if (n < 0 || title.length() == 0) {
        toast("请填写原因和有效星星数");
        return;
      }
      mutation(
        childPath() + "/ledger",
        "POST",
        json(
          "kind",
          new String[] { "spend", "bonus", "deduction" }[kind.getSelectedItemPosition()],
          "title",
          title.getText().toString(),
          "amount",
          n,
          "note",
          note.getText().toString()
        ),
        true,
        "星星记录已保存"
      );
    });
  }

  private int number(EditText e, int min, int max) {
    try {
      int n = Integer.parseInt(e.getText().toString());
      if (n >= min && n <= max) return n;
    } catch (Exception ignored) {}
    toast("请输入 " + min + " 到 " + max + " 的整数");
    return -1;
  }

  private void shop(LinearLayout main) {
    if (parent()) title(main, "奖励小铺管理", () -> rewardForm(null));
    LinearLayout top = ui.row();
    LinearLayout intro = ui.col();
    ui.add(intro, ui.text("让努力，变成小心愿", 20, true), -2);
    ui.gap(intro, 6);
    ui.add(intro, ui.label("家长确认兑换后扣除星星"), -2);
    top.addView(intro, new LinearLayout.LayoutParams(0, -2, 1));
    if (parent()) {
      top.addView(ui.button("审核兑换", false, () -> navigate("review")));
      ui.gap(top, 8);
    }
    top.addView(
      ui.button("兑换记录", false, () -> {
        LinearLayout f = form();
        JSONArray a = array(data, "redemptions");
        if (a.length() == 0) ui.line(f, "还没有兑换记录");
        for (int i = 0; i < a.length(); i++) {
          JSONObject r = obj(a, i);
          ui.line(
            f,
            r.optString("title") +
              " · " +
              r.optInt("cost") +
              " 星\n" +
              status(r.optString("status")) +
              " " +
              r.optString("review_note")
          );
        }
        dialog("我的兑换记录", f, null, null);
      })
    );
    ui.add(main, top, 64);
    ui.gap(main, 10);
    if (parent()) {
      LinearLayout filters = ui.row();
      String[] keys = { "all", "active", "inactive" },
        labels = { "全部奖励", "已上架", "已下架" };
      for (int i = 0; i < 3; i++) {
        String key = keys[i];
        filters.addView(
          ui.button(labels[i], rewardFilter.equals(key), () -> {
            rewardFilter = key;
            regionOffsets.remove("shop:" + childId + ":" + key);
            render();
          }),
          new LinearLayout.LayoutParams(0, ui.dp(48), 1)
        );
        if (i < 2) ui.gap(filters, 8);
      }
      ui.add(main, filters, 54);
      ui.gap(main, 6);
    }
    JSONArray rewards = new JSONArray();
    for (int i = 0; i < array(data, "rewards").length(); i++) {
      JSONObject r = obj(array(data, "rewards"), i);
      if (
        !parent() ||
        rewardFilter.equals("all") ||
        (rewardFilter.equals("active") ? r.optInt("active") == 1 : r.optInt("active") == 0)
      ) rewards.put(r);
    }
    int cols = wide && widthDp() >= 1000 ? 3 : wide ? 2 : 1;
    LinearLayout grid = ui.col();
    ui.grow(main, region(grid, "shop"));
    if (rewards.length() == 0) ui.add(
      grid,
      center(
        parent() ? "当前状态下没有奖励，可以添加或切换筛选" : "心愿小铺准备中，家长可以添加奖励"
      ),
      180
    );
    for (int start = 0; start < rewards.length(); start += cols) {
      LinearLayout line = ui.row();
      ui.add(
        grid,
        line,
        Math.round(
          (parent() ? 368 : 320) * Math.max(1, getResources().getConfiguration().fontScale)
        )
      );
      for (int col = 0; col < cols; col++) {
        int index = start + col;
        if (index >= rewards.length()) {
          line.addView(new View(this), new LinearLayout.LayoutParams(0, -1, 1));
          if (col < cols - 1) ui.gap(line, 12);
          continue;
        }
        JSONObject r = obj(rewards, index);
        LinearLayout card = ui.card();
        ui.pad(card, 12);
        int artIndex = Arrays.asList("icecream", "toy", "movie", "picnic", "gift").indexOf(
          r.optString("icon")
        );
        ui.add(
          card,
          new ArtView(this, "ui/rewards-atlas.png", 3, 2, artIndex < 0 ? 4 : artIndex),
          160
        );
        ui.gap(card, 10);
        TextView rewardTitle = ui.text(r.optString("title"), 20, true);
        ui.ellipsis(rewardTitle, 1);
        ui.add(card, rewardTitle, -2);
        ui.gap(card, 6);
        if (parent()) {
          ui.add(card, ui.label(r.optInt("active") == 1 ? "已上架" : "已下架"), -2);
          ui.gap(card, 5);
        }
        TextView desc = ui.label(r.optString("description"));
        ui.ellipsis(desc, 2);
        ui.grow(card, desc);
        LinearLayout price = ui.row();
        price.addView(new StarView(this, skin), ui.lp(30, 36));
        ui.gap(price, 6);
        price.addView(ui.text("" + r.optInt("cost"), 23, true));
        ui.gap(card, 8);
        TextView b = ui.button(parent() ? "编辑心愿" : "我想兑换", true, () -> {
          if (parent()) rewardForm(r);
          else {
            LinearLayout f = form();
            ui.line(
              f,
              "想用 " +
                r.optInt("cost") +
                " 颗星星兑换「" +
                r.optString("title") +
                "」吗？家长确认后才会扣星。"
            );
            dialog("许下小心愿", f, "提交兑换申请", () ->
              mutation(
                childPath() + "/redemptions",
                "POST",
                json("reward_id", r.optString("id")),
                true,
                "心愿已送达，等待家长确认"
              )
            );
          }
        });
        if (!parent() && data.optJSONObject("wallet").optInt("balance") < r.optInt("cost")) {
          b.setText(
            "还差 " + (r.optInt("cost") - data.optJSONObject("wallet").optInt("balance")) + " 星"
          );
          b.setEnabled(false);
          b.setAlpha(.55f);
        }
        if (parent()) {
          ui.add(card, price, 36);
          ui.gap(card, 6);
          LinearLayout actions = ui.row();
          actions.addView(b, new LinearLayout.LayoutParams(0, ui.dp(48), 1));
          ui.gap(actions, 8);
          actions.addView(
            ui.button(r.optInt("active") == 1 ? "下架" : "重新上架", false, () ->
              mutation(
                "/api/rewards/" + r.optString("id"),
                "PATCH",
                json(
                  "title",
                  r.optString("title"),
                  "description",
                  r.optString("description"),
                  "icon",
                  r.optString("icon"),
                  "cost",
                  r.optInt("cost"),
                  "active",
                  r.optInt("active") != 1
                ),
                false,
                r.optInt("active") == 1 ? "奖励已下架" : "奖励已上架"
              )
            ),
            new LinearLayout.LayoutParams(0, ui.dp(48), 1)
          );
          ui.add(card, actions, 48);
        } else {
          LinearLayout action = ui.row();
          action.addView(price, new LinearLayout.LayoutParams(0, -2, 1));
          action.addView(b);
          ui.add(card, action, 48);
        }
        line.addView(card, new LinearLayout.LayoutParams(0, -1, 1));
        if (col < cols - 1) ui.gap(line, 12);
      }
      if (start + cols < rewards.length()) ui.gap(grid, 12);
    }
  }

  private void rewardForm(JSONObject r) {
    LinearLayout f = form();
    EditText title = ui.input(f, "心愿名称", r == null ? "" : r.optString("title"), 1),
      desc = ui.input(
        f,
        "说明",
        r == null ? "" : r.optString("description"),
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
      ),
      cost = ui.input(f, "需要星星", r == null ? "5" : r.optString("cost"), 2);
    String[] icons = { "icecream", "toy", "movie", "picnic", "gift" };
    Spinner icon = ui.select(
      f,
      "图标",
      new String[] { "冰淇淋", "玩具", "电影", "野餐", "礼物" },
      r == null ? 4 : Math.max(0, Arrays.asList(icons).indexOf(r.optString("icon")))
    );
    CheckBox active = new CheckBox(this);
    active.setText("上架这个心愿");
    active.setChecked(r == null || r.optInt("active") == 1);
    f.addView(active);
    dialog(r == null ? "添加心愿" : "编辑心愿", f, "保存", () -> {
      int n = number(cost, 1, 100000);
      if (n < 0 || title.length() == 0) return;
      mutation(
        r == null ? childPath() + "/rewards" : "/api/rewards/" + r.optString("id"),
        r == null ? "POST" : "PATCH",
        json(
          "title",
          title.getText().toString(),
          "description",
          desc.getText().toString(),
          "cost",
          n,
          "icon",
          icons[icon.getSelectedItemPosition()],
          "active",
          active.isChecked()
        ),
        false,
        "心愿已保存"
      );
    });
  }

  private EditText[] awardFields(LinearLayout f, int stars, int limit) {
    EditText unit = ui.input(f, "每次发放星星（1–101）", String.valueOf(stars), 2);
    EditText quantity = ui.input(f, "本次完成次数（最多 " + limit + " 次，含待确认上限）", "1", 2);
    TextView total = ui.text("合计发放 " + stars + " 颗星星", 16, true);
    ui.add(f, total, -2);
    android.text.TextWatcher watcher = new android.text.TextWatcher() {
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      public void onTextChanged(CharSequence s, int start, int before, int count) {
        int u = number(unit, 1, 101), q = number(quantity, 1, limit);
        total.setText(u < 1 || q < 1 ? "请填写有效星星和次数" : "合计发放 " + u * q + " 颗星星");
      }
      public void afterTextChanged(android.text.Editable e) {}
    };
    unit.addTextChangedListener(watcher); quantity.addTextChangedListener(watcher);
    return new EditText[] {unit, quantity};
  }

  private JSONObject awardBody(JSONObject body, EditText[] award) {
    if (award != null) try {
      body.put("unit_stars", number(award[0], 1, 101));
      body.put("quantity", number(award[1], 1, 20));
    } catch (Exception e) { throw new IllegalArgumentException(e); }
    return body;
  }

  private void reviews(LinearLayout main) {
    title(main, "看见孩子的每一次努力", null);
    LinearLayout list = ui.col();
    int n = 0;
    for (String kind : new String[] { "reviews", "redemptions" }) {
      JSONArray a = array(data, kind);
      for (int i = 0; i < a.length(); i++) {
        JSONObject r = obj(a, i);
        if (kind.equals("redemptions") && !r.optString("status").equals("pending")) continue;
        n++;
        boolean task = kind.equals("reviews");
        LinearLayout c = ui.card();
        ui.line(c, (task ? "任务 · " : "心愿 · ") + r.optString("title"));
        ui.line(
          c,
          (task ? "获得 " + r.optInt("stars") : "消耗 " + r.optInt("cost")) +
            " 星  " +
            r.optString("date")
        );
        ui.line(c, r.optString("note", r.optString("description")));
        LinearLayout actions = ui.row();
        for (boolean approve : new boolean[] { false, true }) {
          actions.addView(
            ui.button(approve ? "通过" : "退回", approve, () -> {
              LinearLayout f = form();
              ui.line(f, r.optString("title"));
              EditText note = ui.input(
                f,
                "给孩子的话（选填）",
                "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
              );
              EditText[] award = task && approve ? awardFields(f, r.optInt("stars"), r.optInt("remaining", 1)) : null;
              dialog(approve ? "确认通过" : "退回申请", f, approve ? "通过" : "退回", () ->
                mutation(
                  "/api/" +
                    (task ? "submissions" : "redemptions") +
                    "/" +
                    r.optString("id") +
                    "/review",
                  "POST",
                  awardBody(json("approve", approve, "note", note.getText().toString()), award),
                  false,
                  approve ? "已通过" : "已退回"
                )
              );
            }),
            new LinearLayout.LayoutParams(0, ui.dp(48), 1)
          );
          ui.gap(actions, 10);
        }
        ui.add(c, actions, 48);
        ui.add(list, c, -2);
        ui.gap(list, 12);
      }
    }
    if (n == 0) ui.line(list, "暂时没有待确认的任务或心愿。");
    ui.grow(main, ui.scroll(list));
  }

  private void rules(LinearLayout main) {
    title(main, "科目与任务规则", () -> ruleForm(null, false));
    subjectStrip(main);
    ui.add(main, ui.label("模板修改从明天生效；当天调整请编辑具体任务。"), -2);
    ui.gap(main, 10);
    LinearLayout list = ui.col();
    list.addView(ui.button("从预制模板开始", false, this::templatePicker));
    ui.gap(list, 14);
    for (int si = 0; si < 5; si++) {
      if (!subject.equals(SUBJECTS[si])) continue;
      LinearLayout heading = ui.row();
      heading.addView(new ArtView(this, "subjects/" + SUBJECTS[si] + ".webp"), ui.lp(66, 66));
      ui.gap(heading, 12);
      heading.addView(ui.text(LABELS[si], 23, true));
      ui.add(list, heading, 72);
      JSONArray a = array(data, "rules");
      int count = 0;
      for (int i = 0; i < a.length(); i++) {
        JSONObject r = obj(a, i);
        if (!r.optString("subject", "other").equals(SUBJECTS[si])) continue;
        count++;
        LinearLayout c = ui.card();
        ui.line(c, r.optString("title") + "  ·  " + (r.optInt("enabled") == 1 ? "启用" : "停用"));
        ui.line(c, r.optString("description"));
        ui.add(
          c,
          ui.label(
            "每次 " +
              r.optInt("stars") +
              " 星 · 上限 " +
              r.optInt("daily_limit") +
              " 次 · v" +
              r.optInt("version")
          ),
          -2
        );
        ui.gap(c, 10);
        c.addView(ui.button("编辑子任务", false, () -> ruleForm(r, false)));
        LinearLayout order = ui.row();
        for (String direction : new String[] {"up", "down"}) order.addView(ui.button(direction.equals("up") ? "上移" : "下移", false, () -> mutation(childPath() + "/task-order", "POST", json("rule_key", r.optString("rule_key"), "direction", direction), false, "任务顺序已保存")), new LinearLayout.LayoutParams(0, ui.dp(48), 1));
        ui.add(c, order, 48);

        ui.add(list, c, -2);
        ui.gap(list, 10);
      }
      if (count == 0) ui.line(list, "还没有子任务，点击添加或选用预制模板。");
    }
    ui.grow(main, ui.scroll(list));
  }

  private void templatePicker() {
    LinearLayout f = form();
    for (int si = 0; si < 5; si++) {
      ui.add(f, ui.text(LABELS[si], 22, true), -2);
      ui.gap(f, 10);
      for (int i = 0; i < templates.length(); i++) {
        JSONObject t = obj(templates, i);
        if (!t.optString("subject").equals(SUBJECTS[si])) continue;
        f.addView(ui.button(t.optString("title") + "  ＋", false, () -> ruleForm(t, false)));
        ui.gap(f, 10);
      }
    }
    dialog("选择科目任务", f, null, null);
  }

  private void ruleForm(JSONObject initial, boolean task) {
    LinearLayout f = form();
    boolean existing = initial != null && initial.has("id");
    if (initial == null) {
      f.addView(ui.button("选用预制模板", false, this::templatePicker));
      ui.gap(f, 12);
    }
    Spinner sub = ui.select(
      f,
      "一级科目",
      LABELS,
      subjectIndex(
        initial == null ? subject : initial.optString("subject")
      )
    );
    EditText title = ui.input(
        f,
        "子任务名称",
        initial == null ? "" : initial.optString("title"),
        1
      ),
      desc = ui.input(
        f,
        "具体完成规则",
        initial == null ? "" : initial.optString("description"),
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
      ),
      stars = ui.input(
        f,
        "每次星星（1–100）",
        initial == null ? "1" : initial.optString("stars"),
        2
      ),
      limit = ui.input(
        f,
        "每日次数上限（1–20）",
        initial == null ? "1" : initial.optString("daily_limit"),
        2
      );
    Spinner schedule = ui.select(
      f,
      "安排频率",
      new String[] { "每天", "每周指定日", "指定一天" },
      initial == null
        ? 0
        : Arrays.asList("daily", "weekly", "once").indexOf(initial.optString("schedule", "daily"))
    );
    LinearLayout week = ui.col();
    CheckBox[] days = new CheckBox[7];
    JSONArray wd = initial == null ? new JSONArray() : array(initial, "weekdays");
    for (int i = 0; i < 7; i++) {
      int day = i;
      days[i] = new CheckBox(this);
      days[i].setText("周" + new String[] { "日", "一", "二", "三", "四", "五", "六" }[i]);
      boolean checked = initial == null && i > 0 && i < 6;
      for (int j = 0; j < wd.length(); j++) if (wd.optInt(j) == i) checked = true;
      days[i].setChecked(checked);
      week.addView(days[i]);
    }
    f.addView(week);
    String[] onDate = {
      initial != null && !initial.isNull("on_date")
        ? initial.optString("on_date")
        : existing
          ? shift(today(), 1)
          : today(),
    };
    TextView pick = ui.button(onDate[0], false, () ->
      chooseDate(onDate[0], d -> {
        onDate[0] = d;
        TextView v = (TextView) f.findViewWithTag("once-date");
        if (v != null) v.setText(d);
      })
    );
    pick.setTag("once-date");
    f.addView(pick);
    schedule.setOnItemSelectedListener(
      new AdapterView.OnItemSelectedListener() {
        public void onNothingSelected(AdapterView<?> a) {}

        public void onItemSelected(AdapterView<?> a, View v, int p, long id) {
          week.setVisibility(!task && p == 1 ? View.VISIBLE : View.GONE);
          pick.setVisibility(!task && p == 2 ? View.VISIBLE : View.GONE);
        }
      }
    );
    CheckBox enabled = new CheckBox(this);
    enabled.setText("启用这个子任务");
    enabled.setChecked(
      initial == null || !initial.has("enabled") || initial.optInt("enabled") == 1
    );
    if (!task) f.addView(enabled);
    else schedule.setVisibility(View.GONE);
    ui.line(
      f,
      task
        ? "仅修改这一天的规则，提交后将冻结。"
        : existing
          ? "从明天起生效，已有每日任务保留原规则。"
          : "从今天开始安排，可在每天的任务中查看。"
    );
    dialog(task ? "编辑当日子任务" : existing ? "编辑子任务" : "添加子任务", f, "保存规则", () -> {
      int s = number(stars, 1, 100),
        l = number(limit, 1, 20);
      if (s < 0 || l < 0 || title.getText().toString().trim().isEmpty()) {
        toast("请填写任务名称和有效数值");
        return;
      }
      JSONArray ds = new JSONArray();
      for (int i = 0; i < 7; i++) if (days[i].isChecked()) ds.put(i);
      String sc = new String[] { "daily", "weekly", "once" }[schedule.getSelectedItemPosition()];
      if (!task && sc.equals("weekly") && ds.length() == 0) {
        toast("至少选择一天");
        return;
      }
      JSONObject body = json(
        "subject",
        SUBJECTS[sub.getSelectedItemPosition()],
        "title",
        title.getText().toString(),
        "description",
        desc.getText().toString(),
        "icon",
        initial == null ? "book" : initial.optString("icon", "book"),
        "stars",
        s,
        "daily_limit",
        l,
        "schedule",
        sc,
        "weekdays",
        ds,
        "on_date",
        sc.equals("once") ? onDate[0] : null,
        "enabled",
        enabled.isChecked()
      );
      mutation(
        task
          ? "/api/tasks/" + initial.optString("id")
          : existing
            ? "/api/rules/" + initial.optString("rule_key")
            : childPath() + "/rules",
        existing ? "PATCH" : "POST",
        body,
        false,
        "任务规则已保存"
      );
    });
  }

  private void adminScreen(LinearLayout main) {
    title(main, "账号分配与数据备份", null);
    LinearLayout actions = ui.row();
    actions.addView(
      ui.button("添加家庭", true, () -> {
        LinearLayout f = form();
        EditText name = ui.input(f, "家庭名称", "", 1);
        dialog("添加家庭", f, "创建", () ->
          mutation(
            "/api/admin/families",
            "POST",
            json("name", name.getText().toString()),
            false,
            "家庭已创建"
          )
        );
      })
    );
    ui.gap(actions, 8);
    actions.addView(ui.button("分配账号", true, this::createAccount));
    ui.gap(actions, 8);
    actions.addView(
      ui.button("下载备份", false, () ->
        async(
          () -> api.get("/api/admin/backup"),
          r -> {
            pendingBackup = r.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_TITLE, "star-backup-" + today() + ".json");
            startActivityForResult(i, 401);
          },
          true
        )
      )
    );
    ui.gap(actions, 8);
    actions.addView(
      ui.button("恢复备份", false, () -> {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, 402);
      })
    );
    if (widthDp() < 640) {
      java.util.ArrayList<View> buttons = new java.util.ArrayList<>();
      for (int i = 0; i < actions.getChildCount(); i++) if (
        actions.getChildAt(i) instanceof TextView
      ) buttons.add(actions.getChildAt(i));
      actions.removeAllViews();
      for (int r = 0; r < 2; r++) {
        LinearLayout line = ui.row();
        for (int c = 0; c < 2; c++) {
          line.addView(buttons.get(r * 2 + c), new LinearLayout.LayoutParams(0, ui.dp(48), 1));
          if (c == 0) ui.gap(line, 8);
        }
        ui.add(main, line, 54);
      }
    } else ui.add(main, actions, 54);
    ui.gap(main, 16);
    LinearLayout list = ui.col();
    if (accounts != null) for (int i = 0; i < array(accounts, "users").length(); i++) {
      JSONObject u = obj(array(accounts, "users"), i);
      LinearLayout card = ui.card();
      ui.line(
        card,
        u.optString("name") + " · " + u.optString("username") + " · " + u.optString("role")
      );
      if (!u.optString("role").equals("admin")) {
        card.addView(ui.button("编辑账号 / 重置密码", false, () -> editAccount(u)));
      }
      ui.add(list, card, -2);
      ui.gap(list, 10);
    }
    ui.grow(main, ui.scroll(list));
  }

  private void createAccount() {
    if (accounts == null) return;
    JSONArray families = array(accounts, "families");
    if (families.length() == 0) {
      toast("请先添加家庭");
      return;
    }
    LinearLayout f = form();
    String[] names = new String[families.length()];
    for (int i = 0; i < names.length; i++) names[i] = obj(families, i).optString("name");
    Spinner family = ui.select(f, "所属家庭", names, 0),
      role = ui.select(f, "身份", new String[] { "小朋友", "家长" }, 0);
    EditText username = ui.input(f, "登录账号（3–40 位字母数字）", "", 1),
      name = ui.input(f, "显示名称", "", 1),
      password = ui.input(f, "初始密码（至少 8 位）", "", 129);
    dialog("预分配账号", f, "创建", () ->
      mutation(
        "/api/admin/users",
        "POST",
        json(
          "family_id",
          obj(families, family.getSelectedItemPosition()).optString("id"),
          "role",
          role.getSelectedItemPosition() == 0 ? "child" : "parent",
          "username",
          username.getText().toString(),
          "name",
          name.getText().toString(),
          "password",
          password.getText().toString()
        ),
        false,
        "账号已创建；小朋友已配置 10 个起步任务"
      )
    );
  }

  private void editAccount(JSONObject u) {
    LinearLayout f = form();
    EditText name = ui.input(f, "显示名称", u.optString("name"), 1),
      password = ui.input(f, "新密码（留空不修改）", "", 129);
    CheckBox active = new CheckBox(this);
    active.setText("启用账号");
    active.setChecked(u.optInt("active") == 1);
    f.addView(active);
    dialog("编辑 " + u.optString("username"), f, "保存", () -> {
      JSONObject body = json("name", name.getText().toString(), "active", active.isChecked());
      try {
        if (password.length() > 0) body.put("password", password.getText().toString());
      } catch (JSONException ignored) {}
      mutation("/api/admin/users/" + u.optString("id"), "PATCH", body, false, "账号已更新");
    });
  }

  @Override
  protected void onActivityResult(int request, int result, Intent intent) {
    super.onActivityResult(request, result, intent);
    if (result != RESULT_OK || intent == null || intent.getData() == null) return;
    Uri uri = intent.getData();
    if (request == 401) {
      byte[] bytes = pendingBackup;
      pendingBackup = null;
      async(
        () -> {
          try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IOException("无法写入文件");
            out.write(
              bytes != null
                ? bytes
                : api
                    .get("/api/admin/backup")
                    .toString()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );
          }
          return true;
        },
        r -> toast("备份已保存"),
        true
      );
    } else if (request == 402) {
      async(
        () ->
          new JSONObject(
            StarApi.readText(getContentResolver().openInputStream(uri), 20 * 1024 * 1024)
          ),
        r -> {
          LinearLayout f = form();
          ui.line(
            f,
            "恢复将替换当前全部业务数据和账号。服务端会先备份当前数据，恢复完成后需重新登录。"
          );
          EditText password = ui.input(f, "当前管理员密码", "", 129);
          CheckBox confirm = new CheckBox(this);
          confirm.setText("我确认用选中的备份替换当前数据");
          f.addView(confirm);
          dialog("恢复数据备份", f, "确认恢复", () -> {
            if (!confirm.isChecked()) {
              toast("请确认替换数据");
              return;
            }
            async(
              () ->
                api.call(
                  "/api/admin/restore",
                  "POST",
                  json("backup", r, "password", password.getText().toString()),
                  false
                ),
              v -> {
                logoutLocal();
                toast("恢复成功，请重新登录");
              },
              true
            );
          });
        },
        false
      );
    }
  }

  @Override
  public void onBackPressed() {
    if (activeDialog != null) {
      closeDialog();
      return;
    }
    if (user != null && !page.equals("today") && !admin()) {
      navigate("today");
      return;
    }
    super.onBackPressed();
  }
}
