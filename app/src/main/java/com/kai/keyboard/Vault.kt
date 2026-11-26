package com.kai.keyboard

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Шифрование всего, что клавиатура пишет на диск: AES-256-GCM (шифр с проверкой целостности —
 * подменённый или битый файл не прочитается как «что-то другое»). Назначение файла («learned»,
 * «pins», имя настройки) входит в проверку: файл одного назначения не подсунуть вместо другого.
 *
 * Ключ данных — случайный, хранится только зашифрованным ключом из хранилища ключей Android
 * (Keystore): тот не извлекается и доступен только Klava. Без Android (для тестов) ключ данных
 * передаётся напрямую.
 *
 * Главное правило: временная недоступность ключа — не «пусто». Кто читает, получает NoKey и
 * не должен перезаписывать файл, пока не прочитает его по-настоящему.
 */
class Vault(private val dataKey: () -> SecretKey?) {
    private val rnd = SecureRandom()

    sealed class Read {
        class Ok(val bytes: ByteArray, val legacy: Boolean) : Read()
        object Missing : Read()
        object NoKey : Read()
        object Corrupt : Read()
    }

    fun seal(plain: ByteArray, purpose: String): ByteArray? {
        val key = runCatching { dataKey() }.getOrNull() ?: return null
        val iv = ByteArray(IV_LEN).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        c.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        return MAGIC2 + iv + c.doFinal(plain)
    }

    fun open(data: ByteArray, purpose: String): Read {
        val v = version(data) ?: return Read.Ok(data, legacy = true)
        val key = runCatching { dataKey() }.getOrNull() ?: return Read.NoKey
        if (data.size < HEAD + IV_LEN + TAG_BITS / 8) return Read.Corrupt
        // «Испорчено» — только если шифр явно не сошёлся. Любая другая ошибка (память, сбой провайдера) —
        // не повод считать данные потерянными: «попробуем позже».
        return try {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, data, HEAD, IV_LEN))
            if (v == 2) c.updateAAD(purpose.toByteArray(Charsets.UTF_8)) // в KLV1 назначения ещё не было
            Read.Ok(c.doFinal(data, HEAD + IV_LEN, data.size - HEAD - IV_LEN), legacy = v == 1)
        } catch (e: javax.crypto.AEADBadTagException) {
            Read.Corrupt
        } catch (e: Exception) {
            Read.NoKey
        }
    }

    /** Записать файл зашифрованным: через временный, с fsync — оборванная запись не испортит прежний. */
    fun write(file: File, plain: ByteArray, purpose: String): Boolean {
        val sealed = seal(plain, purpose) ?: return false
        writeAtomically(file, sealed)
        return true
    }

    fun read(file: File, purpose: String): Read {
        if (!file.exists()) return Read.Missing
        val data = try { file.readBytes() } catch (e: Exception) { return Read.NoKey }
        return open(data, purpose)
    }

    fun sealString(s: String, purpose: String): String? =
        seal(s.toByteArray(Charsets.UTF_8), purpose)?.let { PREFIX + b64(it) }

    fun openString(s: String, purpose: String): Read {
        if (!s.startsWith(PREFIX)) return Read.Ok(s.toByteArray(Charsets.UTF_8), legacy = true)
        val raw = runCatching { unb64(s.substring(PREFIX.length)) }.getOrNull() ?: return Read.Corrupt
        return open(raw, purpose)
    }

    companion object {
        private val MAGIC1 = "KLV1".toByteArray()
        private val MAGIC2 = "KLV2".toByteArray()
        private const val HEAD = 4
        private const val PREFIX = "klv1:"
        private const val IV_LEN = 12
        private const val TAG_BITS = 128
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val ALIAS = "kai.storage.v1"
        private const val RETRY_MS = 30_000L

        private fun starts(data: ByteArray, m: ByteArray) = data.size > HEAD + IV_LEN && m.indices.all { data[it] == m[it] }
        private fun version(data: ByteArray) = when { starts(data, MAGIC2) -> 2; starts(data, MAGIC1) -> 1; else -> null }
        fun isSealed(data: ByteArray) = version(data) != null

        private fun b64(b: ByteArray) = java.util.Base64.getEncoder().encodeToString(b)
        private fun unb64(s: String) = java.util.Base64.getDecoder().decode(s)

        fun asideName(f: File, tag: String): File {
            var n = 0
            while (true) {
                val c = File(f.path + "." + tag + if (n == 0) "" else "$n")
                if (!c.exists()) return c
                n++
            }
        }

        fun recoverAside(f: File, read: (File) -> Read): Read.Ok? {
            if (f.exists()) return null
            val aside = f.parentFile?.listFiles()
                ?.filter { it.name.startsWith(f.name + ".bad") && !it.name.endsWith(".tmp") }
                ?.sortedByDescending { it.lastModified() } ?: return null
            for (c in aside) {
                val r = runCatching { read(c) }.getOrNull() as? Read.Ok ?: continue
                return if (c.renameTo(f)) r else null
            }
            return null
        }

        fun writeAtomically(file: File, bytes: ByteArray) {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            try {
                FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
            } catch (e: Exception) {
                runCatching { tmp.delete() }
                throw e
            }
            // Что кладём — важно (ключи, записи сейфа, закреплённое из буфера): если переименовать
            // не удалось, не докладываем об успехе и прежний файл не трогаем (удалить его и не смочь
            // положить новый — потеря). Так же поступают Preferences, когда запись не удалась.
            if (!move(tmp, file)) {
                runCatching { tmp.delete() }
                throw java.io.IOException("rename ${tmp.name}")
            }
        }

        private fun move(from: File, to: File): Boolean {
            if (from.renameTo(to)) return true
            return runCatching {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
                true
            }.getOrDefault(false) && !from.exists()
        }

        fun withKey(raw: ByteArray) = Vault { SecretKeySpec(raw, "AES") }

        @Volatile private var app: Vault? = null

        /**
         * Хранилище приложения: ключ данных, завёрнутый ключом из Android Keystore.
         * Основной поток хранилище ключей не ждёт: пока ключ не загружен фоном — «ключа нет»
         * (читающие тогда ничего не перезаписывают).
         */
        fun of(ctx: android.content.Context): Vault = app ?: synchronized(this) {
            app ?: run {
                val dir = ctx.applicationContext.noBackupFilesDir
                keyDir = dir
                Vault {
                    keyCache ?: run {
                        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                            loadInBackground() // основной поток не ждёт — но загрузку запускаем
                            return@run null
                        }
                        loadNow()
                    }
                }.also { app = it }
            }
        }

        @Volatile private var keyCache: SecretKey? = null
        @Volatile private var failedAt = 0L
        @Volatile private var keyDir: File? = null
        private val loadLock = java.util.concurrent.locks.ReentrantLock()
        @Volatile var background: ((() -> Unit) -> Unit)? = null
        private val waiting = ArrayList<() -> Unit>()
        @Volatile private var loading = false

        val ready get() = keyCache != null

        fun whenReady(cb: () -> Unit) {
            if (keyCache != null) { cb(); return }
            synchronized(waiting) { waiting.add(cb) }
            if (keyCache != null) { notifyReady(); return }
            loadInBackground()
        }

        fun retryNow() {
            failedAt = 0L
            loadInBackground()
        }

        private var retryScheduled = false

        private fun loadInBackground() {
            synchronized(waiting) { if (loading) return; loading = true }
            val job = {
                try { loadNow() } finally {
                    synchronized(waiting) { loading = false }
                    if (keyCache != null) notifyReady() // и тем, кто встал в очередь, пока грузили
                    // Не вышло, а кто-то ждёт — попробуем ещё раз чуть позже (а не ждём вечно). Один повтор.
                    else if (synchronized(waiting) { waiting.isNotEmpty() && !retryScheduled.also { retryScheduled = true } }) {
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            synchronized(waiting) { retryScheduled = false }
                            loadInBackground()
                        }, RETRY_MS)
                    }
                }
            }
            background?.invoke { job() } ?: Thread { job() }.start()
        }

        private fun loadNow(): SecretKey? {
            keyCache?.let { return it }
            val dir = keyDir ?: return null
            loadLock.lock()
            try {
                keyCache?.let { return it }
                val now = System.currentTimeMillis()
                if (failedAt != 0L && now - failedAt < RETRY_MS) return null
                return loadDataKey(dir).also { if (it == null) failedAt = now else { keyCache = it; notifyReady() } }
            } finally { loadLock.unlock() }
        }

        /** Ключ загружен (кем угодно, на любом потоке) — будим всех, кто ждал. */
        private fun notifyReady() {
            val cbs = synchronized(waiting) { ArrayList(waiting).also { waiting.clear() } }
            if (cbs.isEmpty()) return
            val h = android.os.Handler(android.os.Looper.getMainLooper())
            for (cb in cbs) h.post(cb)
        }

        fun cancelWait(cb: () -> Unit) { synchronized(waiting) { waiting.remove(cb) } }

        private var struckThisRun = false
        /** Ключ признаётся потерянным только после стольких запусков подряд… */
        private const val STRIKES_TO_RESET = 3
        /** …и не раньше, чем через столько времени после первого такого запуска. */
        private const val LOST_MIN_MS = 24 * 60 * 60 * 1000L
        private val KEYFILE_MAGIC = "VK2".toByteArray()

        /** Файл ключа: имя ключа хранилища и обёрнутый ключ данных. Старый формат — без имени (ALIAS). */
        private class KeyFile(val alias: String, val wrapped: ByteArray)

        private fun parseKeyFile(b: ByteArray): KeyFile? {
            if (b.size != LEGACY_KEYFILE && b.size > 4 && KEYFILE_MAGIC.indices.all { b[it] == KEYFILE_MAGIC[it] }) {
                val n = b[3].toInt() and 0xff
                if (b.size == 4 + n + LEGACY_KEYFILE) {
                    val alias = String(b, 4, n, Charsets.UTF_8)
                    if (alias.startsWith(ALIAS)) return KeyFile(alias, b.copyOfRange(4 + n, b.size))
                }
            }
            return KeyFile(ALIAS, b)
        }

        private const val LEGACY_KEYFILE = 12 + 32 + 16

        /**
         * Хранилище точно говорит «такого ключа нет» (а не «сейчас не могу»): на Android 13+ — по коду
         * ошибки; на старых — только null без исключения (и тогда всё равно ждём сутки и три запуска).
         */
        private fun definitelyMissing(e: java.security.UnrecoverableKeyException): Boolean {
            if (android.os.Build.VERSION.SDK_INT < 33) return false
            var c: Throwable? = e.cause
            while (c != null) {
                if (c is android.security.KeyStoreException) {
                    return c.numericErrorCode == android.security.KeyStoreException.ERROR_KEY_DOES_NOT_EXIST
                }
                c = c.cause
            }
            return false
        }

        private fun loadDataKey(dir: File): SecretKey? {
            val file = File(dir, "vault.key")
            val strikes = File(dir, "vault.strikes")
            val ks = try { keyStore() } catch (e: Exception) { return null }
            fun masterFor(alias: String): Pair<SecretKey?, Boolean>? = try {
                val k = ks.getKey(alias, null) as? SecretKey
                // null без исключения — «нет такого». На Android 11 и старше null бывает и при сбое
                // хранилища, поэтому там «нет» — только если хранилище перечислило ключи и этого среди них нет.
                if (k == null && android.os.Build.VERSION.SDK_INT < 31) {
                    val listed = try { ks.aliases().toList() } catch (e: Exception) { null }
                    if (listed == null || alias in listed) null else (null to true)
                } else k to true
            } catch (e: java.security.UnrecoverableKeyException) {
                if (definitelyMissing(e)) null to true else null
            } catch (e: Exception) {
                null
            }
            if (file.exists()) {
                val bytes = try { file.readBytes() } catch (e: Exception) { return null }
                val kf = parseKeyFile(bytes)
                if (kf != null) {
                    val m = masterFor(kf.alias) ?: return null // сбой хранилища — позже
                    val master = m.first
                    if (master != null) {
                        if (kf.wrapped.size > IV_LEN + TAG_BITS / 8) {
                            try {
                                val c = Cipher.getInstance(TRANSFORM)
                                c.init(Cipher.DECRYPT_MODE, master, GCMParameterSpec(TAG_BITS, kf.wrapped, 0, IV_LEN))
                                val key = SecretKeySpec(c.doFinal(kf.wrapped, IV_LEN, kf.wrapped.size - IV_LEN), "AES")
                                strikes.delete()
                                return key
                            } catch (e: javax.crypto.AEADBadTagException) {
                            } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
                            } catch (e: Exception) {
                                return null // сбой — данные целы, попробуем позже
                            }
                        }
                    }
                }
                // Похоже на потерю. Засчитываем раз за запуск; новый ключ — только после 3 запусков и суток.
                val (n, first) = runCatching {
                    strikes.readText().trim().split(' ').let { it[0].toInt() to it[1].toLong() }
                }.getOrDefault(0 to System.currentTimeMillis())
                val count = if (struckThisRun) n else n + 1
                if (!struckThisRun) {
                    struckThisRun = true
                    runCatching { writeAtomically(strikes, "$count $first".toByteArray()) }
                }
                if (count < STRIKES_TO_RESET || System.currentTimeMillis() - first < LOST_MIN_MS) return null
            }
            // Перед новым ключом — может, найдётся старый (отложенный когда-то): тогда данные вернутся.
            for (lost in dir.listFiles()?.filter { it.name.startsWith("vault.key.lost") }?.sortedByDescending { it.name }.orEmpty()) {
                val bytes = try { lost.readBytes() } catch (e: Exception) { return null }
                val kf = parseKeyFile(bytes) ?: continue
                // Сбой хранилища — не «ключ не подходит»: попробуем позже, а не заведём новый.
                val m = (masterFor(kf.alias) ?: return null).first ?: continue
                if (kf.wrapped.size <= IV_LEN + TAG_BITS / 8) continue
                val key = try {
                    val c = Cipher.getInstance(TRANSFORM)
                    c.init(Cipher.DECRYPT_MODE, m, GCMParameterSpec(TAG_BITS, kf.wrapped, 0, IV_LEN))
                    SecretKeySpec(c.doFinal(kf.wrapped, IV_LEN, kf.wrapped.size - IV_LEN), "AES")
                } catch (e: javax.crypto.AEADBadTagException) { continue
                } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) { continue
                } catch (e: Exception) { return null }
                if (file.exists() && !file.renameTo(File(dir, "vault.key.lost-${System.currentTimeMillis()}"))) return null
                if (!lost.renameTo(file)) return null
                strikes.delete()
                return key
            }
            // Новый ключ данных — под ключом хранилища с новым именем (старое имя не трогаем).
            val firstEver = !file.exists() && !File(dir, "vault.key.used").exists() &&
                dir.list()?.none { it.startsWith("vault.key.lost") } == true
            // Ключ хранилища, заведённый прошлой (оборвавшейся) попыткой, — берём его, а не плодим новые.
            val pendingFile = File(dir, "vault.alias.pending")
            val pending = runCatching { pendingFile.readText().trim() }.getOrNull()?.takeIf { it.startsWith(ALIAS) }
            val reused = pending?.let { masterFor(it)?.first }
            val alias = when {
                reused != null -> pending
                firstEver && masterFor(ALIAS)?.let { it.first == null && it.second } == true -> ALIAS
                else -> "$ALIAS.${System.currentTimeMillis()}"
            }
            if (reused == null) runCatching { writeAtomically(pendingFile, alias.toByteArray()) }.onFailure { return null }
            // Сначала новый ключ хранилища — и только когда он есть, откладываем старый файл.
            val m = reused ?: newMasterKey(alias) ?: return null
            if (file.exists()) {
                if (!file.renameTo(File(dir, "vault.key.lost-${System.currentTimeMillis()}"))) return null
                strikes.delete()
            }
            return try {
                val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val c = Cipher.getInstance(TRANSFORM)
                c.init(Cipher.ENCRYPT_MODE, m) // IV выбирает сам Keystore (12 байт)
                val a = alias.toByteArray(Charsets.UTF_8)
                if (file.exists()) return null // файл появился (или stat ошибся) — не затираем
                writeAtomically(file, KEYFILE_MAGIC + byteArrayOf(a.size.toByte()) + a + c.iv + c.doFinal(raw))
                runCatching { File(dir, "vault.key.used").createNewFile() }
                pendingFile.delete()
                SecretKeySpec(raw, "AES")
            } catch (e: Exception) { null }
        }

        private fun keyStore() = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        /**
         * Ключ в Android Keystore: не извлекается, только для Klava. Имя — новое (или основное при первом
         * запуске, когда хранилище точно говорит, что его нет): существующий ключ так не заменить.
         */
        private fun newMasterKey(alias: String): SecretKey? = runCatching {
            val gen = javax.crypto.KeyGenerator.getInstance(
                android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(android.security.keystore.KeyGenParameterSpec.Builder(alias,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            gen.generateKey()
        }.getOrNull()
    }
}
