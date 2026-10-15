package com.starexplorer.app.nativeui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Durable operation identities survive process recreation and changes to a user's login token. */
public final class PendingOperationStore {
  public interface Storage {
    Map<String, String> entries();
    boolean commit(Map<String, String> values, Set<String> removals);
  }

  public static final class Operation {
    private final String key;
    public final String id;
    private Operation(String key, String id) { this.key = key; this.id = id; }
  }

  // SharedPreferences can be shared by several API clients in this process.
  private static final Object LOCK = new Object();
  private final Storage storage;

  public PendingOperationStore(Storage storage) { this.storage = storage; }

  private static String key(String base, String userId, String request) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(
        (base + "\n" + userId + "\n" + request).getBytes(StandardCharsets.UTF_8));
      StringBuilder value = new StringBuilder("pending-v2:");
      for (byte b : hash) value.append(String.format(java.util.Locale.US, "%02x", b & 255));
      return value.toString();
    } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }

  private void requireSaved(Map<String, String> values, Set<String> removals) throws IOException {
    if (!storage.commit(values, removals))
      throw new IOException("无法保存操作编号，请检查设备存储空间后重试");
  }

  /** Call only after the server has verified which user owns this exact token. */
  public void migrateLegacy(String base, String verifiedToken, String userId) throws IOException {
    if (verifiedToken.isEmpty() || userId.isEmpty()) return;
    synchronized (LOCK) {
      Map<String, String> saved = storage.entries();
      Map<String, String> values = new HashMap<>();
      Set<String> removals = new HashSet<>();
      String prefix = base + ":" + verifiedToken.hashCode() + ":";
      for (Map.Entry<String, String> old : saved.entrySet()) {
        if (!old.getKey().startsWith(prefix)) continue;
        String next = key(base, userId, old.getKey().substring(prefix.length()));
        String existing = saved.get(next);
        // Keep conflicting legacy records rather than silently discard an unresolved operation.
        if (existing != null && !existing.equals(old.getValue())) continue;
        values.put(next, old.getValue());
        removals.add(old.getKey());
      }
      if (!values.isEmpty()) requireSaved(values, removals);
    }
  }

  public Operation begin(String base, String userId, String method, String path, String body)
    throws IOException {
    if (userId.isEmpty()) throw new IOException("请先恢复登录状态，再重试这次操作");
    synchronized (LOCK) {
      String key = key(base, userId, method + ":" + path + ":" + body);
      String id = storage.entries().get(key);
      if (id == null) id = UUID.randomUUID().toString();
      // A failed SharedPreferences commit may still change its in-memory view. Recommit
      // existing values too: no request can leave until its operation is durable.
      requireSaved(Collections.singletonMap(key, id), Collections.emptySet());
      return new Operation(key, id);
    }
  }

  public void complete(Operation operation) throws IOException {
    synchronized (LOCK) {
      if (!operation.id.equals(storage.entries().get(operation.key))) return;
      if (!storage.commit(Collections.emptyMap(), Collections.singleton(operation.key))) {
        // Restore the in-memory identity as well when removal failed on disk.
        storage.commit(Collections.singletonMap(operation.key, operation.id), Collections.emptySet());
        throw new IOException("操作已完成，但本机记录尚未保存；请检查存储空间后重试");
      }
    }
  }
}
