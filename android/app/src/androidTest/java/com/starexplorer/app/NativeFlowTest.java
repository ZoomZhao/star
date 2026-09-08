package com.starexplorer.app;

import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static com.starexplorer.app.nativeui.StarApi.json;
import static org.junit.Assert.*;

import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.starexplorer.app.nativeui.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Runs only against the isolated local fixture forwarded through adb port 3004. */
@RunWith(AndroidJUnit4.class)
public class NativeFlowTest {

  static final String BASE = "http://127.0.0.1:3004";
  Context context;
  ActivityScenario<MainActivity> scenario;
  StarApi client;
  String originalSkin;

  @Before
  public void setup() {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    assertEquals(
      "Run with -PisolatedTestApp",
      "com.starexplorer.app.testbed",
      context.getPackageName()
    );
    client = new StarApi(context, BASE);
    client.forget();
    originalSkin = context
      .getSharedPreferences(
        context.getPackageName().equals("com.starexplorer.app")
          ? "MainActivity"
          : MainActivity.class.getName(),
        0
      )
      .getString("skin", "dino");
    context
      .getSharedPreferences(
        context.getPackageName().equals("com.starexplorer.app")
          ? "MainActivity"
          : MainActivity.class.getName(),
        0
      )
      .edit()
      .putString("skin", "dino")
      .commit();
  }

  @After
  public void cleanup() {
    if (scenario != null) scenario.close();
    context
      .getSharedPreferences(
        context.getPackageName().equals("com.starexplorer.app")
          ? "MainActivity"
          : MainActivity.class.getName(),
        0
      )
      .edit()
      .putString("skin", originalSkin)
      .commit();
  }

  void start() {
    Intent i = new Intent(context, MainActivity.class).putExtra("test_api", BASE);
    scenario = ActivityScenario.launch(i);
    scenario.onActivity(a -> a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
  }

  void auth(String username, String password) throws Exception {
    JSONObject r = (JSONObject) client.call(
      "/api/login",
      "POST",
      json("username", username, "password", password),
      false
    );
    client.authenticate(r.getString("token"));
  }

  void waitText(String text) throws Exception {
    long end = System.currentTimeMillis() + 18000;
    while (System.currentTimeMillis() < end) {
      AtomicBoolean found = new AtomicBoolean();
      scenario.onActivity(a -> {
        for (View v : views(a.getWindow().getDecorView()))
          if (
            ((v instanceof TextView && ((TextView) v).getText().toString().contains(text)) ||
              (v.getContentDescription() != null &&
                v.getContentDescription().toString().contains(text))) &&
            v.isShown()
          ) found.set(true);
      });
      if (found.get()) return;
      Thread.sleep(100);
    }
    throw new AssertionError("Missing text: " + text);
  }

  List<View> views(View v) {
    ArrayList<View> all = new ArrayList<>();
    all.add(v);
    if (v instanceof ViewGroup) for (
      int i = 0;
      i < ((ViewGroup) v).getChildCount();
      i++
    ) all.addAll(views(((ViewGroup) v).getChildAt(i)));
    return all;
  }

  void screenshot(String name) throws Exception {
    Bitmap b = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
    try (
      OutputStream out = new FileOutputStream(
        new File(context.getExternalFilesDir(null), name + ".png")
      )
    ) {
      b.compress(Bitmap.CompressFormat.PNG, 100, out);
    }
  }

  @Test
  public void loginPersistsNativeLandscapeFitsAndSkinsSurviveRecreation() throws Exception {
    start();
    onView(withContentDescription("账号")).perform(replaceText("child"));
    onView(withContentDescription("密码")).perform(
      replaceText("child123"),
      androidx.test.espresso.action.ViewActions.closeSoftKeyboard()
    );
    onView(withText("进入星星岛")).perform(click());
    waitText("朗读语文课文");
    assertTrue(new StarApi(context, BASE).hasSession());
    scenario.onActivity(a -> {
      int actions = 0;
      for (View v : views(a.getWindow().getDecorView())) {
        assertFalse("No WebView in native activity", v.getClass().getName().contains("WebView"));
        if (v instanceof ScrollView) assertEquals(
          "Only task region scrolls",
          "task-scroll",
          v.getTag()
        );
        if ("task-action".equals(v.getTag())) {
          actions++;
          Rect visible = new Rect();
          if (actions <= 4) {
            assertTrue(v.getGlobalVisibleRect(visible));
            assertEquals("First four buttons completely visible", v.getHeight(), visible.height());
          }
          assertTrue(v.getHeight() >= 48 * a.getResources().getDisplayMetrics().density);
        }
      }
      assertTrue(actions >= 10);
    });
    screenshot("native-dino-landscape");
    onView(withText("英语")).perform(click());
    waitText("读英文绘本");
    onView(withText("全部")).perform(click());
    scenario.onActivity(a -> {
      for (View v : views(a.getWindow().getDecorView()))
        if ("task-scroll".equals(v.getTag())) ((ScrollView) v).smoothScrollTo(
          0,
          ((ScrollView) v).getHeight()
        );
    });
    waitText("读英文绘本");
    onView(withText("换装")).perform(click());
    onView(withText("穿上这套皮肤")).perform(click());
    waitText("星星公主");
    screenshot("native-princess-landscape");
    scenario.recreate();
    waitText("星星公主");
    waitText("读英文绘本");
    scenario.close();
    start();
    waitText("星星公主");
    waitText("朗读语文课文");
    scenario.onActivity(a -> a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
    Thread.sleep(800);
    screenshot("native-princess-portrait");
  }

  @Test
  public void childSubmissionParentReviewAndCategoryRuleEditing() throws Exception {
    auth("child", "child123");
    JSONObject child = (JSONObject) client.get("/api/me");
    JSONObject dashboard = (JSONObject) client.get(
      "/api/children/" + child.getString("id") + "/dashboard"
    );
    JSONObject task = dashboard.getJSONArray("tasks").getJSONObject(0);
    int balance = dashboard.getJSONObject("wallet").getInt("balance");
    start();
    waitText(task.getString("title"));
    onView(withText(task.getString("title"))).perform(click());
    onView(withText("提交给家长确认")).perform(click());
    waitText("待确认 1");
    screenshot("native-stars-pending");
    JSONObject updated = (JSONObject) client.get(
      "/api/children/" + child.getString("id") + "/dashboard"
    );
    assertEquals(balance, updated.getJSONObject("wallet").getInt("balance"));
    scenario.close();
    auth("parent", "parent123");
    start();
    waitText("家长空间");
    onView(withContentDescription("审核")).perform(click());
    waitText(task.getString("title"));
    onView(withText("通过")).perform(click());
    onView(withText("通过")).perform(click());
    Thread.sleep(700);
    JSONObject approved = (JSONObject) client.get(
      "/api/children/" + child.getString("id") + "/dashboard"
    );
    assertEquals(
      balance + task.getInt("stars"),
      approved.getJSONObject("wallet").getInt("balance")
    );
    scenario.close();
    auth("child", "child123");
    start();
    waitText(task.getString("title"));
    screenshot("native-stars-approved");
    scenario.close();
    auth("parent", "parent123");
    start();
    waitText("家长空间");
    onView(withContentDescription("规则")).perform(click());
    waitText("科目与任务规则");
    onView(withText("＋ 添加")).perform(click());
    onView(withText("选用预制模板")).perform(click());
    onView(withText("朗读语文课文  ＋")).perform(click());
    onView(withText("保存规则")).perform(click());
    Thread.sleep(700);
    JSONObject rules = (JSONObject) client.get(
      "/api/children/" + child.getString("id") + "/dashboard"
    );
    assertEquals(
      dashboard.getJSONArray("rules").length() + 1,
      rules.getJSONArray("rules").length()
    );
  }
}
