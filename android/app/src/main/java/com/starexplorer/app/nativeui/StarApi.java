package com.starexplorer.app.nativeui;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.*;

/** Native HTTPS transport, no WebView or JavaScript bridge. Call off the main thread. */
public final class StarApi {

  public static class ApiException extends Exception {

    public final int status;

    public ApiException(int status, String message) {
      super(message);
      this.status = status;
    }
  }

  private final String base;
  private final SessionStore store;
  private final SharedPreferences prefs;
  private volatile String token;

  public StarApi(Context c, String base) {
    this.base = base;
    store = new SessionStore(c, base);
    token = store.read();
    prefs = c.getSharedPreferences("star_requests", Context.MODE_PRIVATE);
    // Upgrade the previous app's saved session once, without bundling its runtime.
    if (
      base.equals(com.starexplorer.app.BuildConfig.API_BASE_URL) &&
      !prefs.getBoolean("legacy-imported", false)
    ) {
      SharedPreferences legacy = c.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE);
      String saved = legacy.getString("star-token", "");
      try {
        if (token.isEmpty() && !saved.isEmpty()) {
          store.save(saved);
          token = saved;
        }
        legacy.edit().remove("star-token").commit();
        prefs.edit().putBoolean("legacy-imported", true).commit();
      } catch (Exception ignored) {
        /* Preserve the legacy value if secure persistence failed. */
      }
    }
  }

  public boolean hasSession() {
    return !token.isEmpty();
  }

  public void authenticate(String token) throws Exception {
    store.save(token);
    this.token = token;
  }

  public void forget() {
    token = "";
    store.clear();
  }

  public Object get(String path) throws Exception {
    return call(path, "GET", null, false);
  }

  public Object call(String path, String method, JSONObject body, boolean idempotent)
    throws Exception {
    String signature =
      base + ":" + token.hashCode() + ":" + method + ":" + path + ":" + String.valueOf(body);
    String operation = null;
    if (idempotent) {
      operation = prefs.getString(signature, null);
      if (operation == null) {
        operation = UUID.randomUUID().toString();
        prefs.edit().putString(signature, operation).commit();
      }
    }
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) new URL(base + path).openConnection();
      connection.setConnectTimeout(12000);
      connection.setReadTimeout(20000);
      connection.setRequestMethod(method);
      connection.setInstanceFollowRedirects(false);
      connection.setRequestProperty("Accept", "application/json");
      if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
      if (operation != null) connection.setRequestProperty("Idempotency-Key", operation);
      if (body != null) {
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream stream = connection.getOutputStream()) {
          stream.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
      }
      int status = connection.getResponseCode();
      InputStream stream =
        status >= 400 ? connection.getErrorStream() : connection.getInputStream();
      String value = readText(stream, 25 * 1024 * 1024);
      Object result = new JSONTokener(value).nextValue();
      // A 5xx response may follow a successful commit behind a gateway. Keep the
      // same operation identifier so retrying cannot award or spend stars twice.
      if (idempotent && status < 500) prefs.edit().remove(signature).apply();
      if (status < 200 || status >= 300) throw new ApiException(
        status,
        result instanceof JSONObject
          ? ((JSONObject) result).optString("error", "操作未完成")
          : "服务器响应异常"
      );
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
