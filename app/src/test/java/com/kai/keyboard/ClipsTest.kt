package com.kai.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipsTest {
    private var t = 0L
    private val clips = Clips { t }

    @Test
    fun recentThenDismissed() {
        clips.add("привет")
        assertEquals("привет", clips.recent()?.text)
        clips.dismissRecent()
        assertNull(clips.recent())
        assertEquals(1, clips.list().size)
    }

    @Test
    fun duplicatesMoveToTopAndOldExpire() {
        clips.add("a"); t += 1000; clips.add("b"); t += 1000; clips.add("a")
        assertEquals(listOf("a", "b"), clips.list().map { it.text })
        t += Clips.RECENT_MS
        assertNull(clips.recent())
        t += Clips.TTL_MS
        assertEquals(0, clips.list().size)
    }

    @Test
    fun imagesAreKeptInMemoryWithLimits() {
        val a = ByteArray(100) { 1 }
        assertTrue(clips.addImage(a, "image/png") != null)
        assertTrue(clips.addImage(a.copyOf(), "image/png") != null) // та же картинка — не дублируется
        assertEquals(1, clips.list().size)
        val id = clips.list()[0].id
        assertTrue(clips.image(id)!!.bytes.contentEquals(a))
        // слишком большая — не берём
        assertNull(clips.addImage(ByteArray(Clips.MAX_IMAGE_BYTES + 1), "image/png"))
        // картинок — не больше MAX_IMAGES, текст не вытесняют
        clips.add("текст")
        for (i in 0 until Clips.MAX_IMAGES + 2) { t += 10; clips.addImage(ByteArray(10) { i.toByte() }, "image/jpeg") }
        assertEquals(Clips.MAX_IMAGES, clips.list().count { it.isImage })
        assertTrue(clips.list().any { it.text == "текст" })
        // через час — нет и картинки, и ссылка на неё больше не работает
        t += Clips.TTL_MS + 1
        assertNull(clips.image(id))
        assertEquals(0, clips.list().size)
    }

    @Test
    fun pinnedLiveForeverAndPersist() {
        val dir = java.nio.file.Files.createTempDirectory("pins").toFile()
        clips.store = PinStore(java.io.File(dir, "pins"))
        clips.add("адрес"); clips.add("пароль-не-закрепляй")
        clips.addImage(ByteArray(50) { 7 }, "image/png")
        val addr = clips.list().first { it.text == "адрес" }
        val img = clips.list().first { it.isImage }
        assertTrue(clips.setPinned(addr, true))
        assertTrue(clips.setPinned(img, true))
        // закреплённые — первыми, переживают и срок, и «Очистить»
        assertTrue(clips.list().take(2).all { it.pinned })
        t += Clips.TTL_MS * 10
        clips.clear()
        assertEquals(2, clips.list().size)
        // и перезапуск
        val again = Clips { t }.apply { store = PinStore(java.io.File(dir, "pins")); loadPins() }
        assertEquals(setOf("адрес", ""), again.list().map { it.text }.toSet())
        assertTrue(again.list().first { it.isImage }.image!!.bytes.contentEquals(ByteArray(50) { 7 }))
        // открепили — снова живёт ttl с этого момента
        val a2 = again.list().first { it.text == "адрес" }
        again.setPinned(a2, false)
        t += Clips.TTL_MS + 1
        assertTrue(again.list().none { it.text == "адрес" })
        // и на диске его больше нет
        val third = Clips { t }.apply { store = PinStore(java.io.File(dir, "pins")); loadPins() }
        assertEquals(1, third.list().size)
    }

    @Test
    fun ttlSetting() {
        clips.ttlMs = 5 * 60_000L
        clips.add("a")
        t += 5 * 60_000L + 1
        assertEquals(0, clips.list().size)
    }

    @Test
    fun idsAreRandomAndImageOrderFollowsCopyTime() {
        val a = clips.add("a")!!
        val b = clips.add("b")!!
        assertTrue(a.id != b.id && a.id > 0 && b.id > 0 && kotlin.math.abs(a.id - b.id) > 1000)
        // картинку скопировали раньше текста, а прочиталась она позже — встаёт под текстом
        t = 10; val copiedAt = t; t = 20; clips.add("текст после картинки")
        clips.addImage(ByteArray(5) { 1 }, "image/png", copiedAt)
        assertEquals("текст после картинки", clips.recent()?.text)
    }

    @Test
    fun clearAllErasesPinsOnDisk() {
        val dir = java.nio.file.Files.createTempDirectory("pins2").toFile()
        val pins = java.io.File(dir, "pins")
        clips.store = PinStore(pins); clips.loadPins()
        clips.add("x"); clips.setPinned(clips.list()[0], true)
        assertTrue(pins.listFiles()!!.isNotEmpty())
        clips.clearAll()
        assertTrue(pins.listFiles().isNullOrEmpty())
    }

    /** Открепили — запись наверху и живёт полный срок, а не вытесняется первой. */
    @Test
    fun unpinnedGoesToTop() {
        val old = clips.add("old")!!
        clips.setPinned(old, true)
        for (i in 0 until Clips.MAX_ITEMS) clips.add("x$i")
        clips.setPinned(old, false)
        assertEquals("old", clips.list()[0].text)
        clips.add("ещё одно")
        assertTrue(clips.list().any { it.text == "old" })
    }
}
