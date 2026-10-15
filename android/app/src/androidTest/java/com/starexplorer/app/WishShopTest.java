package com.starexplorer.app;

import static com.starexplorer.app.nativeui.StarApi.json;
import static org.junit.Assert.*;
import androidx.appcompat.app.AlertDialog;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.starexplorer.app.nativeui.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;
import org.junit.*;

/** Runs only in the isolated app against an in-memory server, including saved-data checks. */
public class WishShopTest {
  private static final String BASE = "http://127.0.0.1:3006";
  private ActivityScenario<MainActivity> scenario;
  private Context context;
  private StarApi parent, child;
  private String path, prefix;
  private JSONObject login(StarApi client, String username, String password) throws Exception {
    JSONObject result = (JSONObject) client.call("/api/login", "POST", json("username", username, "password", password), false);
    client.authenticate(result.getString("token")); return result;
  }
  @Before public void prepare() throws Exception {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    assertEquals("com.starexplorer.app.testbed", context.getPackageName());
    prefix = InstrumentationRegistry.getArguments().getString("form", "wish-phone");
    child = new StarApi(context, BASE); parent = new StarApi(context, BASE);
    path = "/api/children/" + login(child, "child", "child123").getJSONObject("user").getString("id");
    login(parent, "parent", "parent123");
    context.getSharedPreferences(MainActivity.class.getName(), 0).edit().putString("skin", "dino").putInt("lockedOrientation", -1).commit();
  }
  @After public void close() { if (scenario != null) scenario.close(); }
  private List<View> views(View root) {
    List<View> all = new ArrayList<>(); all.add(root);
    if (root instanceof ViewGroup) for (int i=0; i<((ViewGroup)root).getChildCount(); i++) all.addAll(views(((ViewGroup)root).getChildAt(i)));
    return all;
  }
  private View currentRoot(MainActivity activity) {
    try {
      Field field = MainActivity.class.getDeclaredField("activeDialog"); field.setAccessible(true);
      AlertDialog dialog = (AlertDialog) field.get(activity);
      return dialog != null && dialog.isShowing() ? dialog.getWindow().getDecorView() : activity.getWindow().getDecorView();
    } catch (Exception e) { throw new AssertionError(e); }
  }
  private boolean matches(View view, String label) {
    return label.equals(String.valueOf(view.getContentDescription())) || view instanceof TextView && label.contentEquals(((TextView)view).getText());
  }
  private void waitText(String label) throws Exception {
    long deadline = System.currentTimeMillis()+15000;
    while(System.currentTimeMillis()<deadline) {
      AtomicBoolean found = new AtomicBoolean();
      scenario.onActivity(a -> { for(View v:views(currentRoot(a))) if(matches(v,label)) found.set(true); });
      if(found.get()) return; Thread.sleep(50);
    }
    fail("Missing " + label);
  }
  private void click(String label) {
    scenario.onActivity(a -> {
      for(View v:views(currentRoot(a))) if(matches(v,label)) {
        View target=v; while(!target.isClickable() && target.getParent() instanceof View) target=(View)target.getParent();
        boolean clicked=target.performClick();
        if(target instanceof CompoundButton) assertTrue(((CompoundButton)target).isChecked()); else assertTrue("Action " + label,clicked); return;
      }
      fail("Missing action " + label);
    });
  }
  private void launch(String nav) throws Exception {
    scenario=ActivityScenario.launch(new Intent(context,MainActivity.class).putExtra("test_api",BASE));
    waitText("今天的小任务"); click(nav); waitText("心愿小铺");
  }
  private void capture(String name) throws Exception {
    InstrumentationRegistry.getInstrumentation().waitForIdleSync(); Thread.sleep(350);
    Bitmap b=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
    try(OutputStream out=new FileOutputStream(new File(context.getExternalFilesDir(null),prefix+"-"+name+".png"))) { b.compress(Bitmap.CompressFormat.PNG,100,out); }
  }
  @Test public void parentCanPreviewIconsAndInspectHistoryWithoutChangingSavedData() throws Exception {
    String title="周末和家人一起去公园野餐并分享这周最开心的故事";
    parent.call(path+"/rewards", "POST", json("title",title,"description","保留完整说明与自定义价格", "icon","picnic","cost",100000,"active",true),false);
    JSONObject before=(JSONObject)parent.get(path+"/dashboard");
    launch("心愿");
    scenario.onActivity(a -> {
      int visible=0; Rect rect=new Rect();
      for(View v:views(currentRoot(a))) {
        if(matches(v,"小小行动，慢慢发光")) assertTrue("Screen heading is not vertically clipped",v.getGlobalVisibleRect(rect) && rect.height()==v.getHeight());
        if(matches(v,"可用星星")) assertEquals("Balance label stays on one line",1,((TextView)v).getLineCount());
        if("wish-pending".equals(v.getTag())) assertTrue("Pending summary keeps readable width",v.getWidth()>=100*a.getResources().getDisplayMetrics().density);
        if("wish-action".equals(v.getTag())) {
          assertTrue(v.getHeight()>=44*a.getResources().getDisplayMetrics().density);
          if(v.getGlobalVisibleRect(rect) && rect.height()==v.getHeight()) visible++;
        }
      }
      assertTrue("First screen has usable reward actions",visible>=2);
    });
    capture("parent"); click("编辑心愿 "+title); waitText("编辑心愿");
    scenario.onActivity(a -> {
      boolean cost=false, selected=false; int icons=0;
      for(View v:views(currentRoot(a))) {
        if(v instanceof EditText && "100000".contentEquals(((EditText)v).getText())) cost=true;
        if(v instanceof RadioButton) { icons++; if("picnic".equals(v.getTag())) selected=((RadioButton)v).isChecked(); }
      }
      assertTrue(cost && selected); assertEquals(5,icons);
    });
    click("电影"); capture("editor");
    scenario.onActivity(a -> {
      for(View v:views(currentRoot(a))) if(v instanceof RadioButton) {
        TextView option=(TextView)v;
        assertTrue("Icon label is fully visible",option.getLayout().getHeight()<=option.getHeight()-option.getTotalPaddingTop()-option.getTotalPaddingBottom());
      }
    });
    click("关闭");
    click("兑换记录"); waitText("兑换记录"); capture("history"); click("关闭");
    JSONObject after=(JSONObject)parent.get(path+"/dashboard");
    for(String key:new String[]{"rules","tasks","rewards","redemptions","ledger","wallet"}) assertEquals("Preview preserves "+key,before.get(key).toString(),after.get(key).toString());
  }
  @Test public void childPendingWishDisablesDuplicateAndKeepsSnapshotPrice() throws Exception {
    String title="一起看星星 "+System.currentTimeMillis();
    JSONObject r=(JSONObject)parent.call(path+"/rewards","POST",json("title",title,"description","和家人一起的时光","icon","gift","cost",1,"active",true),false);
    JSONObject before=(JSONObject)child.get(path+"/dashboard");
    child.call(path+"/redemptions","POST",json("reward_id",r.getString("id")),true);
    parent.call("/api/rewards/"+r.getString("id"),"PATCH",json("title",title,"description","修改后保留原申请","icon","movie","cost",9,"active",true),false);
    login(child,"child","child123"); launch("心愿小铺");
    scenario.onActivity(a -> {
      for(View v:views(currentRoot(a))) if(v instanceof TextView && title.contentEquals(((TextView)v).getText())) {
        View card=v; while(!"wish-card".equals(card.getTag()) && card.getParent() instanceof View) card=(View)card.getParent();
        boolean found=false;
        for(View item:views(card)) if("wish-action".equals(item.getTag())) { assertFalse(item.isEnabled()); assertTrue(matches(item,"等待确认")); found=true; }
        assertTrue(found); return;
      }
      fail("Pending reward missing");
    });
    capture("child"); click("我想兑换"); waitText("兑换一个小心愿"); capture("confirmation"); click("关闭"); click("兑换记录"); waitText("兑换记录");
    scenario.onActivity(a -> {
      boolean found=false;
      for(View v:views(currentRoot(a))) if(v instanceof TextView && title.contentEquals(((TextView)v).getText())) {
        ViewGroup text=(ViewGroup)v.getParent();
        for(View item:views(text)) if(item instanceof TextView && ((TextView)item).getText().toString().startsWith("1 星 ·")) found=true;
      }
      assertTrue("History keeps original cost",found);
    });
    capture("child-history"); click("关闭");
    JSONObject after=(JSONObject)child.get(path+"/dashboard");
    assertEquals(before.get("wallet").toString(),after.get("wallet").toString());
    int count=0; for(int i=0;i<after.getJSONArray("redemptions").length();i++) if(after.getJSONArray("redemptions").getJSONObject(i).getString("reward_id").equals(r.getString("id"))) count++;
    assertEquals(1,count);
  }
}
