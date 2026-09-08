package com.starexplorer.app;

import static com.starexplorer.app.nativeui.StarApi.json;
import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.starexplorer.app.nativeui.StarApi;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
          if (click) v.performClick();
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
}
