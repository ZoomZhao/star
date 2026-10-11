package com.starexplorer.app;

import static com.starexplorer.app.nativeui.StarApi.json;
import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.starexplorer.app.nativeui.StarApi;
import java.io.*;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class NetworkRecoveryTest {

  interface Reply {
    String[] respond(String path, Map<String, String> headers);
  }

  static class Server implements AutoCloseable {

    final ServerSocket socket = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    volatile Exception failure;

    Server(Reply reply) throws Exception {
      worker.submit(() -> {
        while (!socket.isClosed()) {
          try (Socket client = socket.accept()) {
            client.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(
              new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8)
            );
            String path = in.readLine().split(" ")[1];
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
              int colon = line.indexOf(':');
              headers.put(
                line.substring(0, colon).toLowerCase(Locale.US),
                line.substring(colon + 1).trim()
              );
            }
            int remaining = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            while (remaining-- > 0) in.read();
            String[] response = reply.respond(path, headers);
            byte[] body = response[1].getBytes(StandardCharsets.UTF_8);
            OutputStream out = client.getOutputStream();
            out.write(
              (
                "HTTP/1.1 " +
                response[0] +
                "\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: " +
                body.length +
                "\r\n\r\n"
              ).getBytes(StandardCharsets.UTF_8)
            );
            out.write(body);
            out.flush();
          } catch (Exception e) {
            if (!socket.isClosed()) failure = e;
          }
        }
      });
    }

    String base() {
      return "http://127.0.0.1:" + socket.getLocalPort();
    }

    public void close() throws Exception {
      socket.close();
      worker.shutdownNow();
      assertNull(failure);
    }
  }

  private Context context() {
    Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
    assertEquals("com.starexplorer.app.testbed", c.getPackageName());
    return c;
  }

  @Test
  public void serverFailureRetainsOperationAcrossClientRecreation() throws Exception {
    Context c = context();
    List<String> keys = new CopyOnWriteArrayList<>();
    try (
      Server server = new Server((path, headers) -> {
        keys.add(headers.get("idempotency-key"));
        return keys.size() == 1
          ? new String[] { "502 Bad Gateway", "{\"error\":\"unknown result\"}" }
          : new String[] { "200 OK", "{\"ok\":true}" };
      })
    ) {
      StarApi first = new StarApi(c, server.base());
      first.authenticate("isolated-test-session");
      try {
        first.call("/ledger", "POST", json("amount", 1), true);
        fail("Expected server error");
      } catch (StarApi.ApiException e) {
        assertEquals(502, e.status);
      }
      StarApi next = new StarApi(c, server.base());
      next.call("/ledger", "POST", json("amount", 1), true);
      next.call("/ledger", "POST", json("amount", 1), true);
      assertEquals(3, keys.size());
      assertNotNull(keys.get(0));
      assertEquals(keys.get(0), keys.get(1));
      assertNotEquals(keys.get(1), keys.get(2));
      next.forget();
    }
  }

  private TextView find(View v, String text) {
    if (
      v instanceof TextView && v.isShown() && ((TextView) v).getText().toString().contains(text)
    ) return (TextView) v;
    if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
      TextView found = find(((ViewGroup) v).getChildAt(i), text);
      if (found != null) return found;
    }
    return null;
  }

  private View tagged(View v, String tag) {
    if (tag.equals(v.getTag())) return v;
    if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
      View found = tagged(((ViewGroup) v).getChildAt(i), tag);
      if (found != null) return found;
    }
    return null;
  }

  private void waitText(ActivityScenario<MainActivity> scenario, String text, boolean click)
    throws Exception {
    long end = System.currentTimeMillis() + 12000;
    while (System.currentTimeMillis() < end) {
      java.util.concurrent.atomic.AtomicBoolean found =
        new java.util.concurrent.atomic.AtomicBoolean();
      scenario.onActivity(a -> {
        TextView v = find(a.getWindow().getDecorView(), text);
        if (v != null) {
          found.set(true);
          if (click) {
            View target = v;
            while (!target.isClickable() && target.getParent() instanceof View)
              target = (View) target.getParent();
            target.performClick();
          }
        }
      });
      if (found.get()) return;
      Thread.sleep(50);
    }
    fail("Missing text: " + text);
  }

  @Test
  public void retryReloadsFamilyAfterInitialLoadFailure() throws Exception {
    Context c = context();
    AtomicInteger attempts = new AtomicInteger();
    try (
      Server server = new Server((path, headers) -> {
        if (path.equals("/api/me")) return new String[] {
          "200 OK",
          "{\"id\":\"test-parent\",\"name\":\"测试家长\",\"role\":\"parent\"}",
        };
        if (path.equals("/api/children") && attempts.incrementAndGet() == 1) return new String[] {
          "503 Unavailable",
          "{\"error\":\"initial offline\"}",
        };
        return new String[] { "200 OK", "[]" };
      })
    ) {
      StarApi api = new StarApi(c, server.base());
      api.authenticate("isolated-test-session");
      Intent intent = new Intent(c, MainActivity.class).putExtra("test_api", server.base());
      try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(intent)) {
        waitText(scenario, "initial offline", true);
        waitText(scenario, "还没有分配小朋友", false);
        assertTrue(attempts.get() >= 2);
      }
      api.forget();
    }
  }

  @Test
  public void unchangedRefreshKeepsRuleListAndItsScrollPosition() throws Exception {
    Context c = context();
    AtomicInteger dashboardReads = new AtomicInteger();
    StringBuilder rules = new StringBuilder();
    for (int i = 0; i < 10; i++) {
      if (i > 0) rules.append(',');
      rules.append(
        "{\"id\":\"rule-" +
        i +
        "\",\"rule_key\":\"key-" +
        i +
        "\",\"title\":\"规则 " +
        i +
        "\",\"description\":\"保持滚动位置\",\"icon\":\"book\",\"subject\":\"chinese\",\"stars\":1,\"daily_limit\":2,\"schedule\":\"daily\",\"weekdays\":[],\"on_date\":null,\"effective_from\":\"2026-09-14\",\"enabled\":1,\"version\":1}"
      );
    }
    String dashboard =
      "{\"today\":\"2026-09-13\",\"date\":\"2026-09-13\",\"child\":{\"id\":\"child-1\",\"name\":\"测试孩子\",\"role\":\"child\"},\"tasks\":[],\"wallet\":{\"balance\":0,\"earned\":0,\"spent\":0},\"ledger\":[],\"week\":[],\"reviews\":[],\"rules\":[" +
      rules +
      "],\"rewards\":[],\"redemptions\":[],\"dayEarned\":0}";
    try (
      Server server = new Server((path, headers) -> {
        if (path.equals("/api/me")) return new String[] {
          "200 OK",
          "{\"id\":\"parent-1\",\"name\":\"测试家长\",\"role\":\"parent\",\"family_id\":\"family-1\"}",
        };
        if (path.equals("/api/children")) return new String[] {
          "200 OK",
          "[{\"id\":\"child-1\",\"name\":\"测试孩子\",\"role\":\"child\",\"family_id\":\"family-1\"}]",
        };
        if (path.equals("/api/task-templates")) return new String[] { "200 OK", "[]" };
        if (path.startsWith("/api/children/child-1/dashboard")) {
          dashboardReads.incrementAndGet();
          return new String[] { "200 OK", dashboard };
        }
        return new String[] { "404 Not Found", "{\"error\":\"not found\"}" };
      })
    ) {
      StarApi api = new StarApi(c, server.base());
      api.authenticate("isolated-test-session");
      Intent intent = new Intent(c, MainActivity.class).putExtra("test_api", server.base());
      try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(intent)) {
        waitText(scenario, "更多", true);
        waitText(scenario, "任务规则", true);
        waitText(scenario, "规则 9", false);
        AtomicReference<View> original = new AtomicReference<>();
        AtomicInteger scrollY = new AtomicInteger();
        scenario.onActivity(activity -> {
          ScrollView scroll = (ScrollView) tagged(
            activity.getWindow().getDecorView(),
            "rules-scroll"
          );
          assertNotNull(scroll);
          scroll.fullScroll(View.FOCUS_DOWN);
          original.set(scroll);
        });
        Thread.sleep(150);
        scenario.onActivity(activity -> {
          ScrollView scroll = (ScrollView) tagged(
            activity.getWindow().getDecorView(),
            "rules-scroll"
          );
          assertTrue(scroll.getScrollY() > 0);
          scrollY.set(scroll.getScrollY());
          try {
            Method refresh = MainActivity.class.getDeclaredMethod("refresh", boolean.class);
            refresh.setAccessible(true);
            refresh.invoke(activity, false);
          } catch (Exception e) {
            throw new AssertionError(e);
          }
        });
        long deadline = System.currentTimeMillis() + 5000;
        while (dashboardReads.get() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertTrue("Refresh request did not complete", dashboardReads.get() >= 2);
        Thread.sleep(200);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        scenario.onActivity(activity -> {
          ScrollView scroll = (ScrollView) tagged(
            activity.getWindow().getDecorView(),
            "rules-scroll"
          );
          assertSame("Unchanged data must not rebuild the current page", original.get(), scroll);
          assertEquals(scrollY.get(), scroll.getScrollY());
        });
      }
      api.forget();
    }
  }
}
