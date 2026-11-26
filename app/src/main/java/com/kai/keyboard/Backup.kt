package com.kai.keyboard

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Файл переноса на другое устройство: настройки и (по желанию) выученное.
 *
 * Внутри — текст, по записи в строке:
 *   «KLAVA 1» — заголовок;
 *   s<TAB>ключ<TAB>b|i|s<TAB>значение — настройка;
 *   w, b, x — выученные слова, пары слов, слова «не предлагать» (как в UserStore);
 *   r<TAB>эмодзи через пробел — недавние эмодзи; t<TAB>база=вариант … — цвет кожи.
 * Только настройки — файл открытым текстом. С выученным — только зашифрованным паролем:
 *   «KLVENC1», соль (16 байт), число итераций (4 байта), затем AES-256-GCM (iv 12 байт + шифр),
 *   ключ — PBKDF2-SHA256 от пароля. Файл уходит с телефона, поэтому итераций много.
 */
object Backup {
    private const val HEADER = "KLAVA 1"
    private val ENC_MAGIC = "KLVENC1".toByteArray(Charsets.US_ASCII)
    private const val AAD = "klava-backup"
    var ITERATIONS = 600_000
    const val MIN_PASSWORD = 8

    class Data(val settings: Map<String, Any>, val learned: List<String>) {
        val hasLearned get() = learned.isNotEmpty()
    }

    sealed class Read {
        class Ok(val data: Data) : Read()
        /** Зашифрован — нужен пароль. */
        object NeedPassword : Read()
        object WrongPassword : Read()
        object NotBackup : Read()
    }

    fun encode(data: Data, password: String?): ByteArray {
        val sb = StringBuilder(HEADER).append('\n')
        for ((k, v) in data.settings) {
            // Табуляция и перевод строки — разделители записи, поэтому такие значения пишем в base64 (тип «e»):
            // список моделей хранится как «модель\tназвание» и раньше просто не попадал в файл.
            val raw = v.toString()
            val t = when {
                k.any { it == '\t' || it == '\n' } -> continue
                raw.any { it == '\t' || it == '\n' } -> "e"
                v is Boolean -> "b"
                v is Int -> "i"
                else -> "s"
            }
            val text = if (t == "e")
                java.util.Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
            else raw
            sb.append("s\t").append(k).append('\t').append(t).append('\t').append(text).append('\n')
        }
        for (line in data.learned) sb.append(line).append('\n')
        val text = sb.toString().toByteArray(Charsets.UTF_8)
        if (password == null) {
            require(!data.hasLearned) { "learned data needs a password" }
            return text
        }
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val key = derive(password, salt, ITERATIONS)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(AAD.toByteArray())
        val iter = ITERATIONS
        return ENC_MAGIC + salt + byteArrayOf((iter ushr 24).toByte(), (iter ushr 16).toByte(), (iter ushr 8).toByte(), iter.toByte()) +
            iv + c.doFinal(text)
    }

    fun isEncrypted(bytes: ByteArray) = bytes.size > ENC_MAGIC.size && bytes.copyOf(ENC_MAGIC.size).contentEquals(ENC_MAGIC)

    /** Долго (PBKDF2), если файл зашифрован, — не на основном потоке. */
    fun decode(bytes: ByteArray, password: String?): Read {
        val text = if (isEncrypted(bytes)) {
            if (password == null) return Read.NeedPassword
            val head = ENC_MAGIC.size + 16 + 4 + 12
            if (bytes.size < head + 16) return Read.NotBackup
            val salt = bytes.copyOfRange(ENC_MAGIC.size, ENC_MAGIC.size + 16)
            var p = ENC_MAGIC.size + 16
            val iter = ((bytes[p].toInt() and 0xff) shl 24) or ((bytes[p + 1].toInt() and 0xff) shl 16) or
                ((bytes[p + 2].toInt() and 0xff) shl 8) or (bytes[p + 3].toInt() and 0xff)
            p += 4
            // чужой файл не должен подвешивать телефон на минуты
            if (iter !in 10_000..5_000_000) return Read.NotBackup
            val iv = bytes.copyOfRange(p, p + 12)
            val key = derive(password, salt, iter)
            try {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                c.updateAAD(AAD.toByteArray())
                c.doFinal(bytes, p + 12, bytes.size - p - 12)
            } catch (e: Exception) {
                return Read.WrongPassword
            }
        } else bytes
        val lines = String(text, Charsets.UTF_8).split('\n')
        if (lines.firstOrNull()?.trim() != HEADER) return Read.NotBackup
        val settings = LinkedHashMap<String, Any>()
        val learned = ArrayList<String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val p = line.split('\t')
            when (p[0]) {
                "s" -> if (p.size == 4) {
                    val v: Any? = when (p[2]) {
                        "b" -> p[3].toBooleanStrictOrNull()
                        "i" -> p[3].toIntOrNull()
                        "e" -> runCatching {
                            String(java.util.Base64.getDecoder().decode(p[3]), Charsets.UTF_8)
                        }.getOrNull()
                        "s" -> p[3]
                        else -> null
                    }
                    if (v != null) settings[p[1]] = v
                }
                // выученное — только из зашифрованного файла: открытым текстом мы его не пишем
                "w", "b", "x", "r", "t" -> if (isEncrypted(bytes)) learned.add(line)
            }
        }
        return Read.Ok(Data(settings, learned))
    }

    private fun derive(password: String, salt: ByteArray, iter: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iter, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
}
