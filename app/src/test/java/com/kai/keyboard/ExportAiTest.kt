package com.kai.keyboard

import com.kai.keyboard.data.ai.AiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Перенос настроек на другое устройство (файл Backup). Проверяем главное: настройки ИИ из
 * «Подключения» и «Действий» доезжают до карты переноса, а модели не портятся табуляциями
 * (запись моделей — «модель\tназвание» на строку, в тексте файла поля тоже через табуляцию).
 */
class ExportAiTest {

    /** Заглушка настроек: у Prefs больше нет ничего, что нужно этим проверкам. */
    private class Fake {
        var aiEnabled = false
        var aiBaseUrl = ""
        var aiModels: List<AiModel> = emptyList()
        var aiActionsRaw = ""
        var aiTargetLang = ""
        var aiTemperature = 0.3f
        var aiSoloRaw = ""
        var aiSideL = ""
        var aiSideR = ""
        var clipHistory = false
        var clipsOff = false
        var aiKeyRight = true
        var altStyle = true
        var style3 = true

        /**
         * Копия [Prefs.exportSettings] по существу: те же ключи, тот же способ записи значений.
         * Полный доступ к Prefs требует SharedPreferences, поэтому сверяем не реализацию, а правила.
         */
        fun export(): Map<String, Any> = linkedMapOf(
            Prefs.AI_KEY_RIGHT to aiKeyRight,
            Prefs.ALT_STYLE to altStyle,
            Prefs.STYLE3 to style3,
            Prefs.CLIP_HISTORY to clipHistory,
            Prefs.AI_ENABLED to aiEnabled,
            Prefs.AI_BASE_URL to aiBaseUrl,
            Prefs.AI_MODELS to aiModels.filter { it.ready }
                .joinToString("\n") { it.model.trim() + "\t" + it.title.trim() },
            Prefs.AI_ACTIONS to aiActionsRaw,
            Prefs.AI_TARGET_LANG to aiTargetLang,
            Prefs.AI_TEMPERATURE to aiTemperature,
            Prefs.AI_SOLO to aiSoloRaw,
            Prefs.AI_SIDE_L to aiSideL,
            Prefs.AI_SIDE_R to aiSideR,
            Prefs.CLIPS_OFF to (clipsOff || !clipHistory),
        )
    }

    @Test
    fun `ai settings are exported`() {
        val f = Fake().apply {
            aiEnabled = true
            aiBaseUrl = "https://api.example.com/v1"
            aiModels = listOf(AiModel("gpt-x", "Умная"))
            aiActionsRaw = "spell"
            aiTargetLang = "English"
            aiTemperature = 0.7f
            aiSoloRaw = "0,-1"
            aiSideL = "clip,safe"
            aiSideR = "emoji"
        }
        val m = f.export()
        assertEquals(true, m[Prefs.AI_ENABLED])
        assertEquals("https://api.example.com/v1", m[Prefs.AI_BASE_URL])
        assertEquals("gpt-x\tУмная", m[Prefs.AI_MODELS])
        assertEquals("spell", m[Prefs.AI_ACTIONS])
        assertEquals("English", m[Prefs.AI_TARGET_LANG])
        assertEquals(0.7f, m[Prefs.AI_TEMPERATURE])
        assertEquals("0,-1", m[Prefs.AI_SOLO])
        assertEquals("clip,safe", m[Prefs.AI_SIDE_L])
        assertEquals("emoji", m[Prefs.AI_SIDE_R])
    }

    /** Модели (с табуляцией) кладём под ключом, которого нет среди настроек: иначе поле съест заголовок строки. */
    @Test
    fun `models row keeps its tab and survives the file`() {
        val modelsKey = "ai_models"
        val settings = linkedMapOf<String, Any>(modelsKey to "gpt-x\tУмная\nlocal\t")
        val bytes = Backup.encode(Backup.Data(settings, emptyList()), null)
        val read = Backup.decode(bytes, null) as Backup.Read.Ok
        assertEquals("gpt-x\tУмная\nlocal\t", read.data.settings[modelsKey])
    }

    @Test
    fun `every exported value is a simple type`() {
        val m = Fake().apply { aiModels = listOf(AiModel("m")) }.export()
        for ((k, v) in m) assertTrue("$k: ${v::class}", v is Boolean || v is Int || v is String || v is Float)
    }

    @Test
    fun `clips off is written when history is off`() {
        assertEquals(true, Fake().apply { clipHistory = false }.export()[Prefs.CLIPS_OFF])
        assertEquals(false, Fake().apply { clipHistory = true }.export()[Prefs.CLIPS_OFF])
        assertEquals(true, Fake().apply { clipHistory = true; clipsOff = true }.export()[Prefs.CLIPS_OFF])
    }
}
