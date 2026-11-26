package com.kai.keyboard

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Сейф: записи (пароли, коды, адреса), которые хранятся бессрочно и открываются только ПИНом
 * (или отпечатком — тогда ключ сейфа достаёт из хранилища ключей системное окно отпечатка).
 *
 * Как защищено:
 * - записи зашифрованы ключом сейфа (AES-256-GCM); ключ сейфа хранится только зашифрованным
 *   ключом из ПИНа (PBKDF2-SHA256, много итераций — перебор медленный);
 * - всё вместе ещё запечатано ключом телефона (Vault): вне телефона перебирать нечего;
 * - каждая попытка засчитывается ДО проверки (убить приложение посреди проверки — не поможет),
 *   попытки — по одной; после 5 ошибок — растущие паузы по часам, которые нельзя перевести;
 * - любой ПИН проверяется ровно за два вычисления (настоящий и «для удаления») — по времени не
 *   понять, есть ли «ПИНы для удаления»;
 * - «ПИН для удаления» уничтожает ключ сейфа (записи не расшифровать уже никогда) и открывает новый,
 *   пустой, но полностью рабочий сейф с этим ПИНом — со стороны это обычное открытие.
 * Без Android — чтобы проверять тестами (итерации для тестов можно уменьшить).
 *
 * @param elapsed монотонные часы (на телефоне — время с загрузки, их не перевести)
 */
class Safe(private val dir: File, private val vault: Vault?,
           private val now: () -> Long = System::currentTimeMillis,
           private val elapsed: () -> Long = now,
           private val iterations: Int = ITERATIONS) {

    class Entry(val id: Long, val label: String, val secret: String) {
        /** Название для показа: без автоподписей прежних версий («Из буфера · 3 окт, 12:00»). */
        val title get() = if (label.startsWith("Из буфера · ")) "" else label
    }

    sealed class Unlock {
        object Ok : Unlock()
        class Wrong(val waitMs: Long) : Unlock()
        class Wait(val waitMs: Long) : Unlock()
        /** Сейчас не прочитать (ключ телефона ещё не готов, сейф заперли во время проверки) — позже. */
        object NotNow : Unlock()
        object Busy : Unlock()
        object Broken : Unlock()
    }

    enum class State { MISSING, READY, NOT_NOW, BROKEN }

    private class Meta(
        var salt: ByteArray, var iter: Int, var wrapped: ByteArray,
        var dSalt: ByteArray, val duress: MutableList<ByteArray>,
        var failures: Int, var lastFailAt: Long, var lastFailElapsed: Long, var bootAt: Long,
        var bio: ByteArray?,
        /** Из первой версии: у каждого «ПИНа для удаления» своя соль. */
        val legacyDuress: MutableList<Pair<ByteArray, ByteArray>> = ArrayList(),
        /**
         * «Входящие»: открытый ключ (класть в сейф можно и запертым) и закрытый, зашифрованный ключом сейфа
         * (читать положенное — только открыв сейф).
         */
        var pub: ByteArray? = null, var privWrapped: ByteArray? = null,
        /** Метка ключа сейфа: им зашифрована пустая строка — так проверяем ключ после отпечатка. */
        var kcv: ByteArray? = null,
    )

    private class MetaRead(val meta: Meta?, val state: State)

    private val metaFile get() = File(dir, "meta")
    private val itemsFile get() = File(dir, "items")
    private val rnd = SecureRandom()

    /**
     * Ключ и записи в памяти — под отдельным маленьким замком (mem): основной поток («открыт ли?»,
     * «запереть») не ждёт медленную работу (ПИН, хранилище ключей, RSA), которая идёт под замком сейфа.
     * Сам массив ключа наружу не отдаётся — только копии: запирание затирает его, не мешая тем, кто шифрует.
     */
    private val mem = Any()
    @Volatile private var key: ByteArray? = null
    @Volatile private var items: List<Entry> = emptyList()
    /** Растёт при каждом запирании: проверка ПИНа (или отпечаток), закончившаяся после него, сейф не откроет. */
    @Volatile private var epoch = 0
    private var attempting = false
    /** Ключ верный, а записи не расшифровать — сейф повреждён (в этом запуске). */
    @Volatile private var itemsBroken = false

    val isUnlocked get() = key != null
    /** Номер «сеанса»: запомнить перед долгой проверкой и передать в create/unlock/unlockWithKey. */
    val session get() = epoch
    fun exists() = metaFile.exists()

    @Synchronized
    fun state(): State = when {
        !exists() -> State.MISSING
        itemsBroken -> State.BROKEN
        else -> readMeta().state
    }

    /**
     * Метка текущего ключа сейфа (null — заперт): то, что ждёт этот сейф, после «ПИНа для удаления»
     * (новый ключ) уже не его. Из метки ключ не восстановить.
     */
    fun keyTag(): ByteArray? = keyCopy()?.let { k ->
        try { MessageDigest.getInstance("SHA-256").apply { update("klava-safe-tag".toByteArray()); update(k) }.digest().copyOf(KEY_ID_LEN) }
        finally { k.fill(0) }
    }

    fun entries(): List<Entry> { val i = items; return if (key != null) i else emptyList() }

    private fun keyCopy(): ByteArray? = synchronized(mem) { key?.copyOf() }

    private fun open(k: ByteArray, list: List<Entry>, since: Int): Boolean = synchronized(mem) {
        if (epoch != since) return false
        key?.fill(0)
        key = k.copyOf(); items = list
        // Открыли — значит, ПИН помнят: запрос «Забыли ПИН?» (если был) отменяется.
        runCatching { forgetFile.delete() }
        true
    }

    // ================= «Забыли ПИН?»: удалить сейф без ПИНа — только через час после запроса =================

    private val forgetFile get() = File(dir, "forget")

    /**
     * Сколько ещё ждать до удаления без ПИНа: -1 — не запрашивали, 0 — можно удалять. Считаем по
     * монотонным часам; другая загрузка или перевели часы — час начинается заново (сократить нельзя).
     */
    @Synchronized
    fun forgetLeftMs(): Long {
        val f = forgetFile
        if (!f.exists()) return -1L
        val v = runCatching { f.readText().trim().split(' ').map { it.toLong() } }.getOrNull()
        if (v == null || v.size != 2 || kotlin.math.abs(bootAt() - v[1]) >= BOOT_SLACK_MS) {
            writeForget(); return FORGET_DELAY_MS
        }
        return (v[0] + FORGET_DELAY_MS - elapsed()).coerceIn(0L, FORGET_DELAY_MS)
    }

    private fun writeForget() {
        runCatching { dir.mkdirs(); Vault.writeAtomically(forgetFile, "${elapsed()} ${bootAt()}".toByteArray()) }
    }

    @Synchronized
    fun requestForget(): Long { if (!forgetFile.exists()) writeForget(); return forgetLeftMs() }

    @Synchronized
    fun cancelForget() { runCatching { forgetFile.delete() } }

    @Synchronized
    fun forget(): Boolean {
        if (forgetLeftMs() != 0L) return false
        destroy()
        return true
    }

    // ================= создание и открытие =================

    /**
     * Создать сейф с ПИНом (стирает прежний, если был) и открыть его — если с since его не запирали
     * (ушли с экрана посреди создания — сейф создан, но заперт).
     */
    @Synchronized
    fun create(pin: String, since: Int = epoch): Boolean {
        require(validPin(pin))
        val k = ByteArray(32).also { rnd.nextBytes(it) }
        try {
            val salt = ByteArray(16).also { rnd.nextBytes(it) }
            val kek = derive(pin, salt, iterations)
            val meta = Meta(salt, iterations, wrap(kek, k), ByteArray(16).also { rnd.nextBytes(it) }, ArrayList(),
                0, 0L, 0L, bootAt(), null)
            meta.kcv = gcm(k, ByteArray(0), "safe-kcv")
            kek.fill(0)
            // Сначала метаданные (прежний ключ уничтожен), потом пустые записи: оборвётся между ними —
            // старый файл записей помечен чужим ключом и читается как пустой.
            if (!writeMeta(meta)) return false
            generation++
            itemsBroken = false
            runCatching { forgetFile.delete() }
            if (!saveItems(emptyList(), k)) return false
            dir.listFiles()?.forEach { if (it.name.startsWith(INBOX)) runCatching { it.delete() } }
            open(k, emptyList(), since)
            return true
        } finally { k.fill(0) }
    }

    /** Открыть ПИНом. Долго (PBKDF2) — вызывать не на основном потоке. */
    fun unlock(pin: String, since: Int = epoch): Unlock {
        val meta: Meta
        synchronized(this) {
            if (attempting) return Unlock.Busy
            val r = readMeta()
            meta = r.meta ?: return if (r.state == State.BROKEN) Unlock.Broken else Unlock.NotNow
            val wait = waitLeftRebased(meta)
            if (wait > 0) return Unlock.Wait(wait)
            // Засчитываем попытку заранее: оборвать проверку и не получить ошибку — нельзя.
            meta.failures++
            meta.lastFailAt = now(); meta.lastFailElapsed = elapsed(); meta.bootAt = bootAt()
            if (!writeMeta(meta)) return Unlock.NotNow
            attempting = true
        }
        var k1: ByteArray? = null
        var k2: ByteArray? = null
        var k: ByteArray? = null
        try {
            // Всегда ровно два вычисления — для любого ПИНа.
            k1 = derive(pin, meta.salt, meta.iter)
            k2 = derive(pin, meta.dSalt, meta.iter)
            k = unwrap(k1, meta.wrapped)
            val legacyHits = ArrayList<ByteArray>()
            for ((salt, h) in meta.legacyDuress) {
                val d = derive(pin, salt, meta.iter)
                if (MessageDigest.isEqual(d, h)) legacyHits.add(salt)
                d.fill(0)
            }
            synchronized(this) {
                // Свежие метаданные: пока шла проверка, их могли поменять (отпечатком открыли и сменили ПИН,
                // убрали «ПИНы для удаления»). Решаем по свежим.
                val cur = readMeta().meta ?: return Unlock.NotNow
                if (k != null) {
                    if (!cur.salt.contentEquals(meta.salt) || !cur.wrapped.contentEquals(meta.wrapped)) return Unlock.NotNow
                    val loaded = loadItems(k)
                    if (loaded is Loaded.Later) return Unlock.NotNow
                    cur.failures = 0 // ПИН верный — ошибкой не считается, даже если записи не прочитать
                    if (cur.kcv == null) cur.kcv = gcm(k, ByteArray(0), "safe-kcv")
                    writeMeta(cur)
                    if (loaded !is Loaded.Items) { itemsBroken = true; return Unlock.Broken }
                    if (!open(k, loaded.list, since)) return Unlock.NotNow // заперли во время проверки
                    if (!loaded.verified) saveItems(loaded.list, k) // файл от стёртого ключа — переписать
                    mergeInbox(cur)
                    return Unlock.Ok
                }
                val hit = cur.dSalt.contentEquals(meta.dSalt) && cur.duress.any { MessageDigest.isEqual(k2, it) } ||
                    legacyHits.any { s -> cur.legacyDuress.any { it.first.contentEquals(s) } }
                if (hit) {
                    val fresh = wipeInto(cur, k2) ?: return Unlock.NotNow
                    try {
                        return if (open(fresh, emptyList(), since)) Unlock.Ok else Unlock.NotNow
                    } finally { fresh.fill(0) }
                }
                return Unlock.Wrong(waitLeft(cur))
            }
        } finally {
            k1?.fill(0); k2?.fill(0); k?.fill(0)
            synchronized(this) { attempting = false }
        }
    }

    /**
     * «ПИН для удаления»: ключ сейфа уничтожаем — новый ключ, обёрнутый этим ПИНом; записей нет, сейф рабочий.
     * Порядок: сначала метаданные (старый ключ исчезает), потом пустые записи и уборка — оборвётся
     * посередине, старые записи всё равно не прочитать. Отпечаток не выключаем (кнопка не пропадает):
     * старый ключ отпечатка просто перестаёт подходить.
     */
    private fun wipeInto(meta: Meta, kek: ByteArray): ByteArray? {
        val fresh = ByteArray(32).also { rnd.nextBytes(it) }
        meta.salt = meta.dSalt; meta.wrapped = wrap(kek, fresh)
        meta.dSalt = ByteArray(16).also { rnd.nextBytes(it) }
        meta.duress.clear(); meta.legacyDuress.clear()
        meta.failures = 0
        meta.pub = null; meta.privWrapped = null // старые «входящие» — под старым ключом: прочь
        meta.kcv = gcm(fresh, ByteArray(0), "safe-kcv")
        if (!writeMeta(meta)) { fresh.fill(0); return null }
        generation++
        itemsBroken = false
        if (!saveItems(emptyList(), fresh)) runCatching { itemsFile.delete() }
        dir.listFiles()?.forEach { if (it.name.startsWith(INBOX)) runCatching { it.delete() } }
        return fresh
    }

    @Synchronized
    fun unlockWithKey(k: ByteArray, since: Int = epoch): Boolean {
        if (attempting) return false
        val m = readMeta().meta ?: return false
        // Ключ проверяем по метке в метаданных (или по ключу «входящих»): пустой сейф без файла записей
        // иначе открылся бы любым ключом.
        val known = m.kcv?.let { runCatching { gcmOpen(k, it, "safe-kcv") }.isSuccess }
            ?: m.privWrapped?.let { runCatching { gcmOpen(k, it, "safe-inbox-key") }.isSuccess }
        if (known == false) return false
        val loaded = loadItems(k)
        if (loaded !is Loaded.Items) {
            if (known == true && loaded is Loaded.Bad) itemsBroken = true
            return false
        }
        if (known == null && !loaded.verified) return false // ключ ничем не подтверждён
        if (!open(k, loaded.list, since)) return false
        if (!loaded.verified) saveItems(loaded.list, k)
        if (m.failures != 0 || m.kcv == null) {
            m.failures = 0
            if (m.kcv == null) m.kcv = gcm(k, ByteArray(0), "safe-kcv")
            writeMeta(m)
        }
        mergeInbox(m)
        return true
    }

    // ================= «входящие»: положить в сейф, не открывая его =================

    /** Можно ли класть в запертый сейф (ключи «входящих» уже есть). */
    @Synchronized
    fun canDeposit(): Boolean = readMeta().meta?.pub != null

    /**
     * Положить запись в сейф. Открыт — сразу в записи; заперт — во «входящие»: шифруем открытым ключом,
     * прочитать можно только открыв сейф (тогда положенное и появится в списке).
     */
    /** Какой это сейф — номер его поколения (меняется при создании, «ПИНе для удаления», удалении). */
    class Target(val generation: Int)

    /** Растёт при каждой смене ключа сейфа (в памяти; сравнивается только в этом же процессе). */
    @Volatile private var generation = 0

    fun target(): Target = Target(generation)

    /**
     * Хук для тестов: зовётся, когда `put` уже зашифровал запись, но ещё не записал её (перед проверкой
     * поколения под замком). Нужен, чтобы гонка «положить ↔ стереть сейф» воспроизводилась всегда,
     * а не раз в несколько прогонов. В приложении не задаётся.
     */
    @Volatile var onBeforeInboxWrite: (() -> Unit)? = null

    /**
     * @param to если задан — класть только в тот же сейф: стёрли «ПИНом для удаления» и открыли новый,
     *   пока запись ждала, — не кладём (false). Проверка — под замком сейфа, вместе с самой записью.
     */
    fun put(label: String, secret: String, to: Target? = null): Boolean {
        if (secret.isEmpty()) return false
        val gen: Int
        val pub = synchronized(this) {
            if (to != null && to.generation != generation) return false
            if (isUnlocked) return add(label, secret)
            gen = generation
            readMeta().meta?.pub
        } ?: return false
        val ok = try {
            val entry = java.io.ByteArrayOutputStream().also { b ->
                DataOutputStream(b).use { o ->
                    o.writeLong(Clips.newId())
                    val l = label.trim().take(100).toByteArray(Charsets.UTF_8); o.writeInt(l.size); o.write(l)
                    val sb = secret.take(MAX_SECRET).toByteArray(Charsets.UTF_8); o.writeInt(sb.size); o.write(sb)
                    o.writeLong(now())
                }
            }.toByteArray()
            val aes = ByteArray(32).also { rnd.nextBytes(it) }
            val body = gcm(aes, entry, "safe-inbox")
            entry.fill(0)
            val rsa = Cipher.getInstance(RSA)
            rsa.init(Cipher.ENCRYPT_MODE, java.security.KeyFactory.getInstance("RSA")
                .generatePublic(java.security.spec.X509EncodedKeySpec(pub)), OAEP)
            val ek = rsa.doFinal(aes)
            aes.fill(0)
            val out = java.io.ByteArrayOutputStream()
            DataOutputStream(out).use { o -> o.write(INBOX_MAGIC); o.write(keyId(pub)); o.writeInt(ek.size); o.write(ek); o.write(body) }
            val bytes = out.toByteArray()
            onBeforeInboxWrite?.invoke()
            // Записываем под замком сейфа и только если за время шифрования его не стёрли и не пересоздали:
            // иначе положенное под старым ключом молча пропало бы, а клавиатура уже убрала бы его из буфера.
            synchronized(this) {
                if (generation != gen) false
                else put(File(dir, INBOX + Clips.newId()), bytes, "safe-inbox")
            }
        } catch (e: Exception) { false }
        // Пока шифровали, сейф открыли — положенное сразу к записям, а не до следующего открытия.
        if (ok) synchronized(this) { if (isUnlocked) readMeta().meta?.let { mergeInbox(it) } }
        return ok
    }

    /**
     * Ключи «входящих» для этого сейфа (если их ещё нет). Долго (RSA) — в фоне, сразу после открытия.
     * Доводится и если сейф тем временем заперли — лишь бы ключ сейфа был всё тот же.
     * @return true — теперь класть в запертый сейф можно
     */
    fun ensureInbox(): Boolean {
        val k = keyCopy() ?: return false
        try {
            synchronized(this) { if (readMeta().meta?.pub != null) return true }
            val kp = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048, rnd) }.generateKeyPair()
            val priv = kp.private.encoded
            val wrapped = gcm(k, priv, "safe-inbox-key")
            priv.fill(0)
            synchronized(this) {
                val m = readMeta().meta ?: return false
                if (m.pub != null) return true
                // Пока генерировали, ключ сейфа сменился (ПИН для удаления) — эти ключи уже не его.
                val same = m.kcv?.let { runCatching { gcmOpen(k, it, "safe-kcv") }.isSuccess } ?: false
                if (!same) return false
                m.pub = kp.public.encoded; m.privWrapped = wrapped
                return writeMeta(m)
            }
        } catch (e: Exception) {
            return false
        } finally { k.fill(0) }
    }

    private fun mergeInbox(meta: Meta) {
        val files = dir.listFiles()?.filter { it.name.startsWith(INBOX) && !it.name.endsWith(".tmp") }
            ?.takeIf { it.isNotEmpty() } ?: return
        val pw = meta.privWrapped ?: return
        val myId = meta.pub?.let { keyId(it) } ?: return
        val k: ByteArray
        val e: Int
        synchronized(mem) { k = key?.copyOf() ?: return; e = epoch }
        try {
            val priv = try {
                val raw = gcmOpen(k, pw, "safe-inbox-key")
                java.security.KeyFactory.getInstance("RSA").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(raw))
                    .also { raw.fill(0) }
            } catch (ex: Exception) { return }
            val got = ArrayList<Pair<Long, Entry>>()
            val done = ArrayList<File>()
            for (f in files) {
                val raw = when (val r = get(f, "safe-inbox")) {
                    is Vault.Read.Ok -> r.bytes
                    Vault.Read.Corrupt -> { runCatching { f.delete() }; continue } // испорчен — не храним мусор
                    else -> continue // не прочитать сейчас — подождёт
                }
                try {
                    DataInputStream(raw.inputStream()).use { i ->
                        // С меткой формата — проверяем, под каким ключом положено; без неё (прошлая версия) — как есть.
                        if (raw.size > INBOX_MAGIC.size && INBOX_MAGIC.indices.all { raw[it] == INBOX_MAGIC[it] }) {
                            i.skipBytes(INBOX_MAGIC.size)
                            val id = ByteArray(KEY_ID_LEN).also { i.readFully(it) }
                            // Положено под ключом прежнего (стёртого) сейфа — прочитать нельзя никогда: удаляем.
                            if (!id.contentEquals(myId)) { done.add(f); return@use }
                        }
                        val ek = ByteArray(i.readInt()).also { i.readFully(it) }
                        val body = i.readBytes()
                        val rsa = Cipher.getInstance(RSA)
                        rsa.init(Cipher.DECRYPT_MODE, priv, OAEP)
                        val aes = rsa.doFinal(ek)
                        val entry = gcmOpen(aes, body, "safe-inbox")
                        aes.fill(0)
                        DataInputStream(entry.inputStream()).use { d ->
                            fun str() = String(ByteArray(d.readInt()).also { d.readFully(it) }, Charsets.UTF_8)
                            val id = d.readLong(); val label = str(); val secret = str(); val at = d.readLong()
                            got.add(at to Entry(id, label, secret))
                        }
                        entry.fill(0)
                    }
                    done.add(f)
                } catch (ex: Exception) { done.add(f) } // не расшифровать этим ключом — не наш: не копим
            }
            if (got.isEmpty()) { done.forEach { runCatching { it.delete() } }; return }
            // Уже перенесённое (упали между сохранением и удалением файлов) — второй раз не добавляем.
            val base = items
            val have = base.map { it.id }.toHashSet()
            val next = got.sortedByDescending { it.first }.map { it.second }.filter { have.add(it.id) } + base
            if (saveItems(next, k)) {
                synchronized(mem) { if (epoch == e && key != null) items = next }
                done.forEach { runCatching { it.delete() } }
            }
        } finally { k.fill(0) }
    }

    private fun keyId(pub: ByteArray) = MessageDigest.getInstance("SHA-256").digest(pub).copyOf(KEY_ID_LEN)

    /** Запереть: ключ и записи — из памяти; идущая проверка ПИНа сейф уже не откроет. Не ждёт ничего медленного. */
    fun lock() {
        val was = synchronized(mem) {
            val open = key != null
            key?.fill(0)
            key = null; items = emptyList()
            epoch++
            open
        }
        if (was) for (l in lockListeners) runCatching { l() }
    }

    private val lockListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun addLockListener(l: () -> Unit) { lockListeners.addIfAbsent(l) }
    fun removeLockListener(l: () -> Unit) { lockListeners.remove(l) }

    @Synchronized
    fun waitMs(): Long = readMeta().meta?.let { waitLeftRebased(it) } ?: 0L

    /**
     * Ожидание с поправкой на смену часов: другая загрузка или перевели настенные часы — паузу не
     * сокращаем, а начинаем заново по монотонным часам (перевод часов может только перезапустить её).
     */
    private fun waitLeftRebased(m: Meta): Long {
        if (m.failures < FREE_TRIES) return 0L
        if (kotlin.math.abs(bootAt() - m.bootAt) >= BOOT_SLACK_MS) {
            m.lastFailElapsed = elapsed(); m.bootAt = bootAt(); m.lastFailAt = now()
            writeMeta(m)
        }
        return waitLeft(m)
    }

    /** «Загрузка» телефона в настенных часах: та же загрузка — считаем по монотонным часам. */
    private fun bootAt() = now() - elapsed()

    private fun waitLeft(m: Meta): Long {
        if (m.failures < FREE_TRIES) return 0L
        val delay = minOf(MAX_WAIT_MS, FIRST_WAIT_MS shl minOf(20, m.failures - FREE_TRIES))
        val sameBoot = kotlin.math.abs(bootAt() - m.bootAt) < BOOT_SLACK_MS
        val left = if (sameBoot) m.lastFailElapsed + delay - elapsed() else m.lastFailAt + delay - now()
        // Часы перевели назад — не дольше положенной паузы; вперёд (в той же загрузке) — не помогает.
        return left.coerceIn(0L, delay)
    }

    // ================= записи =================

    /** Изменения — на копии: не сохранилось — в памяти тоже ничего не меняется. */
    @Synchronized
    private fun change(f: (MutableList<Entry>) -> Unit): Boolean {
        val k: ByteArray
        val base: List<Entry>
        val e: Int
        synchronized(mem) { k = key?.copyOf() ?: return false; base = items; e = epoch }
        try {
            val next = base.toMutableList().also(f)
            if (!saveItems(next, k)) return false
            synchronized(mem) { if (epoch == e && key != null) items = next }
            return true
        } finally { k.fill(0) }
    }

    fun add(label: String, secret: String): Boolean {
        if (secret.isEmpty()) return false
        return change { it.add(0, Entry(Clips.newId(), label.trim().take(100), secret.take(MAX_SECRET))) }
    }

    fun update(id: Long, label: String, secret: String): Boolean {
        if (secret.isEmpty()) return false
        return change { l ->
            val i = l.indexOfFirst { it.id == id }
            if (i >= 0) l[i] = Entry(id, label.trim().take(100), secret.take(MAX_SECRET))
        }
    }

    fun remove(id: Long): Boolean = change { l -> l.removeAll { it.id == id } }

    // ================= ПИНы и отпечаток (только когда сейф открыт) =================

    enum class PinResult { OK, CONFLICT, FAILED }

    @Synchronized
    fun changePin(newPin: String): PinResult {
        require(validPin(newPin))
        val k = keyCopy() ?: return PinResult.FAILED
        try {
            val m = readMeta().meta ?: return PinResult.FAILED
            if (isDuress(newPin, m)) return PinResult.CONFLICT
            val salt = ByteArray(16).also { rnd.nextBytes(it) }
            val kek = derive(newPin, salt, iterations)
            m.salt = salt; m.iter = iterations; m.wrapped = wrap(kek, k); m.failures = 0
            kek.fill(0)
            return if (writeMeta(m)) PinResult.OK else PinResult.FAILED
        } finally { k.fill(0) }
    }

    private fun isDuress(pin: String, m: Meta): Boolean {
        val d = derive(pin, m.dSalt, m.iter)
        try {
            if (m.duress.any { MessageDigest.isEqual(d, it) }) return true
        } finally { d.fill(0) }
        return m.legacyDuress.any { (s, h) -> derive(pin, s, m.iter).let { x -> MessageDigest.isEqual(x, h).also { x.fill(0) } } }
    }

    @Synchronized
    fun addDuressPin(pin: String): PinResult {
        require(validPin(pin))
        val k = keyCopy() ?: return PinResult.FAILED
        try {
            val m = readMeta().meta ?: return PinResult.FAILED
            val kek = derive(pin, m.salt, m.iter)
            val same = unwrap(kek, m.wrapped)?.let { u -> u.contentEquals(k).also { u.fill(0) } } == true
            kek.fill(0)
            if (same) return PinResult.CONFLICT
            if (isDuress(pin, m)) return PinResult.OK
            m.duress.add(derive(pin, m.dSalt, m.iter))
            return if (writeMeta(m)) PinResult.OK else PinResult.FAILED
        } finally { k.fill(0) }
    }

    @Synchronized
    fun duressCount(): Int = if (key == null) 0 else readMeta().meta?.let { it.duress.size + it.legacyDuress.size } ?: 0

    @Synchronized
    fun clearDuressPins(): Boolean {
        if (key == null) return false
        val m = readMeta().meta ?: return false
        m.duress.clear(); m.legacyDuress.clear()
        return writeMeta(m)
    }

    fun keyForBiometrics(): ByteArray? = keyCopy()

    @Synchronized
    fun biometricBlob(): ByteArray? = readMeta().meta?.bio

    @Synchronized
    fun setBiometricBlob(blob: ByteArray?): Boolean {
        val m = readMeta().meta ?: return false
        m.bio = blob
        return writeMeta(m)
    }

    @Synchronized
    fun destroy() {
        generation++
        lock()
        itemsBroken = false
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    // ================= хранение =================

    private fun derive(pin: String, salt: ByteArray, iter: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iter, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }

    private fun wrap(kek: ByteArray, k: ByteArray): ByteArray = gcm(kek, k, "safe-key")

    private fun unwrap(kek: ByteArray, blob: ByteArray): ByteArray? =
        try { gcmOpen(kek, blob, "safe-key") } catch (e: Exception) { null }

    private fun gcm(key: ByteArray, plain: ByteArray, aad: String): ByteArray {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad.toByteArray())
        return iv + c.doFinal(plain)
    }

    private fun gcmOpen(key: ByteArray, blob: ByteArray, aad: String): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob, 0, 12))
        c.updateAAD(aad.toByteArray())
        return c.doFinal(blob, 12, blob.size - 12)
    }

    /** Записать; любая ошибка (нет ключа, нет места) — false, а не падение. */
    private fun put(f: File, bytes: ByteArray, purpose: String): Boolean = try {
        dir.mkdirs()
        if (vault != null) vault.write(f, bytes, purpose) else { Vault.writeAtomically(f, bytes); true }
    } catch (e: Exception) { false }

    private fun get(f: File, purpose: String): Vault.Read = try {
        if (vault != null) vault.read(f, purpose)
        else if (f.exists()) Vault.Read.Ok(f.readBytes(), false) else Vault.Read.Missing
    } catch (e: Exception) { Vault.Read.NoKey }

    private fun writeMeta(m: Meta): Boolean {
        val buf = java.io.ByteArrayOutputStream()
        DataOutputStream(buf).use { o ->
            fun bytes(b: ByteArray) { o.writeInt(b.size); o.write(b) }
            o.writeInt(VERSION)
            bytes(m.salt); o.writeInt(m.iter); bytes(m.wrapped)
            bytes(m.dSalt); o.writeInt(m.duress.size); for (d in m.duress) bytes(d)
            o.writeInt(m.legacyDuress.size); for ((s, h) in m.legacyDuress) { bytes(s); bytes(h) }
            o.writeInt(m.failures); o.writeLong(m.lastFailAt); o.writeLong(m.lastFailElapsed); o.writeLong(m.bootAt)
            val b = m.bio
            o.writeInt(b?.size ?: -1); if (b != null) o.write(b)
            val pb = m.pub; val pw = m.privWrapped
            o.writeInt(pb?.size ?: -1); if (pb != null) o.write(pb)
            o.writeInt(pw?.size ?: -1); if (pw != null) o.write(pw)
            val kc = m.kcv
            o.writeInt(kc?.size ?: -1); if (kc != null) o.write(kc)
        }
        return put(metaFile, buf.toByteArray(), "safe-meta")
    }

    private fun readMeta(): MetaRead {
        val raw = when (val r = get(metaFile, "safe-meta")) {
            is Vault.Read.Ok -> r.bytes
            Vault.Read.Missing -> return MetaRead(null, State.MISSING)
            Vault.Read.NoKey -> return MetaRead(null, State.NOT_NOW)
            Vault.Read.Corrupt -> return MetaRead(null, State.BROKEN)
        }
        val m = runCatching {
            DataInputStream(raw.inputStream()).use { i ->
                fun bytes() = ByteArray(i.readInt()).also { i.readFully(it) }
                when (i.readInt()) {
                    1 -> { // первая версия: у «ПИНов для удаления» свои соли
                        val salt = bytes(); val iter = i.readInt(); val wrapped = bytes()
                        val legacy = MutableList(i.readInt()) { bytes() to bytes() }
                        val failures = i.readInt(); val last = i.readLong()
                        val n = i.readInt()
                        val bio = if (n >= 0) ByteArray(n).also { i.readFully(it) } else null
                        Meta(salt, iter, wrapped, ByteArray(16).also { rnd.nextBytes(it) }, ArrayList(),
                            failures, last, 0L, Long.MIN_VALUE / 2, bio, legacy)
                    }
                    2, VERSION -> {
                        val salt = bytes(); val iter = i.readInt(); val wrapped = bytes()
                        val dSalt = bytes(); val duress = MutableList(i.readInt()) { bytes() }
                        val legacy = MutableList(i.readInt()) { bytes() to bytes() }
                        val failures = i.readInt(); val last = i.readLong(); val lastEl = i.readLong(); val boot = i.readLong()
                        val n = i.readInt()
                        val bio = if (n >= 0) ByteArray(n).also { i.readFully(it) } else null
                        fun opt(): ByteArray? = runCatching { i.readInt() }.getOrDefault(-1).let { k ->
                            if (k >= 0) ByteArray(k).also { i.readFully(it) } else null
                        }
                        val pub = opt(); val pw = opt(); val kcv = opt() // в старых версиях их нет
                        Meta(salt, iter, wrapped, dSalt, duress, failures, last, lastEl, boot, bio, legacy, pub, pw, kcv)
                    }
                    else -> null
                }
            }
        }.getOrNull()
        return if (m != null) MetaRead(m, State.READY) else MetaRead(null, State.BROKEN)
    }

    /** Метка ключа в файле записей: файл от другого (стёртого) ключа — не «испорчен», а просто чужой. */
    private fun itemsKeyId(k: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").apply { update("klava-safe-items".toByteArray()); update(k) }.digest().copyOf(KEY_ID_LEN)

    private fun saveItems(list: List<Entry>, k: ByteArray): Boolean {
        val buf = java.io.ByteArrayOutputStream()
        DataOutputStream(buf).use { o ->
            o.writeInt(list.size)
            for (e in list) {
                o.writeLong(e.id)
                val l = e.label.toByteArray(Charsets.UTF_8); o.writeInt(l.size); o.write(l)
                val s = e.secret.toByteArray(Charsets.UTF_8); o.writeInt(s.size); o.write(s)
            }
        }
        val plain = buf.toByteArray()
        return try { put(itemsFile, ITEMS_MAGIC + itemsKeyId(k) + gcm(k, plain, "safe-items"), "safe-items") } finally { plain.fill(0) }
    }

    private sealed class Loaded {
        /** verified — расшифровано этим ключом (а не «файла нет / он от стёртого ключа»). */
        class Items(val list: List<Entry>, val verified: Boolean) : Loaded()
        object Later : Loaded()
        /** Не расшифровать этим ключом: повреждён — или ключ чужой. */
        object Bad : Loaded()
    }

    private fun loadItems(k: ByteArray): Loaded {
        if (!itemsFile.exists()) return Loaded.Items(emptyList(), false)
        val sealed = when (val r = get(itemsFile, "safe-items")) {
            is Vault.Read.Ok -> r.bytes
            Vault.Read.Missing -> return Loaded.Items(emptyList(), false)
            Vault.Read.NoKey -> return Loaded.Later
            Vault.Read.Corrupt -> return Loaded.Bad
        }
        var off = 0
        if (sealed.size > ITEMS_MAGIC.size + KEY_ID_LEN && ITEMS_MAGIC.indices.all { sealed[it] == ITEMS_MAGIC[it] }) {
            val id = sealed.copyOfRange(ITEMS_MAGIC.size, ITEMS_MAGIC.size + KEY_ID_LEN)
            // Записан другим ключом — остаток после «ПИНа для удаления» (оборвался посередине): записей нет.
            if (!id.contentEquals(itemsKeyId(k))) return Loaded.Items(emptyList(), false)
            off = ITEMS_MAGIC.size + KEY_ID_LEN
        }
        val raw = try { gcmOpen(k, sealed.copyOfRange(off, sealed.size), "safe-items") } catch (e: Exception) { return Loaded.Bad }
        return try {
            DataInputStream(raw.inputStream()).use { i ->
                fun str() = String(ByteArray(i.readInt()).also { i.readFully(it) }, Charsets.UTF_8)
                Loaded.Items(List(i.readInt()) { Entry(i.readLong(), str(), str()) }, true)
            }
        } catch (e: Exception) { Loaded.Bad } finally { raw.fill(0) }
    }

    companion object {
        const val VERSION = 3
        private const val INBOX = "inbox-"
        private const val KEY_ID_LEN = 8
        private val INBOX_MAGIC = "KIB1".toByteArray()
        private val ITEMS_MAGIC = "KIT1".toByteArray()
        private const val RSA = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
        private val OAEP = javax.crypto.spec.OAEPParameterSpec("SHA-256", "MGF1",
            java.security.spec.MGF1ParameterSpec.SHA256, javax.crypto.spec.PSource.PSpecified.DEFAULT)
        /** PBKDF2: на телефоне — доли секунды на попытку; перебор вне телефона и так невозможен. */
        const val ITERATIONS = 120_000
        const val FREE_TRIES = 5
        const val FIRST_WAIT_MS = 30_000L
        const val MAX_WAIT_MS = 8 * 60 * 60 * 1000L
        const val MAX_SECRET = 20_000
        /** «Забыли ПИН?»: удалить сейф без ПИНа можно не раньше, чем через час после запроса. */
        const val FORGET_DELAY_MS = 60 * 60 * 1000L
        private const val BOOT_SLACK_MS = 2 * 60 * 1000L
        fun validPin(pin: String) = pin.length in 6..12 && pin.all { it in '0'..'9' }
        fun canTry(pin: String) = pin.length in 4..12 && pin.all { it in '0'..'9' }
    }
}
