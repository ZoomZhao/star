package com.starexplorer.app;

import static com.starexplorer.app.nativeui.StarApi.json;
import static org.junit.Assert.*;

import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.starexplorer.app.nativeui.*;
import org.json.*;
import org.junit.Test;

/** Includes the inherited completion animation regression, in the isolated application only. */
public class RefinementTest extends CompletionAnimationTest {

  private static final String BASE = "http://127.0.0.1:3004";

  private void authenticate(String username, String password) throws Exception {
    JSONObject result = (JSONObject) client.call(
      "/api/login",
      "POST",
      json("username", username, "password", password),
      false
    );
    client.authenticate(result.getString("token"));
  }

  private void clickLabel(String label) {
    scenario.onActivity(a -> {
      for (View v : views(a.getWindow().getDecorView()))
        if (text(v, label)) {
          v.performClick();
          return;
        }
      fail("Missing label " + label);
    });
  }

  private void nav(String label) {
    scenario.onActivity(a -> {
      for (View v : views(a.getWindow().getDecorView()))
        if (
          label.contentEquals(v.getContentDescription() == null ? "" : v.getContentDescription())
        ) {
          v.performClick();
          return;
        }
      fail("Missing navigation " + label);
    });
  }

  private void launch(String skin) throws Exception {
    context
      .getSharedPreferences(MainActivity.class.getName(), 0)
      .edit()
      .putString("skin", skin)
      .commit();
    scenario = ActivityScenario.launch(
      new Intent(context, MainActivity.class).putExtra("test_api", BASE)
    );
    scenario.onActivity(a -> a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
    waitFor(v -> v instanceof JourneyView && v.getWidth() > 0);
    Thread.sleep(800);
  }

  private ScrollView scroll(MainActivity activity, String tag) {
    for (View v : views(activity.getWindow().getDecorView()))
      if (tag.equals(v.getTag())) return (ScrollView) v;
    throw new AssertionError("Missing scroll region " + tag);
  }

  @Test
  public void refinedScreensKeepControlsFixedAndPreserveParentManagement() throws Exception {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    assertEquals("com.starexplorer.app.testbed", context.getPackageName());
    client = new StarApi(context, BASE);
    authenticate("child", "child123");
    JSONObject child = (JSONObject) client.get("/api/me");
    String childPath = "/api/children/" + child.getString("id");
    authenticate("parent", "parent123");
    for (int i = 0; i < 4; i++) client.call(
      childPath + "/rules",
      "POST",
      json(
        "title",
        "英语加练 " + i,
        "subject",
        "english",
        "stars",
        1,
        "daily_limit",
        2,
        "schedule",
        "daily"
      ),
      false
    );
    for (int i = 0; i < 4; i++) client.call(
      childPath + "/rewards",
      "POST",
      json(
        "title",
        "测试心愿 " + i,
        "icon",
        i == 0 ? "picnic" : "gift",
        "cost",
        i == 3 ? 1000 : 2,
        "active",
        true
      ),
      false
    );
    for (int i = 0; i < 10; i++) client.call(
      childPath + "/ledger",
      "POST",
      json("title", "测试额外奖励 " + i, "kind", "bonus", "amount", 1),
      true
    );
    authenticate("child", "child123");
    for (String skin : new String[] { "dino", "princess" }) {
      launch(skin);
      waitFor(v -> text(v, "朗读语文课文"));
      waitFor(v -> text(v, skin.equals("princess") ? "星星公主" : "星星探险家"));
      scenario.onActivity(a -> {
        int scrolls = 0;
        for (View v : views(a.getWindow().getDecorView())) {
          if (v instanceof ScrollView) {
            scrolls++;
            assertEquals("task-scroll", v.getTag());
          }
          if (v instanceof TextView) assertFalse(
            ((TextView) v).getText().toString().contains("当日可得")
          );
        }
        assertEquals(1, scrolls);
      });
      screenshot("refined-" + skin + "-daily");
      clickLabel("英语");
      waitFor(v -> text(v, "英语加练 3"));
      final Rect fixed = new Rect();
      scenario.onActivity(a -> {
        for (View v : views(a.getWindow().getDecorView()))
          if ("subject-strip".equals(v.getTag())) v.getGlobalVisibleRect(fixed);
        ScrollView region = scroll(a, "task-scroll");
        assertEquals(0, region.getScrollY());
        region.scrollTo(0, region.getChildAt(0).getHeight());
      });
      Thread.sleep(200);
      scenario.onActivity(a -> {
        ScrollView region = scroll(a, "task-scroll");
        assertTrue(region.getScrollY() > 0);
        for (View v : views(a.getWindow().getDecorView()))
          if ("subject-strip".equals(v.getTag())) {
            Rect now = new Rect();
            v.getGlobalVisibleRect(now);
            assertEquals(fixed, now);
          }
      });
      screenshot("refined-" + skin + "-filtered-scroll");
      nav("星星宝库");
      waitFor(v -> text(v, "星星收支记录"));
      screenshot("refined-" + skin + "-wallet");
      scenario.onActivity(a -> scroll(a, "ledger-scroll").fullScroll(View.FOCUS_DOWN));
      Thread.sleep(150);
      scenario.onActivity(a -> assertTrue(scroll(a, "ledger-scroll").getScrollY() > 0));
      nav("心愿小铺");
      waitFor(v -> text(v, "测试心愿 3"));
      screenshot("refined-" + skin + "-shop");
      scenario.onActivity(a -> scroll(a, "shop-scroll").fullScroll(View.FOCUS_DOWN));
      Thread.sleep(150);
      scenario.onActivity(a -> assertTrue(scroll(a, "shop-scroll").getScrollY() > 0));
      // Returning to the task tab keeps its independently stored scroll position.
      nav("今日任务");
      waitFor(v -> text(v, "英语加练 3"));
      Thread.sleep(100);
      scenario.onActivity(a -> assertTrue(scroll(a, "task-scroll").getScrollY() > 0));
      scenario.close();
      scenario = null;
    }
    authenticate("parent", "parent123");
    launch("princess");
    waitFor(v -> text(v, "朗读语文课文"));
    nav("小铺");
    waitFor(v -> text(v, "奖励小铺管理"));
    scenario.onActivity(a -> {
      boolean edit = false,
        down = false,
        review = false;
      for (View v : views(a.getWindow().getDecorView())) {
        edit |= text(v, "编辑心愿");
        down |= text(v, "下架");
        review |= text(v, "审核兑换");
      }
      assertTrue(edit && down && review);
    });
    screenshot("refined-parent-shop");
  }
}
