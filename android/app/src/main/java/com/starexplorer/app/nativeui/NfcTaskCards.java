package com.starexplorer.app.nativeui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.nfc.*;
import android.nfc.tech.Ndef;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground NFC reader plus validation of system tag-launch intents. */
public final class NfcTaskCards {
  public static final class CardRead {
    public final TaskCardId card;
    public final String uid;
    public final Tag tag;
    CardRead(TaskCardId card, String uid, Tag tag) { this.card = card; this.uid = uid; this.tag = tag; }
  }

  public interface Callback {
    void read(TaskCardId card, String cardUid);
    void written();
    void error(String message);
  }

  private static final class Session {
    final TaskCardId write;
    final Callback callback;
    final boolean continuous;
    final AtomicBoolean reading = new AtomicBoolean();
    Session(TaskCardId write, Callback callback, boolean continuous) {
      this.write = write; this.callback = callback; this.continuous = continuous;
    }
  }

  private final Activity activity;
  private final NfcAdapter adapter;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private volatile Session session;

  public NfcTaskCards(Activity activity) {
    this.activity = activity;
    adapter = NfcAdapter.getDefaultAdapter(activity);
  }

  public boolean supported() { return adapter != null; }
  public boolean enabled() { return adapter != null && adapter.isEnabled(); }
  public boolean active() { return session != null; }

  // A URI alone is never an award request. Require the NFC action, payload and physical tag.
  public static CardRead fromIntent(Intent intent) {
    if (intent == null || !NfcAdapter.ACTION_NDEF_DISCOVERED.equals(intent.getAction())) return null;
    Parcelable[] messages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
    Parcelable physicalTag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
    if (messages == null || messages.length != 1 || !(messages[0] instanceof NdefMessage) || !(physicalTag instanceof Tag))
      throw new IllegalArgumentException("未收到完整的任务卡信息，请移开卡片后重新碰卡");
    TaskCardId card = decode((NdefMessage) messages[0]);
    if (!card.toUri().equals(intent.getDataString()))
      throw new IllegalArgumentException("任务卡信息不一致，请重新碰卡");
    return new CardRead(card, physicalUid(((Tag) physicalTag).getId()), (Tag) physicalTag);
  }

  public void ignoreUntilRemoved(Tag tag) {
    if (adapter != null) try { adapter.ignore(tag, 500, null, main); }
    catch (RuntimeException ignored) { /* Server-side daily deduplication still applies. */ }
  }

  public static String physicalUid(byte[] id) {
    if (id == null || (id.length != 4 && id.length != 7 && id.length != 10))
      throw new IllegalArgumentException("无法读取实体卡编号，请使用 NTAG213 / 215 / 216 任务卡");
    StringBuilder value = new StringBuilder();
    for (byte b : id) value.append(String.format(java.util.Locale.US, "%02X", b & 255));
    return value.toString();
  }

  public static NdefMessage message(TaskCardId card) {
    return new NdefMessage(new NdefRecord[] { NdefRecord.createUri(card.toUri()) });
  }

  public static TaskCardId decode(NdefMessage message) {
    if (blank(message)) throw new IllegalArgumentException("这是一张空白卡，还没有绑定任务");
    if (message == null || message.getRecords().length != 1)
      throw new IllegalArgumentException("这张卡还不是任务卡，请先在规则页制作任务卡");
    Uri uri = message.getRecords()[0].toUri();
    return TaskCardId.parse(uri == null ? null : uri.toString());
  }

  private static boolean blank(NdefMessage message) {
    if (message == null) return true;
    for (NdefRecord record : message.getRecords())
      if (record.getTnf() != NdefRecord.TNF_EMPTY) return false;
    return true;
  }

  public void start(TaskCardId write, Callback callback) {
    start(write, callback, false);
  }

  public void startContinuousRead(Callback callback) { start(null, callback, true); }

  private void start(TaskCardId write, Callback callback, boolean continuous) {
    stop();
    if (!enabled()) { callback.error("请先打开手机 NFC"); return; }
    Session next = new Session(write, callback, continuous);
    session = next;
    try {
      adapter.enableReaderMode(activity, tag -> {
        if (session != next || !next.reading.compareAndSet(false, true)) return;
        try { io.execute(() -> process(tag, next)); }
        catch (RejectedExecutionException ignored) { /* Activity was destroyed during discovery. */ }
      }, NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B |
        NfcAdapter.FLAG_READER_NFC_F | NfcAdapter.FLAG_READER_NFC_V, null);
    } catch (RuntimeException e) {
      stop();
      callback.error("无法启动 NFC，请检查手机设置后重试");
    }
  }

  private void process(Tag tag, Session current) {
    TaskCardId read = null;
    String cardUid = null;
    String failure = null;
    Ndef ndef = Ndef.get(tag);
    try {
      if (session != current) return;
      if (ndef == null) throw new IOException("不支持这张卡，请使用已格式化为 NDEF 的 NTAG213 / 215 空白卡");
      ndef.connect();
      NdefMessage existing = ndef.getNdefMessage();
      if (current.write == null) {
        read = decode(existing);
        cardUid = physicalUid(tag.getId());
      }
      else {
        if (!ndef.isWritable()) throw new IOException("这张卡已锁定，不能写入，请换一张可写空白卡");
        // Refuse to erase another task or unrelated card without a separate explicit action.
        if (!blank(existing)) {
          TaskCardId previous = null;
          try { previous = decode(existing); } catch (IllegalArgumentException ignored) {}
          if (!current.write.equals(previous))
            throw new IOException("卡片已有其他内容，请换一张空白卡；如需重新分配，请先用 NFC 工具清空卡片");
        }
        NdefMessage content = message(current.write);
        if (ndef.getMaxSize() < content.getByteArrayLength()) throw new IOException("这张卡容量不足，请换用 NTAG213 / 215 卡");
        if (session != current) return;
        ndef.writeNdefMessage(content);
        if (!current.write.equals(decode(ndef.getNdefMessage()))) throw new IOException("写入校验未通过，请保持卡片贴近手机后重试");
      }
    } catch (TagLostException e) {
      failure = "卡片移开太快，请贴近手机 NFC 感应区后重试";
    } catch (Exception e) {
      failure = e.getMessage() == null ? "读写失败，请保持卡片贴近手机后重试" : e.getMessage();
    } finally {
      if (ndef != null) try { ndef.close(); } catch (IOException ignored) {}
    }
    final TaskCardId result = read;
    final String resultUid = cardUid;
    final String error = failure;
    main.post(() -> {
      if (session != current) return;
      if (error != null) {
        current.reading.set(false);
        current.callback.error(error);
      } else {
        // A held card must leave the RF field before it can start another confirmation.
        boolean ignored = false;
        try {
          ignored = adapter.ignore(tag, 500, () -> {
            if (session == current) current.reading.set(false);
          }, main);
        } catch (RuntimeException ignoredFailure) {}
        if (!current.continuous) stop();
        else if (!ignored) current.reading.set(false);
        if (current.write == null) current.callback.read(result, resultUid);
        else current.callback.written();
      }
    });
  }

  public void stop() {
    session = null;
    if (adapter != null) try { adapter.disableReaderMode(activity); } catch (RuntimeException ignored) {}
  }

  public void destroy() { stop(); io.shutdownNow(); main.removeCallbacksAndMessages(null); }
}
