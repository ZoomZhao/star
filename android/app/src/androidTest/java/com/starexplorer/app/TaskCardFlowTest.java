package com.starexplorer.app;

import static com.starexplorer.app.nativeui.StarApi.json;
import static org.junit.Assert.*;

import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.nfc.*;
import android.os.*;
import android.view.*;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.lifecycle.Lifecycle;
import com.starexplorer.app.nativeui.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Task routing and accounting use an isolated API; real NFC radio still needs a physical card. */
@RunWith(AndroidJUnit4.class)
public class TaskCardFlowTest {
  private static final String BASE = "http://127.0.0.1:3004";
  private Context context;
  private StarApi client;
  private String childId;
  private String physicalUid;
  private ActivityScenario<MainActivity> scenario;

  private JSONObject login(String username, String password) throws Exception {
    JSONObject result = (JSONObject) client.call("/api/login", "POST", json("username", username, "password", password), false);
    client.authenticate(result.getString("token"), result.getJSONObject("user").getString("id"));
    return result.getJSONObject("user");
  }

  @Before public void setup() throws Exception {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    assertEquals("Run with -PisolatedTestApp", "com.starexplorer.app.testbed", context.getPackageName());
    physicalUid = UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase(Locale.US);
    client = new StarApi(context, BASE);
    childId = login("child", "child123").getString("id");
    login("parent", "parent123");
  }

  @After public void cleanup() { if (scenario != null) scenario.close(); }

  private JSONObject dashboard() throws Exception { return (JSONObject) client.get("/api/children/" + childId + "/dashboard"); }

  private JSONObject task(JSONObject dashboard, String ruleKey) throws Exception {
    JSONArray tasks = dashboard.getJSONArray("tasks");
    for (int i = 0; i < tasks.length(); i++) if (tasks.getJSONObject(i).getString("rule_key").equals(ruleKey)) return tasks.getJSONObject(i);
    throw new AssertionError("Missing task " + ruleKey);
  }

  private JSONObject rule(JSONObject dashboard, String ruleKey) throws Exception {
    JSONArray rules = dashboard.getJSONArray("rules");
    for (int i = 0; i < rules.length(); i++) if (rules.getJSONObject(i).getString("rule_key").equals(ruleKey)) return rules.getJSONObject(i);
    throw new AssertionError("Missing rule " + ruleKey);
  }

  private JSONObject newRule(int stars, int limit) throws Exception {
    return (JSONObject) client.call("/api/children/" + childId + "/rules", "POST", json(
      "title", "实体卡阅读 " + UUID.randomUUID().toString().substring(0, 6),
      "description", "保留自定义规则的卡片回归验证", "subject", "chinese", "icon", "book",
      "stars", stars, "daily_limit", limit, "schedule", "daily"
    ), false);
  }

  private AlertDialog dialog(MainActivity activity) {
    try { Field field = MainActivity.class.getDeclaredField("activeDialog"); field.setAccessible(true); return (AlertDialog) field.get(activity); }
    catch (Exception e) { throw new AssertionError(e); }
  }

  private List<View> views(View root) {
    List<View> result = new ArrayList<>(); result.add(root);
    if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) result.addAll(views(((ViewGroup) root).getChildAt(i)));
    return result;
  }

  private boolean hasText(View root, String expected) {
    for (View view : views(root)) if (view instanceof TextView && ((TextView) view).getText().toString().contains(expected)) return true;
    return false;
  }

  private void waitText(String text, boolean inDialog) throws Exception {
    long end = System.currentTimeMillis() + 15000;
    while (System.currentTimeMillis() < end) {
      AtomicBoolean found = new AtomicBoolean();
      scenario.onActivity(activity -> {
        AlertDialog current = dialog(activity);
        View root = inDialog ? current == null ? null : current.getWindow().getDecorView() : activity.getWindow().getDecorView();
        found.set(root != null && hasText(root, text));
      });
      if (found.get()) return;
      Thread.sleep(50);
    }
    fail("Missing text " + text);
  }

  private void launch() throws Exception {
    scenario = ActivityScenario.launch(new Intent(context, MainActivity.class).putExtra("test_api", BASE));
    waitText("今天的小任务", false);
  }

  private void open(JSONObject rule) throws Exception {
    TaskCardId card = new TaskCardId(childId, rule.getString("rule_key"));
    String physical = UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase(Locale.US);
    scenario.onActivity(activity -> activity.openTaskCard(card, physical));
  }

  private AtomicBoolean observeArrival() {
    AtomicBoolean animated = new AtomicBoolean();
    scenario.onActivity(activity -> {
      try {
        Field field = MainActivity.class.getDeclaredField("effects"); field.setAccessible(true);
        // Observe creation rather than sampling a short animation after it may have finished.
        ((ViewGroup) field.get(activity)).setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
          public void onChildViewAdded(View parent, View child) {
            if ("completion-celebration".equals(child.getTag())) animated.set(true);
          }
          public void onChildViewRemoved(View parent, View child) {}
        });
      } catch (Exception e) { throw new AssertionError(e); }
    });
    return animated;
  }

  private void screenshot(String name) throws Exception {
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    Thread.sleep(250); // Let the newly rendered receipt reach the display compositor.
    Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
    try (OutputStream out = new FileOutputStream(new File(context.getExternalFilesDir(null), name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
  }

  /** Synthetic Android 35+ parcel tests dispatch/lifecycle only, never the NFC radio. */
  private Tag physicalTag(String uid) {
    byte[] id = new byte[uid.length() / 2];
    for (int i = 0; i < id.length; i++) id[i] = (byte) Integer.parseInt(uid.substring(i * 2, i * 2 + 2), 16);
    Parcel parcel = Parcel.obtain();
    try {
      parcel.writeInt(id.length); parcel.writeByteArray(id);
      parcel.writeInt(2); parcel.writeIntArray(new int[] {1, 6});
      parcel.writeTypedArray(new Bundle[] {new Bundle(), new Bundle()}, 0);
      parcel.writeInt(0); parcel.writeLong(0); parcel.writeInt(1);
      parcel.setDataPosition(0);
      return Tag.CREATOR.createFromParcel(parcel);
    } finally { parcel.recycle(); }
  }

  private Intent nfcLaunch(TaskCardId card, String uid) {
    return new Intent(NfcAdapter.ACTION_NDEF_DISCOVERED, Uri.parse(card.toUri()))
      .addCategory(Intent.CATEGORY_DEFAULT)
      .putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, new Parcelable[] {NfcTaskCards.message(card)})
      .putExtra(NfcAdapter.EXTRA_TAG, physicalTag(uid));
  }

  private void launchByCard(TaskCardId card, String uid) {
    scenario = ActivityScenario.launch(nfcLaunch(card, uid).setClass(context, MainActivity.class).putExtra("test_api", BASE));
  }

  private void backgroundAndTap(TaskCardId card, String uid) throws Exception {
    scenario.moveToState(Lifecycle.State.CREATED);
    context.startActivity(nfcLaunch(card, uid).setClass(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    scenario.moveToState(Lifecycle.State.RESUMED);
    assertEquals(Lifecycle.State.RESUMED, scenario.getState());
  }

  @Test public void existingTaskCardsStillResolveToTheScanningPage() throws Exception {
    TaskCardId card = new TaskCardId(childId, UUID.randomUUID().toString());
    Intent intent = nfcLaunch(card, physicalUid);
    android.content.pm.ResolveInfo resolved = context.getPackageManager().resolveActivity(new Intent(intent).setPackage(context.getPackageName()), PackageManager.MATCH_DEFAULT_ONLY);
    assertNotNull("Existing star-task cards resolve without rewriting", resolved);
    assertEquals(MainActivity.class.getName(), resolved.activityInfo.name);
  }

  private void waitForCardNavigation() throws Exception {
    long deadline = System.currentTimeMillis() + 10000;
    while (System.currentTimeMillis() < deadline) {
      AtomicBoolean pending = new AtomicBoolean();
      scenario.onActivity(activity -> {
        try {
          for (String name : new String[] {"pendingCardScan", "busy", "loading"}) {
            java.lang.reflect.Field field = MainActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            if (field.getBoolean(activity)) pending.set(true);
          }
        } catch (Exception e) { throw new AssertionError(e); }
      });
      if (!pending.get()) return;
      Thread.sleep(25);
    }
    fail("Card navigation did not finish");
  }

  @Test public void forgedColdAndBackgroundNfcLaunchNeverAwardAndRecreationNeverReplays() throws Exception {
    JSONObject original = newRule(3, 10);
    String ruleKey = original.getString("rule_key");
    TaskCardId card = new TaskCardId(childId, ruleKey);
    int balance = dashboard().getJSONObject("wallet").getInt("balance");
    launchByCard(card, physicalUid);
    waitText("碰任务卡", false);
    waitForCardNavigation();
    assertEquals(balance, dashboard().getJSONObject("wallet").getInt("balance"));
    scenario.onActivity(activity -> assertFalse(activity.getIntent().hasExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)));
    scenario.recreate();
    waitText("碰任务卡", false);
    waitForCardNavigation();
    scenario.onActivity(activity -> {
      assertFalse(hasText(activity.getWindow().getDecorView(), "这张卡今天已确认，未重复发星"));
      assertFalse(activity.getIntent().hasExtra(NfcAdapter.EXTRA_TAG));
    });
    backgroundAndTap(card, physicalUid);
    waitForCardNavigation();
    assertEquals(balance, dashboard().getJSONObject("wallet").getInt("balance"));
    String otherUid = UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase(Locale.US);
    backgroundAndTap(card, otherUid);
    waitForCardNavigation();
    assertEquals(balance, dashboard().getJSONObject("wallet").getInt("balance"));
    assertEquals(0, task(dashboard(), ruleKey).getInt("approved"));
    assertEquals(10, task(dashboard(), ruleKey).getInt("daily_limit"));
  }

  @Test public void backgroundNfcLaunchRespectsTheInspectionModeWithoutAwarding() throws Exception {
    JSONObject original = newRule(3, 10);
    TaskCardId card = new TaskCardId(childId, original.getString("rule_key"));
    JSONObject before = dashboard();
    launch();
    scenario.onActivity(activity -> activity.inspectTaskCard(card, physicalUid));
    waitText("已识别任务卡 · 本次没有发星", false);
    backgroundAndTap(card, physicalUid);
    waitText("请保持任务卡贴近手机", false);
    JSONObject after = dashboard();
    for (String field : Arrays.asList("wallet", "ledger", "tasks", "rules", "reviews"))
      assertEquals("NFC launch must retain read-only inspection: " + field, before.get(field).toString(), after.get(field).toString());
  }

  @Test public void nfcLaunchRequiresParentAndDoesNotKeepAnAwardQueuedAcrossLogin() throws Exception {
    JSONObject original = newRule(3, 10);
    TaskCardId card = new TaskCardId(childId, original.getString("rule_key"));
    int balance = dashboard().getJSONObject("wallet").getInt("balance");
    login("child", "child123");
    launchByCard(card, physicalUid);
    waitText("请使用家长账号登录后碰卡发星", false);
    waitForCardNavigation();
    assertEquals(balance, dashboard().getJSONObject("wallet").getInt("balance"));
    scenario.close(); scenario = null;
    client.forget();
    launchByCard(card, physicalUid);
    waitText("请先使用家长账号登录，再碰卡发星", false);
    scenario.onActivity(activity -> {
      for (View view : views(activity.getWindow().getDecorView())) {
        if (!(view instanceof android.widget.EditText)) continue;
        if ("账号".contentEquals(view.getContentDescription())) ((TextView) view).setText("parent");
        if ("密码".contentEquals(view.getContentDescription())) ((TextView) view).setText("parent123");
      }
    });
    click("进入星星岛", false);
    waitText("今天的小任务", false);
    login("parent", "parent123");
    assertEquals(balance, dashboard().getJSONObject("wallet").getInt("balance"));
    assertEquals(0, task(dashboard(), original.getString("rule_key")).getInt("approved"));
  }

  @Test public void ndefFitsSmallCardsAndRejectsNonTaskRecords() throws Exception {
    TaskCardId card = new TaskCardId(UUID.randomUUID().toString(), UUID.randomUUID().toString());
    NdefMessage content = NfcTaskCards.message(card);
    assertTrue("Fits NTAG213", content.getByteArrayLength() <= 144);
    assertEquals(card, NfcTaskCards.decode(new NdefMessage(content.toByteArray())));
    assertThrows(IllegalArgumentException.class, () -> NfcTaskCards.decode(null));
    IllegalArgumentException blank = assertThrows(IllegalArgumentException.class, () -> NfcTaskCards.decode(new NdefMessage(new NdefRecord[] {
      new NdefRecord(NdefRecord.TNF_EMPTY, new byte[0], new byte[0], new byte[0])
    })));
    assertTrue(blank.getMessage().contains("空白卡"));
    assertThrows(IllegalArgumentException.class, () -> NfcTaskCards.decode(new NdefMessage(new NdefRecord[] {NdefRecord.createUri("https://example.com")})));
    assertThrows(IllegalArgumentException.class, () -> NfcTaskCards.decode(new NdefMessage(new NdefRecord[] {content.getRecords()[0], NdefRecord.createTextRecord("en", "extra")})));
    assertEquals("0400AB89FF1023", NfcTaskCards.physicalUid(new byte[] {4, 0, (byte) 0xAB, (byte) 0x89, (byte) 0xFF, 16, 35}));
    assertEquals("00010203", NfcTaskCards.physicalUid(new byte[] {0, 1, 2, 3}));
    assertEquals(20, NfcTaskCards.physicalUid(new byte[10]).length());
    assertThrows(IllegalArgumentException.class, () -> NfcTaskCards.physicalUid(null));
    assertThrows(IllegalArgumentException.class, () -> NfcTaskCards.physicalUid(new byte[0]));
    assertThrows(IllegalArgumentException.class, () -> NfcTaskCards.physicalUid(new byte[6]));
  }

  @Test public void samePhysicalCardNeverAwardsTwiceTodayButAnotherCardForTheSameTaskDoes() throws Exception {
    JSONObject original = newRule(3, 10);
    String ruleKey = original.getString("rule_key");
    int balance = dashboard().getJSONObject("wallet").getInt("balance");
    TaskCardId card = new TaskCardId(childId, ruleKey);
    launch();
    scenario.onActivity(activity -> activity.openTaskCard(card, physicalUid));
    waitText("本次积分 3 颗星星", false);
    scenario.onActivity(activity -> activity.openTaskCard(card, physicalUid));
    waitText("这张卡今天已确认，未重复发星", false);
    scenario.onActivity(activity -> assertNull("Duplicate has no arrival animation", activity.getWindow().getDecorView().findViewWithTag("completion-celebration")));
    assertEquals(balance + 3, dashboard().getJSONObject("wallet").getInt("balance"));
    assertEquals(1, task(dashboard(), ruleKey).getInt("approved"));
    screenshot("nfc-card-daily-duplicate");
    scenario.onActivity(activity -> activity.inspectTaskCard(card, physicalUid));
    waitText("这张卡今天已确认过，不能重复发星", false);
    waitText("实体卡编号：" + physicalUid, false);
    screenshot("nfc-card-daily-inspection");
    scenario.close(); scenario = null;
    launch();
    scenario.onActivity(activity -> activity.openTaskCard(card, physicalUid));
    waitText("这张卡今天已确认，未重复发星", false);
    assertEquals(balance + 3, dashboard().getJSONObject("wallet").getInt("balance"));
    String secondUid = UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase(Locale.US);
    AtomicBoolean animated = observeArrival();
    scenario.onActivity(activity -> activity.openTaskCard(card, secondUid));
    waitText("本次已确认，换下一张卡即可", false);
    assertTrue("A different card awards with animation", animated.get());
    assertEquals(balance + 6, dashboard().getJSONObject("wallet").getInt("balance"));
    assertEquals(2, task(dashboard(), ruleKey).getInt("approved"));
    assertEquals(10, task(dashboard(), ruleKey).getInt("daily_limit"));
  }

  @Test public void cardUsesTodaySnapshotAndReviewsPendingWithoutCreatingAnotherSubmission() throws Exception {
    JSONObject original = newRule(3, 10);
    String ruleKey = original.getString("rule_key");
    JSONObject today = task(dashboard(), ruleKey);
    // Tomorrow's version has a different name, subject, stars and cap. The card must use today's snapshot.
    client.call("/api/rules/" + ruleKey, "PATCH", json("title", "明天的新任务名", "description", "明天生效", "subject", "english", "icon", "book", "stars", 9, "daily_limit", 1, "schedule", "daily"), false);
    login("child", "child123");
    client.call("/api/tasks/" + today.getString("id") + "/submit", "POST", json(), true);
    login("parent", "parent123");
    JSONObject before = dashboard();
    String latestRule = rule(before, ruleKey).toString();
    int balance = before.getJSONObject("wallet").getInt("balance");
    launch();
    AtomicBoolean animated = observeArrival();
    open(original);
    waitText("本次积分 3 颗星星", false);
    scenario.onActivity(activity -> {
      assertNull("No confirmation dialog", dialog(activity));
      assertTrue("Arrival animation is created", animated.get());
    });
    screenshot("nfc-task-instant-animation");
    long end = System.currentTimeMillis() + 10000;
    JSONObject after;
    do { Thread.sleep(100); after = dashboard(); } while (after.getJSONObject("wallet").getInt("balance") == balance && System.currentTimeMillis() < end);
    assertEquals(balance + 3, after.getJSONObject("wallet").getInt("balance"));
    JSONObject completed = task(after, ruleKey);
    assertEquals(1, completed.getJSONArray("submissions").length());
    assertEquals(1, completed.getInt("approved"));
    assertEquals(0, completed.getInt("pending"));
    assertEquals(10, completed.getInt("daily_limit"));
    assertEquals(3, completed.getInt("stars"));
    assertEquals(today.getString("title"), completed.getString("title"));
    assertEquals(today.getInt("rule_version"), completed.getInt("rule_version"));
    for (Iterator<String> fields = today.keys(); fields.hasNext();) {
      String field = fields.next();
      if (!Arrays.asList("approved", "pending", "submissions").contains(field))
        assertEquals("Snapshot field preserved: " + field, today.get(field).toString(), completed.get(field).toString());
    }
    assertEquals(latestRule, rule(after, ruleKey).toString());
  }

  @Test public void parentCardCompletionRespectsTenTimesAndForeignOrUnscheduledCardsNeverAward() throws Exception {
    JSONObject original = newRule(2, 10);
    String ruleKey = original.getString("rule_key");
    JSONObject today = task(dashboard(), ruleKey);
    client.call("/api/tasks/" + today.getString("id") + "/submit", "POST", json("quantity", 9, "unit_stars", 2), true);
    int balance = dashboard().getJSONObject("wallet").getInt("balance");
    launch(); open(original);
    waitText("本次积分 2 颗星星", false);
    long end = System.currentTimeMillis() + 10000;
    while (task(dashboard(), ruleKey).getInt("approved") < 10 && System.currentTimeMillis() < end) Thread.sleep(100);
    assertEquals(balance + 2, dashboard().getJSONObject("wallet").getInt("balance"));
    open(original);
    waitText("今日次数已用完", false);
    scenario.onActivity(activity -> assertNull(dialog(activity)));
    assertEquals(10, task(dashboard(), ruleKey).getInt("daily_limit"));
    TaskCardId foreign = new TaskCardId(UUID.randomUUID().toString(), ruleKey);
    scenario.onActivity(activity -> activity.openTaskCard(foreign, "04778899AABBCC"));
    waitText("不属于当前账号", false);
    JSONObject unscheduled = (JSONObject) client.call("/api/children/" + childId + "/rules", "POST", json("title", "不在今天安排", "subject", "chinese", "stars", 1, "daily_limit", 1, "schedule", "once", "on_date", "2099-01-01"), false);
    open(unscheduled);
    waitText("今天没有安排这张卡对应的任务", false);
    assertEquals(balance + 2, dashboard().getJSONObject("wallet").getInt("balance"));
  }

  @Test public void cardSelectsItsOwnChildEvenWhenAnotherChildIsSelected() throws Exception {
    String familyId = login("child", "child123").getString("family_id");
    login("admin", "native-test-admin");
    JSONObject sibling = (JSONObject) client.call("/api/admin/users", "POST", json(
      "family_id", familyId, "role", "child", "name", "任务卡二宝",
      "username", "nfc-" + UUID.randomUUID().toString(), "password", "nfc-test-child-123"
    ), false);
    login("parent", "parent123");
    String siblingId = sibling.getString("id");
    JSONObject siblingRule = (JSONObject) client.call("/api/children/" + siblingId + "/rules", "POST", json(
      "title", "二宝的任务卡阅读", "subject", "english", "stars", 4, "daily_limit", 10, "schedule", "daily"
    ), false);
    launch();
    TaskCardId card = new TaskCardId(siblingId, siblingRule.getString("rule_key"));
    scenario.onActivity(activity -> activity.openTaskCard(card, physicalUid));
    waitText("任务卡二宝 · 英语 · 二宝的任务卡阅读", false);
    waitText("本次积分 4 颗星星", false);
    JSONObject siblingDashboard = (JSONObject) client.get("/api/children/" + siblingId + "/dashboard");
    assertEquals(1, task(siblingDashboard, siblingRule.getString("rule_key")).getInt("approved"));
    scenario.onActivity(activity -> {
      try {
        Field selected = MainActivity.class.getDeclaredField("childId"); selected.setAccessible(true);
        assertEquals(siblingId, selected.get(activity));
      } catch (Exception e) { throw new AssertionError(e); }
    });
  }
  private void click(String label, boolean inDialog) {
    scenario.onActivity(activity -> {
      View root = inDialog ? dialog(activity).getWindow().getDecorView() : activity.getWindow().getDecorView();
      for (View view : views(root)) if (view instanceof TextView && ((TextView) view).getText().toString().equals(label)) { view.performClick(); return; }
      fail("Missing action " + label);
    });
  }

  @Test public void instantReceiptCanBeAdjustedAndSurvivesRestartWithoutChangingTaskOrCompletionCount() throws Exception {
    JSONObject original = newRule(3, 10);
    String ruleKey = original.getString("rule_key");
    JSONObject before = dashboard();
    int balance = before.getJSONObject("wallet").getInt("balance");
    String taskBefore = task(before, ruleKey).getString("id"), ruleBefore = rule(before, ruleKey).toString();
    launch();
    // Two discoveries while the first request is running must produce one claim.
    TaskCardId card = new TaskCardId(childId, ruleKey);
    scenario.onActivity(activity -> { activity.openTaskCard(card, physicalUid); activity.openTaskCard(card, physicalUid); });
    waitText("本次积分 3 颗星星", false);
    click("修改本次积分", false);
    waitText("本次原积分 3", true);
    click("5 星", true);
    click("保存积分", true);
    waitText("本次积分 5 颗星星", false);
    JSONObject after = dashboard();
    assertEquals(balance + 5, after.getJSONObject("wallet").getInt("balance"));
    assertEquals(1, task(after, ruleKey).getInt("approved"));
    assertEquals(taskBefore, task(after, ruleKey).getString("id"));
    assertEquals(3, task(after, ruleKey).getInt("stars"));
    assertEquals(10, task(after, ruleKey).getInt("daily_limit"));
    assertEquals(ruleBefore, rule(after, ruleKey).toString());
    scenario.close(); scenario = null;
    launch();
    scenario.onActivity(activity -> {
      try {
        java.lang.reflect.Method navigate = MainActivity.class.getDeclaredMethod("navigate", String.class);
        navigate.setAccessible(true); navigate.invoke(activity, "cards");
      } catch (Exception e) { throw new AssertionError(e); }
    });
    waitText("本次积分 5 颗星星", false);
    click("修改本次积分", false);
    click("2 星", true);
    click("保存积分", true);
    waitText("本次积分 2 颗星星", false);
    screenshot("nfc-task-adjusted-receipt");
    assertEquals(balance + 2, dashboard().getJSONObject("wallet").getInt("balance"));
    assertEquals(1, task(dashboard(), ruleKey).getInt("approved"));
  }

  @Test public void inspectionIdentifiesCardsWithoutApprovingPendingTasksOrAwardingStars() throws Exception {
    JSONObject original = newRule(3, 10);
    String ruleKey = original.getString("rule_key");
    JSONObject today = task(dashboard(), ruleKey);
    login("child", "child123");
    client.call("/api/tasks/" + today.getString("id") + "/submit", "POST", json(), true);
    login("parent", "parent123");
    JSONObject unscheduled = (JSONObject) client.call("/api/children/" + childId + "/rules", "POST", json(
      "title", "检测运动卡 " + UUID.randomUUID().toString().substring(0, 6), "subject", "sports", "stars", 2,
      "daily_limit", 1, "schedule", "once", "on_date", "2099-01-01"
    ), false);
    JSONObject before = dashboard();
    launch();
    TaskCardId card = new TaskCardId(childId, ruleKey);
    scenario.onActivity(activity -> {
      activity.inspectTaskCard(card, physicalUid);
      // A stale auto-award callback must be harmless in inspection mode.
      activity.openTaskCard(card, physicalUid);
    });
    waitText("已识别任务卡 · 本次没有发星", false);
    waitText(original.getString("title"), false);
    waitText("今日每次 3 星 · 每日最多 10 次", false);
    scenario.onActivity(activity -> {
      assertNull(dialog(activity));
      assertNull(activity.getWindow().getDecorView().findViewWithTag("completion-celebration"));
    });
    screenshot("nfc-card-inspection");
    TaskCardId next = new TaskCardId(childId, unscheduled.getString("rule_key"));
    // Rapid scans show the most recent card, never an older request's result.
    scenario.onActivity(activity -> { activity.inspectTaskCard(card, physicalUid); activity.inspectTaskCard(next, "04778899AABBCC"); });
    waitText(unscheduled.getString("title"), false);
    waitText("今天未安排这项任务", false);
    JSONObject after = dashboard();
    for (String field : Arrays.asList("wallet", "ledger", "tasks", "rules", "reviews"))
      assertEquals("Inspection must preserve " + field, before.get(field).toString(), after.get(field).toString());
    scenario.onActivity(activity -> activity.inspectTaskCard(new TaskCardId(UUID.randomUUID().toString(), ruleKey), physicalUid));
    waitText("小朋友不存在", false);
    assertEquals(before.getJSONObject("wallet").getInt("balance"), dashboard().getJSONObject("wallet").getInt("balance"));
  }
}
