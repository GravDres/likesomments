package ru.hod.chess;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** A user-entered personal key, never bundled into the APK or sent to logs. */
final class ApiKeyStore {
    private static final String ALIAS = "hod.tooken.api.v1", PREFS = "hod-api-key", VALUE = "encrypted";
    private static final byte[] AAD = "ru.hod.chess:tooken.club:v1".getBytes(StandardCharsets.UTF_8);
    private ApiKeyStore() {}
    static boolean configured(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(VALUE); }
    static void clear(Context c) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(VALUE).apply(); }
    static void save(Context c, String entered) throws Exception {
        String value = entered.trim();
        if (!value.matches("tc_live_[A-Za-z0-9_-]{8,500}")) throw new IllegalArgumentException("Нужен API-ключ Tooken Club вида tc_live_…");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secret()); cipher.updateAAD(AAD);
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        String packed = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        if (!c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(VALUE, packed).commit())
            throw new java.io.IOException("Не удалось сохранить ключ");
    }
    static String load(Context c) throws java.io.IOException {
        String value = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(VALUE, null);
        if (value == null) throw new java.io.IOException("Добавь ключ Tooken Club в главных настройках ХОД");
        try {
            String[] parts = value.split(":", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secret(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception unreadable) { throw new java.io.IOException("Ключ API не читается · введи его заново в настройках"); }
    }
    private static synchronized SecretKey secret() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return generator.generateKey();
    }
}
