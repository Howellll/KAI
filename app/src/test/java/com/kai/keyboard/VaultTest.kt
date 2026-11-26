package com.kai.keyboard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec

/** Шифрование данных на диске: без ключа нечитаемо, подмена видна, сбой ключа не стирает данные. */
class VaultTest {
    private val keyBytes = ByteArray(32) { it.toByte() }
    private val vault = Vault.withKey(keyBytes)
    private val other = Vault.withKey(ByteArray(32) { (it + 1).toByte() })
    private val dir: File = Files.createTempDirectory("vault").toFile()

    private fun containsText(f: File, s: String) = String(f.readBytes(), Charsets.ISO_8859_1)
        .contains(String(s.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1))

    private fun ok(r: Vault.Read) = (r as Vault.Read.Ok).bytes

    @Test
    fun roundTripTamperWrongKeyAndPurpose() {
        val plain = "кузнецы и пароль 123".toByteArray()
        val sealed = vault.seal(plain, "learned")!!
        assertTrue(Vault.isSealed(sealed))
        assertArrayEquals(plain, ok(vault.open(sealed, "learned")))
        assertFalse(sealed.contentEquals(vault.seal(plain, "learned")!!)) // каждый раз новый IV
        assertSame(Vault.Read.Corrupt, other.open(sealed, "learned")) // чужой ключ
        assertSame(Vault.Read.Corrupt, vault.open(sealed, "pins")) // файл другого назначения не подсунуть
        sealed[sealed.size - 1] = (sealed[sealed.size - 1] + 1).toByte()
        assertSame(Vault.Read.Corrupt, vault.open(sealed, "learned")) // испорченный — не «что-то другое»
        assertTrue((vault.open(plain, "learned") as Vault.Read.Ok).legacy) // старый незашифрованный — как есть
        assertNull(Vault { null }.seal(plain, "learned")) // нет ключа — не пишем вовсе
        assertSame(Vault.Read.NoKey, Vault { null }.open(vault.seal(plain, "x")!!, "x")) // нет ключа ≠ пусто
        assertArrayEquals(ByteArray(0), ok(vault.open(vault.seal(ByteArray(0), "e")!!, "e")))
    }

    @Test
    fun strings() {
        val s = vault.sealString("😀 👍🏽", "recent_emoji")!!
        assertFalse(s.contains("😀"))
        assertEquals("😀 👍🏽", String(ok(vault.openString(s, "recent_emoji"))))
        assertTrue((vault.openString("старое", "recent_emoji") as Vault.Read.Ok).legacy)
    }

    @Test
    fun learnedWordsAreEncryptedAndMigrateRightAway() {
        // старый незашифрованный файл читается и сразу же (без новых слов) перешифровывается
        File(dir, "learned.txt").writeText("w\tкринж\t5\n")
        val u = UserStore(dir, vault).apply { load(); save() }
        assertEquals(5, u.count("кринж"))
        val f = File(dir, "learned.txt")
        assertFalse(containsText(f, "кринж"))
        u.addWord("чекни"); u.save()
        val again = UserStore(dir, vault).apply { load() }
        assertEquals(5, again.count("кринж"))
        assertEquals(1, again.count("чекни"))
    }

    /** Ключ временно недоступен при чтении — новое не затирает старое, а дописывается к нему. */
    @Test
    fun keyOutageDoesNotLoseLearnedWords() {
        UserStore(dir, vault).apply { load(); addWord("кринж", 5); save() }
        var keyUp = false
        val flaky = Vault { if (keyUp) SecretKeySpec(keyBytes, "AES") else null }
        val u = UserStore(dir, flaky).apply { load() } // ключа нет — ничего не прочитано
        u.addWord("щас"); u.save() // и не сохранено поверх
        assertTrue(File(dir, "learned.txt").exists())
        keyUp = true
        u.addWord("щас"); u.save() // теперь дочитали, сложили, сохранили
        val again = UserStore(dir, vault).apply { load() }
        assertEquals(5, again.count("кринж")) // ровно: без двойного сложения
        assertEquals(2, again.count("щас"))
    }

    @Test
    fun corruptLearnedFileIsSetAside() {
        File(dir, "learned.txt").writeBytes(other.seal("w\tчужое\t9\n".toByteArray(), "learned")!!)
        val u = UserStore(dir, vault).apply { load() }
        assertEquals(0, u.count("чужое"))
        assertTrue(File(dir, "learned.txt.bad").exists()) // не перезаписан молча — отложен
    }

    @Test
    fun pinnedClipsAreEncryptedAndSurviveKeyOutage() {
        var t = 0L
        val pins = File(dir, "pins")
        val clips = Clips { t }.apply { store = PinStore(pins, vault); loadPins() }
        clips.add("мой адрес: Ленина 1")
        clips.addImage(ByteArray(64) { 9 }, "image/png")
        clips.list().forEach { clips.setPinned(it, true) }
        assertTrue(pins.listFiles()!!.none { containsText(it, "Ленина") })

        // ключа нет при запуске: закрепили новое — старое на диске не затёрто
        var keyUp = false
        val flaky = Vault { if (keyUp) SecretKeySpec(keyBytes, "AES") else null }
        val c2 = Clips { t }.apply { store = PinStore(pins, flaky); loadPins() }
        assertEquals(0, c2.list().size)
        c2.add("код 1234")
        c2.setPinned(c2.list()[0], true)
        keyUp = true
        c2.add("ещё"); c2.setPinned(c2.list().first { it.text == "ещё" }, true) // ключ вернулся: дочитали и сохранили всё
        val c3 = Clips { t }.apply { store = PinStore(pins, vault); loadPins() }
        assertEquals(setOf("мой адрес: Ленина 1", "", "код 1234", "ещё"), c3.list().map { it.text }.toSet())
        assertTrue(c3.list().first { it.isImage }.image!!.bytes.contentEquals(ByteArray(64) { 9 }))
    }

    @Test
    fun legacyPinsFileMigrates() {
        val pins = File(dir, "pins")
        // первая версия: один файл, открытым текстом
        java.io.DataOutputStream(pins.outputStream()).use { o ->
            o.writeInt(1); o.writeInt(1); o.writeBoolean(false)
            val b = "старый".toByteArray(); o.writeInt(b.size); o.write(b)
        }
        val c = Clips { 0L }.apply { store = PinStore(pins, vault); loadPins() }
        assertEquals("старый", c.list()[0].text)
        assertTrue(pins.isDirectory) // переехал в новый формат
        assertFalse(pins.listFiles()!!.any { containsText(it, "старый") })
        assertEquals("старый", Clips { 0L }.apply { store = PinStore(pins, vault); loadPins() }.list()[0].text)
    }

    private fun writeLegacyPins(pins: File, text: String) {
        java.io.DataOutputStream(pins.outputStream()).use { o ->
            o.writeInt(1); o.writeInt(1); o.writeBoolean(false)
            val b = text.toByteArray(); o.writeInt(b.size); o.write(b)
        }
    }

    /** Ключ был при проверке, но пропал посреди переезда — старый файл цел, данные не потеряны. */
    @Test
    fun pinsMigrationSurvivesKeyLossMidWrite() {
        val pins = File(dir, "pins")
        writeLegacyPins(pins, "важное")
        var calls = 0
        val dying = Vault { if (++calls <= 1) SecretKeySpec(keyBytes, "AES") else null } // проверка — да, запись — нет
        Clips { 0L }.apply { store = PinStore(pins, dying); loadPins() }
        assertTrue(pins.isFile) // старый файл на месте
        assertFalse(File(dir, "pins.new").exists())
        val again = Clips { 0L }.apply { store = PinStore(pins, vault); loadPins() }
        assertEquals("важное", again.list()[0].text)
        assertTrue(pins.isDirectory) // а с ключом — переехал
    }

    /** Временный файл первой версии (мог быть открытым текстом) после переезда не остаётся. */
    @Test
    fun legacyTempFileIsRemovedAfterMigration() {
        val pins = File(dir, "pins")
        writeLegacyPins(pins, "адрес")
        val tmp = File(dir, "pins.tmp").apply { writeText("адрес открытым текстом") }
        Clips { 0L }.apply { store = PinStore(pins, vault); loadPins() }
        assertFalse(tmp.exists())
    }

    /** Переезд оборвался после удаления старого файла — новая папка подхватывается при чтении. */
    @Test
    fun interruptedMigrationIsCompletedOnLoad() {
        val pins = File(dir, "pins")
        writeLegacyPins(pins, "текст")
        Clips { 0L }.apply { store = PinStore(pins, vault); loadPins() }
        assertTrue(pins.renameTo(File(dir, "pins.new"))) // как будто упали до переименования
        val again = Clips { 0L }.apply { store = PinStore(pins, vault); loadPins() }
        assertEquals("текст", again.list()[0].text)
    }

    /** Ошибка, не связанная с порчей (память, сбой провайдера), — «попробуем позже», а не «испорчено». */
    @Test
    fun nonAuthErrorsAreNotCorruption() {
        val sealed = vault.seal("x".toByteArray(), "p")!!
        val broken = Vault { object : javax.crypto.SecretKey {
            override fun getAlgorithm() = "AES"; override fun getFormat() = "RAW"
            override fun getEncoded(): ByteArray = throw IllegalStateException("сбой")
        } }
        assertSame(Vault.Read.NoKey, broken.open(sealed, "p"))
    }

    /** Слово забыли, пока файл не читался, — при дочитывании оно не возвращается. */
    @Test
    fun forgottenBeforeLoadStaysForgotten() {
        UserStore(dir, vault).apply { load(); addWord("плохое", 5); save() }
        var keyUp = false
        val flaky = Vault { if (keyUp) SecretKeySpec(keyBytes, "AES") else null }
        val u = UserStore(dir, flaky).apply { load() }
        u.forget("плохое")
        keyUp = true
        u.save()
        val again = UserStore(dir, vault).apply { load() }
        assertEquals(0, again.count("плохое"))
        assertTrue(again.isBlocked("плохое"))
    }

    /** Закреплённая картинка, которую не прочитать, не удаляется ни сразу, ни при следующем закреплении. */
    @Test
    fun unreadablePinnedImageIsKept() {
        val pins = File(dir, "pins")
        val c = Clips { 0L }.apply { store = PinStore(pins, vault); loadPins() }
        val img = c.addImage(ByteArray(40) { 3 }, "image/png")!!
        c.setPinned(img, true)
        val f = File(pins, "${img.id}.img")
        f.writeBytes(other.seal(ByteArray(40) { 3 }, "pin-image")!!) // подменили/испортили
        val c2 = Clips { 0L }.apply { store = PinStore(pins, vault); loadPins() }
        assertTrue(c2.list().none { it.isImage })
        c2.add("текст"); c2.setPinned(c2.list().first { it.text == "текст" }, true)
        assertTrue(f.exists()) // не стёрта
        // и в списке осталась: вернём правильный файл — прочитается
        f.writeBytes(vault.seal(ByteArray(40) { 3 }, "pin-image")!!)
        val c3 = Clips { 0L }.apply { store = PinStore(pins, vault); loadPins() }
        assertTrue(c3.list().any { it.isImage })
    }

    /** Список закреплённого испорчен — картинки на диске не стираются при следующем закреплении. */
    @Test
    fun corruptIndexKeepsImages() {
        val pins = File(dir, "pins")
        val c = Clips { 0L }.apply { store = PinStore(pins, vault); loadPins() }
        c.setPinned(c.addImage(ByteArray(40) { 5 }, "image/png")!!, true)
        File(pins, "index").writeBytes(other.seal(ByteArray(8), "pins")!!)
        val c2 = Clips { 0L }.apply { store = PinStore(pins, vault); loadPins() }
        c2.add("новое"); c2.setPinned(c2.list().first { it.text == "новое" }, true)
        assertEquals(1, pins.listFiles()!!.count { it.name.endsWith(".img") })
    }

    /** Сняли последнее закрепление после нечитаемого списка — отложенный список и картинки целы и возвращаются с ключом. */
    @Test
    fun unpinAfterCorruptIndexKeepsAsideAndRecovers() {
        val pins = File(dir, "pins")
        val c = Clips { 0L }.apply { store = PinStore(pins, vault); loadPins() }
        c.setPinned(c.addImage(ByteArray(40) { 5 }, "image/png")!!, true)
        // другой ключ (как после потери ключа): список не читается и откладывается
        val c2 = Clips { 0L }.apply { store = PinStore(pins, other); loadPins() }
        c2.add("новое"); c2.setPinned(c2.list().first { it.text == "новое" }, true)
        c2.remove(c2.list().first { it.text == "новое" }) // последнее закрепление
        assertEquals(1, pins.listFiles()!!.count { it.name.endsWith(".img") })
        assertTrue(pins.listFiles()!!.any { it.name.startsWith("index.bad") })
        // прежний ключ вернулся — вернулось и закреплённое
        val c3 = Clips { 0L }.apply { store = PinStore(pins, vault); loadPins() }
        assertTrue(c3.list().any { it.isImage && it.pinned })
    }

    /** Убрали последнее читаемое закрепление — непрочитанная картинка на диске всё равно остаётся. */
    @Test
    fun removingLastReadablePinKeepsUnreadableImage() {
        val pins = File(dir, "pins")
        val c = Clips { 0L }.apply { store = PinStore(pins, vault); loadPins() }
        val img = c.addImage(ByteArray(40) { 7 }, "image/png")!!
        c.setPinned(img, true)
        c.add("текст"); c.setPinned(c.list().first { it.text == "текст" }, true)
        val f = File(pins, "${img.id}.img")
        val good = f.readBytes()
        f.writeBytes(other.seal(ByteArray(40) { 7 }, "pin-image")!!) // сейчас не прочитать
        val c2 = Clips { 0L }.apply { store = PinStore(pins, vault); loadPins() }
        c2.remove(c2.list().first { it.text == "текст" }) // последнее, что видно
        assertTrue(f.exists())
        f.writeBytes(good) // снова читается — и всё ещё закреплена
        val c3 = Clips { 0L }.apply { store = PinStore(pins, vault); loadPins() }
        assertTrue(c3.list().any { it.isImage && it.pinned })
        // а явное «стереть всё» стирает и её
        c3.clearAll()
        assertTrue(pins.listFiles().isNullOrEmpty())
    }
}
