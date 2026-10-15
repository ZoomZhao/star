package com.starexplorer.app.nativeui;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.json.*;

/** Native HTTPS transport, no WebView or JavaScript bridge. Call off the main thread. */
public final class StarApi {

  public static class ApiException extends Exception {

    public final int status;
    private final SessionStore.Session requestedSession;

    private ApiException(int status, String message, SessionStore.Session requestedSession) {
      super(message);
      this.status = status;
      this.requestedSession = requestedSession;
    }
  }

  private final String base;
  private final SessionStore store;
  private final SharedPreferences prefs;
  private final PendingOperationStore operations;
  private volatile SessionStore.Session session;

  public StarApi(Context c, String base) {
    this.base = base;
    store = new SessionStore(c, base);
    session = store.read();
    prefs = c.getSharedPreferences("star_requests", Context.MODE_PRIVATE);
    operations = new PendingOperationStore(new PendingOperationStore.Storage() {
      public Map<String, String> entries() {
        Map<String, String> values = new HashMap<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet())
          if (entry.getValue() instanceof String) values.put(entry.getKey(), (String) entry.getValue());
        return values;
      }
      public boolean commit(Map<String, String> values, Set<String> removals) {
        SharedPreferences.Editor edit = prefs.edit();
        for (String key : removals) edit.remove(key);
        for (Map.Entry<String, String> value : values.entrySet()) edit.putString(value.getKey(), value.getValue());
        return edit.commit();
      }
    });
    // Upgrade the previous app's saved session once, without bundling its runtime.
    if (
      base.equals(com.starexplorer.app.BuildConfig.API_BASE_URL) &&
      !prefs.getBoolean("legacy-imported", false)
    ) {
      SharedPreferences legacy = c.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE);
      String saved = legacy.getString("star-token", "");
      try {
        if (session.token.isEmpty() && !saved.isEmpty()) {
          store.save(saved, "");
          session = new SessionStore.Session(saved, "");
        }
        legacy.edit().remove("star-token").commit();
        prefs.edit().putBoolean("legacy-imported", true).commit();
      } catch (Exception ignored) {
        /* Preserve the legacy value if secure persistence failed. */
      }
    }
  }

  public boolean hasSession() {
    return !session.token.isEmpty();
  }

  /** Session identity also acts as a generation, without exposing the login token to callers. */
  public boolean isCurrentSession(ApiException failure) {
    return failure.requestedSession == session;
  }

  public synchronized void authenticate(String token, String userId) throws Exception {
    if (token.isEmpty() || userId.isEmpty()) throw new IllegalArgumentException("登录信息不完整");
    operations.migrateLegacy(base, token, userId);
    store.save(token, userId);
    session = new SessionStore.Session(token, userId);
  }

  public synchronized void forget() {
    session = new SessionStore.Session("", "");
    store.clear();
  }

  /** Capture before local logout; this best-effort request never changes the current session. */
  public Runnable prepareLogout() {
    SessionStore.Session current = session;
    return () -> {
      if (current.token.isEmpty()) return;
      try {
        call("/api/logout", "POST", json(), false, current);
      } catch (Exception ignored) {
        // Local logout is already complete, even if this session cannot be revoked online.
      }
    };
  }

  public JSONObject resumeSession() throws Exception {
    SessionStore.Session current = session;
    JSONObject user = (JSONObject) call("/api/me", "GET", null, false, current);
    synchronized (this) {
      if (session != current) throw new IOException("登录状态已变化，请重试");
      authenticate(current.token, user.getString("id"));
    }
    return user;
  }

  public Object get(String path) throws Exception {
    return call(path, "GET", null, false);
  }

  public Object call(String path, String method, JSONObject body, boolean idempotent)
    throws Exception {
    return call(path, method, body, idempotent, session);
  }

  private Object call(String path, String method, JSONObject body, boolean idempotent,
    SessionStore.Session current) throws Exception {
    String payload = String.valueOf(body);
    PendingOperationStore.Operation operation = idempotent
      ? operations.begin(base, current.userId, method, path, payload) : null;
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) new URL(base + path).openConnection();
      connection.setConnectTimeout(12000);
      connection.setReadTimeout(20000);
      connection.setRequestMethod(method);
      connection.setInstanceFollowRedirects(false);
      connection.setRequestProperty("Accept", "application/json");
      if (!current.token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + current.token);
      if (operation != null) connection.setRequestProperty("Idempotency-Key", operation.id);
      if (body != null) {
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream stream = connection.getOutputStream()) {
          stream.write(payload.getBytes(StandardCharsets.UTF_8));
        }
      }
      int status = connection.getResponseCode();
      InputStream stream =
        status >= 400 ? connection.getErrorStream() : connection.getInputStream();
      String value = readText(stream, 25 * 1024 * 1024);
      Object result = new JSONTokener(value).nextValue();
      // Neither an authentication failure nor a gateway error resolves an earlier
      // uncertain result. Keep its identity until success is actually confirmed.
      if (status < 200 || status >= 300) throw new ApiException(
        status,
        result instanceof JSONObject
          ? ((JSONObject) result).optString("error", "操作未完成")
          : "服务器响应异常",
        current
      );
      if (operation != null) operations.complete(operation);
      return result;
    } catch (SocketTimeoutException | UnknownHostException | ConnectException e) {
      throw new IOException("暂时连接不上服务器，请检查网络后重试");
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  public static String readText(InputStream input, int limit) throws IOException {
    if (input == null) throw new IOException("服务器没有返回内容");
    try (InputStream stream = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] b = new byte[8192];
      int n;
      while ((n = stream.read(b)) != -1) {
        if (out.size() + n > limit) throw new IOException("文件超过大小限制");
        out.write(b, 0, n);
      }
      return out.toString("UTF-8");
    }
  }

  public static JSONObject json(Object... pairs) {
    JSONObject value = new JSONObject();
    try {
      for (int i = 0; i < pairs.length; i += 2) value.put(
        (String) pairs[i],
        pairs[i + 1] == null ? JSONObject.NULL : pairs[i + 1]
      );
    } catch (JSONException e) {
      throw new IllegalArgumentException(e);
    }
    return value;
  }
}
