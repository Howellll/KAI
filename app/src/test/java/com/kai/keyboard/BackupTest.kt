package com.kai.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class BackupTest {
    init { Backup.ITERATIONS = 20_000 } // в тестах — быстро

    private val settings = mapOf<String, Any>("theme" to "light", "height" to 110, "swipe" to false)

    @Test
    fun settingsOnlyIsPlainText() {
        val bytes = Backup.encode(Backup.Data(settings, emptyList()), null)
        assertTrue(String(bytes).startsWith("KLAVA 1"))
        val r = Backup.decode(bytes, null) as Backup.Read.Ok
        assertEquals(settings, r.data.settings)
        assertTrue(!r.data.hasLearned)
    }

    @Test
    fun learnedNeedsPasswordAndSurvivesRoundTrip() {
        val learned = listOf("w\tкринж\t7", "b\tочень\tкринж\t3", "x\tпжив", "r\t😂 👍")
        val bytes = Backup.encode(Backup.Data(settings, learned), "секретный пароль")
        assertTrue(Backup.isEncrypted(bytes))
        assertTrue(!String(bytes, Charsets.ISO_8859_1).contains("KLAVA 1"))
        assertEquals(Backup.Read.NeedPassword, Backup.decode(bytes, null))
        assertEquals(Backup.Read.WrongPassword, Backup.decode(bytes, "не тот пароль"))
        val r = Backup.decode(bytes, "секретный пароль") as Backup.Read.Ok
        assertEquals(settings, r.data.settings)
        assertEquals(learned, r.data.learned)
    }

    @Test(expected = IllegalArgumentException::class)
    fun learnedWithoutPasswordIsRefused() {
        Backup.encode(Backup.Data(settings, listOf("w\tслово\t3")), null)
    }

    /** Подложенный открытый файл с «выученным» не принимаем: выученное приходит только зашифрованным. */
    @Test
    fun plainLearnedIgnored() {
        val r = Backup.decode("KLAVA 1\ns\ttheme\ts\tdark\nw\tспам\t999\n".toByteArray(), null) as Backup.Read.Ok
        assertEquals(mapOf<String, Any>("theme" to "dark"), r.data.settings)
        assertTrue(r.data.learned.isEmpty())
    }

    @Test
    fun notBackup() {
        assertEquals(Backup.Read.NotBackup, Backup.decode("hello".toByteArray(), null))
    }

    @Test
    fun learnedMovesBetweenStores() {
        val a = UserStore(Files.createTempDirectory("klava-a").toFile())
        repeat(5) { a.addWord("кринж") }
        a.addBigram("очень", "кринж")
        a.forget("пжив")
        val lines = a.exportLines()!!
        val b = UserStore(Files.createTempDirectory("klava-b").toFile())
        repeat(2) { b.addWord("кринж") }
        assertTrue(b.importLines(lines))
        assertEquals(7, b.count("кринж")) // складывается с выученным здесь
        assertEquals(1, b.bigram("очень", "кринж"))
        assertTrue(b.isBlocked("пжив"))
    }
}
