package com.starexplorer.app.nativeui;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SessionStore {

  private final SharedPreferences prefs;
  private final String realm;
  private static final String ALIAS = "star-native-session-v1";

  public SessionStore(Context c, String server) {
    prefs = c.getSharedPreferences("star_native", Context.MODE_PRIVATE);
    realm = "session:" + server;
  }

  private SecretKey key() throws Exception {
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null);
    if (!ks.containsAlias(ALIAS)) {
      KeyGenerator gen = KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES,
        "AndroidKeyStore"
      );
      gen.init(
        new KeyGenParameterSpec.Builder(
          ALIAS,
          KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .build()
      );
      gen.generateKey();
    }
    return (SecretKey) ks.getKey(ALIAS, null);
  }

  public String read() {
    try {
      String value = prefs.getString(realm, "");
      if (value.isEmpty()) return "";
      String[] pair = value.split(":");
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
        Cipher.DECRYPT_MODE,
        key(),
        new GCMParameterSpec(128, Base64.decode(pair[0], Base64.NO_WRAP))
      );
      return new String(
        cipher.doFinal(Base64.decode(pair[1], Base64.NO_WRAP)),
        StandardCharsets.UTF_8
      );
    } catch (Exception e) {
      clear();
      return "";
    }
  }

  public void save(String token) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    byte[] value = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
    boolean saved = prefs
      .edit()
      .putString(
        realm,
        Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) +
          ":" +
          Base64.encodeToString(value, Base64.NO_WRAP)
      )
      .commit();
    if (!saved) throw new java.io.IOException("无法保存登录状态，请检查设备存储空间");
  }

  public void clear() {
    prefs.edit().remove(realm).commit();
  }
}
