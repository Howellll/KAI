package com.kai.keyboard

import android.content.Context
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Вход в сейф отпечатком. Ключ сейфа зашифрован ключом из хранилища ключей телефона, которым можно
 * воспользоваться только сразу после подтверждения отпечатком (каждый раз). Добавили новый отпечаток
 * в систему — ключ становится недействительным, и открыть сейф можно только ПИНом.
 */
object SafeBio {
    private const val ALIAS = "kai.safe.bio.v1"

    fun available(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false
        val bm = ctx.getSystemService(android.hardware.biometrics.BiometricManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 30)
            bm.canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS
        else @Suppress("DEPRECATION") bm.canAuthenticate() == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun newKey(): SecretKey {
        val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(b.build()) }.generateKey()
    }

    fun encryptCipher(): Cipher {
        runCatching { keyStore().deleteEntry(ALIAS) }
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, newKey()) }
    }

    fun decryptCipher(blob: ByteArray): Cipher? = runCatching {
        val key = keyStore().getKey(ALIAS, null) as SecretKey
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12)) }
    }.getOrNull()

    fun disable() { runCatching { keyStore().deleteEntry(ALIAS) } }

    /**
     * Показать системное окно отпечатка. Только из Activity (у клавиатуры своего экрана нет).
     * @param onDone расшифрованное/зашифрованное шифром (или null — отменили, не узнали, ключ недействителен)
     */
    fun prompt(activity: android.app.Activity, title: String, cipher: Cipher, onDone: (Cipher?) -> Unit) {
        if (Build.VERSION.SDK_INT < 28) { onDone(null); return }
        val exec = activity.mainExecutor
        val b = BiometricPrompt.Builder(activity).setTitle(title)
            .setNegativeButton(activity.getString(R.string.sf_pin), exec) { _, _ -> onDone(null) }
        if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG)
        b.build().authenticate(BiometricPrompt.CryptoObject(cipher), CancellationSignal(), exec,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onDone(result.cryptoObject?.cipher)
                }
                override fun onAuthenticationError(code: Int, msg: CharSequence) { onDone(null) }
            })
    }
}

/**
 * Окно отпечатка поверх приложения, когда сейф открывают из клавиатуры: подтвердили — сейф открыт,
 * окно закрывается, и клавиатура, вернувшись в поле, показывает записи.
 */
class SafeUnlockActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        Brain.init(this)
        Thread {
            val blob = runCatching { Brain.safe.biometricBlob() }.getOrNull()
            runOnUiThread { if (!isFinishing && !isDestroyed) start(blob) }
        }.apply { isDaemon = true }.start()
    }

    private fun start(blob: ByteArray?) {
        val cipher = blob?.let { SafeBio.decryptCipher(it) }
        if (blob == null || cipher == null) {
            android.widget.Toast.makeText(this, getString(R.string.sf_bio_off),
                android.widget.Toast.LENGTH_LONG).show()
            finish(); return
        }
        SafeBio.prompt(this, getString(R.string.sf_open_klava), cipher) { c ->
            val k = c?.let { runCatching { it.doFinal(blob, 12, blob.size - 12) }.getOrNull() }
            if (k == null) { finish(); overridePendingTransition(0, 0); return@prompt }
            val since = Brain.safe.session
            Thread {
                val ok = runCatching { Brain.safe.unlockWithKey(k, since) }.getOrDefault(false)
                if (ok) runCatching { Brain.safe.ensureInbox() }
                k.fill(0)
                runOnUiThread {
                    if (ok) {
                        Brain.safeOpenedAt = android.os.SystemClock.elapsedRealtime()
                        Brain.lockSafeLater(KlavaIME.SAFE_RETURN_MS)
                        Brain.onSafeOpened?.invoke()
                    } else android.widget.Toast.makeText(this, getString(R.string.sf_bio_failed),
                        android.widget.Toast.LENGTH_LONG).show()
                    finish()
                    overridePendingTransition(0, 0)
                }
            }.apply { isDaemon = true }.start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) Brain.safeBioPending = false
    }
}
