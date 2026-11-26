package com.kai.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec

/** Сейф: ПИН, паузы, «ПИНы для удаления», запирание, шифрование на диске, сбои. */
class SafeTest {
    private val dir: File = Files.createTempDirectory("safe").toFile()
    private val keyBytes = ByteArray(32) { 1 }
    private val vault = Vault.withKey(keyBytes)
    private var t = 1_000_000_000L
    private var el = 5_000_000L
    private fun safe(v: Vault? = vault, iter: Int = 1000) = Safe(dir, v, { t }, { el }, iter)

    @Test
    fun createUnlockLock() {
        val s = safe()
        assertTrue(s.create("123456"))
        assertTrue(s.add("почта", "p@ss-word"))
        s.lock()
        assertFalse(s.isUnlocked)
        assertTrue(s.entries().isEmpty())
        val s2 = safe()
        assertSame(Safe.Unlock.Ok, s2.unlock("123456"))
        assertEquals("p@ss-word", s2.entries()[0].secret)
        for (f in dir.listFiles()!!) assertFalse(String(f.readBytes(), Charsets.ISO_8859_1).contains("p@ss"))
    }

    @Test
    fun pinRules() {
        assertFalse(Safe.validPin("1234")) // новый — от 6 цифр
        assertTrue(Safe.validPin("123456"))
        assertTrue(Safe.canTry("1234")) // а старым коротким открыть можно
        assertFalse(Safe.canTry("12a4"))
    }

    @Test
    fun wrongPinsBackOffButNeverWipe() {
        val s = safe().apply { create("123456"); add("a", "секрет"); lock() }
        repeat(Safe.FREE_TRIES - 1) { assertEquals(0L, (s.unlock("000000") as Safe.Unlock.Wrong).waitMs) }
        val last = s.unlock("000000") as Safe.Unlock.Wrong
        assertTrue(last.waitMs > 0)
        assertTrue(s.unlock("123456") is Safe.Unlock.Wait) // даже верный — после паузы
        el += Safe.FIRST_WAIT_MS + 1
        assertSame(Safe.Unlock.Ok, s.unlock("123456"))
        assertEquals("секрет", s.entries()[0].secret)
    }

    /** Перевод часов: вперёд пауза не снимается (а начинается заново), назад — не запирает на годы. */
    @Test
    fun clockChangesDontHelpOrHurt() {
        val s = safe().apply { create("123456"); lock() }
        repeat(Safe.FREE_TRIES) { s.unlock("000000") }
        assertTrue(s.waitMs() in 1..Safe.FIRST_WAIT_MS)
        t += 9 * 3600 * 1000L // перевели настенные часы на 9 часов вперёд
        assertTrue("перевод вперёд снял паузу", s.waitMs() > 0)
        assertTrue(s.unlock("000000") is Safe.Unlock.Wait)
        t -= 2 * 365L * 24 * 3600 * 1000 // на два года назад
        assertTrue(s.waitMs() in 1..Safe.FIRST_WAIT_MS)
        // настоящее время ожидания прошло (монотонные часы) — пускает
        el += Safe.FIRST_WAIT_MS + 1
        assertSame(Safe.Unlock.Ok, s.unlock("123456"))
    }

    /** Чужой ключ (не от этого сейфа) не открывает его, даже пустой. */
    @Test
    fun wrongKeyIsRejected() {
        val s = safe().apply { create("123456"); lock() }
        assertFalse(s.unlockWithKey(ByteArray(32) { 7 }))
        assertSame(Safe.Unlock.Ok, s.unlock("123456"))
    }

    /** Повтор переноса «входящих» (упали посреди) не даёт дублей; чужие файлы не копятся. */
    @Test
    fun inboxNoDuplicatesNoJunk() {
        val s = safe().apply { create("123456"); ensureInbox(); lock() }
        assertTrue(s.put("a", "1"))
        val f = dir.listFiles()!!.first { it.name.startsWith("inbox-") }
        val copy = f.readBytes()
        assertSame(Safe.Unlock.Ok, s.unlock("123456"))
        s.lock()
        File(dir, "inbox-again").writeBytes(copy) // как будто файл не удалился
        File(dir, "inbox-junk").writeBytes(vault.seal(ByteArray(300) { 1 }, "safe-inbox")!!)
        assertSame(Safe.Unlock.Ok, s.unlock("123456"))
        assertEquals(1, s.entries().size)
        assertTrue(dir.listFiles()!!.none { it.name.startsWith("inbox-") })
    }

    /** Параллельные попытки не дают лишних: вторая, пока идёт первая, — «занято», а не проверка. */
    @Test
    fun attemptsAreSerializedAndCountedFirst() {
        val s = safe(iter = 200_000).apply { create("123456"); lock() }
        val results = java.util.Collections.synchronizedList(ArrayList<Safe.Unlock>())
        val threads = List(10) { Thread { results.add(s.unlock("000000")) } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        val tried = results.count { it is Safe.Unlock.Wrong }
        assertTrue(results.all { it is Safe.Unlock.Wrong || it === Safe.Unlock.Busy || it is Safe.Unlock.Wait })
        assertTrue(tried >= 1)
        // счётчик ошибок = сколько ПИНов реально проверено
        repeat(Safe.FREE_TRIES - tried - 1) { s.unlock("000000") }
        if (tried < Safe.FREE_TRIES) assertTrue((s.unlock("000000") as Safe.Unlock.Wrong).waitMs > 0)
    }

    /** Заперли, пока проверялся ПИН, — сейф не открывается. */
    @Test
    fun lockDuringUnlockWins() {
        val s = safe(iter = 400_000).apply { create("123456"); lock() }
        var r: Safe.Unlock? = null
        val th = Thread { r = s.unlock("123456") }
        th.start(); Thread.sleep(30); s.lock(); th.join()
        assertSame(Safe.Unlock.NotNow, r)
        assertFalse(s.isUnlocked)
    }

    @Test
    fun duressPinMakesARealEmptySafe() {
        val s = safe().apply { create("123456"); add("банк", "пароль") }
        assertEquals(Safe.PinResult.CONFLICT, s.addDuressPin("123456")) // совпадает с настоящим — нельзя
        assertEquals(Safe.PinResult.OK, s.addDuressPin("000000"))
        assertEquals(Safe.PinResult.OK, s.addDuressPin("555555"))
        assertEquals(2, s.duressCount())
        s.lock()
        assertSame(Safe.Unlock.Ok, s.unlock("555555"))
        // со стороны — обычный сейф: пусто, ПИНов для удаления 0, записи добавляются
        assertTrue(s.entries().isEmpty())
        assertEquals(0, s.duressCount())
        assertTrue(s.add("x", "y"))
        s.lock()
        assertTrue(s.unlock("123456") is Safe.Unlock.Wrong) // старого ключа больше нет
        assertSame(Safe.Unlock.Ok, s.unlock("555555"))
        assertEquals("y", s.entries()[0].secret)
    }

    @Test
    fun changePinAndBiometricKey() {
        val s = safe().apply { create("123456"); add("x", "y"); addDuressPin("999999") }
        assertEquals(Safe.PinResult.CONFLICT, s.changePin("999999")) // нельзя сделать «ПИН для удаления» настоящим
        assertEquals(Safe.PinResult.OK, s.changePin("87654321"))
        s.lock()
        assertTrue(s.unlock("123456") is Safe.Unlock.Wrong)
        assertSame(Safe.Unlock.Ok, s.unlock("87654321"))
        val k = s.keyForBiometrics()!!
        s.lock()
        assertTrue(safe().unlockWithKey(k))
    }

    /** Нет ключа телефона — сохранение не проходит, и в памяти ничего не меняется (не «видно, но пропадёт»). */
    @Test
    fun failedSaveDoesNotChangeMemory() {
        var up = true
        val flaky = Vault { if (up) SecretKeySpec(keyBytes, "AES") else null }
        val s = safe(flaky).apply { create("123456"); add("a", "1") }
        up = false
        assertFalse(s.add("b", "2"))
        assertEquals(listOf("a"), s.entries().map { it.label })
        assertFalse(s.remove(s.entries()[0].id))
        assertEquals(1, s.entries().size)
    }

    @Test
    fun brokenMetaIsReportedAndDeletable() {
        val s = safe().apply { create("123456"); lock() }
        File(dir, "meta").writeBytes(Vault.withKey(ByteArray(32) { 9 }).seal("x".toByteArray(), "safe-meta")!!)
        assertSame(Safe.Unlock.Broken, s.unlock("123456"))
        assertEquals(Safe.State.BROKEN, s.state())
        s.destroy()
        assertEquals(Safe.State.MISSING, s.state())
    }

    /** Положить в запертый сейф можно, а прочитать — только открыв его. */
    @Test
    fun depositIntoLockedSafe() {
        val s = safe().apply { create("123456"); add("старое", "1") }
        assertFalse(s.canDeposit())
        assertTrue(s.ensureInbox())
        s.lock()
        assertTrue(s.canDeposit())
        assertTrue(s.put("из буфера", "секретный-код-42"))
        assertTrue(s.entries().isEmpty()) // заперт — ничего не видно
        for (f in dir.listFiles()!!) assertFalse(String(f.readBytes(), Charsets.ISO_8859_1).contains("секрет"))
        assertSame(Safe.Unlock.Ok, s.unlock("123456"))
        assertEquals(listOf("секретный-код-42", "1"), s.entries().map { it.secret })
        assertTrue(dir.listFiles()!!.none { it.name.startsWith("inbox-") }) // перенесено в записи
        // «ПИН для удаления» стирает и положенное, и ключи «входящих»
        s.addDuressPin("000000"); s.lock()
        assertTrue(s.put("ещё", "x"))
        assertSame(Safe.Unlock.Ok, s.unlock("000000"))
        assertTrue(s.entries().isEmpty())
        assertFalse(s.canDeposit())
    }

    /** Верный ПИН, а записи не расшифровать — «повреждён» (можно удалить), а не вечная ошибка с паузами. */
    @Test
    fun corruptItemsWithRightPinIsBroken() {
        val s = safe().apply { create("123456"); add("a", "1"); lock() }
        File(dir, "items").writeBytes(vault.seal(ByteArray(100) { 3 }, "safe-items")!!)
        repeat(Safe.FREE_TRIES + 1) { assertSame(Safe.Unlock.Broken, s.unlock("123456")) }
        assertEquals(0L, s.waitMs()) // верный ПИН ошибкой не засчитан
        assertEquals(Safe.State.BROKEN, s.state())
        s.destroy()
        assertEquals(Safe.State.MISSING, s.state())
    }

    /** «ПИН для удаления» оборвался после метаданных — старые записи не читаются, сейф открывается пустым. */
    @Test
    fun interruptedWipeOpensEmpty() {
        val s = safe().apply { create("123456"); add("банк", "пароль"); addDuressPin("000000"); lock() }
        val old = File(dir, "items").readBytes()
        assertSame(Safe.Unlock.Ok, s.unlock("000000"))
        s.lock()
        File(dir, "items").writeBytes(old) // как будто пустые записи не успели записаться
        assertSame(Safe.Unlock.Ok, s.unlock("000000"))
        assertTrue(s.entries().isEmpty())
        assertTrue(s.add("x", "y"))
    }

    /** Заперли после начала (ушли с экрана, погас экран) — ни ПИН, ни отпечаток, ни создание не открывают. */
    @Test
    fun staleSessionNeverOpens() {
        val s = safe().apply { create("123456") }
        val k = s.keyForBiometrics()!!
        val since = s.session
        s.lock()
        assertSame(Safe.Unlock.NotNow, s.unlock("123456", since))
        assertFalse(s.unlockWithKey(k, since))
        assertFalse(s.isUnlocked)
        val since2 = s.session
        s.lock()
        assertTrue(s.create("654321", since2)) // создан…
        assertFalse(s.isUnlocked) // …но заперт
        assertSame(Safe.Unlock.Ok, s.unlock("654321"))
    }

    /** Ключи «входящих» доводятся и если сейф заперли, пока они создавались; после стирания — нет. */
    @Test
    fun inboxKeysSurviveLockButNotWipe() {
        val s = safe().apply { create("123456"); addDuressPin("000000") }
        s.lock()
        assertFalse(s.ensureInbox()) // заперт и ключа в руках нет — нечего доводить
        assertSame(Safe.Unlock.Ok, s.unlock("123456"))
        assertTrue(s.ensureInbox())
        s.lock()
        assertSame(Safe.Unlock.Ok, s.unlock("000000"))
        assertFalse(s.canDeposit()) // ключи старого сейфа стёрты вместе с ним
        assertTrue(s.ensureInbox())
        assertTrue(s.canDeposit())
    }

    /** «Забыли ПИН?»: удалить без ПИНа — только через час; перезагрузка или перевод часов не сокращают; открыли — отмена. */
    @Test
    fun forgetWaitsAnHourAndUnlockCancels() {
        val s = safe().apply { create("123456"); add("a", "1"); lock() }
        assertEquals(-1L, s.forgetLeftMs())
        assertEquals(Safe.FORGET_DELAY_MS, s.requestForget())
        assertFalse(s.forget())
        fun pass(ms: Long) { el += ms; t += ms } // идёт время (и монотонное, и настенное)
        pass(Safe.FORGET_DELAY_MS / 2)
        t += 10 * Safe.FORGET_DELAY_MS // перевели часы вперёд — час начинается заново
        assertEquals(Safe.FORGET_DELAY_MS, s.forgetLeftMs())
        pass(Safe.FORGET_DELAY_MS / 2)
        assertTrue(s.forgetLeftMs() > 0)
        assertSame(Safe.Unlock.Ok, s.unlock("123456")) // вспомнили ПИН — запрос отменён
        assertEquals(-1L, s.forgetLeftMs())
        s.lock()
        s.requestForget()
        pass(Safe.FORGET_DELAY_MS + 1)
        assertEquals(0L, s.forgetLeftMs())
        assertTrue(s.forget())
        assertEquals(Safe.State.MISSING, s.state())
    }

    /** Метка ключа: та же после повторного открытия, другая после «ПИНа для удаления», нет — когда заперт. */
    @Test
    fun keyTagChangesOnWipe() {
        val s = safe().apply { create("123456"); addDuressPin("000000") }
        val tag = s.keyTag()!!
        s.lock()
        assertEquals(null, s.keyTag())
        assertSame(Safe.Unlock.Ok, s.unlock("123456"))
        assertTrue(tag.contentEquals(s.keyTag()))
        s.lock()
        assertSame(Safe.Unlock.Ok, s.unlock("000000"))
        assertFalse(tag.contentEquals(s.keyTag()))
    }

    /** Запись для прежнего сейфа после «ПИНа для удаления» в новый не попадает — ни в открытый, ни в «входящие». */
    @Test
    fun putGoesOnlyToTheSameSafe() {
        val s = safe().apply { create("123456"); ensureInbox(); addDuressPin("000000") }
        val open = s.target()
        s.lock()
        val locked = s.target()
        assertSame(Safe.Unlock.Ok, s.unlock("000000")) // стёрли, открыт новый
        assertFalse(s.put("", "секрет", open))
        assertTrue(s.ensureInbox()); s.lock()
        assertFalse(s.put("", "секрет", locked))
        assertTrue(dir.listFiles()!!.none { it.name.startsWith("inbox-") })
        assertSame(Safe.Unlock.Ok, s.unlock("000000"))
        assertTrue(s.entries().isEmpty())
        assertTrue(s.put("", "новое", s.target())) // в свой — можно
    }

    /** Сейф стёрли, пока шифровалось положенное в запертый сейф, — put честно говорит «не вышло». */
    @Test
    fun depositRacingWipeFails() {
        val s = safe(iter = 400_000).apply { create("123456"); ensureInbox(); addDuressPin("000000"); lock() }
        // Точка гонки задана явно: put заканчивает шифрование и останавливается, пока «ПИН для удаления»
        // стирает сейф и открывает новый. Стирание идёт мимо замка сейфа, поэтому без хука момент не поймать.
        val encryptDone = java.util.concurrent.CountDownLatch(1)
        val wiped = java.util.concurrent.CountDownLatch(1)
        s.onBeforeInboxWrite = {
            encryptDone.countDown()
            assertTrue("сейф стёрли и открыли новый", wiped.await(10, java.util.concurrent.TimeUnit.SECONDS))
        }
        var stored = false
        var putDone = java.util.concurrent.CountDownLatch(1)
        val th = Thread {
            try { stored = s.put("", "секрет", s.target()) } finally { putDone.countDown() }
        }
        th.start()
        assertTrue("шифрование не дошло до записи", encryptDone.await(30, java.util.concurrent.TimeUnit.SECONDS))
        assertSame(Safe.Unlock.Ok, s.unlock("000000")) // стёрли старый сейф, открыт новый — и он уже на месте
        wiped.countDown()
        assertTrue(putDone.await(30, java.util.concurrent.TimeUnit.SECONDS))
        th.join()
        assertFalse("запись пропала под стёртым ключом", stored)
        assertTrue(s.entries().none { it.secret.startsWith("секрет") })
        // в новый сейф положенное не попало — ни записями, ни «входящими»
        s.lock()
        assertSame(Safe.Unlock.Ok, s.unlock("000000"))
        assertTrue(s.entries().isEmpty())
    }
}
