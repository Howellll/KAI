package com.kai.keyboard

import com.kai.keyboard.data.ai.AiAction
import com.kai.keyboard.data.ai.AiModel
import com.kai.keyboard.data.ai.AiPrompts
import com.kai.keyboard.data.ai.AiStore
import com.kai.keyboard.data.ai.Builtin
import com.kai.keyboard.data.ai.MAX_AI_TABS
import com.kai.keyboard.data.ai.OpenAiCompatibleClient
import com.kai.keyboard.data.ai.OpenAiCompatibleClient.Kind
import com.kai.keyboard.data.ai.OpenAiCompatibleClient.Result
import com.kai.keyboard.data.ai.TextDiff
import com.kai.keyboard.data.ai.aiPages
import com.kai.keyboard.data.ai.aiTabs
import com.kai.keyboard.data.ai.withBuiltinActions
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class AiClientTest {
    private lateinit var server: MockWebServer
    private val client = OpenAiCompatibleClient()

    @Before fun up() { server = MockWebServer(); server.start() }
    @After fun down() { server.shutdown() }

    private fun cfg(key: String = "sk-test") =
        OpenAiCompatibleClient.Config(server.url("/v1").toString(), key, "m1", 0.3)

    private fun fail(r: Result) = (r as Result.Fail).failure

    @Test fun okResponseAndRequestShape() {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"  Привет  "}}]}"""))
        val r = client.chatBlocking(cfg(), "prompt-text")
        assertEquals("Привет", (r as Result.Ok).text)
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("m1", body.getString("model"))
        assertEquals(0.3, body.getDouble("temperature"), 1e-9)
        assertEquals("prompt-text", body.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test fun noKeyNoAuthHeader() {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"x"}}]}"""))
        client.chatBlocking(cfg(key = ""), "p")
        assertNull(server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization"))
    }

    @Test fun httpErrorsAreMapped() {
        for ((code, kind) in listOf(401 to Kind.UNAUTHORIZED, 403 to Kind.UNAUTHORIZED, 429 to Kind.RATE_LIMIT,
            500 to Kind.SERVER, 503 to Kind.SERVER, 404 to Kind.CLIENT)) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("""{"error":{"message":"nope"}}"""))
            val f = fail(client.chatBlocking(cfg(), "p"))
            assertEquals("code $code", kind, f.kind)
            assertEquals(code, f.code)
        }
    }

    @Test fun clientErrorCarriesServerMessage() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"model not found"}}"""))
        assertEquals("model not found", fail(client.chatBlocking(cfg(), "p")).detail)
    }

    @Test fun badBodyIsBadResponse() {
        for (body in listOf("not json", """{"choices":[]}""", """{"choices":[{"message":{"content":""}}]}""")) {
            server.enqueue(MockResponse().setBody(body))
            assertEquals(body, Kind.BAD_RESPONSE, fail(client.chatBlocking(cfg(), "p")).kind)
        }
    }

    @Test fun redirectIsNotFollowed() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:1/x"))
        val f = fail(client.chatBlocking(cfg(), "p"))
        assertEquals(Kind.CLIENT, f.kind)
        assertEquals(1, server.requestCount)
    }

    @Test fun timeoutIsMapped() {
        val fast = okhttp3.OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val f = fail(OpenAiCompatibleClient(fast).chatBlocking(cfg(), "p"))
        assertEquals(Kind.TIMEOUT, f.kind)
    }

    @Test fun badUrl() {
        for (u in listOf("", "   ", "ftp://x", "host/v1")) {
            val f = fail(client.chatBlocking(OpenAiCompatibleClient.Config(u, "k", "m", 0.3), "p"))
            assertEquals(u, Kind.BAD_URL, f.kind)
        }
    }

    @Test fun noRetryOnFailure() {
        server.enqueue(MockResponse().setResponseCode(500))
        client.chatBlocking(cfg(), "p")
        assertEquals(1, server.requestCount)
    }

    @Test fun asyncDeliversOnceAndCancelSilences() {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}]}"""))
        val latch = java.util.concurrent.CountDownLatch(1)
        var got: Result? = null
        client.chat(cfg(), "p") { got = it; latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals("ok", (got as Result.Ok).text)

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        var called = false
        val h = client.chat(cfg(), "p") { called = true }
        Thread.sleep(200)
        h.cancel()
        Thread.sleep(300)
        assertFalse(called)
    }

    @Test fun modelsCatalogParsesSortsAndDedups() {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"b-model"},{"id":"A-model"},{"id":"b-model"},{"id":""}]}"""))
        val r = client.modelsBlocking(server.url("/v1").toString(), "sk-test")
        assertEquals(listOf("A-model", "b-model"), (r as OpenAiCompatibleClient.ModelsResult.Ok).ids)
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", req.method)
        assertEquals("/v1/models", req.path)
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
    }

    @Test fun modelsCatalogErrors() {
        server.enqueue(MockResponse().setResponseCode(401))
        val a = client.modelsBlocking(server.url("/v1").toString(), "k") as OpenAiCompatibleClient.ModelsResult.Fail
        assertEquals(Kind.UNAUTHORIZED, a.failure.kind)
        server.enqueue(MockResponse().setBody("not json"))
        val b = client.modelsBlocking(server.url("/v1").toString(), "k") as OpenAiCompatibleClient.ModelsResult.Fail
        assertEquals(Kind.BAD_RESPONSE, b.failure.kind)
        val c = client.modelsBlocking("host/v1", "k") as OpenAiCompatibleClient.ModelsResult.Fail
        assertEquals(Kind.BAD_URL, c.failure.kind)
    }

    @Test fun modelsEndpointBuilding() {
        assertEquals("https://a.b/v1/models", OpenAiCompatibleClient.modelsEndpoint("https://a.b/v1/chat/completions"))
        assertEquals("https://a.b/v1/models", OpenAiCompatibleClient.modelsEndpoint("https://a.b/v1/"))
        assertNull(OpenAiCompatibleClient.modelsEndpoint("a.b"))
    }

    @Test fun endpointBuilding() {
        assertEquals("https://a.b/v1/chat/completions", OpenAiCompatibleClient.endpoint("https://a.b/v1/"))
        assertEquals("https://a.b/v1/chat/completions", OpenAiCompatibleClient.endpoint(" https://a.b/v1/chat/completions "))
        assertNull(OpenAiCompatibleClient.endpoint("a.b"))
    }
}

class AiPromptsTest {
    @Test fun substitutesAllPlaceholders() {
        val s = AiPrompts.build("T={{text}} I={{instruction}} L={{target_lang}}", "abc", "make short", "en")
        assertEquals("T=abc I=make short L=en", s)
    }

    @Test fun userTextWithPlaceholderStaysLiteral() {
        val s = AiPrompts.build("{{instruction}} | {{text}}", "{{instruction}}", "X", "")
        assertEquals("X | {{instruction}}", s)
    }

    @Test fun missingTextPlaceholderAppendsText() {
        val s = AiPrompts.build("Fix it", "hello", "", "")
        assertTrue(s.startsWith("Fix it") && s.endsWith("hello"))
    }

    /** Действие по одной инструкции (без {{text}}) — исходный текст в промт не приписываем. */
    @Test fun instructionOnlyKeepsTextOut() {
        val s = AiPrompts.build("{{instruction}}", "hello", "Придумай поздравление", "")
        assertEquals("Придумай поздравление", s)
    }

    @Test fun defaultsContainTheirPlaceholders() {
        assertTrue(AiPrompts.DEFAULT_SPELL.contains(AiPrompts.TEXT))
        assertTrue(AiPrompts.DEFAULT_TRANSLATE.contains(AiPrompts.TARGET_LANG))
        assertTrue(AiPrompts.DEFAULT_EDIT.contains(AiPrompts.INSTRUCTION))
        for (b in Builtin.values()) assertTrue(b.id, AiPrompts.default(b).contains(AiPrompts.TEXT))
    }

    @Test fun autoTargetLang() {
        assertEquals("английский", AiPrompts.autoTargetLang("Привет, мир"))
        assertEquals("русский", AiPrompts.autoTargetLang("Hello world"))
    }

    @Test fun requiredVariableDecidesWhetherItCanRun() {
        // Без обязательной переменной действие не запускается вовсе.
        assertFalse(AiPrompts.hasRequired("Переведи текст"))
        assertFalse(AiPrompts.canRun("Переведи текст", true))
        // {{text}} без текста — нельзя, с текстом — можно.
        assertTrue(AiPrompts.needsText(AiPrompts.DEFAULT_SPELL))
        assertFalse(AiPrompts.canRun(AiPrompts.DEFAULT_SPELL, false))
        assertTrue(AiPrompts.canRun(AiPrompts.DEFAULT_SPELL, true))
        // Только {{instruction}} — пустое поле не мешает.
        assertFalse(AiPrompts.needsText("{{instruction}}"))
        assertTrue(AiPrompts.canRun("{{instruction}}", false))
    }
}

class AiActionsTest {
    /** Три стандартных действия всегда на месте и идут первыми, свои — после них. */
    @Test fun storedListIsCompletedWithBuiltins() {
        val stored = listOf(AiAction("c7", "Поздравить", "{{instruction}}"), AiAction("spell", enabled = false))
        val full = withBuiltinActions(stored)
        assertEquals(listOf("spell", "translate", "edit", "c7"), full.map { it.id })
        assertFalse(full[0].enabled) // состояние стандартного действия перенесено
        assertEquals("Поздравить", full[3].title)
        assertTrue(full[3].custom)
        assertNull(Builtin.of("c7"))
        assertEquals(Builtin.EDIT, Builtin.of("edit"))
    }

    /** Удалить можно только своё действие. */
    @Test fun builtinActionIsNotCustom() {
        assertFalse(AiAction("translate", builtin = Builtin.TRANSLATE).custom)
        assertTrue(AiAction("c1", "Своё").custom)
    }

    /** Подсветка отличий — настройка действия: у орфографии всегда, у остальных — как выставил владелец. */
    @Test fun diffHighlightFollowsSetting() {
        // «Проверить орфографию»: подсветка отличий — суть действия, выключить её нельзя.
        assertTrue(AiAction("spell", prompt = AiPrompts.DEFAULT_SPELL, builtin = Builtin.SPELL).showsDiff)
        assertTrue(AiAction("spell", builtin = Builtin.SPELL, highlight = false).showsDiff)
        // Остальные действия: по умолчанию весь ответ целиком, владелец может включить отличия.
        assertFalse(AiAction("edit", prompt = AiPrompts.DEFAULT_EDIT, builtin = Builtin.EDIT).showsDiff)
        assertFalse(AiAction("translate", prompt = AiPrompts.DEFAULT_TRANSLATE, builtin = Builtin.TRANSLATE).showsDiff)
        assertFalse(AiAction("c1").showsDiff)
        assertTrue(AiAction("edit", builtin = Builtin.EDIT, highlight = true).showsDiff)
        assertTrue(AiAction("c1", highlight = true).showsDiff)
    }

    /** Форма ИИ: по две модели на вкладку, максимум три вкладки. */
    @Test fun modelsAreChunkedIntoTabs() {
        fun models(n: Int) = (1..n).map { AiModel("m$it") }
        assertEquals(0, aiTabs(models(0)).size)
        assertEquals(listOf(1), aiTabs(models(1)).map { it.size })
        assertEquals(listOf(2), aiTabs(models(2)).map { it.size })
        assertEquals(listOf(2, 1), aiTabs(models(3)).map { it.size })
        assertEquals(listOf(2, 2), aiTabs(models(4)).map { it.size })
        assertEquals(listOf(2, 2, 1), aiTabs(models(5)).map { it.size })
        assertEquals(listOf(2, 2, 2), aiTabs(models(6)).map { it.size })
        assertEquals(MAX_AI_TABS, aiTabs(models(10)).size)
    }

    /** Название модели: пусто — показываем саму модель. */
    @Test fun modelShownFallsBackToModelName() {
        assertEquals("claude-haiku-5-5", AiModel("claude-haiku-5-5").shown)
        assertEquals("Хайку", AiModel("claude-haiku-5-5", "Хайку").shown)
        assertFalse(AiModel("", "Хайку").ready)
        assertTrue(AiModel("m1").ready)
    }

    /** Своё действие назвал владелец — у него нет промта, пока он его не напишет. */
    @Test fun customActionHasNoDefaultPrompt() {
        assertEquals("", AiAction("c1", "Своё").prompt)
        assertFalse(AiPrompts.hasRequired(AiAction("c1", "Своё").prompt))
    }

    /** Отмечают модель по названию, которое видно в форме ИИ, а не по техническому имени модели. */
    @Test fun ownershipFollowsTheShownModelName() {
        val models = listOf(AiModel("claude-haiku-5-5", "Хайку"), AiModel("gpt-fake-1"))
        val byShown = AiAction("spell", builtin = Builtin.SPELL, models = listOf("Хайку"))
        val pages = aiPages(models, listOf(byShown))
        assertEquals(listOf(0), pages.tabs[0][0].actions)
        assertEquals(emptyList<Int>(), pages.tabs[0][1].actions) // вторая модель действие не показывает
    }

    /**
     * Принадлежность модели. Модель без единого своего действия не показывается — её столбец остаётся
     * пустым, а следующая модель сдвигается на вкладку раньше: вкладки не разъезжаются.
     */
    @Test fun modelWithoutActionsLeavesAnEmptyColumn() {
        val models = (1..3).map { AiModel("m$it") }
        val pages = aiPages(models, listOf(AiAction("c1", "Пересказ", "{{text}}", models = listOf("m1", "m3"))))
        assertEquals(2, pages.tabs.size) // две вкладки: (1,2) и (3)
        assertEquals(listOf("m1", "m2"), pages.tabs[0].map { it.model?.model })
        assertEquals(listOf("m3"), pages.tabs[1].map { it.model?.model })
        assertEquals(emptyList<Int>(), pages.tabs[0][1].actions) // у модели 2 своих действий нет
        assertEquals(listOf(0), pages.tabs[1][0].actions)
        assertFalse(pages.empty)
    }

    /** Без отметок действие стоит у каждой модели; отметили — только у выбранных. */
    @Test fun actionsFollowTheirModelOwnership() {
        val models = (1..3).map { AiModel("m$it") }
        val forAll = AiAction("translate", builtin = Builtin.TRANSLATE)
        val forTwo = AiAction("c1", "Пересказ", "{{text}}", models = listOf("m1", "m3"))
        val pages = aiPages(models, listOf(forAll, forTwo))
        assertEquals(listOf(0, 1), pages.tabs[0][0].actions) // модель 1: общее действие и своё
        assertEquals(listOf(0), pages.tabs[0][1].actions) // модель 2: только общее
        assertEquals(listOf(0, 1), pages.tabs[1][0].actions)
        // Отметка по названию, которое видно в форме ИИ, а не по техническому имени модели.
        val named = aiPages(listOf(AiModel("claude-haiku-5-5", "Хайку")),
            listOf(AiAction("spell", builtin = Builtin.SPELL, models = listOf("Хайку"))))
        assertEquals(listOf(0), named.tabs[0][0].actions)
    }

    /** Ни одного действия ни у одной модели — форма ИИ открывается пустой (кнопка скажет об этом). */
    @Test fun allModelsEmptyMeansNothingToShow() {
        val models = listOf(AiModel("m1"), AiModel("m2"))
        val noText = AiAction("c1", "Своё")
        assertFalse(aiPages(models, listOf(noText)).empty) // без отметок действие видно всем
        assertTrue(aiPages(models, emptyList()).empty)
    }

    /** Модель одна, но у неё нет своих действий — форма пуста, хотя действие включено. */
    @Test fun singleModelWithoutActionsIsEmptyForm() {
        val pages = aiPages(listOf(AiModel("m1")), listOf(AiAction("c1", "Своё", "{{text}}", models = listOf("m2"))))
        assertEquals(1, pages.tabs.size)
        assertEquals(1, pages.tabs[0].size)
        assertEquals(emptyList<Int>(), pages.tabs[0][0].actions)
        assertEquals(listOf("m1"), aiTabs(listOf(AiModel("m1"))).flatten().map { it.model })
    }

    /** Каждое действие может стоять у нескольких моделей сразу. */
    @Test fun oneActionCanBelongToSeveralModels() {
        val models = (1..3).map { AiModel("m$it") }
        val shared = AiAction("c1", "Пересказ", "{{text}}", models = listOf("m1", "m2", "m3"))
        val pages = aiPages(models, listOf(shared))
        for (tab in pages.tabs) for (col in tab) assertEquals(listOf(0), col.actions)
    }

    /** Пропавшая модель из списка действий не выпадает: у каждой модели свой набор номеров действий. */
    @Test fun eachColumnKeepsItsOwnActionNumbers() {
        val models = listOf(AiModel("m1"), AiModel("m2"))
        val shown = listOf(
            AiAction("spell", builtin = Builtin.SPELL, models = listOf("m2")),
            AiAction("c1", "Своё", models = listOf("m1", "m2")),
        )
        val pages = aiPages(models, shown)
        assertEquals(listOf(1), pages.tabs[0][0].actions)
        assertEquals(listOf(0, 1), pages.tabs[0][1].actions)
    }
}
/**
 * Запись действий в настройки. Промт со пустой строкой внутри не должен рассыпаться на «лишние действия»
 * и не должен обрезаться — это была ошибка, когда промт писали в строку как есть.
 */
class AiStoreTest {
    private val multiLine = "Исправь текст ниже.\n\nИнструкция: {{instruction}}\n\nТекст:\n{{text}}"

    @Test fun multilinePromptSurvivesRoundTrip() {
        val a = AiAction("c1", "Своё\nназвание", multiLine, true)
        val store = listOf(a).joinToString("\n") { AiStore.line(it) }
        // Запись — ровно одна строка: пустая строка внутри промта не рвёт её на части.
        assertEquals(1, store.split('\n').size)
        val back = store.split('\n').mapNotNull { AiStore.parse(it) }
        assertEquals(1, back.size)
        assertEquals(a.id, back[0].id)
        assertEquals(a.title, back[0].title)
        assertEquals(multiLine, back[0].prompt)
    }

    /** Строки от прежней версии (там промт писался как есть и резался) в действия не попадают. */
    @Test fun rubbishLinesAreIgnored() {
        val raw = "spell\t1\t\t\n\nТекст:\n{{text}}\nc7\t1\tПоздравить\t{{instruction}}"
        val parsed = raw.split('\n').mapNotNull { AiStore.parse(it) }
        assertEquals(listOf("spell", "c7"), parsed.map { it.id })
        assertEquals("{{instruction}}", parsed[1].prompt)
    }

    /** Пятое поле — настройка подсветки; нет его — владелец её ещё не трогал. */
    @Test fun highlightFieldIsOptional() {
        assertNull(AiStore.parse("edit\t1\t\tSome prompt")!!.highlight)
        assertEquals(true, AiStore.parse("edit\t1\t\tSome prompt\t1")!!.highlight)
        assertEquals(false, AiStore.parse("edit\t1\t\tSome prompt\t0")!!.highlight)
    }

    /** Обратный слэш экранируется первым: раскрытие идёт однозначно в обе стороны. */
    @Test fun backslashesRoundTrip() {
        for (s in listOf("""C:\путь\n""", """\n""", """a\tb""", "без слэшей")) {
            assertEquals(s, AiStore.unesc(AiStore.esc(s)))
        }
    }

    /** Шестое поле — принадлежность модели: несколько названий в одной строке, порядок сохраняется. */
    @Test fun modelOwnershipSurvivesRoundTrip() {
        val a = AiAction("c1", "Своё", "{{text}}", true, models = listOf("m1", "m2"))
        val line = AiStore.line(a)
        assertEquals(1, line.split('\n').size) // названия моделей не рвут запись на строки
        val back = AiStore.parse(line)!!
        assertEquals(listOf("m1", "m2"), back.models)
        // Одно название — тоже список.
        assertEquals(listOf("m1"), AiStore.parse(AiStore.line(a.copy(models = listOf("m1"))))!!.models)
    }

    /** Поля нет (настройки прежних версий) — действие видно всем моделям. */
    @Test fun missingModelsFieldMeansAllModels() {
        assertEquals(emptyList<String>(), AiStore.parse("edit\t1\t\tSome prompt\t0")!!.models)
        assertEquals(emptyList<String>(), AiStore.parse("edit\t1\t\tSome prompt")!!.models)
    }

    /** Название модели в поле не должно ломать запись: табуляция и перевод строки в имени — мусор, но не сбой. */
    @Test fun oddModelNamesDoNotBreakTheLine() {
        val a = AiAction("c1", "Своё", "p", models = listOf("m\t1", "m\n2"))
        val line = AiStore.line(a)
        assertEquals(1, line.split('\n').size)
        assertEquals(listOf("m\t1", "m\n2"), AiStore.parse(line)!!.models)
    }
}

/** Старый пакет не должен остаться ни в манифесте, ни в ресурсах, ни в исходниках. */
class RenameTest {
    private fun root(): File {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "src/main/AndroidManifest.xml").exists()) {
            d = if (File(d, "app/src/main/AndroidManifest.xml").exists()) File(d, "app") else d.parentFile
        }
        return d ?: error("app dir not found")
    }

    @Test fun manifestAndResourcesHaveNoOldPackage() {
        val main = File(root(), "src/main")
        val bad = main.walkTopDown().filter { it.isFile && (it.extension == "xml" || it.extension == "pro") }
            .filter { it.readText().contains("com.klava") || it.readText().contains("com.doroshenko") }
            .map { it.path }.toList()
        assertTrue("old package in: $bad", bad.isEmpty())
        assertTrue(File(main, "AndroidManifest.xml").readText().contains("com.kai.keyboard"))
    }

    @Test fun sourcesUseNewPackage() {
        val src = File(root(), "src/main/java")
        assertFalse(File(src, "com/klava").exists())
        val bad = src.walkTopDown().filter { it.extension == "kt" }
            .filter { Regex("""^package\s+com\.klava""", RegexOption.MULTILINE).containsMatchIn(it.readText()) }.toList()
        assertTrue(bad.isEmpty())
    }

    @Test fun gradleIdIsKai() {
        val g = File(root(), "build.gradle.kts").readText()
        assertTrue(g.contains("applicationId = \"com.kai.keyboard\""))
    }
}

class SystemThemeModelTest {
    @Test fun allFourThemesPresentWithDefaults() {
        assertEquals(listOf("black", "dark", "light", "system"), Theme.ALL.map { it.id })
        assertEquals("system", Theme.of("system").id)
        assertEquals(0xFF123456.toInt(), Theme.of("system").accentFor(0xFF123456.toInt())) // без своего акцента — выбранный
    }
}

class TextDiffSmokeTest {
    @Test fun marksChangedWordOnly() {
        val s = TextDiff.changed("one two three", "one 2 three")
        assertEquals(1, s.size)
        assertEquals("2", "one 2 three".substring(s[0].start, s[0].end))
    }
    @Test fun sameTextNoSpans() { assertTrue(TextDiff.changed("a b", "a b").isEmpty()) }

    /** Посимвольная сверка: один и тот же текст — да, любое отличие, включая регистр и пробел, — нет. */
    @Test fun sameComparesCharacterByCharacter() {
        assertTrue(TextDiff.same("Привет, мир", "Привет, мир"))
        assertFalse(TextDiff.same("Привет, мир", "Привет, миp"))  // последняя буква латинская
        assertFalse(TextDiff.same("Привет", "привет"))
        assertFalse(TextDiff.same("а б", "а  б"))
        assertFalse(TextDiff.same("а б", "а б "))
        // Текст, из которого спрашивали, найден в поле — заменять можно, откуда бы курсор ни пришёл.
        assertTrue(TextDiff.same("Всё поле целиком", "Всё поле целиком"))
    }

    /** Правка одного символа в середине длинного текста — подсвечен ровно он. */
    @Test fun singleCharacterEditInTheMiddle() {
        val s = TextDiff.changed("Сегодня отличный день чтобы проверить", "Сегодня отличный ден, чтобы проверить")
        assertEquals(1, s.size)
        assertEquals(",", "Сегодня отличный ден, чтобы проверить".substring(s[0].start, s[0].end))
    }

    /** Вставка и удаление посреди текста тоже видны как одно место, а не как весь текст. */
    @Test fun insertionAndDeletionMarkTheirPlaceOnly() {
        val ins = TextDiff.changed("начало конец", "начало и конец")
        assertEquals(1, ins.size)
        assertEquals("и ", "начало и конец".substring(ins[0].start, ins[0].end))
        // Только удаление: в новом тексте удалённого знака нет — подсвечиваем соседний, рядом с местом.
        val del = TextDiff.changed("начало и конец", "начало конец")
        assertEquals(1, del.size)
        assertEquals(1, del[0].end - del[0].start)
        assertEquals("начало конец".indexOf('к'), del[0].start)
    }
}
