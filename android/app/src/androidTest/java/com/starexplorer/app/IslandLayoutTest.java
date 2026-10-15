package com.starexplorer.app;

import static com.starexplorer.app.nativeui.StarApi.json;
import static org.junit.Assert.*;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.starexplorer.app.nativeui.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;
import org.junit.*;

/** Visual and data-preservation checks in the isolated application and in-memory fixture. */
public class IslandLayoutTest {
  private ActivityScenario<MainActivity> scenario;
  private Context context;
  private StarApi client;
  private String prefix;
  private List<View> views(View root) {
    List<View> all = new ArrayList<>(); all.add(root);
    if (root instanceof ViewGroup) for (int i=0;i<((ViewGroup)root).getChildCount();i++) all.addAll(views(((ViewGroup)root).getChildAt(i)));
    return all;
  }
  private void waitText(String expected) throws Exception {
    long end=System.currentTimeMillis()+15000;
    while(System.currentTimeMillis()<end) {
      AtomicBoolean found=new AtomicBoolean();
      scenario.onActivity(a->{ for(View v:views(a.getWindow().getDecorView())) if(v instanceof TextView && ((TextView)v).getText().toString().contains(expected) && v.isShown()) found.set(true); });
      if(found.get()) return; Thread.sleep(50);
    }
    fail("Missing " + expected);
  }
  private void click(String label) {
    scenario.onActivity(a->{for(View v:views(a.getWindow().getDecorView())) {
      if((v.getContentDescription()!=null && v.getContentDescription().toString().startsWith(label)) || (v instanceof TextView && label.contentEquals(((TextView)v).getText()))) {v.performClick();return;}
    } fail("Missing action " + label);});
  }
  private void capture(String name) throws Exception {
    InstrumentationRegistry.getInstrumentation().waitForIdleSync(); Thread.sleep(300);
    Bitmap b=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
    try(OutputStream out=new FileOutputStream(new File(context.getExternalFilesDir(null),prefix+"-"+name+".png"))) {b.compress(Bitmap.CompressFormat.PNG,100,out);}
  }
  @After public void close() {if(scenario!=null) scenario.close();}
  @Test public void compactNavigationKeepsAllManagementReachableWithoutChangingData() throws Exception {
    context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    assertEquals("com.starexplorer.app.testbed",context.getPackageName());
    prefix=InstrumentationRegistry.getArguments().getString("form","phone");
    client=new StarApi(context,"http://127.0.0.1:3004");
    JSONObject childLogin=(JSONObject)client.call("/api/login","POST",json("username","child","password","child123"),false);
    String childId=childLogin.getJSONObject("user").getString("id");
    JSONObject parentLogin=(JSONObject)client.call("/api/login","POST",json("username","parent","password","parent123"),false);client.authenticate(parentLogin.getString("token"), parentLogin.getJSONObject("user").getString("id"));
    String path="/api/children/"+childId;
    client.call(path+"/rules","POST",json("title","阅读绘本并和爸爸妈妈分享今天最喜欢的故事片段", "description","保留自定义每日十次规则", "subject","chinese","stars",3,"daily_limit",10,"schedule","daily"),false);
    JSONObject before=(JSONObject)client.get(path+"/dashboard");
    context.getSharedPreferences(MainActivity.class.getName(),0).edit().putString("skin","dino").putInt("lockedOrientation",-1).commit();
    scenario=ActivityScenario.launch(new Intent(context,MainActivity.class).putExtra("test_api","http://127.0.0.1:3004"));
    waitText("今天的小任务");
    scenario.onActivity(a->{
      int visibleActions=0; Rect r=new Rect();
      for(View v:views(a.getWindow().getDecorView())) {
        if("task-action".equals(v.getTag())) {
          assertTrue(v.getHeight()>=44*a.getResources().getDisplayMetrics().density);
          if(v.getGlobalVisibleRect(r) && r.height()==v.getHeight()) visibleActions++;
        }
      }
      assertTrue("At least two complete task actions fit the first screen",visibleActions>=2);
    });
    capture("tasks");
    click("更多");waitText("任务管理");capture("more");
    click("任务规则");waitText("科目与任务规则");capture("rules");
    click("星星口袋");waitText("星星收支记录");capture("wallet");
    click("心愿");waitText("奖励小铺管理");capture("shop");
    JSONObject after=(JSONObject)client.get(path+"/dashboard");
    for(String key:new String[]{"rules","tasks","ledger","wallet","reviews","rewards"}) assertEquals("UI navigation preserves " + key,before.get(key).toString(),after.get(key).toString());
  }
  @Test public void everyRoleAndBundledIconRenders() throws Exception {
    context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    assertEquals("com.starexplorer.app.testbed",context.getPackageName());
    prefix=InstrumentationRegistry.getArguments().getString("form","phone");
    for(String file:context.getAssets().list("ui/v2")) {
      try(InputStream in=context.getAssets().open("ui/v2/"+file)) { assertNotNull("Icon " + file,BitmapFactory.decodeStream(in)); }
    }
    assertEquals(39,context.getAssets().list("ui/v2").length);
    for(String role:new String[]{"child","admin"}) {
      client=new StarApi(context,"http://127.0.0.1:3004");
      JSONObject login=(JSONObject)client.call("/api/login","POST",json("username",role,"password",role.equals("admin")?"native-test-admin":"child123"),false);
      client.authenticate(login.getString("token"), login.getJSONObject("user").getString("id"));
      scenario=ActivityScenario.launch(new Intent(context,MainActivity.class).putExtra("test_api","http://127.0.0.1:3004"));
      waitText(role.equals("admin")?"账号分配与数据备份":"今天的小任务");
      capture(role);
      scenario.close(); scenario=null;
    }
  }
}
