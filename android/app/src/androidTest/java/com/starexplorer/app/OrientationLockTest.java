package com.starexplorer.app;

import static org.junit.Assert.*;
import static com.starexplorer.app.nativeui.StarApi.json;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.view.View;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.starexplorer.app.nativeui.StarApi;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.json.JSONObject;
import org.junit.Test;

public class OrientationLockTest extends CompletionAnimationTest {
  private AlertDialog settings(MainActivity activity) {
    try {
      Method settings = MainActivity.class.getDeclaredMethod("settings");
      settings.setAccessible(true);
      settings.invoke(activity);
      Field dialog = MainActivity.class.getDeclaredField("activeDialog");
      dialog.setAccessible(true);
      return (AlertDialog) dialog.get(activity);
    } catch (Exception e) { throw new AssertionError(e); }
  }

  private SwitchCompat toggle(AlertDialog dialog) {
    for (View view : views(dialog.getWindow().getDecorView()))
      if (view instanceof SwitchCompat) return (SwitchCompat) view;
    throw new AssertionError("Missing orientation switch");
  }

  @Test public void lockPersistsAfterRecreationAndCanBeReleased() throws Exception {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    assertEquals("com.starexplorer.app.testbed", context.getPackageName());
    client = new StarApi(context, "http://127.0.0.1:3004");
    JSONObject login = (JSONObject) client.call("/api/login", "POST", json("username", "child", "password", "child123"), false);
    client.authenticate(login.getString("token"));
    context.getSharedPreferences(MainActivity.class.getName(), 0).edit().remove("lockedOrientation").commit();
    scenario = ActivityScenario.launch(new Intent(context, MainActivity.class).putExtra("test_api", "http://127.0.0.1:3004"));
    waitFor(v -> text(v, "设置"));
    scenario.onActivity(a -> a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
    Thread.sleep(700);
    scenario.onActivity(a -> {
      AlertDialog dialog = settings(a);
      SwitchCompat lock = toggle(dialog);
      assertFalse(lock.isChecked());
      lock.performClick();
      assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, a.getRequestedOrientation());
      View close = dialog.getWindow().getDecorView().findViewWithTag("dialog-close");
      assertEquals("关闭", close.getContentDescription());
      close.performClick();
      assertFalse(dialog.isShowing());
    });
    scenario.recreate();
    waitFor(v -> text(v, "设置"));
    scenario.onActivity(a -> {
      assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, a.getRequestedOrientation());
      SwitchCompat lock = toggle(settings(a));
      assertTrue(lock.isChecked());
      lock.performClick();
      assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, a.getRequestedOrientation());
    });
  }
}
