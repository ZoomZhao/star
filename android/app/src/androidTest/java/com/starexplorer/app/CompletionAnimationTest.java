package com.starexplorer.app;

import static com.starexplorer.app.nativeui.StarApi.json;
import static org.junit.Assert.*;

import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.starexplorer.app.nativeui.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Run only with -PisolatedTestApp: never opens or changes the daily-use application. */
@RunWith(AndroidJUnit4.class)
public class CompletionAnimationTest {

  private static final String BASE = "http://127.0.0.1:3004";
  ActivityScenario<MainActivity> scenario;
  Context context;
  StarApi client;

  List<View> views(View root) {
    List<View> all = new ArrayList<>();
    all.add(root);
    if (root instanceof ViewGroup) for (
      int i = 0;
      i < ((ViewGroup) root).getChildCount();
      i++
    ) all.addAll(views(((ViewGroup) root).getChildAt(i)));
    return all;
  }

  void waitFor(Predicate<View> predicate) throws Exception {
    long end = System.currentTimeMillis() + 15000;
    while (System.currentTimeMillis() < end) {
      AtomicBoolean found = new AtomicBoolean();
      scenario.onActivity(a -> {
        for (View v : views(a.getWindow().getDecorView()))
          if (v.isShown() && predicate.test(v)) found.set(true);
      });
      if (found.get()) return;
      Thread.sleep(25);
    }
    fail("Expected view did not appear");
  }

  boolean text(View v, String text) {
    return v instanceof TextView && ((TextView) v).getText().toString().equals(text);
  }

  void screenshot(String name) throws Exception {
    Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
    try (
      OutputStream out = new FileOutputStream(
        new File(context.getExternalFilesDir(null), name + ".png")
      )
    ) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
    }
  }

  @After
  public void cleanup() {
    if (scenario != null) scenario.close();
  }

  @Test
  public void celebrationSurvivesRefreshWithoutAwardingOrDuplicatingPendingStars()
    throws Exception {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    assertEquals(
      "Use the isolated test application",
      "com.starexplorer.app.testbed",
      context.getPackageName()
    );
    client = new StarApi(context, BASE);
    JSONObject login = (JSONObject) client.call(
      "/api/login",
      "POST",
      json("username", "child", "password", "child123"),
      false
    );
    client.authenticate(login.getString("token"));
    JSONObject child = (JSONObject) client.get("/api/me");
    String path = "/api/children/" + child.getString("id") + "/dashboard";
    JSONObject before = (JSONObject) client.get(path);
    int balance = before.getJSONObject("wallet").getInt("balance");
    int pending = before.getJSONArray("tasks").getJSONObject(0).getInt("pending");
    int iteration = 0;
    for (String skin : new String[] { "dino", "princess" }) {
      context
        .getSharedPreferences(MainActivity.class.getName(), 0)
        .edit()
        .putString("skin", skin)
        .commit();
      scenario = ActivityScenario.launch(
        new Intent(context, MainActivity.class).putExtra("test_api", BASE)
      );
      scenario.onActivity(a ->
        a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
      );
      waitFor(v -> text(v, "朗读语文课文"));
      waitFor(v -> text(v, skin.equals("princess") ? "星星公主" : "星星探险家"));
      waitFor(v -> v instanceof JourneyView && v.getWidth() > 0);
      Thread.sleep(800);
      scenario.onActivity(a -> {
        for (View v : views(a.getWindow().getDecorView()))
          if (text(v, "我完成啦")) {
            v.performClick();
            break;
          }
      });
      Thread.sleep(400);
      screenshot("completion-" + skin + "-confirm");
      // Dialog positive action is deliberately invoked twice; busy must suppress duplication.
      int expectedPending = pending + iteration + 1;
      AtomicBoolean refreshedWithAnimation = new AtomicBoolean();
      scenario.onActivity(a ->
        a
          .getWindow()
          .getDecorView()
          .getViewTreeObserver()
          .addOnGlobalLayoutListener(() -> {
            boolean updated = false,
              animated = false;
            for (View v : views(a.getWindow().getDecorView())) {
              animated |= v instanceof SparkleView;
              updated |=
                v.getContentDescription() != null &&
                v
                  .getContentDescription()
                  .toString()
                  .contains("待确认 " + expectedPending);
            }
            if (updated && animated) refreshedWithAnimation.set(true);
          })
      );
      androidx.test.espresso.Espresso.onView(
        androidx.test.espresso.matcher.ViewMatchers.withText("提交给家长确认")
      ).perform(
        new androidx.test.espresso.ViewAction() {
          public org.hamcrest.Matcher<View> getConstraints() {
            return androidx.test.espresso.matcher.ViewMatchers.isEnabled();
          }

          public String getDescription() {
            return "Rapid double click on submit";
          }

          public void perform(androidx.test.espresso.UiController controller, View v) {
            v.performClick();
            v.performClick();
          }
        }
      );
      waitFor(
        v ->
          v.getContentDescription() != null &&
          v
            .getContentDescription()
            .toString()
            .contains("待确认 " + expectedPending)
      );
      // The pending view is from the refreshed dashboard. Its overlay must still be present.
      assertTrue(
        "Dashboard refresh must preserve the active celebration",
        refreshedWithAnimation.get()
      );
      Thread.sleep(450);
      screenshot("completion-" + skin + "-burst");
      JSONObject after = (JSONObject) client.get(path);
      assertEquals(balance, after.getJSONObject("wallet").getInt("balance"));
      iteration++;
      assertEquals(
        pending + iteration,
        after.getJSONArray("tasks").getJSONObject(0).getInt("pending")
      );
      Thread.sleep(2800);
      scenario.onActivity(a -> {
        for (View v : views(a.getWindow().getDecorView()))
          assertFalse("Animation must clean up", v instanceof SparkleView);
      });
      scenario.close();
      scenario = null;
    }
  }
}
