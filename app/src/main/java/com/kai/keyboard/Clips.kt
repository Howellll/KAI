package com.kai.keyboard

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.SecureRandom

/**
 * История буфера обмена: текст и картинки. Обычные записи — только в памяти и живут ttlMs
 * (по умолчанию час): пароль или адрес, скопированный утром, вечером уже не всплывёт.
 * Закреплённые владельцем — живут, пока их не открепят, и переживают перезапуск (PinStore).
 */
class Clips(private val now: () -> Long = System::currentTimeMillis) {
    class Image(val bytes: ByteArray, val mime: String) {
        val hash = bytes.contentHashCode()
    }

    /**
     * Запись: текст или картинка (тогда text пуст). id — случайный, не угадать и не повторится
     * после перезапуска: по нему приложение, в которое вставили картинку, её и читает.
     */
    class Item(val text: String, at: Long, val image: Image? = null, val id: Long = newId()) {
        val isImage get() = image != null
        @Volatile var at = at
            internal set
        @Volatile var pinned = false
            internal set
    }

    @Volatile var ttlMs = TTL_MS

    var store: PinStore? = null

    var onChanged: (() -> Unit)? = null

    private val items = ArrayList<Item>()
    private var used: Item? = null
    /** Закреплённое прочитано с диска; пока нет — не сохраняем, иначе затёрли бы его. */
    private var pinsLoaded = false
    private var saveWanted = false
    private var epoch = 0

    @Synchronized
    fun add(text: String): Item? {
        prune()
        val t = text.take(MAX_LEN)
        if (t.isBlank()) return null
        // Уже закреплено — не дублируем: закреплённое и так под рукой.
        items.firstOrNull { it.pinned && !it.isImage && it.text == t }?.let { return it }
        items.removeAll { !it.isImage && it.text == t }
        return Item(t, now()).also { push(it) }
    }

    /**
     * @param at когда скопировали (картинка читается в фоне — встаёт на своё место, а не поверх
     *   скопированного после неё текста)
     * @return запись или null, если картинка слишком большая и не сохранена
     */
    @Synchronized
    fun addImage(bytes: ByteArray, mime: String, at: Long = now()): Item? {
        prune()
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
        val img = Image(bytes, mime)
        fun same(it: Item) = it.image?.let { o -> o.hash == img.hash && o.bytes.contentEquals(bytes) } == true
        items.firstOrNull { it.pinned && same(it) }?.let { return it }
        items.removeAll { same(it) }
        val item = Item("", at, img)
        val pos = items.indexOfFirst { !it.pinned && it.at < at }.let { if (it < 0) items.size else it }
        items.add(pos, item)
        trim()
        // Картинки тяжёлые — держим только несколько последних (закреплённые не считаем).
        var seen = 0
        items.removeAll { !it.pinned && it.isImage && ++seen > MAX_IMAGES }
        return item.takeIf { it in items }
    }

    private fun push(item: Item) {
        items.add(0, item)
        trim()
    }

    private fun trim() {
        var free = 0
        items.removeAll { !it.pinned && ++free > MAX_ITEMS }
    }

    private fun prune() {
        val limit = now() - ttlMs
        items.removeAll { !it.pinned && it.at < limit }
    }

    @Synchronized
    fun list(): List<Item> {
        prune()
        return items.filter { it.pinned } + items.filter { !it.pinned }
    }

    @Synchronized
    fun byId(id: Long): Item? = list().firstOrNull { it.id == id }

    @Synchronized
    fun setPinned(item: Item, pin: Boolean): Boolean {
        if (item !in items) return false
        if (pin && !item.pinned) {
            if (items.count { it.pinned } >= MAX_PINS) return false
            if (item.isImage && items.count { it.pinned && it.isImage } >= MAX_PINNED_IMAGES) return false
        }
        if (item.pinned == pin) return true
        item.pinned = pin
        item.at = now()
        // Открепили — она как только что скопированная: наверх, а не в хвост, где её первой и вытеснят.
        if (!pin) { items.remove(item); items.add(0, item) }
        save()
        return true
    }

    @Synchronized
    fun image(id: Long): Image? = byId(id)?.image

    @Synchronized
    fun remove(item: Item) {
        val i = items.indexOfFirst { it.id == item.id }
        if (i < 0) return
        val wasPinned = items[i].pinned
        items.removeAt(i)
        if (wasPinned) save()
    }

    @Synchronized
    fun clear() { items.removeAll { !it.pinned }; used = null }

    @Synchronized
    fun clearAll() {
        items.clear(); used = null
        epoch++
        pinsLoaded = true // читать больше нечего: всё стёрто
        saveWanted = false
        store?.erase()
    }

    @Synchronized
    fun recent(): Item? {
        prune()
        val first = items.firstOrNull { !it.pinned } ?: return null
        return first.takeIf { it !== used && now() - it.at < RECENT_MS }
    }

    @Synchronized
    fun markUsed(item: Item) { used = item }

    @Synchronized
    fun dismissRecent() { items.firstOrNull { !it.pinned }?.let { used = it } }

    /**
     * Подгрузить закреплённое (в фоне). Ключ недоступен — попробуем при следующем изменении;
     * закреплённое до этого момента дописывается к прочитанному, а не затирает его.
     */
    fun loadPins() {
        val s = store ?: return
        val e: Int
        synchronized(this) { if (pinsLoaded) return; e = epoch }
        val r = s.load()
        synchronized(this) {
            if (e != epoch || pinsLoaded) return
            when (r) {
                is PinStore.Load.Ok -> {
                    for (p in r.pins) {
                        val dup = items.any {
                            it.pinned && if (p.image != null) it.image?.bytes?.contentEquals(p.image.bytes) == true
                            else !it.isImage && it.text == p.text
                        }
                        // Лимиты тут не режем: лишнее пришлось бы стереть с диска. Новые закрепления
                        // просто не пройдут, пока закреплённого не станет меньше лимита.
                        if (!dup) items.add(Item(p.text, now(), p.image, p.id).also { it.pinned = true })
                    }
                    pinsLoaded = true
                    // старый формат или закрепили до чтения — сохраняем всё вместе
                    if (r.migrate || saveWanted) { saveWanted = false; s.save(items.filter { it.pinned }) }
                }
                PinStore.Load.NoKey -> return
            }
        }
        onChanged?.invoke()
    }

    @Synchronized
    fun ensurePins() {
        if (!pinsLoaded) store?.later { loadPins() }
    }

    private fun save() {
        val s = store ?: return
        if (!pinsLoaded) {
            // Ещё не прочитано с диска — сохранить сейчас значило бы затереть прежнее закреплённое.
            saveWanted = true
            s.later { loadPins() }
            return
        }
        s.save(items.filter { it.pinned })
    }

    companion object {
        const val TTL_MS = 60 * 60 * 1000L
        const val RECENT_MS = 90 * 1000L
        const val MAX_ITEMS = 30
        const val MAX_LEN = 20000
        /** Картинка больше — не храним (в памяти клавиатуры ей не место). */
        const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
        const val MAX_IMAGES = 4
        const val MAX_PINS = 50
        const val MAX_PINNED_IMAGES = 10

        private val rnd = SecureRandom()
        fun newId(): Long = rnd.nextLong() and Long.MAX_VALUE
    }
}

/**
 * Закреплённое на диске, в личной папке приложения (не попадает в резервные копии), зашифровано (Vault):
 * маленький список (текст, тип картинки, id) и каждая картинка — отдельным файлом. Картинка пишется
 * один раз, когда её закрепили, — список меняется без перезаписи мегабайтов. Всё пишется в фоне (run).
 * @param dir папка закреплённого (первая версия хранила всё одним файлом по этому же пути)
 */
class PinStore(private val dir: File, private val vault: Vault? = null,
               private val run: (() -> Unit) -> Unit = { it() }) {
    class Pin(val id: Long, val text: String, val image: Clips.Image?)

    sealed class Load {
        class Ok(val pins: List<Pin>, val migrate: Boolean) : Load()
        object NoKey : Load()
    }

    private val index get() = File(dir, "index")
    /**
     * Картинки, которые мы точно знаем как свои (прочитали или записали): удалять при откреплении можно
     * только их. Незнакомые и непрочитанные файлы никогда не стираются — кроме «стереть всё».
     */
    private val known = HashSet<Long>()
    /** Закреплённые картинки, которые сейчас не прочитать (id → тип): остаются в списке и на диске. */
    private val orphans = HashMap<Long, String>()
    private fun imageFile(id: Long, base: File = dir) = File(base, "$id.img")
    /** Куда пишется новый формат при переезде со старого файла — старый удаляется только после. */
    private val staging get() = File(dir.path + ".new")
    /** Временный файл первой версии (мог остаться открытым текстом). */
    private val legacyTmp get() = File(dir.path + ".tmp")

    fun later(fn: () -> Unit) = run(fn)

    fun save(items: List<Clips.Item>) {
        val snapshot = items.map { Pin(it.id, it.text, it.image) }
        run { write(snapshot) }
    }

    /** Не записалось (ключа не было) — последний снимок ждёт; записать, когда ключ появится. */
    @Volatile private var pending: List<Pin>? = null

    fun flushPending() = run { pending?.let { write(it) } }

    fun erase() = run { wipe() }

    private fun wipe() {
        known.clear(); orphans.clear(); pending = null
        if (dir.isFile) dir.delete() else dir.listFiles()?.forEach { it.delete() }
        staging.deleteRecursively()
        legacyTmp.delete()
    }

    /** Закреплённого не осталось: убираем список и свои картинки; незнакомое и отложенное (.bad) — на месте. */
    private fun clearKnown() {
        pending = null
        if (dir.isFile) dir.delete()
        else {
            index.delete()
            for (id in known) imageFile(id).delete()
        }
        known.clear()
        staging.deleteRecursively()
        legacyTmp.delete()
    }

    private fun write(pins: List<Pin>) {
        // Пусто только то, что мы видим: непрочитанные картинки (orphans) на диске остаются в списке.
        // Стереть всё — только явным erase() («стереть всё», выключение истории).
        if (pins.isEmpty() && orphans.isEmpty()) { clearKnown(); return }
        if (vault != null && vault.seal(ByteArray(0), "probe") == null) { pending = pins; return }
        pending = null
        // Переезд со старого однофайлового формата: пишем рядом, во временную папку, и только когда
        // всё записано — убираем старый файл. Пропал ключ посреди записи — старое цело.
        val migrating = dir.isFile
        val base = if (migrating) staging.also { it.deleteRecursively() } else dir
        base.mkdirs()
        if (!writeInto(base, pins)) { if (migrating) staging.deleteRecursively(); return }
        if (migrating) {
            if (!dir.delete() || !staging.renameTo(dir)) return // не вышло — старое на месте, повторим
        }
        legacyTmp.delete() // остаток первой версии (мог быть открытым текстом)
    }

    private fun writeInto(base: File, pins: List<Pin>): Boolean {
        for (p in pins) {
            val img = p.image ?: continue
            val f = imageFile(p.id, base)
            if (!f.exists() && !put(f, img.bytes, "pin-image")) return false
        }
        val buf = java.io.ByteArrayOutputStream()
        DataOutputStream(buf).use { out ->
            out.writeInt(VERSION)
            out.writeInt(pins.size + orphans.size)
            for (p in pins) {
                out.writeLong(p.id)
                val img = p.image
                out.writeBoolean(img != null)
                if (img != null) out.writeUTF(img.mime) else {
                    val b = p.text.toByteArray(Charsets.UTF_8)
                    out.writeInt(b.size); out.write(b)
                }
            }
            // непрочитанные картинки — в списке остаются, прочитаются, когда получится
            for ((id, mime) in orphans) { out.writeLong(id); out.writeBoolean(true); out.writeUTF(mime) }
        }
        if (!put(File(base, "index"), buf.toByteArray(), "pins")) return false
        for (p in pins) if (p.image != null) known.add(p.id)
        // открепленные картинки — стереть, но только свои, известные (и не непрочитанные)
        val keep = pins.filter { it.image != null }.map { it.id }.toSet()
        base.listFiles()?.forEach { f ->
            val id = f.name.removeSuffix(".img").toLongOrNull() ?: return@forEach
            if (f.name.endsWith(".img") && id in known && id !in keep && id !in orphans) { f.delete(); known.remove(id) }
        }
        return true
    }

    private fun put(f: File, bytes: ByteArray, purpose: String): Boolean = try {
        if (vault != null) vault.write(f, bytes, purpose) else { Vault.writeAtomically(f, bytes); true }
    } catch (e: java.io.IOException) { false } // нет места и т.п. — как «не записалось»

    private fun get(f: File, purpose: String): Vault.Read =
        if (vault != null) vault.read(f, purpose)
        else if (f.exists()) Vault.Read.Ok(f.readBytes(), false) else Vault.Read.Missing

    fun load(): Load {
        if (!dir.exists() && staging.isDirectory) staging.renameTo(dir)
        if (dir.isFile) return loadLegacy()
        val raw = when (val r = get(index, "pins")) {
            is Vault.Read.Ok -> r.bytes
            // Нет списка — может, есть отложенный, который теперь читается (вернулся прежний ключ).
            Vault.Read.Missing -> Vault.recoverAside(index) { get(it, "pins") }?.bytes ?: return Load.Ok(emptyList(), false)
            Vault.Read.NoKey -> return Load.NoKey
            // Список не прочитать — откладываем его; картинки на диске остаются (они нам неизвестны — не тронем).
            // Может, читается отложенный раньше (вернулся прежний ключ) — тогда он снова на месте.
            Vault.Read.Corrupt -> {
                index.renameTo(Vault.asideName(index, "bad"))
                Vault.recoverAside(index) { get(it, "pins") }?.bytes ?: return Load.Ok(emptyList(), false)
            }
        }
        val pins = ArrayList<Pin>()
        try {
            DataInputStream(raw.inputStream()).use { inp ->
                if (inp.readInt() == VERSION) repeat(inp.readInt()) {
                    val id = inp.readLong()
                    if (inp.readBoolean()) {
                        val mime = inp.readUTF()
                        when (val r = get(imageFile(id), "pin-image")) {
                            is Vault.Read.Ok -> { pins.add(Pin(id, "", Clips.Image(r.bytes, mime))); known.add(id) }
                            // Ключа нет — ждём всё целиком; ключ есть, а файл не читается (сбой диска) —
                            // эта картинка подождёт в сторонке, остальное закреплённое — показываем.
                            Vault.Read.NoKey -> if (vault == null || vault.seal(ByteArray(0), "probe") == null) return Load.NoKey
                                else orphans[id] = mime
                            Vault.Read.Corrupt -> orphans[id] = mime // не прочитать — но и не стирать
                            Vault.Read.Missing -> {} // файла нет — без неё
                        }
                    } else {
                        val b = ByteArray(inp.readInt()).also { inp.readFully(it) }
                        pins.add(Pin(id, String(b, Charsets.UTF_8), null))
                    }
                }
            }
        } catch (e: java.io.IOException) {
        }
        return Load.Ok(pins, false)
    }

    private fun loadLegacy(): Load {
        val raw = when (val r = get(dir, "pins")) {
            is Vault.Read.Ok -> r.bytes
            Vault.Read.NoKey -> return Load.NoKey
            else -> { dir.renameTo(Vault.asideName(dir, "bad")); return Load.Ok(emptyList(), false) }
        }
        val pins = runCatching {
            DataInputStream(raw.inputStream()).use { inp ->
                if (inp.readInt() != 1) return@use emptyList()
                List(inp.readInt()) {
                    if (inp.readBoolean()) {
                        val mime = inp.readUTF()
                        val b = ByteArray(inp.readInt()).also { inp.readFully(it) }
                        Pin(Clips.newId(), "", Clips.Image(b, mime))
                    } else {
                        val b = ByteArray(inp.readInt()).also { inp.readFully(it) }
                        Pin(Clips.newId(), String(b, Charsets.UTF_8), null)
                    }
                }
            }
        }.getOrDefault(emptyList())
        return Load.Ok(pins, migrate = true)
    }

    companion object { const val VERSION = 2 }
}

val CLIP_TTL_STEPS = intArrayOf(5, 15, 30, 60, 180, 720, 1440)

fun ttlLabel(min: Int): String = when {
    min < 60 -> "$min мин"
    min % 60 == 0 && min < 1440 -> "${min / 60} ч"
    min == 1440 -> "сутки"
    else -> "${min / 60} ч ${min % 60} мин"
}

fun ttlLabel(ctx: android.content.Context, min: Int): String = when {
    min < 60 -> ctx.getString(R.string.st_ttl_min, min)
    min % 60 == 0 && min < 1440 -> ctx.getString(R.string.st_ttl_hour, min / 60)
    min == 1440 -> ctx.getString(R.string.st_ttl_day)
    else -> ctx.getString(R.string.st_ttl_hm, min / 60, min % 60)
}
