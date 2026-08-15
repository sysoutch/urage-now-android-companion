package com.uragestudio.companion;

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
import org.json.JSONObject;

public final class SecureMatrixRelayStore {
    public record Config(String homeserverUrl, String accessToken, String botUserId, String roomId, boolean allowUnencryptedMedia) {}
    private static final String KEY_ALIAS = "urage_companion_matrix_relay";
    private final SharedPreferences preferences;

    public SecureMatrixRelayStore(Context context) {
        preferences = context.getSharedPreferences("secure_matrix_relay", Context.MODE_PRIVATE);
    }

    public void save(Config config) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] cleartext = new JSONObject()
            .put("homeserverUrl", config.homeserverUrl())
            .put("accessToken", config.accessToken())
            .put("botUserId", config.botUserId())
            .put("roomId", config.roomId())
            .put("allowUnencryptedMedia", config.allowUnencryptedMedia())
            .toString().getBytes(StandardCharsets.UTF_8);
        preferences.edit()
            .putString("ciphertext", Base64.encodeToString(cipher.doFinal(cleartext), Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
            .apply();
    }

    public Config load() {
        String ciphertext = preferences.getString("ciphertext", "");
        String iv = preferences.getString("iv", "");
        if (ciphertext.isEmpty() || iv.isEmpty()) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            JSONObject value = new JSONObject(new String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), StandardCharsets.UTF_8));
            return new Config(
                value.optString("homeserverUrl"), value.optString("accessToken"),
                value.optString("botUserId"), value.optString("roomId"), value.optBoolean("allowUnencryptedMedia", false)
            );
        } catch (Exception error) {
            preferences.edit().clear().apply();
            return null;
        }
    }

    public void clear() {
        preferences.edit().clear().apply();
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return (SecretKey) store.getKey(KEY_ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build());
        return generator.generateKey();
    }
}
