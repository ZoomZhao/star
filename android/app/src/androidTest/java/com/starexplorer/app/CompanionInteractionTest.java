package com.starexplorer.app;

import static com.starexplorer.app.nativeui.StarApi.json;
import static org.junit.Assert.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.*;
import android.view.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.starexplorer.app.nativeui.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.*;
import org.junit.Test;

public class CompanionInteractionTest extends CompletionAnimationTest {
  private long pixels() {
    AtomicLong hash = new AtomicLong();
    scenario.onActivity(a -> {
      for (View v : views(a.getWindow().getDecorView())) if (v instanceof CompanionView && v.isShown()) {
        Bitmap b = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b));
        long sum=1; for (int y=0;y<b.getHeight();y+=11) for (int x=0;x<b.getWidth();x+=11) sum=31*sum+b.getPixel(x,y);
        hash.set(sum);b.recycle();return;
      }
      fail("Companion missing");
    });
    return hash.get();
  }

  @Test public void allFiveCompanionsReactAndResetWithoutChangingTasksOrStars() throws Exception {
    context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    assertEquals("com.starexplorer.app.testbed",context.getPackageName());
    client=new StarApi(context,"http://127.0.0.1:3004");
    JSONObject login=(JSONObject)client.call("/api/login","POST",json("username","child","password","child123"),false);
    client.authenticate(login.getString("token"));
    String path="/api/children/"+login.getJSONObject("user").getString("id")+"/dashboard";
    JSONObject before=(JSONObject)client.get(path);
    assertEquals(5,Skin.values().length);
    String filter = InstrumentationRegistry.getArguments().getString("skin", "");
    for (Skin skin : Skin.values()) {
      if (!filter.isEmpty() && !java.util.Arrays.asList(filter.split(",")).contains(skin.key)) continue;
      try (java.io.InputStream in=context.getAssets().open("companions/"+skin.key+".webp")) {
        Bitmap b=BitmapFactory.decodeStream(in);assertNotNull(b);assertTrue(b.hasAlpha());b.recycle();
      }
      context.getSharedPreferences(MainActivity.class.getName(),0).edit().putString("skin",skin.key).commit();
      scenario=ActivityScenario.launch(new Intent(context,MainActivity.class).putExtra("test_api","http://127.0.0.1:3004"));
      scenario.onActivity(a->a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
      waitFor(v->v instanceof CompanionView && v.getWidth()>0);
      Thread.sleep(350);
      long initial=pixels();
      screenshot("companion-"+skin.key+"-idle");
      scenario.onActivity(a->{for(View v:views(a.getWindow().getDecorView()))if(v instanceof CompanionView){assertTrue(v.isFocusable());v.performClick();}});
      Thread.sleep(200);
      assertNotEquals(initial,pixels());
      screenshot("companion-"+skin.key+"-tap");
      scenario.onActivity(a->{for(View v:views(a.getWindow().getDecorView()))if(v instanceof CompanionView){for(int n=0;n<8;n++)v.performClick();}});
      Thread.sleep(2100);
      assertEquals(initial,pixels());
      scenario.recreate();
      waitFor(v->v instanceof CompanionView && v.getWidth()>0);
      assertEquals(skin.key,context.getSharedPreferences(MainActivity.class.getName(),0).getString("skin",""));
      scenario.close();scenario=null;
    }
    JSONObject after=(JSONObject)client.get(path);
    assertEquals(before.getJSONObject("wallet").toString(),after.getJSONObject("wallet").toString());
    assertEquals(before.getJSONArray("tasks").toString(),after.getJSONArray("tasks").toString());
  }
}
