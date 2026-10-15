package com.starexplorer.app;

import static org.junit.Assert.*;

import com.starexplorer.app.nativeui.PendingOperationStore;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class PendingOperationStoreTest {
  private static final String BASE = "https://isolated.example";
  private static final String BODY = "{amount:1}";

  private static final class Storage implements PendingOperationStore.Storage {
    final Map<String, String> disk;
    final Map<String, String> memory;
    boolean writable = true;
    Storage() { this(new HashMap<>()); }
    Storage(Map<String, String> disk) { this.disk = disk; memory = new HashMap<>(disk); }
    public Map<String, String> entries() { return new HashMap<>(memory); }
    public boolean commit(Map<String, String> values, Set<String> removals) {
      for (String key : removals) memory.remove(key);
      memory.putAll(values);
      if (!writable) return false;
      disk.clear(); disk.putAll(memory);
      return true;
    }
  }

  @Test public void recreationRetainsOperationAndSeparatesAccountsAndServers() throws Exception {
    Storage storage = new Storage();
    PendingOperationStore first = new PendingOperationStore(storage);
    String id = first.begin(BASE, "parent-1", "POST", "/ledger", BODY).id;
    PendingOperationStore next = new PendingOperationStore(new Storage(storage.disk));
    assertEquals(id, next.begin(BASE, "parent-1", "POST", "/ledger", BODY).id);
    assertNotEquals(id, next.begin(BASE, "parent-2", "POST", "/ledger", BODY).id);
    assertNotEquals(id, next.begin("https://another.example", "parent-1", "POST", "/ledger", BODY).id);
    assertThrows(IOException.class, () -> next.begin(BASE, "", "POST", "/ledger", BODY));
  }

  @Test public void verifiedLegacySessionMigratesWithoutDiscardingOtherSessions() throws Exception {
    Storage storage = new Storage();
    String token = "old-test-session";
    String old = BASE + ":" + token.hashCode() + ":POST:/ledger:" + BODY;
    String other = BASE + ":" + "another-test-session".hashCode() + ":POST:/ledger:" + BODY;
    storage.memory.put(old, "original-operation");
    storage.memory.put(other, "other-operation");
    PendingOperationStore store = new PendingOperationStore(storage);
    store.migrateLegacy(BASE, token, "parent-1");
    assertFalse(storage.disk.containsKey(old));
    assertEquals("other-operation", storage.disk.get(other));
    assertEquals("original-operation", store.begin(BASE, "parent-1", "POST", "/ledger", BODY).id);
    assertNotEquals("original-operation", store.begin(BASE, "parent-2", "POST", "/ledger", BODY).id);
  }

  @Test public void failedCommitCannotBecomeSuccessfulThroughTheMemoryCache() throws Exception {
    Storage storage = new Storage();
    PendingOperationStore store = new PendingOperationStore(storage);
    storage.writable = false;
    assertThrows(IOException.class, () -> store.begin(BASE, "parent-1", "POST", "/ledger", BODY));
    assertFalse(storage.memory.isEmpty());
    assertTrue(storage.disk.isEmpty());
    assertThrows(IOException.class, () -> store.begin(BASE, "parent-1", "POST", "/ledger", BODY));
    String original = storage.memory.values().iterator().next();
    storage.writable = true;
    assertEquals(original, store.begin(BASE, "parent-1", "POST", "/ledger", BODY).id);
    assertEquals(original, new PendingOperationStore(new Storage(storage.disk))
      .begin(BASE, "parent-1", "POST", "/ledger", BODY).id);
  }

  @Test public void failedCompletionKeepsRetryIdentityUntilItCanBeRemoved() throws Exception {
    Storage storage = new Storage();
    PendingOperationStore store = new PendingOperationStore(storage);
    PendingOperationStore.Operation operation = store.begin(BASE, "parent-1", "POST", "/ledger", BODY);
    storage.writable = false;
    assertThrows(IOException.class, () -> store.complete(operation));
    storage.writable = true;
    assertEquals(operation.id, store.begin(BASE, "parent-1", "POST", "/ledger", BODY).id);
    store.complete(operation);
    assertNotEquals(operation.id, store.begin(BASE, "parent-1", "POST", "/ledger", BODY).id);
  }
}
