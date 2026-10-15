package com.starexplorer.app;

import static com.starexplorer.app.nativeui.StarApi.json;

import android.app.DatePickerDialog;
import android.content.*;
import android.content.res.Configuration;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.os.*;
import android.provider.Settings;
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
  private NfcTaskCards taskCards;
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
    subject = "all",
    rewardFilter = "all",
    error = "";
  private int taskScrollY = 0,
    requestVersion = 0;
  private volatile int sessionVersion = 0;
  private boolean busy = false,
    initialized = false,
    wide = false,
    resumed = false,
    loading = false;
  private int contentW = 0,
    contentH = 0;
  private AlertDialog activeDialog;
  private AlertDialog nfcDialog;
  private JSONObject lastCardReceipt;
  private JSONObject inspectedCard;
  private String inspectionMessage = "等待任务卡…";
  private String cardStatus = "";
  private boolean pendingCardScan;
  private int inspectionVersion = 0;
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
    return ShanghaiDate.today();
  }

  private String shift(String d, int n) {
    try {
      return ShanghaiDate.shift(d, n);
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

  private String selectedChildName() {
    JSONObject child = data == null ? null : data.optJSONObject("child");
    String name = child == null ? "" : child.optString("name").trim();
    if (!name.isEmpty()) return name;
    for (int i = 0; i < children.length(); i++) {
      JSONObject option = obj(children, i);
      if (option.optString("id").equals(childId)) return option.optString("name").trim();
    }
    return "小朋友";
  }

  private String subjectName(JSONObject t) {
    return LABELS[subjectIndex(t.optString("subject"))];
  }

  private String taskSummary(JSONObject task) {
    return selectedChildName() + " · " + subjectName(task) + " · " + task.optString("title");
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
    taskCards = new NfcTaskCards(this);
    if (b != null) {
      date = b.getString("date", date);
      page = b.getString("page", page);
      subject = b.getString("subject", subject);
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
    if (b == null && (getIntent().getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) handleTaskCardIntent(getIntent());
    else consumeTaskCardIntent(getIntent());
    if (api.hasSession()) resumeSession();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    handleTaskCardIntent(intent);
  }

  void handleTaskCardIntent(Intent intent) {
    if (intent == null || !NfcAdapter.ACTION_NDEF_DISCOVERED.equals(intent.getAction()) || intent.getBooleanExtra("task-card-consumed", false)) return;
    try {
      // The exported Activity accepts navigation only. Never use caller-supplied Tag,
      // NDEF payload or card identity to authorize an award, even if they look valid.
      TaskCardId.parse(intent.getDataString());
      if (busy || activeDialog != null) {
        toast("当前操作尚未结束，请完成或关闭窗口后重新碰卡");
        return;
      }
      if (!api.hasSession()) {
        pendingCardScan = false;
        error = "请先使用家长账号登录，再碰卡发星";
        render();
        return;
      }
      pendingCardScan = true;
      processTaskCardLaunch();
    } catch (RuntimeException e) {
      taskCardLaunchError("任务卡信息读取失败，请重新碰卡");
    } finally { consumeTaskCardIntent(intent); }
  }

  private void taskCardLaunchError(String message) {
    pendingCardScan = false;
    error = message;
    if (page.equals("card-inspect")) {
      inspectionVersion++;
      inspectedCard = null;
      inspectionMessage = error;
      error = "";
    }
    render();
    startCardScanning();
  }

  private void consumeTaskCardIntent(Intent intent) {
    if (intent == null || !NfcAdapter.ACTION_NDEF_DISCOVERED.equals(intent.getAction())) return;
    // Keep the activity's original routing identity; clear the NFC payload after handling it.
    intent.putExtra("task-card-consumed", true);
    intent.removeExtra(NfcAdapter.EXTRA_TAG);
    intent.removeExtra(NfcAdapter.EXTRA_ID);
    intent.removeExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
  }

  private void processTaskCardLaunch() {
    if (!pendingCardScan || !resumed || user == null || !initialized || loading || busy) return;
    pendingCardScan = false;
    if (activeDialog != null) { toast("请先关闭当前窗口，再重新碰卡"); return; }
    if (!parent()) {
      error = "请使用家长账号登录后碰卡发星";
      render();
    } else {
      taskCards.stop();
      error = "";
      if (page.equals("card-inspect")) {
        inspectionVersion++;
        inspectedCard = null;
        inspectionMessage = "请保持任务卡贴近手机，或移开后重新碰卡检测";
      } else {
        page = "cards";
        cardStatus = "请保持任务卡贴近手机，或移开后重新碰卡";
        loadCardReceipt();
      }
      render();
      startCardScanning();
    }
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
    processTaskCardLaunch();
    startCardScanning();
  }

  @Override
  protected void onPause() {
    super.onPause();
    resumed = false;
    pendingCardScan = false;
    inspectionVersion++;
    if (inspectionMessage.equals("正在查询这张卡的绑定信息…")) inspectionMessage = "请重新碰卡检测";
    handler.removeCallbacks(poll);
    if (nfcDialog != null) closeDialog();
    if (taskCards != null) taskCards.stop();
    if (effects != null) effects.removeAllViews();
  }

  @Override
  protected void onDestroy() {
    if (taskCards != null) taskCards.destroy();
    handler.removeCallbacksAndMessages(null);
    worker.shutdownNow();
    super.onDestroy();
  }

  private void async(Work work, Done done, boolean mutating) {
    if (mutating && busy) return;
    int currentSessionVersion = sessionVersion;
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
      if (currentSessionVersion != sessionVersion) return;
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
        if (isDestroyed() || currentSessionVersion != sessionVersion) return;
        if (mutating) busy = false;
        if (operationDialog != null && operationDialog.isShowing()) {
          operationDialog.setCancelable(true);
          operationDialog.getWindow().getDecorView().findViewWithTag("dialog-close").setEnabled(true);
        }
        if (action != null) {
          action.setEnabled(true);
          action.setText(actionTitle);
        }
        if (err instanceof StarApi.ApiException && !api.isCurrentSession((StarApi.ApiException) err)) return;
        if (err != null) {
          if (err instanceof StarApi.ApiException && ((StarApi.ApiException) err).status == 401) {
            logoutLocal();
          }
          error = err.getMessage() == null ? "操作未完成，请重试" : err.getMessage();
          if (pendingCardScan) {
            pendingCardScan = false;
            error += "；请恢复连接后重新碰卡，本次尚未确认";
          }
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
      () -> api.resumeSession(),
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
          JSONObject next = (JSONObject) r;
          boolean unchanged = accounts != null && error.isEmpty() && samePayload(accounts, next);
          accounts = next;
          loading = false;
          error = "";
          if (!unchanged) render();
          processTaskCardLaunch();
        },
        false
      );
      return;
    }
    if (childId.isEmpty()) {
      loading = false;
      render();
      processTaskCardLaunch();
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
        JSONObject next = (JSONObject) r;
        boolean unchanged =
          !show && previous != null && error.isEmpty() && samePayload(previous, next);
        data = next;
        loading = false;
        error = "";
        if (
          previous != null &&
          !parent() &&
          data.optJSONObject("wallet").optInt("balance") >
            previous.optJSONObject("wallet").optInt("balance")
        ) toast("收到新的星星啦！你的努力被看见了 ★");
        if (!unchanged) render();
        processTaskCardLaunch();
      },
      false
    );
  }

  private boolean samePayload(Object current, Object next) {
    return current == next ||
    (current != null && next != null && current.toString().equals(next.toString()));
  }

  private void mutation(String path, String method, JSONObject body, boolean idem, String message) {
    mutation(path, method, body, idem, message, null);
  }

  private void mutation(String path, String method, JSONObject body, boolean idem, String message, Runnable after) {
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
        if (after != null && resumed) after.run();
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
    wide = LayoutPolicy.landscape(widthDp(), heightDp());
    root.setBackgroundColor(skin.background);
    root.removeAllViews();
    if (user == null) { login(); return; }
    LinearLayout shell = ui.col();
    shell.setPadding(ui.dp(wide ? 20 : 14), ui.dp(compact() ? 6 : 10), ui.dp(wide ? 20 : 14), ui.dp(6));
    root.addView(shell, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout header = ui.row();
    LinearLayout identity = ui.col();
    TextView identityTitle = ui.text(admin() ? "管理中心" : parent() ? selectedChildName() + "的成长日" : user.optString("name") + "的星星日", 21, true);
    ui.ellipsis(identityTitle, 1);
    ui.add(identity, identityTitle, -2);
    ui.gap(identity, 3);
    TextView identityHint = ui.label(admin() ? "家庭与账号管理" : "小小行动，慢慢发光");
    ui.ellipsis(identityHint, 1);
    ui.add(identity, identityHint, -2);
    header.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
    if (data != null && !admin()) {
      TextView balance = ui.button("星星 " + data.optJSONObject("wallet").optInt("balance"), false, () -> navigate("wallet"));
      balance.setContentDescription("星星口袋，可用 " + data.optJSONObject("wallet").optInt("balance") + " 颗星星");
      header.addView(balance, ui.lp(-2, 44));
      ui.gap(header, 8);
    }
    LinearLayout settingsIcon = ui.iconBox("Settings", 21);
    settingsIcon.setContentDescription("设置"); settingsIcon.setFocusable(true); settingsIcon.setOnClickListener(v -> settings());
    header.addView(settingsIcon, ui.lp(44, 44));
    header.setMinimumHeight(ui.dp(56));
    ui.add(shell, header, -2);
    ui.gap(shell, 8);
    if (!error.isEmpty()) {
      ui.add(shell, ui.button(error + " · 重试", false, () -> refresh(true)), 48);
      ui.gap(shell, 8);
    }
    LinearLayout body = wide ? ui.row() : ui.col();
    ui.grow(shell, body);
    LinearLayout nav = navigation();
    if (wide) { body.addView(nav, ui.lp(88, -1)); ui.gap(body, 20); }
    LinearLayout main = ui.col();
    ui.grow(body, main);
    if (parent() && !admin() && children.length() > 1) {
      HorizontalScrollView childScroll = new HorizontalScrollView(this);
      childScroll.setHorizontalScrollBarEnabled(false);
      LinearLayout childRow = ui.row();
      for (int i = 0; i < children.length(); i++) {
        JSONObject child = obj(children, i);
        String id = child.optString("id");
        TextView chip = ui.button(child.optString("name"), false, () -> {
          if (!childId.equals(id)) { childId = id; data = null; taskScrollY = 0; refresh(true); }
        });
        chip.setCompoundDrawables(null, null, null, null);
        chip.setSelected(childId.equals(id));
        if (childId.equals(id)) chip.setBackground(ui.bg(skin.soft, 50, true));
        childRow.addView(chip, ui.lp(-2, 44));
        ui.gap(childRow, 8);
      }
      childScroll.addView(childRow);
      ui.add(main, childScroll, 48);
      ui.gap(main, 6);
    }
    if (admin() && page.equals("admin")) adminScreen(main);
    else if (loading) ui.grow(main, center("正在寻找今天的星星…"));
    else if (page.equals("more")) more(main);
    else if (data == null) {
      ui.grow(main, center(childId.isEmpty() ? "还没有分配小朋友，请联系管理员" : "星星岛准备中…"));
      main.addView(ui.button("刷新", true, () -> refresh(true)));
    } else switch (page) {
      case "wallet": wallet(main); break;
      case "cards": cards(main); break;
      case "card-inspect": cardInspection(main); break;
      case "shop": shop(main); break;
      case "review": reviews(main); break;
      case "rules": rules(main); break;
      default: daily(main);
    }
    if (!wide) { ui.gap(shell, 8); ui.add(shell, nav, 64); }
  }

  private TextView center(String value) {
    TextView t = ui.text(value, 18, true);
    t.setGravity(Gravity.CENTER);
    return t;
  }

  private void navigate(String next) {
    if (next.equals("rules") && subject.equals("all")) subject = "chinese";
    if (!next.equals(page)) {
      taskCards.stop();
      inspectionVersion++;
    }
    page = next;
    error = "";
    render();
    if (next.equals("admin")) refresh(false);
  }

  private LinearLayout navigation() {
    LinearLayout nav = wide ? ui.col() : ui.row();
    nav.setBackground(ui.bg(wide ? Color.TRANSPARENT : skin.surface, 16, false));
    ui.pad(nav, 4);
    String[][] items = admin()
      ? new String[][] { { "admin", "管理" }, { "more", "更多" } }
      : parent()
        ? new String[][] { { "today", "今日" }, { "review", "待确认" }, { "shop", "心愿" }, { "more", "更多" } }
        : new String[][] { { "today", "今日任务" }, { "wallet", "星星宝库" }, { "shop", "心愿小铺" }, { "more", "更多" } };
    for (String[] n : items) {
      boolean selected = page.equals(n[0]) || (n[0].equals("more") && page.equals("rules"));
      LinearLayout item = ui.col();
      item.setGravity(Gravity.CENTER);
      item.setSelected(selected);
      item.setBackground(ui.bg(selected ? skin.soft : Color.TRANSPARENT, 14, false));
      item.addView(new NavigationIconView(this, skin, n[0]), ui.lp(26, 26));
      ui.gap(item, 3);
      String label = n[1];
      if (n[0].equals("review") && data != null && array(data, "reviews").length() > 0) label += " " + array(data, "reviews").length();
      TextView text = ui.text(label, 11, selected);
      text.setTextColor(selected ? skin.primary : skin.muted);
      text.setGravity(Gravity.CENTER);
      item.addView(text);
      item.setContentDescription(n[1]);
      item.setFocusable(true);
      item.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); navigate(n[0]); });
      if (wide) { ui.add(nav, item, 72); ui.gap(nav, 8); }
      else nav.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
    }
    return nav;
  }

  private void more(LinearLayout main) {
    title(main, "更多", null);
    LinearLayout list = ui.col();
    if (parent() && !admin()) {
      ui.add(list, ui.label("任务管理"), -2); ui.gap(list, 10);
      moreItem(list, "BookOpen", "任务规则", "科目、星星、每日次数与安排", () -> navigate("rules"));
      moreItem(list, "Nfc", "碰卡发星", "打开扫描页面，保持任务卡贴近手机", () -> showTaskCardReader(null, null));
      moreItem(list, "Nfc", "制作任务卡", "选择任务，将它写入一张空白卡", () -> navigate("rules"));
      moreItem(list, "ScanLine", "检测卡片", "核对孩子、任务与今日使用状态", this::showTaskCardInspection);
      ui.gap(list, 14);
    }
    ui.add(list, ui.label("偏好与账号"), -2); ui.gap(list, 10);
    moreItem(list, "Palette", "主题换装", "选择喜欢的颜色和探险伙伴", this::skins);
    moreItem(list, "ShieldCheck", "账号与设置", "密码、设备方向和 NFC 唤起设置", this::settings);
    ui.gap(list, 12);
    ui.add(list, ui.label("星星岛 · " + BuildConfig.VERSION_NAME), -2);
    ui.grow(main, region(list, "more"));
  }

  private void moreItem(LinearLayout list, String icon, String title, String detail, Runnable action) {
    LinearLayout row = ui.row(); ui.pad(row, 14);
    row.setBackground(ui.bg(skin.surface, 16, true));
    row.addView(ui.iconBox(icon, 21), ui.lp(40, 40)); ui.gap(row, 12);
    LinearLayout copy = ui.col();
    ui.add(copy, ui.text(title, 16, true), -2); ui.gap(copy, 5);
    TextView note = ui.label(detail); note.setTextSize(11); ui.add(copy, note, -2);
    row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
    row.addView(new NavigationIconView(this, skin, "ChevronRight"), ui.lp(18, 18));
    row.setContentDescription(title); row.setFocusable(true); row.setOnClickListener(v -> action.run());
    ui.add(list, row, -2); ui.gap(list, 8);
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
      outer.addView(new ArtView(this, "island-v2/star-mascot.png").fit(), new LinearLayout.LayoutParams(0, -1, 1));
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
            api.authenticate(r.getString("token"), r.getJSONObject("user").getString("id"));
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
    LinearLayout panel = ui.col();
    if (parent()) {
      LinearLayout pending = ui.card();
      ui.add(pending, ui.text("待确认 · " + array(data, "reviews").length(), 18, true), -2); ui.gap(pending, 12);
      JSONArray reviews = array(data, "reviews");
      for (int i = 0; i < Math.min(3, reviews.length()); i++) {
        JSONObject review = obj(reviews, i); LinearLayout row = ui.row();
        row.addView(ui.iconBox(subjectIcon(review.optString("subject")), 20), ui.lp(38, 38)); ui.gap(row, 10);
        LinearLayout copy = ui.col(); TextView label = ui.text(review.optString("title"), 14, true); ui.ellipsis(label, 2);
        ui.add(copy, label, -2); ui.gap(copy, 4); ui.add(copy, ui.label(review.optInt("stars") + " 星"), -2);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1)); ui.add(pending, row, -2); ui.gap(pending, 14);
      }
      if (reviews.length() == 0) { ui.line(pending, "都确认好啦"); ui.add(pending, ui.label("孩子的新提交会出现在这里"), -2); ui.gap(pending, 12); }
      ui.add(pending, ui.button("查看全部", false, () -> navigate("review")), 44);
      ui.add(panel, pending, -2); ui.gap(panel, 16);
    }
    LinearLayout companion = ui.card(); companion.setGravity(Gravity.CENTER);
    companion.setBackground(ui.bg(skin.soft, 20, false));
    ArtView art = new ArtView(this, "island-v2/star-mascot.png").fit(); ui.add(companion, art, 150);
    ui.add(companion, ui.text(skin.title, 18, true), -2); ui.gap(companion, 8);
    ui.add(companion, ui.label("每一份努力，都值得被看见"), -2);
    companion.setOnClickListener(v -> toast("你努力的样子，闪闪发光！")); companion.setContentDescription("和星星伙伴打招呼");
    ui.add(panel, companion, -2);
    return panel;
  }

  private String subjectIcon(String key) {
    return key.equals("chinese") ? "BookOpen" : key.equals("math") ? "Calculator" : key.equals("english") ? "Languages" : key.equals("sports") ? "Dumbbell" : "Shapes";
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
    LinearLayout tasks = ui.col(); tasks.setTag("daily-panel");
    layout.addView(tasks, new LinearLayout.LayoutParams(wide ? 0 : -1, wide ? -1 : 0, 1));
    LinearLayout overview = ui.row();
    overview.setBackground(ui.bg(skin.surface, 22, true));
    overview.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10));
    String[][] stats = { {date.equals(data.optString("today")) ? "今日已获得" : "当日已获得", "" + data.optInt("dayEarned") + " 星"}, {"已完成", sum(array(data, "tasks"), "approved") + " 次"}, {"待确认", sum(array(data, "tasks"), "pending") + " 次"} };
    for (String[] stat : stats) {
      LinearLayout block = ui.col();
      ui.add(block, ui.label(stat[0]), -2); ui.gap(block, 5);
      ui.add(block, ui.text(stat[1], 21, true), -2);
      overview.addView(block, new LinearLayout.LayoutParams(0, -2, 1));
    }
    overview.setTag("daily-earned");
    ui.add(tasks, overview, wide ? 64 : 72); ui.gap(tasks, 10);
    LinearLayout taskHeading = ui.row();
    taskHeading.addView(new NavigationIconView(this, skin, "ClipboardCheck"), ui.lp(18, 18)); ui.gap(taskHeading, 7);
    taskHeading.addView(ui.text("今天的小任务", 18, true)); ui.add(tasks, taskHeading, wide ? 28 : 32);
    dateStrip(tasks); subjectStrip(tasks); ui.gap(tasks, 6);
    JSONArray selected = new JSONArray();
    for (int i = 0; i < array(data, "tasks").length(); i++) {
      JSONObject task = obj(array(data, "tasks"), i);
      if (subject.equals("all") || task.optString("subject", "other").equals(subject)) selected.put(task);
    }
    ScrollView scroll = new ScrollView(this); taskScroller = scroll;
    scroll.setTag("task-scroll"); scroll.setFillViewport(true); scroll.setClipToPadding(false);
    LinearLayout grid = ui.col(); grid.setTag("task-grid");
    int availableWidth = widthDp() - (wide ? 148 : 28) - (wide && widthDp() >= 1080 ? 276 : 0);
    int cols = availableWidth >= 660 ? 2 : 1;
    int cardHeight = Math.round(98 * Math.max(1, getResources().getConfiguration().fontScale));
    if (selected.length() == 0) ui.add(grid, center("这个科目还没有安排任务"), 150);
    for (int start = 0; start < selected.length(); start += cols) {
      LinearLayout row = ui.row();
      for (int c = 0; c < cols; c++) {
        row.addView(start + c < selected.length() ? taskCard(obj(selected, start + c)) : new View(this), new LinearLayout.LayoutParams(0, -1, 1));
        if (c < cols - 1) ui.gap(row, 10);
      }
      ui.add(grid, row, cardHeight); ui.gap(grid, 10);
    }
    scroll.addView(grid, new ScrollView.LayoutParams(-1, -2)); ui.grow(tasks, scroll);
    final int restoreY = taskScrollY;
    scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> { if (taskScroller == scroll) taskScrollY = y; });
    scroll.post(() -> { if (taskScroller == scroll) scroll.scrollTo(0, restoreY); });
    if (wide && widthDp() >= 1080 && heightDp() >= 540) {
      ui.gap(layout, 20); layout.addView(hero(), ui.lp(256, -1));
    } else if (wide && !parent() && heightDp() >= 540) {
      ui.gap(layout, 16); layout.addView(hero(), ui.lp(210, -1));
    }
  }

  private void dateStrip(LinearLayout box) {
    LinearLayout row = ui.row(); row.setTag("date-strip");
    row.addView(ui.button("‹", false, () -> { date = shift(date, -1); taskScrollY = 0; refresh(true); }), ui.lp(44, 44));
    ui.gap(row, 6);
    TextView day = ui.button(date + (date.equals(today()) ? " · 今天" : ""), false, () -> chooseDate(date, next -> { date = next; taskScrollY = 0; refresh(true); }));
    row.addView(day, new LinearLayout.LayoutParams(0, ui.dp(44), 1)); ui.gap(row, 6);
    row.addView(ui.button("›", false, () -> { date = shift(date, 1); taskScrollY = 0; refresh(true); }), ui.lp(44, 44));
    if (!date.equals(today())) { ui.gap(row, 6); row.addView(ui.button("今天", false, () -> { date = today(); taskScrollY = 0; refresh(true); }), ui.lp(-2, 44)); }
    ui.add(box, row, 48);
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
    String[] keys = page.equals("rules") ? SUBJECTS : new String[] {"all", "chinese", "math", "english", "sports", "other"};
    String[] labels = page.equals("rules") ? LABELS : new String[] {"全部", "语文", "数学", "英语", "体育", "其他"};
    for (int i = 0; i < keys.length; i++) {
      String key = keys[i];
      TextView b = ui.button(labels[i], false, () -> {
        subject = key;
        taskScrollY = 0;
        if (page.equals("rules")) regionOffsets.remove("rules:" + childId);
        render();
      });
      b.setTextSize(14);
      b.setBackgroundColor(Color.TRANSPARENT); b.setTextColor(subject.equals(key) ? skin.primary : skin.muted);
      if (subject.equals(key)) { android.graphics.drawable.LayerDrawable underline = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{ui.bg(Color.TRANSPARENT, 0, false), ui.bg(skin.primary, 2, false)}); underline.setLayerGravity(1, Gravity.BOTTOM); underline.setLayerHeight(1, ui.dp(3)); underline.setLayerInset(1, ui.dp(12), 0, ui.dp(12), 0); b.setBackground(underline); }
      b.setPadding(0, ui.dp(6), 0, ui.dp(6));
      row.addView(b, new LinearLayout.LayoutParams(0, ui.dp(44), 1));
      if (i < keys.length - 1) ui.gap(row, 5);
    }
    ui.add(box, row, 48);
  }

  private LinearLayout taskCard(JSONObject t) {
    LinearLayout card = ui.row(); ui.pad(card, 12); card.setBackground(ui.bg(skin.surface, 16, true));
    String subjectKey = t.optString("subject", "other");
    LinearLayout icon = ui.iconBox(subjectIcon(subjectKey), 21);
    if (subjectKey.equals("math")) icon.setBackground(ui.bg(0xFFFFF3D9, 12, false));
    else if (subjectKey.equals("english")) icon.setBackground(ui.bg(0xFFFBEAF3, 12, false));
    else if (subjectKey.equals("sports")) icon.setBackground(ui.bg(0xFFE7F4FA, 12, false));
    card.addView(icon, ui.lp(40, 40)); ui.gap(card, 10);
    LinearLayout copy = ui.col();
    int approved = t.optInt("approved"), pending = t.optInt("pending"), limit = t.optInt("daily_limit");
    InlineTaskTitle titleRow = new InlineTaskTitle(ui, t.optString("title"), approved, limit);
    ui.add(copy, titleRow, -2); ui.gap(copy, 6);
    TextView meta = ui.label("每次 " + t.optInt("stars") + " 星" + (pending > 0 ? " · " + pending + " 次待确认" : "")); meta.setTextSize(12); ui.add(copy, meta, -2);
    card.addView(copy, new LinearLayout.LayoutParams(0, -2, 1)); ui.gap(card, 8);
    boolean available = approved + pending < limit && date.compareTo(data.optString("today")) <= 0 && (parent() || date.equals(data.optString("today")));
    TextView done = ui.button(approved >= limit ? "已完成 ✓" : pending + approved >= limit ? "等待确认" : parent() ? "确认完成" : "我完成啦", available, () -> taskDetail(t));
    done.setTextSize(13); done.setPadding(ui.dp(10), 0, ui.dp(10), 0); done.setEnabled(!busy); done.setTag("task-action");
    card.addView(done, ui.lp(-2, 44)); card.setOnClickListener(v -> taskDetail(t)); card.setFocusable(true);
    return card;
  }

  private AlertDialog dialog(String title, LinearLayout content, String action, Runnable onAction) {
    if (page.equals("cards") || page.equals("card-inspect")) taskCards.stop();
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
      if (nfcDialog == d) {
        nfcDialog = null;
        taskCards.stop();
      }
      handler.post(this::startCardScanning);
    });
    d.setOnShowListener(v -> {
      if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(ui.bg(skin.surface, 24, true));
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

  private LinearLayout collapsedSection(LinearLayout parent, String label) {
    LinearLayout body = ui.col();
    body.setVisibility(View.GONE);
    TextView[] control = new TextView[1];
    control[0] = ui.button("查看" + label + "  ▾", false, () -> {
      boolean expanded = body.getVisibility() != View.VISIBLE;
      body.setVisibility(expanded ? View.VISIBLE : View.GONE);
      control[0].setText((expanded ? "收起" : "查看") + label + (expanded ? "  ▴" : "  ▾"));
      control[0].setContentDescription((expanded ? "收起" : "查看") + label);
      parent.requestLayout();
      if (activeDialog != null) parent.post(() -> {
        if (activeDialog != null) resizeDialog(activeDialog);
      });
    });
    control[0].setMinHeight(0);
    control[0].setContentDescription("查看" + label);
    ui.add(parent, control[0], 40);
    ui.gap(parent, 8);
    ui.add(parent, body, -2);
    return body;
  }

  private EditText optionalNote(LinearLayout parent, String label) {
    LinearLayout body = ui.col();
    EditText note = ui.input(
      body,
      label,
      "",
      InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
    );
    body.setVisibility(View.GONE);
    TextView[] control = new TextView[1];
    control[0] = ui.button("＋ 添加备注（选填）", false, () -> {
      boolean expanded = body.getVisibility() != View.VISIBLE;
      body.setVisibility(expanded ? View.VISIBLE : View.GONE);
      control[0].setText(expanded ? "收起备注  ▴" : "＋ 添加备注（选填）");
      control[0].setContentDescription(expanded ? "收起备注" : "添加备注");
      parent.requestLayout();
      if (activeDialog != null) parent.post(() -> {
        if (activeDialog != null) resizeDialog(activeDialog);
      });
    });
    control[0].setMinHeight(0);
    control[0].setContentDescription("添加备注");
    ui.add(parent, control[0], 40);
    ui.gap(parent, 8);
    ui.add(parent, body, -2);
    return note;
  }

  private void taskDetail(JSONObject t) {
    LinearLayout f = form();
    if (
      !parent() &&
      t.optInt("approved") + t.optInt("pending") < t.optInt("daily_limit")
    ) {
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
        ui.text("提交后，等待家长确认", 14, false),
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
    if (parent()) {
      ui.add(f, ui.text(taskSummary(t), 16, true), -2);
      ui.gap(f, 8);
    }
    LinearLayout details = parent() ? collapsedSection(f, "任务详情") : f;
    ui.line(
      details,
      subjectName(t) + " · " + t.optString("date") + " · 规则 v" + t.optInt("rule_version")
    );
    ui.line(details, t.optString("description"));
    ui.line(
      details,
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
    for (int i = 0; i < array(t, "submissions").length(); i++) {
      JSONObject s = obj(array(t, "submissions"), i);
      ui.line(
        details,
        status(s.optString("status")) +
          " · " +
          s.optString("note") +
          " " +
          s.optString("review_note")
      );
    }
    if (parent() && array(t, "submissions").length() == 0 && date.compareTo(today()) >= 0) {
      details.addView(ui.button("编辑这一天的规则", false, () -> ruleForm(t, true)));
    }
    AwardSelection award = parent()
      ? awardFields(
        f,
        t.optInt("stars"),
        t.optInt("daily_limit") - t.optInt("approved") - t.optInt("pending")
      )
      : null;
    EditText note = parent() ? optionalNote(f, "完成备注（选填）") : null;
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

  private void showTaskCardReader(TaskCardId write, String summary) {
    if (!parent() || busy) return;
    if (!ensureTaskCardNfc()) return;
    LinearLayout f = form();
    if (write == null) {
      closeDialog();
      taskCards.stop();
      inspectionVersion++;
      page = "cards";
      cardStatus = "";
      error = "";
      loadCardReceipt();
      render();
      startCardScanning();
      return;
    }
    ui.add(f, ui.text(summary, 18, true), -2);
    ui.gap(f, 12);
    ui.line(f, "准备一张可写的 NDEF 空白卡，点击开始后贴近手机背面的 NFC 感应区。");
    ui.line(f, "卡片可每天重复使用。以后修改名称或星星，无需重写卡片。请在卡面标注孩子和任务名称。");
    TextView status = ui.text("等待开始写卡", 16, true);
    ui.add(f, status, -2);
    final AlertDialog[] reader = new AlertDialog[1];
    Runnable start = () -> {
      status.setText("请保持空白卡贴近手机，正在等待写入…");
      reader[0].getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
      taskCards.start(write, new NfcTaskCards.Callback() {
        public void read(TaskCardId card, String cardUid) { openTaskCard(card, cardUid); }
        public void written() {
          closeDialog();
          LinearLayout success = form();
          ui.add(success, ui.text(summary, 18, true), -2);
          ui.line(success, "写入并校验成功。可以先检测绑定信息；进入碰卡发星后，再碰卡会直接确认今日任务并发星。");
          dialog("任务卡制作成功", success, "检测这张卡 · 不发星", MainActivity.this::showTaskCardInspection);
        }
        public void error(String message) {
          status.setText(message);
          reader[0].getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
        }
      });
    };
    reader[0] = dialog("制作 NFC 任务卡", f, "开始写卡", start);
    nfcDialog = reader[0];
  }

  private boolean ensureTaskCardNfc() {
    LinearLayout f = form();
    if (!taskCards.supported()) {
      ui.line(f, "这台设备没有 NFC。请用支持 NFC 的安卓手机登录同一个家长账号，再制作或确认任务卡。");
      dialog("需要支持 NFC 的手机", f, null, null);
      return false;
    }
    if (!taskCards.enabled()) {
      ui.line(f, "打开手机 NFC 后，回到这里重新碰卡。");
      dialog("请打开 NFC", f, "打开 NFC 设置", () -> {
        closeDialog();
        try { startActivity(new Intent(Settings.ACTION_NFC_SETTINGS)); }
        catch (ActivityNotFoundException e) { toast("请在手机系统设置中打开 NFC"); }
      });
      return false;
    }
    return true;
  }

  private void showTaskCardInspection() {
    if (!parent() || busy || !ensureTaskCardNfc()) return;
    closeDialog();
    taskCards.stop();
    inspectionVersion++;
    page = "card-inspect";
    inspectedCard = null;
    inspectionMessage = "等待任务卡…";
    error = "";
    effects.removeAllViews();
    render();
    startCardScanning();
  }

  private void cardInspection(LinearLayout main) {
    LinearLayout content = ui.col();
    LinearLayout heading = ui.row();
    heading.addView(ui.text("检测任务卡", 24, true), new LinearLayout.LayoutParams(0, -2, 1));
    heading.addView(ui.button("返回任务", false, () -> navigate("today")));
    ui.add(content, heading, 52);
    ui.gap(content, 16);
    LinearLayout instructions = ui.card();
    ui.add(instructions, ui.text("仅检测 · 不发星", 22, true), -2);
    ui.line(instructions, "把一张卡贴近手机背面，查看绑定的孩子和任务。检测不会记录完成、审核任务或改写卡片。");
    ui.add(instructions, ui.text(inspectionMessage, 18, true), -2);
    ui.add(content, instructions, -2);
    if (inspectedCard != null) {
      JSONObject c = inspectedCard.optJSONObject("child");
      JSONObject rule = inspectedCard.optJSONObject("rule");
      ui.gap(content, 16);
      LinearLayout result = ui.card();
      ui.add(result, ui.label("这张卡绑定的任务"), -2);
      ui.gap(result, 12);
      String label = c.optString("name") + " · " + subjectName(rule) + " · " + rule.optString("title");
      TextView binding = ui.text(label, 24, true);
      binding.setTextIsSelectable(true);
      ui.add(result, binding, -2);
      ui.line(result, "建议把以上名称写在卡面，避免混淆。");
      if (c.optInt("active", 1) == 0) ui.line(result, "这个孩子的账号已停用。");
      ui.line(result, "最新规则：" + (rule.optInt("enabled") == 1 ? "启用" : "停用") + " · " + rule.optString("effective_from") + " 起生效");
      JSONObject todayTask = inspectedCard.optJSONObject("today_task");
      if (todayTask == null && inspectedCard.optBoolean("scheduled_today")) todayTask = inspectedCard.optJSONObject("today_rule");
      if (todayTask == null) ui.line(result, "今天未安排这项任务，卡片仍然可以正常检测。");
      else {
        ui.line(result, "今日任务：" + subjectName(todayTask) + " · " + todayTask.optString("title"));
        ui.line(result, "今日每次 " + todayTask.optInt("stars") + " 星 · 每日最多 " + todayTask.optInt("daily_limit") + " 次");
      }
      ui.line(result, "任务编号：" + rule.optString("rule_key"));
      if (!inspectedCard.isNull("card_uid")) {
        ui.line(result, "实体卡编号：" + inspectedCard.optString("card_uid"));
        ui.add(result, ui.text(inspectedCard.optBoolean("claimed_today") ? "这张卡今天已确认过，不能重复发星" : "这张卡今天尚未发星", 18, true), -2);
      }
      ui.add(content, result, -2);
    }
    ui.gap(content, 14);
    ui.line(content, "移开这张卡，再碰下一张即可继续检测。检测需要联网查询任务名称。");
    ui.grow(main, ui.scroll(content));
    handler.post(this::startCardScanning);
  }

  // A separate read-only route is used even when today's task has not been materialized.
  void inspectTaskCard(TaskCardId card, String cardUid) {
    if (!parent() || busy) return;
    if (!page.equals("card-inspect")) taskCards.stop();
    page = "card-inspect";
    inspectedCard = null;
    inspectionMessage = "正在查询这张卡的绑定信息…";
    error = "";
    effects.removeAllViews();
    int version = ++inspectionVersion;
    String owner = user.optString("id");
    render();
    async(() -> {
      try { return api.get("/api/children/" + card.childId + "/task-cards/" + card.ruleKey + "?card_uid=" + Uri.encode(cardUid)); }
      catch (Exception e) { return e; }
    }, result -> {
      if (!resumed || !page.equals("card-inspect") || version != inspectionVersion || user == null || !owner.equals(user.optString("id"))) return;
      if (result instanceof StarApi.ApiException && !api.isCurrentSession((StarApi.ApiException) result)) return;
      if (result instanceof StarApi.ApiException && ((StarApi.ApiException) result).status == 401) { logoutLocal(); return; }
      if (result instanceof Exception) {
        inspectionMessage = ((Exception) result).getMessage();
        if (inspectionMessage == null) inspectionMessage = "查询失败，请检查网络后重新碰卡";
      } else {
        inspectedCard = (JSONObject) result;
        inspectionMessage = "已识别任务卡 · 本次没有发星";
      }
      render();
    }, false);
  }

  private void loadCardReceipt() {
    lastCardReceipt = null;
    if (user == null) return;
    try {
      String saved = getPreferences(0).getString("card-receipt:" + user.optString("id"), "");
      if (!saved.isEmpty()) lastCardReceipt = new JSONObject(saved);
    } catch (JSONException ignored) {}
  }

  private void saveCardReceipt() {
    if (user != null && lastCardReceipt != null)
      getPreferences(0).edit().putString("card-receipt:" + user.optString("id"), lastCardReceipt.toString()).apply();
  }

  private void cards(LinearLayout main) {
    LinearLayout content = ui.col();
    LinearLayout heading = ui.row();
    heading.addView(ui.text("碰任务卡", 24, true), new LinearLayout.LayoutParams(0, -2, 1));
    heading.addView(ui.button("返回任务", false, () -> navigate("today")));
    ui.add(content, heading, 52); ui.gap(content, 10);
    if (lastCardReceipt == null) loadCardReceipt();
    JSONObject entry = lastCardReceipt == null ? null : lastCardReceipt.optJSONObject("entry");
    if (entry != null) {
      JSONObject receipt = lastCardReceipt;
      LinearLayout recent = ui.card(); recent.setGravity(Gravity.CENTER_HORIZONTAL);
      ui.add(recent, new ArtView(this, "island-v2/star-mascot.png").fit(), 135);
      TextView arrived = ui.text(receipt.optBoolean("already_claimed") ? "这张卡今天已确认" : "星星到账啦", 23, true); arrived.setGravity(Gravity.CENTER); ui.add(recent, arrived, -2); ui.gap(recent, 10);
      TextView amount = ui.text("本次积分 " + entry.optInt("amount") + " 颗星星", 28, true); amount.setGravity(Gravity.CENTER); amount.setTextColor(skin.primary); ui.add(recent, amount, -2); ui.gap(recent, 14);
      TextView summary = ui.text(cardSummary(receipt), 16, true); summary.setGravity(Gravity.CENTER); ui.add(recent, summary, -2); ui.gap(recent, 7);
      ui.add(recent, ui.label("最近一次到账 · " + receipt.optJSONObject("task").optString("date")), -2); ui.gap(recent, 12);
      if (!cardStatus.isEmpty()) { TextView status = ui.text(cardStatus, 14, true); status.setGravity(Gravity.CENTER); ui.add(recent, status, -2); ui.gap(recent, 12); }
      ui.add(recent, ui.button("继续碰卡", true, () -> { startCardScanning(); toast("请移开上一张卡，将下一张卡贴近手机"); }), 48); ui.gap(recent, 10);
      boolean current = true;
      for (int i = 0; i < array(data, "ledger").length(); i++) {
        JSONObject live = obj(array(data, "ledger"), i);
        if (live.optString("id").equals(entry.optString("id"))) current = editableAward(live);
      }
      if (current) ui.add(recent, ui.button("修改本次积分", false, () -> adjustLedgerStars(entry, cardSummary(receipt))), 48);
      else ui.line(recent, "这笔到账已修改或撤销，请到星星账本查看最新记录。");
      ui.gap(recent, 12);
      TextView detail = ui.label("完成 " + receipt.optJSONObject("submission").optInt("quantity", 1) + " 次 · 修改只影响本次到账"); detail.setTextSize(11); detail.setGravity(Gravity.CENTER); ui.add(recent, detail, -2);
      if (receipt.has("card_uid")) { ui.gap(recent, 8); TextView uid = ui.label("实体卡编号：" + receipt.optString("card_uid")); uid.setTextSize(10); uid.setGravity(Gravity.CENTER); ui.add(recent, uid, -2); }
      ui.add(content, recent, -2);
    } else {
      LinearLayout reader = ui.card();
      ui.add(reader, new ArtView(this, "island-v2/nfc-tap.png").fit(), 170);
      ui.add(reader, ui.text(busy ? "正在确认，请稍候…" : "把任务卡贴近手机背面", 22, true), -2); ui.gap(reader, 12);
      ui.line(reader, "碰卡自动到账，可接着碰下一张。每张卡每天确认一次，多次完成使用多张卡。");
      if (!cardStatus.isEmpty()) ui.add(reader, ui.text(cardStatus, 18, true), -2);
      ui.add(content, reader, -2);
    }
    ui.gap(content, 12);
    ui.add(content, ui.button("检测卡片 · 不发星", false, this::showTaskCardInspection), 48); ui.gap(content, 8);
    ui.add(content, ui.button("查看星星账本", false, () -> navigate("wallet")), 48);
    ui.grow(main, ui.scroll(content));
    handler.post(this::startCardScanning);
  }

  private String cardSummary(JSONObject receipt) {
    JSONObject task = receipt.optJSONObject("task");
    return receipt.optJSONObject("child").optString("name") + " · " + subjectName(task) + " · " + task.optString("title");
  }

  private void startCardScanning() {
    boolean inspect = page.equals("card-inspect");
    if (taskCards == null || !resumed || !parent() || (!page.equals("cards") && !inspect) || busy || activeDialog != null || taskCards.active() || !taskCards.enabled()) return;
    taskCards.startContinuousRead(new NfcTaskCards.Callback() {
      public void read(TaskCardId card, String cardUid) {
        if (inspect && page.equals("card-inspect")) inspectTaskCard(card, cardUid);
        else if (!inspect && page.equals("cards")) openTaskCard(card, cardUid);
      }
      public void written() {}
      public void error(String message) {
        if (inspect && page.equals("card-inspect")) {
          inspectionVersion++;
          inspectedCard = null;
          inspectionMessage = message;
          render();
        } else toast(message);
      }
    });
  }

  // Isolated UI tests use this entry; NFC radio is verified with a physical card.
  void openTaskCard(TaskCardId card, String cardUid) {
    if (!parent() || page.equals("card-inspect")) return;
    if (busy) { toast("上一张卡正在处理中，请移开后重新碰卡"); return; }
    boolean allowed = false;
    for (int i = 0; i < children.length(); i++)
      if (card.childId.equals(obj(children, i).optString("id"))) allowed = true;
    closeDialog();
    page = "cards";
    error = "";
    cardStatus = "";
    if (!allowed) {
      error = "这张卡不属于当前账号可管理的孩子，请检查当前登录的家长账号";
      render();
      return;
    }
    requestVersion++;
    async(() -> api.call("/api/children/" + card.childId + "/task-cards/claim", "POST",
      json("rule_key", card.ruleKey, "date", today(), "card_uid", cardUid), true), result -> {
      JSONObject receipt = (JSONObject) result;
      lastCardReceipt = receipt;
      saveCardReceipt();
      childId = receipt.optJSONObject("child").optString("id");
      date = receipt.optJSONObject("task").optString("date");
      subject = receipt.optJSONObject("task").optString("subject", "other");
      taskScrollY = 0;
      error = "";
      boolean duplicate = receipt.optBoolean("already_claimed");
      cardStatus = duplicate ? "这张卡今天已确认，未重复发星" : "本次已确认，换下一张卡即可";
      render();
      refresh(false);
      if (duplicate) { effects.removeAllViews(); toast(cardStatus); }
      else celebrate("已到账 " + receipt.optJSONObject("entry").optInt("amount") + " 颗星星", cardSummary(receipt));
    }, true);
    render();
  }

  private boolean editableAward(JSONObject entry) {
    return entry.optInt("amount") > 0 && entry.isNull("reversed_by") &&
      (entry.optString("kind").equals("task") || (entry.optString("kind").equals("bonus") && entry.optString("request_key").startsWith("award-adjust:")));
  }

  private void adjustLedgerStars(JSONObject entry, String summary) {
    if (!parent() || busy) return;
    LinearLayout f = form();
    ui.add(f, ui.text(summary, 18, true), -2);
    ui.line(f, "本次原积分 " + entry.optInt("amount") + " 颗星星。选择修改后的总积分，任务完成次数保持不变。");
    final int[] amount = { entry.optInt("amount") };
    TextView value = ui.text("", 28, true);
    value.setGravity(Gravity.CENTER);
    Runnable update = () -> value.setText("本次积分 " + amount[0] + " 颗星星");
    LinearLayout picker = ui.row();
    picker.addView(ui.button("−", false, () -> { amount[0] = Math.max(1, amount[0] - 1); update.run(); }), ui.lp(64, 52));
    picker.addView(value, new LinearLayout.LayoutParams(0, ui.dp(52), 1));
    picker.addView(ui.button("+", false, () -> { amount[0] = Math.min(2020, amount[0] + 1); update.run(); }), ui.lp(64, 52));
    ui.add(f, picker, 52);
    ui.gap(f, 12);
    LinearLayout quick = ui.row();
    for (int i = 1; i <= 5; i++) {
      final int chosen = i;
      quick.addView(ui.button(i + " 星", false, () -> { amount[0] = chosen; update.run(); }), new LinearLayout.LayoutParams(0, ui.dp(48), 1));
    }
    ui.add(f, quick, 48);
    ui.line(f, "原到账记录会保留，账本会追加撤销与新积分记录；若要取消这次到账，可到星星账本撤销。");
    update.run();
    dialog("修改本次积分", f, "保存积分", () -> {
      if (amount[0] == entry.optInt("amount")) { closeDialog(); return; }
      async(() -> api.call("/api/ledger/" + entry.optString("id") + "/adjust", "POST",
        json("amount", amount[0], "expected_amount", entry.optInt("amount")), true), result -> {
        JSONObject next = ((JSONObject) result).optJSONObject("entry");
        if (lastCardReceipt != null && lastCardReceipt.optJSONObject("entry").optString("id").equals(entry.optString("id"))) {
          lastCardReceipt.put("entry", next);
          saveCardReceipt();
        }
        closeDialog();
        error = "";
        render();
        refresh(false);
        toast("本次积分已修改为 " + next.optInt("amount") + " 颗星星");
      }, true);
    });
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
    if (parent() && taskCards.supported()) {
      ui.line(f, "开启 NFC 并保持联网，亮屏解锁后碰任务卡会打开扫描页面。请保持卡片贴近手机，未读到时移开再碰一次；读取确认后发星，同一张卡每天只发星一次。");
      if (Build.VERSION.SDK_INT >= 36) f.addView(ui.button("允许 NFC 唤起应用", false, () -> {
        closeDialog();
        try { startActivity(new Intent(NfcAdapter.ACTION_CHANGE_TAG_INTENT_PREFERENCE)); }
        catch (ActivityNotFoundException e) {
          try { startActivity(new Intent(Settings.ACTION_NFC_SETTINGS)); }
          catch (ActivityNotFoundException missing) { toast("请在系统设置中允许星星探险家通过 NFC 启动"); }
        }
      }));
    }
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
        dialog("切换账号", c, "退出登录", () -> {
          Runnable revokeOldSession = api.prepareLogout();
          logoutLocal();
          new Thread(revokeOldSession, "star-session-logout").start();
        });
      })
    );
    dialog("账号设置", f, null, null);
  }

  private void logoutLocal() {
    sessionVersion++;
    api.forget();
    busy = false;
    loading = false;
    pendingCardScan = false;
    taskCards.stop();
    lastCardReceipt = null;
    inspectedCard = null;
    inspectionVersion++;
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
    subject = "all";
    taskScrollY = 0;
    error = "";
    closeDialog();
    render();
  }

  private void title(LinearLayout main, String title, Runnable add) {
    LinearLayout h = ui.row();
    h.addView(ui.text(title, 22, true), new LinearLayout.LayoutParams(0, -2, 1));
    if (add != null) h.addView(ui.button("＋ 添加", true, add));
    ui.add(main, h, 52);
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
        if (editableAward(e)) {
          row.addView(ui.button("修改本次积分", false, () -> adjustLedgerStars(e, e.optString("title"))));
          ui.gap(row, 8);
        }
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

  private String wishIconName(String key) {
    switch (key) {
      case "icecream": return "IceCreamBowl";
      case "toy": return "Gamepad2";
      case "movie": return "Clapperboard";
      case "picnic": return "TentTree";
      default: return "Gift";
    }
  }

  private LinearLayout wishIcon(String key, int size) {
    LinearLayout icon = ui.iconBox(wishIconName(key), size / 2);
    int color = key.equals("icecream") ? 0xFFFAEAF2 : key.equals("toy") ? 0xFFE7F1FB
      : key.equals("picnic") ? 0xFFE9F4ED : key.equals("gift") ? 0xFFFFF2D6 : 0xFFEFEAFD;
    icon.setBackground(ui.bg(color, 14, false));
    return icon;
  }

  private boolean wishWaiting(String id) {
    JSONArray entries = array(data, "redemptions");
    for (int i = 0; i < entries.length(); i++) {
      JSONObject entry = obj(entries, i);
      if (entry.optString("reward_id").equals(id) && entry.optString("status").equals("pending")) return true;
    }
    return false;
  }

  private TextView wishBadge(String label, String icon, boolean positive, boolean pending) {
    TextView badge = ui.text(label, 11, false);
    int color = positive ? 0xFF4A7A60 : pending ? 0xFF936820 : skin.muted;
    badge.setTextColor(color);
    badge.setBackground(ui.bg(positive ? 0xFFEDF6EF : pending ? 0xFFFFF3D9 : skin.soft, 7, false));
    badge.setPadding(ui.dp(7), ui.dp(4), ui.dp(7), ui.dp(4));
    badge.setCompoundDrawables(ui.icon(icon, 12, color), null, null, null);
    badge.setCompoundDrawablePadding(ui.dp(4));
    return badge;
  }

  private void shop(LinearLayout main) {
    LinearLayout content = ui.col();
    ui.grow(main, region(content, "shop"));
    LinearLayout heading = ui.row();
    heading.addView(new NavigationIconView(this, skin, "Heart"), ui.lp(21, 21));
    ui.gap(heading, 8);
    heading.addView(ui.text("心愿小铺", 21, true), new LinearLayout.LayoutParams(0, -2, 1));
    LinearLayout history = ui.iconBox("Clock3", 20);
    history.setContentDescription("兑换记录"); history.setFocusable(true);
    history.setOnClickListener(v -> wishHistory());
    heading.addView(history, ui.lp(44, 44));
    if (parent()) {
      ui.gap(heading, 8);
      TextView add = ui.button("添加", "Plus", true, () -> rewardForm(null));
      add.setContentDescription("添加心愿"); add.setTextSize(13);
      heading.addView(add, ui.lp(-2, 44));
    }
    ui.add(content, heading, 48); ui.gap(content, 12);
    int balance = data.optJSONObject("wallet").optInt("balance"), pending = 0, active = 0;
    for (int i = 0; i < array(data, "redemptions").length(); i++) if (obj(array(data, "redemptions"), i).optString("status").equals("pending")) pending++;
    for (int i = 0; i < array(data, "rewards").length(); i++) if (obj(array(data, "rewards"), i).optInt("active") == 1) active++;
    boolean stackedOverview = widthDp() < 360 && getResources().getConfiguration().fontScale > 1.2f;
    LinearLayout overview = ui.col(); ui.pad(overview, 14); overview.setBackground(ui.bg(skin.surface, 16, true));
    LinearLayout balanceRow = ui.row(); ui.add(overview, balanceRow, -2);
    LinearLayout star = ui.iconBox("Star", 23); star.setBackground(ui.bg(0xFFFFF3CE, 13, false));
    balanceRow.addView(star, ui.lp(42, 42)); ui.gap(balanceRow, 10);
    LinearLayout amount = ui.col(); ui.add(amount, ui.label("可用星星"), -2); ui.gap(amount, 4);
    ui.add(amount, ui.text(balance + " 星", 24, true), -2);
    balanceRow.addView(amount, new LinearLayout.LayoutParams(0, -2, 1));
    if (!stackedOverview) ui.gap(balanceRow, 8);
    LinearLayout awaiting = ui.col(); awaiting.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
    TextView line = ui.text(pending > 0 ? pending + " 个心愿待确认" : "期待下一份小心愿", 12, true);
    line.setTextColor(skin.primary); line.setGravity(Gravity.RIGHT); ui.add(awaiting, line, -2); ui.gap(awaiting, 5);
    TextView hint = ui.label("确认兑换后扣星 ›"); hint.setTextSize(11); hint.setGravity(Gravity.RIGHT); ui.add(awaiting, hint, -2);
    awaiting.setContentDescription(parent() ? "审核兑换申请" : "查看兑换记录"); awaiting.setFocusable(true);
    awaiting.setOnClickListener(v -> { if (parent()) navigate("review"); else wishHistory(); });
    awaiting.setMinimumHeight(ui.dp(48)); awaiting.setTag("wish-pending");
    if (stackedOverview) {
      ui.gap(overview, 10); awaiting.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
      line.setGravity(Gravity.LEFT); hint.setGravity(Gravity.LEFT); ui.add(overview, awaiting, -2);
    } else balanceRow.addView(awaiting, ui.lp(134, -2));
    ui.add(content, overview, -2); ui.gap(content, 12);
    if (parent()) {
      LinearLayout filters = ui.row(); ui.pad(filters, 4); filters.setBackground(ui.bg(skin.soft, 13, false));
      String[] keys = {"all", "active", "inactive"};
      String[] labels = {"全部 " + array(data, "rewards").length(), "已上架 " + active, "已下架 " + (array(data, "rewards").length() - active)};
      for (int i = 0; i < keys.length; i++) {
        String key = keys[i];
        TextView filter = ui.button(labels[i], null, false, () -> { rewardFilter = key; regionOffsets.remove("shop:" + childId + ":" + key); render(); });
        filter.setTextSize(13); filter.setPadding(0, 0, 0, 0); filter.setSelected(rewardFilter.equals(key));
        filter.setContentDescription(i == 0 ? "全部奖励" : i == 1 ? "已上架" : "已下架");
        filter.setTextColor(rewardFilter.equals(key) ? skin.primary : skin.muted);
        filter.setBackground(ui.bg(rewardFilter.equals(key) ? skin.surface : Color.TRANSPARENT, 10, false));
        filters.addView(filter, new LinearLayout.LayoutParams(0, ui.dp(44), 1));
      }
      ui.add(content, filters, 52); ui.gap(content, 12);
    } else { ui.add(content, ui.label("用攒下的星星，兑换喜欢的时光"), -2); ui.gap(content, 12); }
    JSONArray rewards = new JSONArray();
    for (int i = 0; i < array(data, "rewards").length(); i++) {
      JSONObject r = obj(array(data, "rewards"), i);
      if (parent() ? rewardFilter.equals("all") || (rewardFilter.equals("active") ? r.optInt("active") == 1 : r.optInt("active") == 0) : r.optInt("active") == 1) rewards.put(r);
    }
    int cols = Math.max(1, Math.min(3, (widthDp() - (wide ? 148 : 28)) / 300));
    if (rewards.length() == 0) {
      LinearLayout empty = ui.card(); empty.setGravity(Gravity.CENTER); ui.pad(empty, 24);
      empty.addView(wishIcon("gift", 48), ui.lp(48, 48)); ui.gap(empty, 12);
      ui.add(empty, center(parent() ? "这里还没有心愿" : "小心愿，准备中"), -2); ui.gap(empty, 8);
      TextView emptyHint = ui.label(parent() ? "添加一个心愿，或切换上架状态查看" : "和家长一起，约定第一份奖励吧"); emptyHint.setGravity(Gravity.CENTER); ui.add(empty, emptyHint, -2);
      ui.add(content, empty, -2);
    }
    for (int start = 0; start < rewards.length(); start += cols) {
      LinearLayout row = ui.row(); row.setGravity(Gravity.TOP);
      for (int col = 0; col < cols; col++) {
        int index = start + col;
        row.addView(index < rewards.length() ? wishCard(obj(rewards, index), balance) : new View(this), new LinearLayout.LayoutParams(0, -2, 1));
        if (col < cols - 1) ui.gap(row, 12);
      }
      ui.add(content, row, -2); ui.gap(content, 12);
    }
    TextView footnote = ui.label(parent() ? "心愿由家人共享，兑换记录按孩子分别保存。" : "申请时不会扣星，家长确认后才会扣除。"); footnote.setTextSize(11); ui.add(content, footnote, -2);
  }

  private LinearLayout wishCard(JSONObject r, int balance) {
    LinearLayout card = ui.col(); ui.pad(card, 14); card.setTag("wish-card"); card.setBackground(ui.bg(skin.surface, 16, true));
    LinearLayout heading = ui.row(); heading.setGravity(Gravity.TOP);
    heading.addView(wishIcon(r.optString("icon"), 48), ui.lp(48, 48)); ui.gap(heading, 12);
    LinearLayout copy = ui.col();
    TextView name = ui.text(r.optString("title"), 17, true); ui.ellipsis(name, 2); ui.add(copy, name, -2);
    if (!r.optString("description").isEmpty()) {
      ui.gap(copy, 4); TextView desc = ui.label(r.optString("description")); desc.setTextSize(12); ui.ellipsis(desc, 2); ui.add(copy, desc, -2);
    }
    heading.addView(copy, new LinearLayout.LayoutParams(0, -2, 1)); ui.add(card, heading, -2); ui.gap(card, 10);
    boolean waiting = wishWaiting(r.optString("id")), active = r.optInt("active") == 1;
    int missing = Math.max(0, r.optInt("cost") - balance);
    LinearLayout state = ui.row();
    state.addView(wishBadge(parent() ? active ? "已上架" : "已下架" : waiting ? "等待确认" : missing > 0 ? "攒星中" : "可以兑换",
      parent() ? active ? "Check" : "Power" : waiting ? "Clock3" : missing > 0 ? "Star" : "Check", parent() ? active : !waiting && missing == 0, !parent() && (waiting || missing > 0)));
    if (parent() && waiting || !parent() && !waiting) {
      ui.gap(state, 8); TextView note = ui.label(parent() ? "有待确认申请" : missing > 0 ? "已攒 " + Math.max(0, balance) + " / " + r.optInt("cost") + " 星" : "已经攒够啦"); note.setTextSize(10);
      state.addView(note, new LinearLayout.LayoutParams(0, -2, 1));
    }
    ui.add(card, state, -2); ui.gap(card, 10);
    TextView price = ui.text(r.optInt("cost") + " 星", 21, true);
    price.setCompoundDrawables(ui.icon("Star", 18, 0xFFBB8B2D), null, null, null); price.setCompoundDrawablePadding(ui.dp(4));
    LinearLayout actions = ui.row();
    if (parent()) {
      TextView edit = ui.button("编辑", "Pencil", false, () -> rewardForm(r)); edit.setContentDescription("编辑心愿 " + r.optString("title")); edit.setBackground(ui.bg(skin.soft, 12, false));
      TextView toggle = ui.button(active ? "下架" : "上架", "Power", false, () -> mutation("/api/rewards/" + r.optString("id"), "PATCH",
        json("title", r.optString("title"), "description", r.optString("description"), "icon", r.optString("icon"), "cost", r.optInt("cost"), "active", !active), false, active ? "奖励已下架" : "奖励已上架"));
      toggle.setContentDescription((active ? "下架心愿 " : "上架心愿 ") + r.optString("title"));
      for (TextView button : new TextView[] {edit, toggle}) { button.setTextSize(12); button.setPadding(ui.dp(9), 0, ui.dp(9), 0); button.setEnabled(!busy); button.setTag("wish-action"); }
      actions.addView(edit, ui.lp(-2, 44)); ui.gap(actions, 7); actions.addView(toggle, ui.lp(-2, 44));
    } else {
      TextView redeem = ui.button(waiting ? "等待确认" : missing > 0 ? "还差 " + missing + " 星" : "我想兑换", waiting ? "Clock3" : "Gift", !waiting && missing == 0, () -> redeemWish(r));
      redeem.setTextSize(12); redeem.setTag("wish-action"); redeem.setPadding(ui.dp(10), 0, ui.dp(10), 0); redeem.setEnabled(!busy && !waiting && missing == 0);
      if (waiting || missing > 0) { redeem.setTextColor(skin.muted); redeem.setBackground(ui.bg(skin.soft, 12, false)); }
      actions.addView(redeem, ui.lp(-2, 44));
    }
    if (getResources().getConfiguration().fontScale > 1.2f) {
      ui.add(card, price, -2); ui.gap(card, 8); actions.setGravity(Gravity.RIGHT); ui.add(card, actions, -2);
    } else {
      LinearLayout bottom = ui.row(); bottom.addView(price, new LinearLayout.LayoutParams(0, -2, 1)); ui.gap(bottom, 6); bottom.addView(actions, ui.lp(-2, -2)); ui.add(card, bottom, -2);
    }
    if (!parent()) {
      ui.gap(card, 10); ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
      progress.setMax(r.optInt("cost")); progress.setProgress(Math.max(0, Math.min(balance, r.optInt("cost"))));
      progress.setProgressTintList(android.content.res.ColorStateList.valueOf(waiting ? 0xFFD4B570 : skin.primary));
      progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(skin.soft)); progress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      ui.add(card, progress, 4);
    }
    return card;
  }

  private String wishTime(String value) {
    try {
      java.text.SimpleDateFormat input = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US);
      java.text.SimpleDateFormat output = new java.text.SimpleDateFormat("M月d日 HH:mm", Locale.CHINA); output.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
      return output.format(input.parse(value));
    } catch (Exception ignored) { return value.length() >= 10 ? value.substring(0, 10) : value; }
  }

  private void wishHistory() {
    LinearLayout f = form(); JSONArray entries = array(data, "redemptions");
    if (entries.length() == 0) {
      LinearLayout empty = ui.col(); empty.setGravity(Gravity.CENTER); ui.pad(empty, 20);
      empty.addView(wishIcon("gift", 48), ui.lp(48, 48)); ui.gap(empty, 14);
      ui.add(empty, center("还没有兑换记录"), -2); ui.gap(empty, 8);
      TextView hint = ui.label("攒下小星星，期待第一个心愿实现"); hint.setGravity(Gravity.CENTER); ui.add(empty, hint, -2); ui.add(f, empty, -2);
    }
    for (int i = 0; i < entries.length(); i++) {
      JSONObject r = obj(entries, i); LinearLayout row = ui.row(); row.setGravity(Gravity.TOP);
      row.addView(wishIcon(r.optString("icon"), 42), ui.lp(42, 42)); ui.gap(row, 12);
      LinearLayout text = ui.col(); ui.add(text, ui.text(r.optString("title"), 16, true), -2); ui.gap(text, 6);
      TextView meta = ui.label(r.optInt("cost") + " 星 · " + wishTime(r.optString("created_at"))); meta.setTextSize(12); ui.add(text, meta, -2); ui.gap(text, 8);
      String status = r.optString("status");
      text.addView(wishBadge(status.equals("pending") ? "等待确认" : status.equals("approved") ? "兑换成功" : "已退回", status.equals("pending") ? "Clock3" : status.equals("approved") ? "Check" : "X", status.equals("approved"), status.equals("pending")));
      if (!r.optString("review_note").isEmpty()) { ui.gap(text, 8); ui.add(text, ui.label(r.optString("review_note")), -2); }
      row.addView(text, new LinearLayout.LayoutParams(0, -2, 1)); ui.add(f, row, -2); ui.gap(f, 18);
    }
    dialog("兑换记录", f, null, null);
  }

  private void redeemWish(JSONObject r) {
    LinearLayout f = form(), heading = ui.row(); heading.setGravity(Gravity.TOP);
    heading.addView(wishIcon(r.optString("icon"), 48), ui.lp(48, 48)); ui.gap(heading, 12);
    LinearLayout copy = ui.col(); ui.add(copy, ui.text(r.optString("title"), 19, true), -2); ui.gap(copy, 6);
    ui.add(copy, ui.label(r.optString("description").isEmpty() ? "一份值得期待的小心愿" : r.optString("description")), -2);
    heading.addView(copy, new LinearLayout.LayoutParams(0, -2, 1)); ui.add(f, heading, -2); ui.gap(f, 20);
    LinearLayout receipt = ui.row(); ui.pad(receipt, 16); receipt.setBackground(ui.bg(skin.soft, 14, false));
    String[][] values = {{"兑换所需", r.optInt("cost") + " 星"}, {"兑换后剩余", (data.optJSONObject("wallet").optInt("balance") - r.optInt("cost")) + " 星"}};
    for (String[] value : values) {
      LinearLayout item = ui.col(); ui.add(item, ui.label(value[0]), -2); ui.gap(item, 6); ui.add(item, ui.text(value[1], 24, true), -2); receipt.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
    }
    ui.add(f, receipt, -2); ui.gap(f, 14);
    TextView hint = ui.label("家长确认后才会扣星，提交申请时不扣除。"); hint.setTextSize(12); ui.add(f, hint, -2);
    dialog("兑换一个小心愿", f, "请家长帮我兑换", () -> mutation(childPath() + "/redemptions", "POST", json("reward_id", r.optString("id")), true, "心愿已送达，等待家长确认"));
  }

  private void rewardForm(JSONObject r) {
    LinearLayout f = form();
    EditText title = ui.input(f, "心愿名称", r == null ? "" : r.optString("title"), 1);
    title.setHint("例如：周末一起去野餐"); title.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(100)});
    EditText desc = ui.input(f, "心愿说明", r == null ? "" : r.optString("description"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    desc.setHint("约定好内容，让小期待更具体"); desc.setMinLines(2); desc.setMaxLines(4); desc.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(1000)});
    EditText cost = ui.input(f, "兑换所需星星", r == null ? "5" : r.optString("cost"), InputType.TYPE_CLASS_NUMBER);
    ui.add(f, ui.label("选择心愿图标"), -2); ui.gap(f, 8);
    String[] icons = {"icecream", "toy", "movie", "picnic", "gift"}, labels = {"冰淇淋", "玩具", "电影", "野餐", "礼物"};
    RadioGroup picker = new RadioGroup(this); picker.setOrientation(RadioGroup.HORIZONTAL);
    for (int i = 0; i < icons.length; i++) {
      RadioButton option = new RadioButton(this); option.setId(View.generateViewId()); option.setTag(icons[i]); option.setText(labels[i]); option.setTextSize(11);
      option.setTypeface(IslandTypeface.get(this, false)); option.setGravity(Gravity.CENTER); option.setButtonDrawable((android.graphics.drawable.Drawable) null);
      option.setPadding(ui.dp(2), ui.dp(8), ui.dp(2), ui.dp(8)); option.setCompoundDrawables(null, ui.icon(wishIconName(icons[i]), 24, skin.primary), null, null); option.setCompoundDrawablePadding(ui.dp(8));
      option.setMinHeight(ui.dp(72));
      RadioGroup.LayoutParams params = new RadioGroup.LayoutParams(0, -1, 1); if (i > 0) params.leftMargin = ui.dp(4); picker.addView(option, params);
    }
    picker.setOnCheckedChangeListener((group, checked) -> {
      for (int i = 0; i < group.getChildCount(); i++) {
        RadioButton option = (RadioButton) group.getChildAt(i); boolean selected = option.getId() == checked;
        android.graphics.drawable.GradientDrawable background = ui.bg(selected ? skin.soft : skin.surface, 12, true);
        if (selected) background.setStroke(ui.dp(2), skin.primary); option.setBackground(background); option.setTextColor(selected ? skin.primary : skin.muted);
      }
    });
    int chosen = r == null ? 4 : Arrays.asList(icons).indexOf(r.optString("icon")); picker.check(picker.getChildAt(chosen < 0 ? 4 : chosen).getId());
    ui.add(f, picker, -2); ui.gap(f, 14);
    CheckBox active = new CheckBox(this); active.setText("在心愿小铺上架"); active.setTextSize(14); active.setTypeface(IslandTypeface.get(this, false)); active.setTextColor(skin.ink);
    active.setButtonTintList(android.content.res.ColorStateList.valueOf(skin.primary)); active.setBackground(ui.bg(skin.soft, 12, false)); active.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8));
    active.setChecked(r == null || r.optInt("active") == 1); ui.add(f, active, -2);
    dialog(r == null ? "添加心愿" : "编辑心愿", f, "保存心愿", () -> {
      int n = number(cost, 1, 100000); if (n < 0 || title.length() == 0) return;
      RadioButton selected = picker.findViewById(picker.getCheckedRadioButtonId());
      mutation(r == null ? childPath() + "/rewards" : "/api/rewards/" + r.optString("id"), r == null ? "POST" : "PATCH",
        json("title", title.getText().toString(), "description", desc.getText().toString(), "cost", n, "icon", selected.getTag().toString(), "active", active.isChecked()), r == null, "心愿已保存");
    });
  }

  private static final class AwardSelection {

    int unitStars;
    int quantity = 1;
    final int limit;
    TextView unitValue, quantityValue, total, minus, plus;
    final ArrayList<TextView> starButtons = new ArrayList<>();
    final ArrayList<TextView> quantityButtons = new ArrayList<>();
    final ArrayList<Integer> quantityValues = new ArrayList<>();

    AwardSelection(int stars, int limit) {
      unitStars = Math.max(1, Math.min(101, stars));
      this.limit = Math.max(1, Math.min(20, limit));
    }
  }

  private void awardChoiceStyle(TextView button, boolean selected, boolean star, boolean filled) {
    int face = selected
      ? (star
        ? androidx.core.graphics.ColorUtils.blendARGB(skin.surface, 0xFFFFD25E, .38f)
        : skin.primary)
      : skin.surface;
    int text = selected
      ? (star ? 0xFF9A6B00 : 0xFFFFFFFF)
      : (star && filled ? 0xFFD09119 : skin.primary);
    android.graphics.drawable.GradientDrawable shape = ui.bg(face, 12, false);
    shape.setStroke(ui.dp(selected ? 2 : 1), selected ? (star ? 0xFFD7A728 : skin.primary) : skin.border);
    button.setBackground(
      new android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(0x22444444),
        shape,
        null
      )
    );
    button.setTextColor(text);
  }

  private void refreshAward(AwardSelection award) {
    award.unitValue.setText(award.unitStars + " 星");
    award.quantityValue.setText(award.quantity + " 次");
    award.minus.setEnabled(award.unitStars > 1);
    award.minus.setAlpha(award.unitStars > 1 ? 1f : .45f);
    award.plus.setEnabled(award.unitStars < 101);
    award.plus.setAlpha(award.unitStars < 101 ? 1f : .45f);
    for (int i = 0; i < award.starButtons.size(); i++) {
      int value = i + 1;
      TextView button = award.starButtons.get(i);
      button.setText((award.unitStars >= value ? "★ " : "☆ ") + value);
      awardChoiceStyle(button, award.unitStars == value, true, award.unitStars >= value);
    }
    for (int i = 0; i < award.quantityButtons.size(); i++) {
      int value = award.quantityValues.get(i);
      awardChoiceStyle(award.quantityButtons.get(i), award.quantity == value, false, false);
    }
    award.total.setText(
      "实发 " +
        award.unitStars * award.quantity +
        " 颗星星  ·  " +
        award.quantity +
        " × " +
        award.unitStars +
        " 星"
    );
    award.total.setContentDescription(
      "本次实发 " +
        award.unitStars * award.quantity +
        " 颗星星，" +
        award.quantity +
        " 次，每次 " +
        award.unitStars +
        " 星"
    );
  }

  private AwardSelection awardFields(LinearLayout f, int stars, int limit) {
    AwardSelection award = new AwardSelection(stars, limit);
    LinearLayout panel = ui.col();
    ui.pad(panel, 12);
    panel.setBackground(ui.bg(skin.soft, 16, true));

    LinearLayout starHeading = ui.row();
    starHeading.setGravity(Gravity.CENTER_VERTICAL);
    TextView starLabel = ui.text("每次星星", 15, true);
    starHeading.addView(starLabel, new LinearLayout.LayoutParams(0, -2, 1));
    award.minus = ui.button("−", false, () -> {
      award.unitStars = Math.max(1, award.unitStars - 1);
      refreshAward(award);
    });
    award.minus.setMinHeight(0);
    award.minus.setPadding(0, 0, 0, 0);
    award.minus.setTextSize(20);
    award.minus.setContentDescription("减少一颗星星");
    starHeading.addView(award.minus, ui.lp(40, 40));
    award.unitValue = ui.text("", 14, true);
    award.unitValue.setGravity(Gravity.CENTER);
    starHeading.addView(award.unitValue, ui.lp(64, 40));
    award.plus = ui.button("+", false, () -> {
      award.unitStars = Math.min(101, award.unitStars + 1);
      refreshAward(award);
    });
    award.plus.setMinHeight(0);
    award.plus.setPadding(0, 0, 0, 0);
    award.plus.setTextSize(18);
    award.plus.setContentDescription("增加一颗星星");
    starHeading.addView(award.plus, ui.lp(40, 40));
    ui.add(panel, starHeading, 40);
    ui.gap(panel, 6);

    LinearLayout starsRow = ui.row();
    for (int value = 1; value <= 5; value++) {
      final int selected = value;
      TextView choice = ui.button("", false, () -> {
        award.unitStars = selected;
        refreshAward(award);
      });
      choice.setMinHeight(0);
      choice.setPadding(ui.dp(4), 0, ui.dp(4), 0);
      choice.setTextSize(15);
      choice.setContentDescription("每次发放 " + value + " 颗星星");
      award.starButtons.add(choice);
      starsRow.addView(choice, new LinearLayout.LayoutParams(0, ui.dp(44), 1));
      if (value < 5) ui.gap(starsRow, 6);
    }
    ui.add(panel, starsRow, 44);
    ui.gap(panel, 10);

    LinearLayout quantityHeading = ui.row();
    quantityHeading.addView(ui.text("完成次数", 15, true), new LinearLayout.LayoutParams(0, -2, 1));
    award.quantityValue = ui.text("", 14, true);
    quantityHeading.addView(award.quantityValue);
    ui.add(panel, quantityHeading, -2);
    ui.gap(panel, 6);

    ArrayList<Integer> values = new ArrayList<>();
    for (int value = 1; value <= Math.min(award.limit, 10); value++) values.add(value);
    if (award.limit > 10) values.add(award.limit);
    LinearLayout quantityRow = ui.row();
    for (int value : values) {
      TextView choice = ui.button(
        value == award.limit && award.limit > 10 ? "最多 " + value : String.valueOf(value),
        false,
        () -> {
          award.quantity = value;
          refreshAward(award);
        }
      );
      choice.setMinHeight(0);
      choice.setPadding(ui.dp(4), 0, ui.dp(4), 0);
      choice.setTextSize(value == award.limit && award.limit > 10 ? 12 : 15);
      choice.setContentDescription("本次完成 " + value + " 次");
      award.quantityButtons.add(choice);
      award.quantityValues.add(value);
      quantityRow.addView(choice, ui.lp(value == award.limit && award.limit > 10 ? 66 : 44, 42));
      ui.gap(quantityRow, 6);
    }
    HorizontalScrollView quantityScroll = new HorizontalScrollView(this);
    quantityScroll.setHorizontalScrollBarEnabled(false);
    quantityScroll.setFillViewport(false);
    quantityScroll.addView(quantityRow, new HorizontalScrollView.LayoutParams(-2, ui.dp(42)));
    ui.add(panel, quantityScroll, 42);
    ui.gap(panel, 10);

    award.total = ui.text("", 16, true);
    award.total.setTextSize(15);
    award.total.setTextColor(0xFFFFFFFF);
    award.total.setGravity(Gravity.CENTER_VERTICAL);
    award.total.setPadding(ui.dp(12), 0, ui.dp(12), 0);
    award.total.setBackground(ui.bg(skin.primary, 14, false));
    ui.add(panel, award.total, 44);
    refreshAward(award);
    ui.add(f, panel, -2);
    ui.gap(f, 12);
    return award;
  }

  private JSONObject awardBody(JSONObject body, AwardSelection award) {
    if (award != null) try {
      body.put("unit_stars", award.unitStars);
      body.put("quantity", award.quantity);
    } catch (Exception e) { throw new IllegalArgumentException(e); }
    return body;
  }

  private void reviewDetails(LinearLayout container, JSONObject review, boolean task) {
    ui.line(container, (task ? subjectName(review) + " · " : "心愿 · ") + review.optString("title"));
    if (task) {
      ui.line(container, "任务日期：" + review.optString("date") + " · 每次 " + review.optInt("stars") + " 星 · 每日上限 " + review.optInt("daily_limit") + " 次");
      String description = review.optString("description").trim();
      ui.line(container, "任务说明：" + (description.isEmpty() ? "未填写任务说明" : description));
    } else {
      ui.line(container, "消耗 " + review.optInt("cost") + " 星");
    }
    String note = review.optString("note").trim();
    if (!note.isEmpty()) ui.line(container, "提交备注：" + note);
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
        reviewDetails(c, r, task);
        LinearLayout actions = ui.row();
        for (boolean approve : new boolean[] { false, true }) {
          actions.addView(
            ui.button(approve ? "通过" : "退回", approve, () -> {
              LinearLayout f = form();
              ui.line(
                f,
                task ? taskSummary(r) : "心愿 · " + r.optString("title")
              );
              LinearLayout details = collapsedSection(f, task ? "任务详情" : "心愿详情");
              reviewDetails(details, r, task);
              AwardSelection award = task && approve
                ? awardFields(f, r.optInt("stars"), r.optInt("remaining", 1))
                : null;
              EditText note = optionalNote(f, "给孩子的话（选填）");
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
    ui.grow(main, region(list, "reviews"));
  }

  private void rules(LinearLayout main) {
    title(main, "科目与任务规则", () -> ruleForm(null, false));
    subjectStrip(main);
    ui.add(main, ui.label("模板修改从明天生效；当天调整请编辑具体任务。"), -2);
    ui.gap(main, 12);
    LinearLayout list = ui.col();
    ui.add(list, ui.button("从预制模板开始", false, this::templatePicker), 44); ui.gap(list, 12);
    int columns = widthDp() - (wide ? 148 : 28) >= 700 ? 2 : 1;
    LinearLayout row = null; int count = 0;
    JSONArray rules = array(data, "rules");
    for (int i = 0; i < rules.length(); i++) {
      JSONObject rule = obj(rules, i);
      if (!rule.optString("subject", "other").equals(subject)) continue;
      if (count % columns == 0) { row = ui.row(); row.setGravity(Gravity.TOP); ui.add(list, row, -2); }
      LinearLayout card = ui.card();
      LinearLayout heading = ui.row();
      TextView name = ui.text(rule.optString("title"), 17, true); ui.ellipsis(name, 2);
      heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1)); ui.gap(heading, 8);
      heading.addView(ui.label(rule.optInt("enabled") == 1 ? "启用" : "停用"));
      ui.add(card, heading, -2); ui.gap(card, 8);
      TextView description = ui.label(rule.optString("description")); ui.ellipsis(description, 2);
      ui.add(card, description, -2); ui.gap(card, 10);
      ui.add(card, ui.label("每次 " + rule.optInt("stars") + " 星 · 上限 " + rule.optInt("daily_limit") + " 次 · v" + rule.optInt("version")), -2);
      ui.gap(card, 12);
      LinearLayout actions = ui.row();
      actions.addView(ui.button("编辑子任务", false, () -> ruleForm(rule, false)), new LinearLayout.LayoutParams(0, ui.dp(44), 1)); ui.gap(actions, 6);
      actions.addView(ui.button("制作任务卡", false, () -> showTaskCardReader(new TaskCardId(childId, rule.optString("rule_key")), taskSummary(rule))), new LinearLayout.LayoutParams(0, ui.dp(44), 1));
      ui.add(card, actions, 44); ui.gap(card, 8);
      LinearLayout order = ui.row();
      for (String direction : new String[] {"up", "down"}) {
        TextView control = ui.button(direction.equals("up") ? "上移" : "下移", false, () -> mutation(childPath() + "/task-order", "POST", json("rule_key", rule.optString("rule_key"), "direction", direction), false, "任务顺序已保存"));
        control.setTextSize(12); order.addView(control, new LinearLayout.LayoutParams(0, ui.dp(44), 1));
        if (direction.equals("up")) ui.gap(order, 6);
      }
      ui.add(card, order, 44);
      row.addView(card, new LinearLayout.LayoutParams(0, -2, 1));
      if (columns == 2 && count % 2 == 0) ui.gap(row, 12);
      count++; if (count % columns == 0) ui.gap(list, 12);
    }
    if (count == 0) ui.line(list, "还没有子任务，点击添加或选用预制模板。");
    if (columns == 2 && count % 2 != 0) row.addView(new View(this), new LinearLayout.LayoutParams(0, -2, 1));
    ui.grow(main, region(list, "rules"));
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
        !existing,
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
            true,
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
    ui.gap(main, 8);
    LinearLayout stats = ui.row();
    int parents = 0, kids = 0, disabled = 0;
    if (accounts != null) for (int i = 0; i < array(accounts, "users").length(); i++) {
      JSONObject u = obj(array(accounts, "users"), i);
      if (u.optString("role").equals("parent")) parents++;
      if (u.optString("role").equals("child")) kids++;
      if (!u.optBoolean("active", u.optInt("active") == 1)) disabled++;
    }
    String[][] metrics = {{"Building2", "家庭", "" + array(accounts, "families").length()}, {"Users", "家长", "" + parents}, {"Star", "小朋友", "" + kids}, {"Power", "已停用", "" + disabled}};
    for (int i = 0; i < metrics.length; i++) {
      LinearLayout card = ui.card(); ui.pad(card, 10);
      card.addView(new NavigationIconView(this, skin, metrics[i][0]), ui.lp(20, 20)); ui.gap(card, 7);
      ui.add(card, ui.text(metrics[i][2], 23, true), -2); ui.gap(card, 3);
      TextView label = ui.label(metrics[i][1]); label.setTextSize(11); ui.add(card, label, -2);
      stats.addView(card, new LinearLayout.LayoutParams(0, -1, 1)); if (i < 3) ui.gap(stats, 8);
    }
    ui.add(main, stats, 104); ui.gap(main, 12);
    LinearLayout search = ui.row(); ui.pad(search, 9); search.setBackground(ui.bg(skin.surface, 12, true));
    search.addView(new NavigationIconView(this, skin, "Search"), ui.lp(20, 20)); ui.gap(search, 7);
    EditText query = new EditText(this); query.setHint("搜索称呼、账号或家庭"); query.setTextSize(14); query.setTextColor(skin.ink); query.setHintTextColor(skin.muted); query.setSingleLine(true); query.setBackgroundColor(Color.TRANSPARENT); query.setPadding(0, 0, 0, 0);
    search.addView(query, new LinearLayout.LayoutParams(0, -1, 1)); ui.add(main, search, 48); ui.gap(main, 12);
    LinearLayout list = ui.col();
    adminAccountRows(list, "");
    query.addTextChangedListener(watch(value -> adminAccountRows(list, value)));
    ui.grow(main, region(list, "admin"));
  }

  private String familyName(String id) {
    if (accounts != null) for (int i = 0; i < array(accounts, "families").length(); i++) {
      JSONObject family = obj(array(accounts, "families"), i); if (family.optString("id").equals(id)) return family.optString("name");
    }
    return "全局管理";
  }

  private void adminAccountRows(LinearLayout list, String query) {
    list.removeAllViews();
    if (accounts == null) { ui.add(list, ui.label("正在加载账号…"), -2); return; }
    for (int i = 0; i < array(accounts, "users").length(); i++) {
      JSONObject u = obj(array(accounts, "users"), i);
      String family = familyName(u.optString("family_id"));
      if (!(u.optString("name") + " " + u.optString("username") + " " + family).toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT))) continue;
      String role = u.optString("role");
      LinearLayout card = wide ? ui.row() : ui.card(); ui.pad(card, 13); card.setBackground(ui.bg(skin.surface, 16, true));
      LinearLayout row = ui.row();
      row.addView(ui.iconBox(role.equals("child") ? "Star" : role.equals("parent") ? "Users" : "ShieldCheck", 20), ui.lp(38, 38)); ui.gap(row, 10);
      LinearLayout copy = ui.col(); ui.add(copy, ui.text(u.optString("name"), 15, true), -2); ui.gap(copy, 4);
      TextView username = ui.label(u.optString("username")); username.setTextSize(11); ui.add(copy, username, -2);
      row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
      TextView badge = ui.label(role.equals("child") ? "小朋友" : role.equals("parent") ? "家长" : "管理员"); badge.setTextSize(11); row.addView(badge);
      if (wide) { card.addView(row, new LinearLayout.LayoutParams(0, -2, 1)); ui.gap(card, 24); }
      else { ui.add(card, row, -2); ui.gap(card, 10); }
      LinearLayout foot = ui.row();
      TextView details = ui.label(family + " · " + (u.optInt("active", 1) == 1 ? "使用中" : "已停用")); details.setTextSize(11); foot.addView(details, wide ? ui.lp(210, -2) : new LinearLayout.LayoutParams(0, -2, 1));
      if (!role.equals("admin")) {
        LinearLayout edit = ui.iconBox("Pencil", 17); edit.setContentDescription("编辑账号 " + u.optString("name")); edit.setFocusable(true); edit.setOnClickListener(v -> editAccount(u)); foot.addView(edit, ui.lp(44, 44)); ui.gap(foot, 6);
        LinearLayout key = ui.iconBox("KeyRound", 17); key.setContentDescription("重置密码 " + u.optString("name")); key.setFocusable(true); key.setOnClickListener(v -> editAccount(u)); foot.addView(key, ui.lp(44, 44));
      }
      if (wide) card.addView(foot, ui.lp(-2, 44)); else ui.add(card, foot, 44);
      ui.add(list, card, -2); ui.gap(list, 8);
    }
    if (list.getChildCount() == 0) ui.add(list, center("没有找到匹配的账号"), 100);
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
