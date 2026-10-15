package com.starexplorer.app;

import static com.starexplorer.app.nativeui.StarApi.json;
import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.starexplorer.app.nativeui.StarApi;
import com.starexplorer.app.nativeui.SessionStore;
import com.starexplorer.app.nativeui.NfcTaskCards;
import com.starexplorer.app.nativeui.TaskCardId;
import java.io.*;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
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
      first.authenticate("isolated-test-session", "test-parent");
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

  @Test public void unknownResultSurvivesUnauthorizedRetryAndNewLoginForSameAccount() throws Exception {
    Context c = context();
    List<String> keys = new CopyOnWriteArrayList<>();
    List<String> credentials = new CopyOnWriteArrayList<>();
    try (Server server = new Server((path, headers) -> {
      keys.add(headers.get("idempotency-key"));
      credentials.add(headers.get("authorization"));
      int attempt = keys.size();
      return new String[] {attempt == 1 ? "502 Bad Gateway" : attempt == 2 ? "401 Unauthorized" : "200 OK",
        attempt < 3 ? "{\"error\":\"retry later\"}" : "{\"ok\":true}"};
    })) {
      StarApi first = new StarApi(c, server.base());
      first.authenticate("first-test-session", "parent-1");
      assertEquals(502, assertThrows(StarApi.ApiException.class,
        () -> first.call("/ledger", "POST", json("amount", 1), true)).status);
      StarApi next = new StarApi(c, server.base());
      assertEquals(401, assertThrows(StarApi.ApiException.class,
        () -> next.call("/ledger", "POST", json("amount", 1), true)).status);
      next.forget();
      next.authenticate("renewed-test-session", "parent-1");
      next.call("/ledger", "POST", json("amount", 1), true);
      next.call("/ledger", "POST", json("amount", 1), true);
      assertEquals(keys.get(0), keys.get(1));
      assertEquals(keys.get(0), keys.get(2));
      assertNotEquals(keys.get(2), keys.get(3));
      assertEquals("Bearer first-test-session", credentials.get(0));
      assertEquals("Bearer renewed-test-session", credentials.get(2));
      next.forget();
    }
  }

  @Test public void pendingOperationNeverCrossesAccounts() throws Exception {
    Context c = context();
    List<String> keys = new CopyOnWriteArrayList<>();
    try (Server server = new Server((path, headers) -> {
      keys.add(headers.get("idempotency-key"));
      return new String[] {keys.size() == 1 ? "502 Bad Gateway" : "200 OK", "{\"ok\":true}"};
    })) {
      StarApi api = new StarApi(c, server.base());
      api.authenticate("parent-one-session", "parent-1");
      assertThrows(StarApi.ApiException.class, () -> api.call("/ledger", "POST", json("amount", 1), true));
      api.authenticate("parent-two-session", "parent-2");
      api.call("/ledger", "POST", json("amount", 1), true);
      api.authenticate("parent-one-renewed", "parent-1");
      api.call("/ledger", "POST", json("amount", 1), true);
      assertNotEquals(keys.get(0), keys.get(1));
      assertEquals(keys.get(0), keys.get(2));
      api.forget();
    }
  }

  @Test public void failedOperationPersistenceSendsNoRequest() throws Exception {
    Context c = context();
    AtomicBoolean writable = new AtomicBoolean(false);
    AtomicInteger requests = new AtomicInteger();
    Context blocked = new ContextWrapper(c) {
      @Override public SharedPreferences getSharedPreferences(String name, int mode) {
        SharedPreferences delegate = super.getSharedPreferences(name, mode);
        if (!name.equals("star_requests")) return delegate;
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
          new Class<?>[] {SharedPreferences.class}, (proxy, method, args) -> {
            if (!method.getName().equals("edit")) return method.invoke(delegate, args);
            SharedPreferences.Editor editor = delegate.edit();
            return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
              new Class<?>[] {SharedPreferences.Editor.class}, (editProxy, editMethod, editArgs) -> {
                if (editMethod.getName().equals("commit") && !writable.get()) return false;
                Object result = editMethod.invoke(editor, editArgs);
                return result == editor ? editProxy : result;
              });
          });
      }
    };
    try (Server server = new Server((path, headers) -> {
      requests.incrementAndGet();
      return new String[] {"200 OK", "{\"ok\":true}"};
    })) {
      StarApi api = new StarApi(blocked, server.base());
      api.authenticate("storage-test-session", "parent-1");
      assertThrows(IOException.class, () -> api.call("/ledger", "POST", json("amount", 1), true));
      assertEquals(0, requests.get());
      writable.set(true);
      api.call("/ledger", "POST", json("amount", 1), true);
      assertEquals(1, requests.get());
      api.forget();
    }
  }

  @Test public void restoredLegacyTokenMustResolveItsOwnerBeforeMigratingPendingOperation() throws Exception {
    Context c = context();
    List<String> paths = new CopyOnWriteArrayList<>();
    List<String> keys = new CopyOnWriteArrayList<>();
    try (Server server = new Server((path, headers) -> {
      paths.add(path);
      if (path.equals("/api/me")) return new String[] {"200 OK", "{\"id\":\"parent-1\",\"role\":\"parent\"}"};
      keys.add(headers.get("idempotency-key"));
      return new String[] {"200 OK", "{\"ok\":true}"};
    })) {
      String token = "legacy-test-session";
      String operation = UUID.randomUUID().toString();
      String oldKey = server.base() + ":" + token.hashCode() + ":POST:/ledger:" + json("amount", 1);
      SharedPreferences requests = c.getSharedPreferences("star_requests", Context.MODE_PRIVATE);
      assertTrue(requests.edit().putString(oldKey, operation).commit());
      new SessionStore(c, server.base()).save(token, "");
      StarApi api = new StarApi(c, server.base());
      assertThrows(IOException.class, () -> api.call("/ledger", "POST", json("amount", 1), true));
      assertTrue(paths.isEmpty());
      assertEquals("parent-1", api.resumeSession().getString("id"));
      assertFalse(requests.contains(oldKey));
      api.call("/ledger", "POST", json("amount", 1), true);
      assertEquals(Arrays.asList("/api/me", "/ledger"), paths);
      assertEquals(Collections.singletonList(operation), keys);
      api.forget();
    }
  }

  @Test public void authenticationChangeDoesNotReassignAnInFlightOperation() throws Exception {
    Context c = context();
    CountDownLatch arrived = new CountDownLatch(1), release = new CountDownLatch(1);
    List<String> credentials = new CopyOnWriteArrayList<>();
    List<String> keys = new CopyOnWriteArrayList<>();
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try (Server server = new Server((path, headers) -> {
      credentials.add(headers.get("authorization"));
      keys.add(headers.get("idempotency-key"));
      if (keys.size() == 1) {
        arrived.countDown();
        try { release.await(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return new String[] {"502 Bad Gateway", "{\"error\":\"unknown result\"}"};
      }
      return new String[] {"200 OK", "{\"ok\":true}"};
    })) {
      StarApi api = new StarApi(c, server.base());
      api.authenticate("inflight-first-session", "parent-1");
      Future<?> request = worker.submit(() -> {
        try { api.call("/ledger", "POST", json("amount", 1), true); fail("Expected gateway error"); }
        catch (StarApi.ApiException e) { assertEquals(502, e.status); }
        catch (Exception e) { throw new AssertionError(e); }
      });
      assertTrue(arrived.await(5, TimeUnit.SECONDS));
      api.authenticate("inflight-second-session", "parent-2");
      release.countDown();
      request.get(5, TimeUnit.SECONDS);
      api.call("/ledger", "POST", json("amount", 1), true);
      api.authenticate("inflight-renewed-session", "parent-1");
      api.call("/ledger", "POST", json("amount", 1), true);
      assertEquals("Bearer inflight-first-session", credentials.get(0));
      assertEquals("Bearer inflight-second-session", credentials.get(1));
      assertNotEquals(keys.get(0), keys.get(1));
      assertEquals(keys.get(0), keys.get(2));
      api.forget();
    } finally { release.countDown(); worker.shutdownNow(); }
  }

  @Test public void delayedUnauthorizedResponseCannotInvalidateTheNewSession() throws Exception {
    Context c = context();
    CountDownLatch arrived = new CountDownLatch(1), release = new CountDownLatch(1);
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try (Server server = new Server((path, headers) -> {
      if (path.equals("/delayed")) {
        arrived.countDown();
        try { release.await(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      }
      return new String[] {"401 Unauthorized", "{\"error\":\"expired\"}"};
    })) {
      StarApi api = new StarApi(c, server.base());
      api.authenticate("old-delayed-session", "parent-1");
      Future<StarApi.ApiException> request = worker.submit(() ->
        assertThrows(StarApi.ApiException.class, () -> api.get("/delayed")));
      assertTrue(arrived.await(5, TimeUnit.SECONDS));
      api.authenticate("new-current-session", "parent-2");
      release.countDown();
      StarApi.ApiException old = request.get(5, TimeUnit.SECONDS);
      assertEquals(401, old.status);
      assertFalse("The UI must ignore a previous session's error", api.isCurrentSession(old));
      assertTrue(api.hasSession());
      StarApi.ApiException current = assertThrows(StarApi.ApiException.class, () -> api.get("/current"));
      assertTrue(api.isCurrentSession(current));
      api.forget();
    } finally { release.countDown(); worker.shutdownNow(); }
  }

  @Test public void delayedLogoutOnlyRevokesTheCapturedSession() throws Exception {
    Context c = context();
    CountDownLatch arrived = new CountDownLatch(1), release = new CountDownLatch(1);
    List<String> credentials = new CopyOnWriteArrayList<>();
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try (Server server = new Server((path, headers) -> {
      credentials.add(headers.get("authorization"));
      if (path.equals("/api/logout")) {
        arrived.countDown();
        try { release.await(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return new String[] { "401 Unauthorized", "{\"error\":\"already expired\"}" };
      }
      return new String[] { "200 OK", "{\"ok\":true}" };
    })) {
      StarApi api = new StarApi(c, server.base());
      api.authenticate("logout-old-session", "parent-1");
      Runnable logout = api.prepareLogout();
      api.forget();
      assertFalse(new StarApi(c, server.base()).hasSession());
      Future<?> request = worker.submit(logout);
      assertTrue(arrived.await(5, TimeUnit.SECONDS));
      api.authenticate("logout-new-session", "parent-2");
      release.countDown();
      request.get(5, TimeUnit.SECONDS);
      assertTrue(api.hasSession());
      assertEquals("parent-2", new SessionStore(c, server.base()).read().userId);
      api.get("/check-current-session");
      assertEquals(Arrays.asList("Bearer logout-old-session", "Bearer logout-new-session"), credentials);
      api.forget();
    } finally { release.countDown(); worker.shutdownNow(); }
  }

  @Test public void localLogoutIsImmediateWhenTheServerIsOffline() throws Exception {
    Context c = context();
    try (Server server = new Server((path, headers) -> path.equals("/api/me")
      ? new String[] { "200 OK", "{\"id\":\"offline-parent\",\"name\":\"Offline parent\",\"role\":\"parent\"}" }
      : new String[] { "200 OK", "[]" })) {
      StarApi api = new StarApi(c, server.base());
      api.authenticate("offline-logout-session", "offline-parent");
      Intent intent = new Intent(c, MainActivity.class).putExtra("test_api", server.base());
      try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(intent)) {
        waitText(scenario, "还没有分配小朋友", false);
        server.close();
        logoutThroughSettings(scenario);
        assertFalse(new StarApi(c, server.base()).hasSession());
        scenario.recreate();
        waitText(scenario, "进入星星岛", false);
        assertFalse(new StarApi(c, server.base()).hasSession());
      }
      api.forget();
    }
  }

  @Test public void delayedInitialLoadCannotRestoreStateAfterLocalLogout() throws Exception {
    Context c = context();
    CountDownLatch arrived = new CountDownLatch(1), release = new CountDownLatch(1);
    try (Server server = new Server((path, headers) -> {
      if (path.equals("/api/me")) return new String[] {
        "200 OK", "{\"id\":\"late-parent\",\"name\":\"Late parent\",\"role\":\"parent\"}"
      };
      if (path.equals("/api/children")) {
        arrived.countDown();
        try { release.await(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return new String[] { "200 OK", "[{\"id\":\"old-child\",\"name\":\"Old child\"}]" };
      }
      if (path.equals("/api/logout")) return new String[] { "200 OK", "{\"ok\":true}" };
      return new String[] { "200 OK", "[]" };
    })) {
      StarApi api = new StarApi(c, server.base());
      api.authenticate("late-load-session", "late-parent");
      Intent intent = new Intent(c, MainActivity.class).putExtra("test_api", server.base());
      try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(intent)) {
        assertTrue(arrived.await(5, TimeUnit.SECONDS));
        logoutThroughSettings(scenario);
        AtomicReference<Future<?>> drained = new AtomicReference<>();
        scenario.onActivity(activity -> {
          try {
            java.lang.reflect.Field field = MainActivity.class.getDeclaredField("worker");
            field.setAccessible(true);
            drained.set(((ExecutorService) field.get(activity)).submit(() -> {}));
          } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        });
        release.countDown();
        drained.get().get(5, TimeUnit.SECONDS);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        scenario.onActivity(activity -> {
          try {
            java.lang.reflect.Field initialized = MainActivity.class.getDeclaredField("initialized");
            initialized.setAccessible(true);
            assertFalse("An old response must not restore initialized state", initialized.getBoolean(activity));
            java.lang.reflect.Field childId = MainActivity.class.getDeclaredField("childId");
            childId.setAccessible(true);
            assertEquals("", childId.get(activity));
          } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        });
        waitText(scenario, "进入星星岛", false);
      }
      api.forget();
    } finally { release.countDown(); }
  }

  private void logoutThroughSettings(ActivityScenario<MainActivity> scenario) {
    scenario.onActivity(activity -> {
      View settings = described(activity.getWindow().getDecorView(), "设置");
      assertNotNull(settings);
      settings.performClick();
      AlertDialog dialog = activeDialog(activity);
      assertNotNull(dialog);
      TextView exit = find(dialog.getWindow().getDecorView(), "退出并切换账号");
      assertNotNull(exit);
      exit.performClick();
    });
    // Dialog delivers OnShowListener through the main queue; let it install the
    // app's positive-button listener before simulating the separate user click.
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    scenario.onActivity(activity -> {
      AlertDialog confirmation = activeDialog(activity);
      assertNotNull(confirmation);
      assertEquals("退出登录", confirmation.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
      assertTrue(confirmation.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
      confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
      assertNull("Local logout must close its confirmation immediately", activeDialog(activity));
      assertNotNull("Local logout must show login immediately", find(activity.getWindow().getDecorView(), "进入星星岛"));
    });
  }

  private View described(View view, String description) {
    if (view.getContentDescription() != null && description.contentEquals(view.getContentDescription())) return view;
    if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
      View found = described(((ViewGroup) view).getChildAt(i), description);
      if (found != null) return found;
    }
    return null;
  }

  private Tag untrustedTag() {
    byte[] id = new byte[] {1, 2, 3, 4};
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

  @Test public void externalNfcIntentsWithOrWithoutTagNeverCallTheClaimApi() throws Exception {
    Context c = context();
    String child = UUID.randomUUID().toString();
    TaskCardId card = new TaskCardId(child, UUID.randomUUID().toString());
    AtomicInteger claims = new AtomicInteger();
    String dashboard = "{\"today\":\"" + MainActivity.today() + "\",\"date\":\"" + MainActivity.today()
      + "\",\"child\":{\"id\":\"" + child + "\",\"name\":\"测试孩子\"},"
      + "\"tasks\":[],\"wallet\":{\"balance\":0,\"earned\":0,\"spent\":0},\"ledger\":[],"
      + "\"week\":[],\"reviews\":[],\"rules\":[],\"rewards\":[],\"redemptions\":[],\"dayEarned\":0}";
    try (Server server = new Server((path, headers) -> {
      if (path.endsWith("/task-cards/claim")) claims.incrementAndGet();
      if (path.equals("/api/me")) return new String[] {"200 OK", "{\"id\":\"parent-1\",\"role\":\"parent\"}"};
      if (path.equals("/api/children")) return new String[] {"200 OK", "[{\"id\":\"" + child + "\",\"name\":\"测试孩子\"}]"};
      if (path.equals("/api/task-templates")) return new String[] {"200 OK", "[]"};
      if (path.contains("/dashboard")) return new String[] {"200 OK", dashboard};
      return new String[] {"404 Not Found", "{\"error\":\"unexpected request\"}"};
    })) {
      StarApi api = new StarApi(c, server.base());
      api.authenticate("nfc-navigation-test-session", "parent-1");
      for (boolean includeTag : new boolean[] {true, false}) {
        Intent intent = new Intent(NfcAdapter.ACTION_NDEF_DISCOVERED, Uri.parse(card.toUri()))
          .setClass(c, MainActivity.class).putExtra("test_api", server.base());
        if (includeTag) {
          intent.putExtra(NfcAdapter.EXTRA_TAG, untrustedTag());
          intent.putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, new Parcelable[] {NfcTaskCards.message(card)});
        }
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(intent)) {
          waitText(scenario, "碰任务卡", false);
          waitText(scenario, "请保持任务卡贴近手机", false);
          InstrumentationRegistry.getInstrumentation().waitForIdleSync();
          assertEquals("Navigation must never claim the caller's card identity", 0, claims.get());
          scenario.onActivity(activity -> {
            assertFalse(activity.getIntent().hasExtra(NfcAdapter.EXTRA_TAG));
            assertFalse(activity.getIntent().hasExtra(NfcAdapter.EXTRA_NDEF_MESSAGES));
          });
          scenario.recreate();
          waitText(scenario, "碰任务卡", false);
          assertEquals(0, claims.get());
        }
      }
      api.forget();
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
      api.authenticate("isolated-test-session", "test-parent");
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
  public void familyCreationFormRetainsItsKeyAfterAnUnknownResult() throws Exception {
    Context c = context();
    List<String> keys = new CopyOnWriteArrayList<>();
    try (Server server = new Server((path, headers) -> {
      if (path.equals("/api/me")) return new String[] {
        "200 OK", "{\"id\":\"test-admin\",\"name\":\"测试管理员\",\"role\":\"admin\"}"
      };
      if (path.equals("/api/children") || path.equals("/api/task-templates"))
        return new String[] { "200 OK", "[]" };
      if (path.equals("/api/admin/accounts"))
        return new String[] { "200 OK", "{\"families\":[],\"users\":[]}" };
      if (path.equals("/api/admin/families")) {
        keys.add(headers.get("idempotency-key"));
        return keys.size() == 1
          ? new String[] { "502 Bad Gateway", "{\"error\":\"unknown result\"}" }
          : new String[] { "201 Created", "{\"id\":\"created-family\"}" };
      }
      return new String[] { "404 Not Found", "{\"error\":\"not found\"}" };
    })) {
      StarApi api = new StarApi(c, server.base());
      api.authenticate("isolated-test-session", "test-admin");
      Intent intent = new Intent(c, MainActivity.class).putExtra("test_api", server.base());
      try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(intent)) {
        waitText(scenario, "添加家庭", true);
        scenario.onActivity(activity -> {
          AlertDialog dialog = activeDialog(activity);
          assertNotNull(dialog);
          EditText name = firstInput(dialog.getWindow().getDecorView());
          assertNotNull(name);
          name.setText("Isolated test family");
          dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        });
        AtomicBoolean retried = new AtomicBoolean();
        AtomicBoolean completed = new AtomicBoolean();
        long end = System.currentTimeMillis() + 12000;
        while (!completed.get() && System.currentTimeMillis() < end) {
          scenario.onActivity(activity -> {
            AlertDialog dialog = activeDialog(activity);
            if (!retried.get() && keys.size() == 1 && dialog != null &&
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled()) {
              retried.set(true);
              dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            }
            completed.set(retried.get() && keys.size() == 2 && dialog == null);
          });
          if (!completed.get()) Thread.sleep(50);
        }
        assertTrue("The failed creation must remain retryable", retried.get());
        assertTrue("The successful retry must close the form", completed.get());
        assertEquals(2, keys.size());
        assertNotNull(keys.get(0));
        assertFalse(keys.get(0).isEmpty());
        assertEquals(keys.get(0), keys.get(1));
      }
      api.forget();
    }
  }

  private AlertDialog activeDialog(MainActivity activity) {
    try {
      java.lang.reflect.Field field = MainActivity.class.getDeclaredField("activeDialog");
      field.setAccessible(true);
      return (AlertDialog) field.get(activity);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private EditText firstInput(View view) {
    if (view instanceof EditText) return (EditText) view;
    if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
      EditText input = firstInput(((ViewGroup) view).getChildAt(i));
      if (input != null) return input;
    }
    return null;
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
      api.authenticate("isolated-test-session", "test-parent");
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
