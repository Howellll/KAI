package com.kai.keyboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.kai.keyboard.data.ai.AiAction
import com.kai.keyboard.data.ai.AiModel
import com.kai.keyboard.data.ai.AiPrompts
import com.kai.keyboard.data.ai.Builtin
import com.kai.keyboard.data.ai.withBuiltinActions

/**
 * KAI_VERY_LIGHT / KAI_VERY_BOLD: самые тонкое и самое жирное начертания, какие есть у шрифта.
 * Нужны для шапки («K» тонкая, «AI» жирная): android:textStyle даёт только NORMAL/BOLD,
 * а системный шрифт без этих запросов подсовывает синтетическую жирность. Итог подгоняем по всей
 * семье — берём крайние доступные начертания, чтобы начертания гарантированно отличались.
 */
object Fonts {
    fun light(base: Typeface): Typeface =
        Typeface.create(Typeface.create(base, 100), Typeface.NORMAL)

    fun bold(base: Typeface): Typeface =
        Typeface.create(Typeface.create(base, 900), Typeface.BOLD)

    /**
     * Одно начертание внутри строки: стиль действует только на этот кусок текста.
     * @param bold индексы символов, включительно; выходить за длину строки нельзя —
     *   setSpan с концом за её пределами валит приложение, поэтому диапазон обрезается.
     */
    fun spanStyle(s: CharSequence, bold: IntRange): CharSequence {
        val from = bold.first.coerceIn(0, s.length)
        val to = (bold.last + 1).coerceIn(from, s.length)
        if (from >= to) return s
        return android.text.SpannableString(s).apply {
            setSpan(android.text.style.StyleSpan(Typeface.BOLD), from, to,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}

class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private lateinit var scroll: ScrollView
    private lateinit var header: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var search: EditText
    private lateinit var status: TextView
    private lateinit var setup: LinearLayout
    private var openSection: String? = null

    override fun onStart() { super.onStart(); AppGate.onStart() }
    override fun onStop() {
        super.onStop()
        tryField?.setText("") // вдруг туда вставили запись сейфа
        keyField?.setText("")
        deleteField?.let { val s = getString(R.string.st_delete_sample); it.setText(s); it.setSelection(s.length) }
        AppGate.onStop()
    }
    private var tryField: EditText? = null
    private var deleteField: EditText? = null
    private var keyField: EditText? = null
    private val aiClient by lazy { com.kai.keyboard.data.ai.OpenAiCompatibleClient() }

    override fun onDestroy() {
        for (h in aiCalls.toList()) h.cancel()
        aiCalls.clear()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Palette.apply(this)
        Brain.init(this)
        defineItems()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(40))
        }
        scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(Palette.bg)
            addView(root)
        }
        setContentView(scroll)

        header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        head.addView(text(getString(R.string.st_kicker), 11f, Palette.muted).apply { letterSpacing = 0.27f })
        head.addView(TextView(this).apply {
            // Шапка как в образце: первая буква тонкая, остальные жирные («K» + «AI»). Начертание внутри
            // строки задаёт StyleSpan — диапазон считаем от самой строки приложения (app_name), а не от
            // жёсткого 1..2: у форка или перевода надпись другой длины, и прежний диапазон
            // выходил за её конец — setSpan валил приложение (IndexOutOfBoundsException).
            val name = getString(R.string.app_name)
            text = if (name.length >= 2) Fonts.spanStyle(name, 1 until name.length) else name
            textSize = 46f
            setTextColor(Palette.text)
            typeface = Fonts.light(Typeface.DEFAULT)
            setLineSpacing(0f, 1f)
            setPadding(0, dp(6), 0, dp(2))
        })
        head.addView(text(getString(R.string.header_tag), 13f, Palette.muted).apply { maxWidth = dp(300) })
        head.addView(View(this).apply { setBackgroundColor(Palette.separator) },
            LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { topMargin = dp(20) })
        header.addView(head)

        status = text("", 15f, Palette.text)
        setup = card().apply {
            addView(status)
            addView(primary(getString(R.string.st_step_enable)) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
                .apply { tag = "enable" })
            addView(primary(getString(R.string.st_step_pick)) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }.apply { tag = "pick" })
        }
        header.addView(setup)

        header.addView(field(getString(R.string.st_try_hint)).also { tryField = it }.apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(20)
        })

        search = searchField(getString(R.string.search_hint)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            isSingleLine = true
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(28)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { if (openSection == null) showMain() }
            })
        }
        header.addView(search)

        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(header)
        root.addView(body)

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }
        val reopen = savedInstanceState?.getString(STATE_SECTION)
        if (reopen != null && sections.any { it.id == reopen }) showSection(reopen) else showMain()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString(STATE_SECTION, openSection)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { goBack() }

    private fun goBack() {
        when {
            openSection != null -> showMain()
            search.text.isNotEmpty() -> search.setText("")
            else -> finish()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateStatus()
    }

    private fun updateStatus() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
        status.text = when {
            current -> getString(R.string.st_status_on)
            enabled -> getString(R.string.st_status_almost)
            else -> getString(R.string.st_status_off)
        }
        status.setTextColor(if (current) Palette.success else Palette.text)
        setup.findViewWithTag<View>("enable").visibility = if (enabled) View.GONE else View.VISIBLE
        setup.findViewWithTag<View>("pick").visibility = if (current) View.GONE else View.VISIBLE
        setup.background = if (current) null else rounded(Palette.surface)
        setup.setPadding(if (current) 0 else dp(16), dp(if (current) 4 else 14), dp(16), dp(if (current) 0 else 14))
    }

    // ================= экраны =================

    private class Section(val id: String, val icon: Int, val color: Int, val title: String, val subtitle: String)
    private class Item(val section: String, val search: String, val build: () -> View)

    // lazy: getString нужен готовый Context
    private val sections by lazy { listOf(
        Section("typing", SectionIcon.KEYBOARD, Color.rgb(0x00, 0x7A, 0xFF), getString(R.string.st_sec_typing), getString(R.string.st_sec_typing_sub)),
        Section("delete", SectionIcon.BACKSPACE, Color.rgb(0xFF, 0x3B, 0x30), getString(R.string.st_sec_delete), getString(R.string.st_sec_delete_sub)),
        Section("look", SectionIcon.PALETTE, Color.rgb(0xAF, 0x52, 0xDE), getString(R.string.st_sec_look), getString(R.string.st_sec_look_sub)),
        Section("feedback", SectionIcon.FEEDBACK, Color.rgb(0xFF, 0x95, 0x00), getString(R.string.st_sec_feedback), getString(R.string.st_sec_feedback_sub)),
        Section("ai", SectionIcon.AI, Color.rgb(0x5E, 0x5C, 0xE6), getString(R.string.ai_section_title), getString(R.string.ai_section_sub)),
        Section("privacy", SectionIcon.SHIELD, Color.rgb(0x34, 0xC7, 0x59), getString(R.string.st_sec_privacy), getString(R.string.st_sec_privacy_sub)),
        Section("about", SectionIcon.INFO, Color.rgb(0x8E, 0x8E, 0x93), getString(R.string.st_sec_about), getString(R.string.st_sec_about_sub)),
    ) }
    private val items = ArrayList<Item>()

    private fun showMain() {
        openSection = null
        header.visibility = View.VISIBLE
        body.removeAllViews()
        val q = search.text.toString().trim().lowercase()
        if (q.isNotEmpty()) { showFound(q); return }
        body.addView(group(sections.map { s -> navRow(SectionIcon(this, s.icon, s.color), s.title, s.subtitle) { showSection(s.id) } })
            .apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(16) })
        body.addView(group(listOf(navRow(SectionIcon(this, SectionIcon.SAFE, Color.rgb(0x58, 0x56, 0xD6)), getString(R.string.st_safe), getString(R.string.st_safe_sub)) {
            startActivity(Intent(this, SafeActivity::class.java))
        })).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(16) })
    }

    private fun showSection(id: String) {
        val s = sections.first { it.id == id }
        openSection = id
        header.visibility = View.GONE
        body.removeAllViews()
        body.addView(text(getString(R.string.st_back), 17f, Palette.accent).apply {
            setPadding(0, dp(4), dp(16), dp(10))
            setOnClickListener { showMain() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        body.addView(text(s.title, 32f, Palette.text, bold = true).apply { setPadding(0, 0, 0, dp(8)) })
        for (item in items.filter { it.section == id }) body.addView(cardOf(item.build()))
        scroll.scrollTo(0, 0)
    }

    private fun showFound(q: String) {
        val words = q.split(' ').filter { it.isNotBlank() }
        val found = items.filter { it -> words.all { w -> it.search.contains(w) } }
        if (found.isEmpty()) {
            body.addView(text(getString(R.string.st_nothing_found), 15f, Palette.secondary).apply { setPadding(dp(4), dp(24), 0, 0) })
            return
        }
        for (item in found) {
            val s = sections.first { it.id == item.section }
            body.addView(caption(s.title.uppercase()).apply { setPadding(dp(4), dp(22), 0, dp(6)) })
            body.addView(cardOf(item.build()).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = 0 })
        }
    }

    // ================= параметры =================

    private fun item(section: String, search: String, build: () -> View) { items.add(Item(section, search.lowercase(), build)) }

    private fun sw(section: String, title: String, hint: String?, keys: String, get: () -> Boolean, set: (Boolean) -> Unit) =
        item(section, "$title ${hint ?: ""} $keys") { switch(title, hint, get(), set) }

    private fun defineItems() {
        items.clear()
        // ---- Набор текста ----
        sw("typing", getString(R.string.st_smart), getString(R.string.st_smart_hint), "попадание точность hit accuracy targets",
            { prefs.smartTargets }) { prefs.smartTargets = it }
        sw("typing", getString(R.string.st_swipe), getString(R.string.st_swipe_hint), "жест swipe gesture",
            { prefs.swipe }) { prefs.swipe = it }
        sw("typing", getString(R.string.st_numrow), getString(R.string.st_numrow_hint), "числа digits numbers",
            { prefs.numberRow }) { prefs.numberRow = it }
        sw("typing", getString(R.string.st_clipkey), getString(R.string.st_clipkey_hint), "буфер обмена clipboard вставить скопированное paste copy",
            { prefs.clipBottomKey }) { prefs.clipBottomKey = it }
        item("typing", "символы долгим нажатием знаки двойные символы скобки кавычки пара symbols long press double brackets quotes pair") {
            // «Двойные символы» - только вместе с символами долгим нажатием: без них и попапа нет.
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val doubles = switch(getString(R.string.st_double), getString(R.string.st_double_hint), prefs.doubleSymbols) { prefs.doubleSymbols = it }
            doubles.visibility = if (prefs.lpSymbols) View.VISIBLE else View.GONE
            box.addView(switch(getString(R.string.st_lpsym), getString(R.string.st_lpsym_hint), prefs.lpSymbols) {
                prefs.lpSymbols = it
                doubles.visibility = if (it) View.VISIBLE else View.GONE
            })
            box.addView(doubles)
            box
        }
        sw("typing", getString(R.string.st_reorder), getString(R.string.st_reorder_hint), "перестановка reorder swap",
            { prefs.reorder }) { prefs.reorder = it }
        sw("typing", getString(R.string.st_emojisug), getString(R.string.st_emojisug_hint), "смайлик emoji",
            { prefs.emojiSuggest }) { prefs.emojiSuggest = it }
        item("typing", "автоисправление опечатки исправлять autocorrect typo fix") {
            slider(0, 100, prefs.autocorrectLevel, getString(R.string.st_autocorrect_hint),
                { v -> getString(R.string.st_autocorrect, if (v == 0) getString(R.string.st_off) else "$v") }) { prefs.autocorrectLevel = it }
        }
        sw("typing", getString(R.string.st_autocap), null, "автозаглавные регистр autocapitalize case", { prefs.autocap }) { prefs.autocap = it }
        sw("typing", getString(R.string.st_doublespace), null, "пробел space", { prefs.doubleSpace }) { prefs.doubleSpace = it }
        sw("typing", getString(R.string.st_langswipe), getString(R.string.st_langswipe_hint), "раскладка переключение глобус language layout globe", { prefs.langSwipe }) { prefs.langSwipe = it }

        // ---- Удаление ----
        // Поле для пробы - первым: ниже его закрыла бы клавиатура.
        item("delete", "попробовать удаление проверить try deleting test") { deleteTryout() }
        item("delete", "удаление словами удерживать стирать delete words hold erase") {
            timeSlider(getString(R.string.st_del_words), 100, 3000, prefs.deleteWordsAfter, off = true,
                hint = getString(R.string.st_del_words_hint)) { prefs.deleteWordsAfter = it }
        }
        item("delete", "пауза между словами удаление pause between words deleting") {
            timeSlider(getString(R.string.st_del_pause), 50, 1500, prefs.deleteWordPause, off = false,
                hint = getString(R.string.st_del_pause_hint)) { prefs.deleteWordPause = it }
        }

        // ---- Внешний вид ----
        item("look", "тема светлая тёмная чёрная theme light dark black") { themeChooser() }
        sw("look", getString(R.string.st_zones), getString(R.string.st_zones_hint), "зоны отладка акцент точность zones debug accent accuracy",
            { prefs.showZones }) { prefs.showZones = it }
        item("look", "акцентный цвет акцент accent colour color") { accentChooser() }
        item("look", "положение кнопок ии ai смайлик слева справа край эмодзи position buttons emoji left right corner") { aiKeySideChooser() }
        item("look", "стиль символов нижнего ряда запятая style symbols bottom row comma") { styleChooser() }
        item("look", "высота клавиатуры размер height size") {
            slider(80, 120, prefs.height, null, { getString(R.string.st_height, it) }) { prefs.height = it }
        }
        item("look", "ширина клавиш промежутки размер key width gaps size") {
            // Ширина видимых клавиш - за счёт промежутков, шаг 0,5 dp. Подпись - насколько шире буква
            // в русском ряду из 11 клавиш на обычном экране 360 dp. Ползунок вправо - шире: промежуток = 22 − p.
            fun widthTitle(p: Int): String {
                val pitch = (360f - 6f) / 11f
                val pct = Math.round(((pitch - (22 - p) / 2f) / (pitch - 6f) - 1f) * 100)
                val value = when {
                    pct == 0 -> getString(R.string.st_width_normal)
                    pct > 0 -> "+$pct%"
                    else -> "$pct%"
                }
                return getString(R.string.st_width, value)
            }
            slider(2, 20, 22 - prefs.keyGap, getString(R.string.st_width_hint), ::widthTitle) { prefs.keyGap = 22 - it }
        }
        sw("look", getString(R.string.st_preview), null, "превью preview", { prefs.preview }) { prefs.preview = it }
        item("look", "одной рукой режим one hand mode") {
            text(getString(R.string.st_onehand_hint), 14f, Palette.secondary)
                .apply { setPadding(0, dp(4), 0, dp(4)) }
        }

        // ---- Отклик ----
        sw("feedback", getString(R.string.st_vibrate), null, "отклик feedback", { prefs.vibrate }) { prefs.vibrate = it }
        sw("feedback", getString(R.string.st_sound), null, "отклик feedback", { prefs.sound }) { prefs.sound = it }

        // ---- Приватность ----
        sw("privacy", getString(R.string.st_learn), getString(R.string.st_learn_hint), "обучение словарь learning dictionary", { prefs.learn }) { prefs.learn = it }
        sw("privacy", getString(R.string.st_cliphist), getString(R.string.st_cliphist_hint), "копировать вставить clipboard copy paste",
            { prefs.clipHistory }) { prefs.clipHistory = it; prefs.clipsOff = !it; if (!it) Brain.clips.clearAll() }
        item("privacy", "хранить в буфере время срок keep clipboard time ttl") {
            val ttlPos = CLIP_TTL_STEPS.indexOfFirst { it >= prefs.clipTtlMin }.let { if (it < 0) CLIP_TTL_STEPS.size - 1 else it }
            slider(0, CLIP_TTL_STEPS.size - 1, ttlPos, getString(R.string.st_clip_ttl_hint),
                { getString(R.string.st_clip_ttl, ttlLabel(this, CLIP_TTL_STEPS[it])) }) { prefs.clipTtlMin = CLIP_TTL_STEPS[it] }
        }
        item("privacy", "сейф пароли коды пин safe passwords codes pin") {
            actionRow(getString(R.string.st_safe_row), Palette.accent) { startActivity(Intent(this, SafeActivity::class.java)) }
        }
        item("privacy", "перенос экспорт импорт другое устройство телефон резервная копия файл сохранить загрузить transfer export import another device phone backup file save load") { transferBlock() }
        item("privacy", "стереть всё выученное забыть очистить erase everything learned forget clear") {
            actionRow(getString(R.string.st_erase_learned), Palette.danger) { v ->
                Brain.clearLearned()
                // Недавние эмодзи и цвет кожи - тоже выученное о владельце.
                prefs.clearEmojiHistory()
                ((v as LinearLayout).getChildAt(0) as TextView).text = getString(R.string.st_erased)
            }
        }

        // ---- ИИ ----
        item("ai", "ии ai нейросеть приватность включить openai") { aiMainBlock() }
        item("ai", "base url адрес сервер api ключ модель модели температура подключение проверить название") { aiConnectionBlock() }
        item("ai", "действия орфография перевод изменить свои добавить настроить промт переменные язык перевода выделять изменения подсветка") { aiFunctionsBlock() }

        // ---- О приложении ----
        item("about", "kai klava форк о приложении gpl репозиторий перенос данных fork about repository data transfer") { aboutForkBlock() }
        item("about", "сеть интернет телеметрия okhttp apache network internet telemetry") {
            text(getString(R.string.about_network) + "\n\n" + getString(R.string.about_okhttp),
                14f, Palette.secondary).apply { setPadding(0, dp(4), 0, dp(4)) }
        }
        item("about", "лицензия gpl словари источники licence dictionaries sources") {
            text(getString(R.string.st_dictionaries), 14f, Palette.secondary).apply { setPadding(0, dp(4), 0, dp(4)) }
        }
        item("about", "лицензия unicode licence") {
            actionRow(getString(R.string.st_unicode_license), Palette.accent) {
                val t = assets.open("UNICODE-LICENSE.txt").bufferedReader().use { it.readText() }
                android.app.AlertDialog.Builder(this, Palette.dialog)
                    .setTitle("Unicode License v3").setMessage(t).setPositiveButton(getString(R.string.cf_close), null).create()
                    .show()
            }
        }
    }

    // ================= раздел «ИИ» =================

    private var models: MutableList<AiModel> = mutableListOf()

    private var modelsRender: (() -> Unit)? = null

    private val aiCalls = java.util.Collections.synchronizedList(
        ArrayList<com.kai.keyboard.data.ai.OpenAiCompatibleClient.Handle>())

    private fun aiMainBlock(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(6)) }
        box.addView(text(getString(R.string.ai_privacy_title), 17f, Palette.text))
        box.addView(text(getString(R.string.ai_privacy_warning), 14f, Palette.secondary).apply { setPadding(0, dp(6), 0, dp(8)) })
        box.addView(switch(getString(R.string.ai_enable), getString(R.string.ai_enable_hint), prefs.aiEnabled) { v ->
            prefs.aiEnabled = v
        })
        return box
    }

    private fun prefField(hint: String, value: String, uri: Boolean = false, save: (String) -> Unit) = field(hint).apply {
        inputType = InputType.TYPE_CLASS_TEXT or
            (if (uri) InputType.TYPE_TEXT_VARIATION_URI else 0) or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        isSingleLine = true
        setText(value)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { save(s.toString()) }
        })
        (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
    }

    /** Маленькое поле внутри строки модели (в списке их до шести - обычные великоваты). */
    private fun modelField(hint: String, value: String, save: (String) -> Unit) = field(hint).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        isSingleLine = true
        textSize = 15f
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setText(value)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { save(s.toString()) }
        })
        (layoutParams as LinearLayout.LayoutParams).topMargin = dp(6)
    }

    private fun aiConnectionBlock(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(8)) }
        box.addView(text(getString(R.string.ai_connection), 17f, Palette.text))

        box.addView(text(getString(R.string.ai_base_url_title), 14f, Palette.secondary).apply { setPadding(0, dp(12), 0, 0) })
        box.addView(prefField(getString(R.string.ai_base_url_hint), prefs.aiBaseUrl, uri = true) { prefs.aiBaseUrl = it })
        box.addView(text(getString(R.string.ai_base_url_help), 13f, Palette.secondary).apply { setPadding(0, dp(4), 0, 0) })

        // ---- ключ ----
        box.addView(text(getString(R.string.ai_key_title), 14f, Palette.secondary).apply { setPadding(0, dp(16), 0, 0) })
        val keyState = text("", 13.5f, Palette.secondary).apply { setPadding(0, dp(6), 0, 0) }
        fun refreshKeyState() {
            keyState.text = when {
                !prefs.hasAiKey -> getString(R.string.ai_key_none)
                prefs.aiKey.isEmpty() -> getString(R.string.ai_key_unavailable)
                else -> getString(R.string.ai_key_saved, "••••••••" + prefs.aiKey.takeLast(4))
            }
        }
        val key = field(getString(R.string.ai_key_hint)).also { keyField = it }.apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            isSingleLine = true
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
        }
        box.addView(key)
        box.addView(keyState)
        refreshKeyState()
        val keyButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        keyButtons.addView(link(getString(R.string.ai_save), Palette.accent) {
            val v = key.text.toString().trim()
            if (v.isNotEmpty()) {
                prefs.aiKey = v
                key.setText("")
                refreshKeyState()
                toast(getString(R.string.ai_key_saved_toast))
            }
        })
        keyButtons.addView(link(getString(R.string.ai_delete), Palette.danger) {
            prefs.deleteAiKey(); key.setText(""); refreshKeyState()
            toast(getString(R.string.ai_key_deleted_toast))
        })
        box.addView(keyButtons)
        box.addView(text(getString(R.string.ai_key_help), 13f, Palette.secondary))

        // ---- модели: до шести, адрес и ключ у всех общие ----
        box.addView(text(getString(R.string.ai_models_title), 14f, Palette.secondary).apply { setPadding(0, dp(18), 0, 0) })
        box.addView(text(getString(R.string.ai_models_help), 13f, Palette.secondary).apply { setPadding(0, dp(4), 0, 0) })
        models = prefs.aiModels.toMutableList()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val addRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, 0) }

        lateinit var render: () -> Unit
        render = {
            list.removeAllViews()
            models.forEachIndexed { i, m ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(12), 0, 0)
                }
                val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                head.addView(text(getString(R.string.ai_model_n, i + 1), 13.5f, Palette.secondary),
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                head.addView(text(getString(R.string.ai_delete), 14f, Palette.danger).apply {
                    setPadding(dp(12), dp(4), 0, dp(4))
                    setOnClickListener {
                        if (models.size <= 1) { toast(getString(R.string.ai_model_last)); return@setOnClickListener }
                        askDeleteModel(i)
                    }
                })
                row.addView(head)
                row.addView(modelField(getString(R.string.ai_model_hint), m.model) { s ->
                    models[i] = models[i].copy(model = s.trim()); prefs.aiModels = models
                })
                row.addView(modelField(getString(R.string.ai_model_name_hint), m.title) { s ->
                    models[i] = models[i].copy(title = s.trim()); prefs.aiModels = models
                })
                list.addView(row)
            }
            addRow.removeAllViews()
            if (models.size < AiModel.MAX) {
                addRow.addView(link(getString(R.string.ai_model_add), Palette.accent) {
                    models.add(AiModel()); prefs.aiModels = models; render()
                })
            } else {
                addRow.addView(text(getString(R.string.ai_models_max, AiModel.MAX), 13f, Palette.secondary))
            }
        }
        modelsRender = render
        render()
        box.addView(list)
        box.addView(addRow)

        box.addView(slider(0, 20, Math.round(prefs.aiTemperature * 10), getString(R.string.ai_temperature_help),
            { getString(R.string.ai_temperature, "%.1f".format(java.util.Locale.ROOT, it / 10f)) }) { prefs.aiTemperature = it / 10f })

        // ---- проверка: сразу все модели ----
        val result = text("", 14f, Palette.secondary).apply { setPadding(0, dp(8), 0, 0) }
        box.addView(primary(getString(R.string.ai_test)) { testConnection(result) })
        box.addView(result)
        box.addView(primary(getString(R.string.ai_catalog)) { openCatalog(result) })
        return box
    }

    private fun openCatalog(result: TextView) {
        if (prefs.aiBaseUrl.isBlank()) {
            result.setTextColor(Palette.danger); result.text = getString(R.string.ai_test_need_fields); return
        }
        for (h in aiCalls.toList()) h.cancel()
        aiCalls.clear()
        result.setTextColor(Palette.secondary); result.text = getString(R.string.ai_catalog_loading)
        val h = aiClient.models(prefs.aiBaseUrl, prefs.aiKey) { r ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (r) {
                    is com.kai.keyboard.data.ai.OpenAiCompatibleClient.ModelsResult.Fail -> {
                        result.setTextColor(Palette.danger)
                        result.text = getString(R.string.ai_catalog_failed,
                            com.kai.keyboard.data.ai.AiErrors.message(this, r.failure))
                    }
                    is com.kai.keyboard.data.ai.OpenAiCompatibleClient.ModelsResult.Ok -> {
                        if (r.ids.isEmpty()) {
                            result.setTextColor(Palette.danger); result.text = getString(R.string.ai_catalog_empty)
                        } else { result.text = ""; showCatalog(r.ids) }
                    }
                }
            }
        }
        aiCalls.add(h)
    }

    private fun showCatalog(ids: List<String>) {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), dp(4)) }
        for (id in ids) {
            list.addView(text(id, 16f, Palette.text).apply {
                setPadding(0, dp(12), 0, dp(12))
                setOnClickListener {
                    when {
                        models.any { it.model == id } -> toast(getString(R.string.ai_catalog_have, id))
                        models.size >= AiModel.MAX -> toast(getString(R.string.ai_catalog_limit, AiModel.MAX))
                        else -> {
                            val blank = models.indexOfFirst { it.model.isBlank() }
                            if (blank >= 0) models[blank] = models[blank].copy(model = id) else models.add(AiModel(id))
                            prefs.aiModels = models
                            modelsRender?.invoke()
                            toast(getString(R.string.ai_catalog_added, id))
                        }
                    }
                }
            })
        }
        android.app.AlertDialog.Builder(this, Palette.dialog)
            .setTitle(getString(R.string.ai_catalog))
            .setView(ScrollView(this).apply { addView(list) })
            .setNegativeButton(getString(R.string.ai_cancel), null)
            .show()
    }

    /**
     * Маскот KAI: картинка из assets, растянутая по ширине карточки. Рисуем её фоном прямоугольника
     * (не ImageView): размеры известны сразу из ширины, а файла может и не быть — тогда подпись
     * в том же месте (пустая рамка выглядела бы поломкой).
     */
    private fun mascotView(): View {
        val placeholder = text(getString(R.string.st_mascot_missing), 14f, Palette.secondary).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val frame = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
            addView(placeholder, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            // Картинка внутри рамки, а не фоном: фон редакторы и темы иногда подменяют.
            runCatching {
                addView(android.widget.ImageView(context).apply {
                    scaleType = android.widget.ImageView.ScaleType.FIT_XY
                    setImageDrawable(android.graphics.drawable.BitmapDrawable(resources,
                        android.graphics.BitmapFactory.decodeStream(assets.open("KAI_mascot.png"))))
                }, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }.onFailure { placeholder.visibility = View.VISIBLE }
        }
        frame.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            // Высоту считаем от ширины: картинка квадратная, а FrameLayout с одним MATCH_PARENT-ребёнком
            // сам её не выведет.
            val h = Math.round(v.width * MASCOT_RATIO)
            if (h > 0 && v.layoutParams.height != h) {
                v.layoutParams = v.layoutParams.apply { height = h }
            }
        }
        return frame
    }

    private fun link(label: String, color: Int, onClick: (TextView) -> Unit) = text(label, 16f, color).apply {
        setPadding(0, dp(8), dp(24), dp(8))
        setOnClickListener { onClick(this) }
    }

    /** Проверить все заполненные модели - параллельно и коротким служебным запросом (без текста владельца). */
    private fun testConnection(result: TextView) {
        if (prefs.aiBaseUrl.isBlank()) {
            result.setTextColor(Palette.danger); result.text = getString(R.string.ai_test_need_fields); return
        }
        val list = prefs.aiModels.filter { it.ready }
        if (list.isEmpty()) {
            result.setTextColor(Palette.danger); result.text = getString(R.string.ai_test_need_model); return
        }
        for (h in aiCalls.toList()) h.cancel()
        aiCalls.clear()
        result.setTextColor(Palette.secondary); result.text = getString(R.string.ai_test_running)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val errors = java.util.Collections.synchronizedList(ArrayList<String>())
        val url = prefs.aiBaseUrl
        val keyText = prefs.aiKey
        val temp = prefs.aiTemperature.toDouble()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(list.size) { r -> Thread(r).apply { isDaemon = true } }
        for (m in list) {
            val cfg = com.kai.keyboard.data.ai.OpenAiCompatibleClient.Config(url, keyText, m.model, temp)
            val h = aiClient.chat(cfg, "ping", maxTokens = 5) { r ->
                if (r is com.kai.keyboard.data.ai.OpenAiCompatibleClient.Result.Fail)
                    errors.add(m.shown + ": " + com.kai.keyboard.data.ai.AiErrors.message(this, r.failure))
                if (done.incrementAndGet() == list.size) runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val bad = errors.toList()
                    if (bad.isEmpty()) {
                        result.setTextColor(Palette.success)
                        result.text = getString(R.string.ai_test_ok, list.size)
                    } else {
                        result.setTextColor(Palette.danger)
                        result.text = getString(R.string.ai_test_failed, bad.joinToString("\n"))
                    }
                }
            }
            aiCalls.add(h)
        }
        pool.shutdown()
    }

    private fun aiFunctionsBlock(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(8)) }
        box.addView(text(getString(R.string.ai_functions), 17f, Palette.text))
        box.addView(text(getString(R.string.ai_functions_help), 13f, Palette.secondary).apply { setPadding(0, dp(4), 0, 0) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lateinit var render: () -> Unit
        render = {
            list.removeAllViews()
            for (a in prefs.aiActions) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(10), 0, dp(10))
                }
                row.addView(text(actionTitle(a), 16.5f, Palette.text), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                val sw = Switch(this).apply {
                    isChecked = a.enabled
                    Palette.tint(this)
                    setOnCheckedChangeListener { _, v ->
                        prefs.saveAiAction(a.copy(enabled = v)); render()
                    }
                }
                row.addView(sw)
                row.setOnClickListener { actionMenu(a) }
                list.addView(row)
                list.addView(View(this).apply { setBackgroundColor(Palette.separator) },
                    LinearLayout.LayoutParams(MATCH_PARENT, 1))
            }
            list.addView(link(getString(R.string.ai_function_add), Palette.accent) { addAction() })
        }
        render()
        box.addView(list)
        box.addView(text(getString(R.string.ai_target_lang_title), 14f, Palette.secondary).apply { setPadding(0, dp(12), 0, 0) })
        box.addView(prefField("English", prefs.aiTargetLang) { prefs.aiTargetLang = it })
        box.addView(text(getString(R.string.ai_target_lang_hint), 13f, Palette.secondary).apply { setPadding(0, dp(4), 0, 0) })
        return box
    }

    private fun actionTitle(a: AiAction): String = a.builtin?.let {
        when (it) {
            Builtin.SPELL -> getString(R.string.ai_fn_spell)
            Builtin.TRANSLATE -> getString(R.string.ai_fn_translate)
            Builtin.EDIT -> getString(R.string.ai_fn_edit)
        }
    } ?: a.title.ifBlank { "-" }

    private fun actionMenu(a: AiAction) {
        val items = ArrayList<String>()
        items.add(getString(if (a.enabled) R.string.ai_action_off else R.string.ai_action_on))
        if (a.custom) items.add(getString(R.string.ai_action_delete))
        items.add(getString(R.string.ai_action_prompt))
        android.app.AlertDialog.Builder(this, Palette.dialog)
            .setTitle(actionTitle(a))
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    getString(R.string.ai_action_on), getString(R.string.ai_action_off) -> {
                        prefs.saveAiAction(a.copy(enabled = !a.enabled)); reopen()
                    }
                    getString(R.string.ai_action_delete) -> {
                        prefs.removeAiAction(a.id); reopen()
                    }
                    else -> promptDialog(a)
                }
            }
            .show()
    }

    private fun promptDialog(a: AiAction) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val vars = listOf(
            AiPrompts.TEXT to getString(R.string.ai_var_text),
            AiPrompts.INSTRUCTION to getString(R.string.ai_var_instruction),
            AiPrompts.TARGET_LANG to getString(R.string.ai_var_target_lang),
        )
        // Промт — в поле с ограниченной высотой (4–8 строк): вместе с принадлежностью модели и
        // настройкой подсветки всё содержимое обязано влезать в прокрутку, иначе нижний пункт не виден.
        val f = field("").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            minLines = 4; maxLines = 8
            gravity = Gravity.TOP
            textSize = 14f
            setText(prefs.aiPrompt(a))
        }
        box.addView(f)
        box.addView(text(getString(R.string.ai_variables), 14f, Palette.secondary).apply {
            setPadding(0, dp(10), 0, 0)
        })
        // Переменные — в один ряд с горизонтальной прокруткой: на узком экране они не уезжают за край.
        val varRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val varScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(varRow)
        }
        for ((value, label) in vars) {
            varRow.addView(text(label, 15f, Palette.accent).apply {
                setPadding(dp(4), dp(4), dp(16), dp(4))
                setOnClickListener {
                    val s = f.selectionStart.coerceAtLeast(0)
                    f.text.replace(s, f.selectionEnd.coerceAtLeast(s), value)
                }
            })
        }
        box.addView(varScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        box.addView(text(getString(R.string.ai_prompt_help), 12.5f, Palette.secondary).apply { setPadding(0, dp(4), 0, dp(6)) })
        // Принадлежность модели — сразу под промтом: это главное, что здесь настраивают, и ниже
        // подсветки она уходила за нижний край прокрутки и её было не найти.
        val picker = modelPicker(a)
        box.addView(picker)
        // Настройка действия — рядом с промтом: она и меняет вид предпросмотра у этого действия.
        // У «Проверить орфографию» подсветка отличий — суть действия: показываем, но не даём выключить.
        // Значение запоминаем и сохраняем вместе с промтом: «Отмена» ничего не меняет.
        var hlValue: Boolean? = a.highlight
        val hl: ViewGroup = switch(getString(R.string.ai_highlight), getString(R.string.ai_highlight_hint), a.showsDiff) { on ->
            hlValue = on
        } as ViewGroup
        if (a.builtin == Builtin.SPELL) {
            // Серым и неизменяемым: видно, что настройка есть и включена всегда. Снимаем и обработчик
            // строки: Switch.toggle() включает свойство в обход isEnabled.
            hl.alpha = 0.45f
            hl.setOnClickListener(null)
            hl.isClickable = false
            for (i in 0 until hl.childCount) if (hl.getChildAt(i) is Switch) (hl.getChildAt(i) as Switch).isEnabled = false
        }
        box.addView(hl)
        // Промт бывает длинным: содержимое в прокрутке ограниченной высоты, иначе кнопки диалога
        // уезжают за край экрана (из-за этого переключатель и не было видно).
        val frame = android.widget.FrameLayout(this)
        frame.addView(ScrollView(this).apply { addView(box) },
            android.widget.FrameLayout.LayoutParams(MATCH_PARENT, minOf(dp(460), resources.displayMetrics.heightPixels * 2 / 3)))
        android.app.AlertDialog.Builder(this, Palette.dialog)
            .setTitle(actionTitle(a))
            .setView(frame)
            .setPositiveButton(getString(R.string.ai_save)) { _, _ ->
                prefs.saveAiAction(a.copy(prompt = f.text.toString().trim(), highlight = hlValue,
                    models = pickedModels(picker)))
                toast(getString(R.string.ai_prompt_saved))
                reopen()
            }
            .setNeutralButton(getString(R.string.ai_prompt_reset)) { _, _ ->
                prefs.saveAiAction(a.copy(prompt = ""))
                reopen()
            }
            .setNegativeButton(getString(R.string.ai_cancel), null)
            .show()
    }

    /**
     * «Принадлежность модели»: каким моделям адресовано действие. Свёрнутая строка (сколько выбрано) —
     * тапом раскрывается список моделей с галочками: отметок может быть и несколько, и ни одной.
     * Ни одной — действие видят все модели (так же читаются настройки прежних версий, где выбора не было).
     * Отмечаем модели по названию, которое видно в форме ИИ ([AiModel.shown]), — по нему же действие
     * и находит свой столбец.
     */
    private fun modelPicker(a: AiAction): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(2)) }
        if (prefs.aiModels.isEmpty()) return box
        var picked = a.models
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val value = text(modelPickTitle(picked), 14f, Palette.accent)
        head.addView(text(getString(R.string.ai_model_owner), 17f, Palette.text),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        head.addView(value)
        head.addView(text("⌄", 16f, Palette.secondary).apply { setPadding(dp(8), 0, 0, 0) })
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = rounded(Palette.surface2, 12)
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        lateinit var render: () -> Unit
        render = {
            value.text = modelPickTitle(picked)
            list.removeAllViews()
            for (m in prefs.aiModels) {
                val on = m.shown in picked
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(10), 0, dp(10))
                }
                row.addView(text(m.shown, 16f, Palette.text), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                row.addView(text(if (on) "✓" else "", 16f, Palette.accent).apply { setPadding(dp(8), 0, 0, 0) })
                row.setOnClickListener {
                    // Пустой выбор — по-прежнему «все модели»: сколько отметок, столько и моделей.
                    picked = if (on) picked - m.shown else picked + m.shown
                    render()
                }
                list.addView(row)
            }
            list.addView(text(getString(R.string.ai_model_none_hint), 12.5f, Palette.secondary)
                .apply { setPadding(0, dp(4), 0, dp(8)) })
        }
        render()
        head.setOnClickListener { list.visibility = if (list.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        box.addView(head)
        box.addView(list)
        box.tag = ModelPick { picked }
        return box
    }

    private class ModelPick(val get: () -> List<String>)

    private fun pickedModels(picker: View): List<String> =
        (picker.tag as? ModelPick)?.get() ?: emptyList()

    private fun modelPickTitle(picked: List<String>): String = when {
        picked.isEmpty() -> getString(R.string.ai_model_all)
        picked.size == 1 -> picked[0]
        else -> getString(R.string.ai_model_several, picked.size)
    }

    /**
     * Удаление модели — с подтверждением: сначала показываем, какие действия на неё ссылаются, потому что
     * после удаления они перестанут быть видны у этой модели. Сами действия не удаляются: из их
     * «принадлежности» название модели уходит, а выбор без отметок снова значит «все модели».
     */
    private fun askDeleteModel(index: Int) {
        val m = models.getOrNull(index) ?: return
        val attached = if (m.ready) prefs.aiEnabledActions.filter { m.shown in it.models } else emptyList()
        val message = buildString {
            append(getString(R.string.ai_model_delete_ask, m.shown))
            if (attached.isEmpty()) append("\n\n").append(getString(R.string.ai_model_delete_free))
            else {
                append("\n\n").append(getString(R.string.ai_model_delete_used))
                for (a in attached) append("\n• ").append(actionTitle(a))
                append("\n\n").append(getString(R.string.ai_model_delete_keep))
            }
        }
        android.app.AlertDialog.Builder(this, Palette.dialog)
            .setTitle(getString(R.string.ai_model_delete_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.ai_delete)) { _, _ ->
                val saved = models.take(index).count { it.ready }
                if (m.ready) prefs.removeAiModel(saved)
                models = models.filterIndexed { i, _ -> i != index }.toMutableList()
                // Список на экране перерисовываем сразу: иначе на месте удалённой модели остаётся строка,
                // которой в настройках уже нет (и «Удалить» на ней выглядит ничего не делающей кнопкой).
                modelsRender?.invoke()
                reopen()
            }
            .setNegativeButton(getString(R.string.ai_cancel), null)
            .show()
    }

    private fun addAction() {
        if (prefs.aiActions.count { it.custom } >= AiAction.MAX_CUSTOM) {
            toast(getString(R.string.ai_functions_max, AiAction.MAX_CUSTOM)); return
        }
        val f = field(getString(R.string.ai_function_name_hint)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = true
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(f)
        }
        android.app.AlertDialog.Builder(this, Palette.dialog)
            .setTitle(getString(R.string.ai_function_add))
            .setView(wrap)
            .setPositiveButton(getString(R.string.ai_save)) { _, _ ->
                val name = f.text.toString().trim()
                if (name.isEmpty()) { toast(getString(R.string.ai_function_need_name)); return@setPositiveButton }
                val id = prefs.addAiAction(System.currentTimeMillis()) ?: return@setPositiveButton
                prefs.saveAiAction(prefs.aiActions.first { it.id == id }.copy(title = name))
                reopen()
            }
            .setNegativeButton(getString(R.string.ai_cancel), null)
            .show()
    }

    private fun reopen() {
        val s = openSection ?: return
        showSection(s)
    }

    private fun aboutForkBlock(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(4)) }
        box.addView(text(getString(R.string.about_fork_title), 17f, Palette.text))
        box.addView(mascotView())
        box.addView(text(getString(R.string.about_fork_text), 14f, Palette.secondary).apply { setPadding(0, dp(12), 0, 0) })
        box.addView(text(getString(R.string.about_fork_data), 14f, Palette.secondary).apply { setPadding(0, dp(10), 0, dp(4)) })
        fun open(url: String) = runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            .onFailure { toast(url) }
        box.addView(actionRow(getString(R.string.about_open_klava), Palette.accent) { open("https://github.com/doroshenkosa/klava") })
        box.addView(actionRow(getString(R.string.about_open_gpl), Palette.accent) { open("https://www.gnu.org/licenses/gpl-3.0.html") })
        return box
    }

    // ================= конструктор интерфейса =================

    private fun rounded(color: Int, r: Int = 16) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.1f)
    }

    private fun secondary(s: String) = text(s, 13f, Palette.secondary)

    /**
     * Поле поиска: лупа нарисована в самом поле (в подписи-подсказке эмодзи выглядит наклейкой
     * из другого приложения), поэтому слева оставлено место.
     */
    private fun searchField(hint: String) = field(hint).apply {
        setPadding(dp(42), dp(14), dp(16), dp(14))
        setCompoundDrawables(drawable(Glyph(context, Glyph.SEARCH, Palette.secondary), dp(20)), null, null, null)
        compoundDrawablePadding = dp(8)
    }

    /** Значок-`View` превращаем в картинку нужного размера — TextView ждёт Drawable. */
    private fun drawable(v: View, size: Int): android.graphics.drawable.Drawable? = runCatching {
        v.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, size, size)
        val b = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        android.graphics.drawable.BitmapDrawable(resources, b).apply { setBounds(0, 0, size, size) }
    }.getOrNull()

    private fun caption(s: String) = text(s, 12f, Palette.secondary).apply { letterSpacing = 0.06f }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Palette.surface)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(20) }
    }

    private fun cardOf(v: View) = card().apply {
        (layoutParams as LinearLayout.LayoutParams).topMargin = dp(14)
        setPadding(dp(16), dp(6), dp(16), dp(6))
        addView(v)
    }

    private fun group(rows: List<View>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Palette.surface)
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        rows.forEachIndexed { i, r ->
            if (i > 0) addView(View(context).apply { setBackgroundColor(Palette.separator) },
                LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { leftMargin = dp(60) })
            addView(r)
        }
    }

    private fun navRow(icon: View, title: String, sub: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(14), dp(14), dp(14))
        background = pressable(Palette.surface, 0f)
        addView(icon, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(14) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 17f, Palette.text))
            addView(text(sub, 13f, Palette.secondary).apply { setPadding(0, dp(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(text("›", 24f, Palette.secondary).apply { setPadding(dp(8), 0, 0, 0) })
        setOnClickListener { onClick() }
    }

    private fun actionRow(title: String, color: Int, onClick: (View) -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(12), 0, dp(12))
        addView(text(title, 17f, color))
        setOnClickListener(onClick)
    }

    private fun primary(s: String, onClick: () -> Unit) = TextView(this).apply {
        text = s; textSize = 16f; setTextColor(Palette.onAccent); typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = pressable(Palette.accent, dp(12).toFloat())
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(50)).apply { topMargin = dp(10) }
        setOnClickListener { onClick() }
    }

    private fun field(hint: String) = EditText(this).apply {
        this.hint = hint
        textSize = 16f; setTextColor(Palette.text); setHintTextColor(Palette.secondary)
        background = rounded(Palette.surface, 14)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        isSaveEnabled = false // вставленное сюда не сохраняется в состоянии экрана
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun switch(label: String, hint: String?, value: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(12) }
            addView(text(label, 17f, Palette.text))
            if (hint != null) addView(text(hint, 13.5f, Palette.secondary).apply { setPadding(0, dp(4), 0, 0) })
        }
        val sw = Switch(this).apply {
            isChecked = value
            Palette.tint(this)
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        row.addView(texts)
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        return row
    }

    private fun deleteTryout(): View {
        val sample = getString(R.string.st_delete_sample)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(4)) }
        box.addView(text(getString(R.string.st_try), 17f, Palette.text).apply { setPadding(0, 0, 0, dp(8)) })
        val f = field("").also { deleteField = it }.apply {
            setText(sample)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background = rounded(Palette.surface2, 12)
            setOnFocusChangeListener { v, has -> if (has) (v as EditText).setSelection(v.text.length) }
        }
        box.addView(f)
        box.addView(text(getString(R.string.st_restore_text), 15f, Palette.accent).apply {
            gravity = Gravity.END
            setPadding(0, dp(10), 0, dp(6))
            setOnClickListener { f.setText(sample); f.setSelection(sample.length) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return box
    }

    private fun themeChooser(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
        box.addView(text(getString(R.string.st_theme), 17f, Palette.text))
        box.addView(text(getString(R.string.st_theme_hint), 13.5f, Palette.secondary)
            .apply { setPadding(0, dp(4), 0, 0) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        val cards = ArrayList<Pair<Theme, View>>()
        fun mark() {
            for ((t, v) in cards) v.background = GradientDrawable().apply {
                setColor(t.bg); cornerRadius = dp(12).toFloat()
                setStroke(dp(2), if (t.id == prefs.theme) prefs.accent else Palette.separator)
            }
        }
        for (t in Theme.ALL) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(8), dp(12), dp(8), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(4); marginEnd = dp(4) }
                addView(object : View(context) {
                    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dp(30))
                    override fun onDraw(c: android.graphics.Canvas) {
                        val kw = width / 4f
                        for (i in 0 until 4) {
                            val l = i * kw + dp(2)
                            if (t.shadow != 0) {
                                p.color = t.shadow
                                c.drawRoundRect(l, dp(3).toFloat(), l + kw - dp(4), height.toFloat(), dp(4).toFloat(), dp(4).toFloat(), p)
                            }
                            p.color = if (i == 3) t.fn else t.key
                            c.drawRoundRect(l, dp(2).toFloat(), l + kw - dp(4), height - dp(1).toFloat(), dp(4).toFloat(), dp(4).toFloat(), p)
                        }
                    }
                })
                addView(TextView(context).apply {
                    text = themeTitle(t); textSize = 13f; setTextColor(t.text); gravity = Gravity.CENTER
                    // На узком экране «Системная» и «Светлая» переносятся на вторую строку и растягивают
                    // только свою карточку. Запас в две строки у всех - карточки одной высоты.
                    minLines = 2; maxLines = 2
                    setPadding(0, dp(6), 0, 0)
                })
                setOnClickListener {
                    val wasLight = Palette.light
                    prefs.theme = t.id; mark()
                    if (t.light != wasLight || t.id == Theme.SYSTEM_ID || prefs.theme == Theme.SYSTEM_ID) recreate()
                }
            }
            cards.add(t to card)
            row.addView(card)
        }
        themeMarker = ::mark
        mark()
        box.addView(row)
        return box
    }

    private fun refreshColors() {
        val id = openSection ?: return
        val y = scroll.scrollY
        body.post {
            showSection(id)
            scroll.post { scroll.scrollTo(0, y) }
        }
    }

    private var themeMarker: (() -> Unit)? = null

    /**
     * Название темы в языке интерфейса. У [Theme] названия хранятся по-русски (ими пользуются тесты
     * контраста и старая клавиатура) — здесь подставляем перевод по id.
     */
    private fun themeTitle(t: Theme): String = when (t.id) {
        Theme.SYSTEM_ID -> getString(R.string.st_theme_system)
        "light" -> getString(R.string.st_theme_light)
        "dark" -> getString(R.string.st_theme_dark)
        else -> getString(R.string.st_theme_black)
    }

    private fun aiKeySideChooser(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
        box.addView(text(getString(R.string.st_ai_pos), 17f, Palette.text))
        box.addView(text(getString(R.string.st_ai_pos_hint), 13.5f, Palette.secondary)
            .apply { setPadding(0, dp(4), 0, 0) })
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(Palette.surface2, 12)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            // Параметры задаём сразу: у ещё не добавленного представления layoutParams - null.
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) }
        }
        val cells = ArrayList<Pair<Boolean, TextView>>()
        fun mark() {
            for ((right, v) in cells) {
                v.setTextColor(if (right == prefs.aiKeyRight) Palette.onAccent else Palette.text)
                v.background = if (right == prefs.aiKeyRight) rounded(Palette.accent, 10) else null
            }
        }
        for ((right, title) in listOf(false to getString(R.string.st_left), true to getString(R.string.st_right))) {
            val cell = text(title, 15.5f, Palette.text).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { prefs.aiKeyRight = right; mark() }
            }
            cells.add(right to cell)
            bar.addView(cell, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        box.addView(bar)
        mark()
        return box
    }

    private fun accentChooser(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
        box.addView(text(getString(R.string.st_accent), 17f, Palette.text))
        val sysNote = if (prefs.theme == Theme.SYSTEM_ID) getString(R.string.st_accent_system_note) else ""
        box.addView(text(getString(R.string.st_accent_hint) + sysNote, 13.5f, Palette.secondary)
            .apply { setPadding(0, dp(4), 0, 0) })
        val swatches = ArrayList<Pair<Int?, View>>()
        val custom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val hsv = FloatArray(3)
        val bars = ArrayList<SeekBar>()
        var syncing = false

        fun mark() {
            val cur = prefs.accent
            val isPreset = cur in Accent.PRESETS
            for ((c, v) in swatches) v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (c != null) setColor(c) else {
                    gradientType = GradientDrawable.SWEEP_GRADIENT
                    colors = intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED)
                }
                val on = if (c == null) !isPreset else c == cur
                setStroke(dp(3), if (on) Palette.text else Color.TRANSPARENT)
            }
            themeMarker?.invoke()
        }
        fun set(c: Int) { prefs.accent = c; Palette.accent = c; mark() }
        fun syncBars() {
            Color.colorToHSV(prefs.accent, hsv)
            syncing = true
            bars.getOrNull(0)?.progress = hsv[0].toInt()
            bars.getOrNull(1)?.progress = (hsv[1] * 100).toInt()
            bars.getOrNull(2)?.progress = (hsv[2] * 100).toInt()
            syncing = false
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, dp(4)) }
        for (c in Accent.PRESETS + listOf<Int?>(null)) {
            val v = View(this).apply {
                setOnClickListener {
                    if (c != null) { set(c); custom.visibility = View.GONE; refreshColors() }
                    else { custom.visibility = if (custom.visibility == View.VISIBLE) View.GONE else View.VISIBLE; syncBars() }
                }
            }
            if (c == null) v.contentDescription = getString(R.string.st_custom_color)
            swatches.add(c to v)
            row.addView(android.widget.FrameLayout(this).apply {
                addView(v, android.widget.FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
            }, LinearLayout.LayoutParams(0, dp(40), 1f))
        }
        box.addView(row)

        for ((i, name) in listOf(getString(R.string.st_hue), getString(R.string.st_saturation),
            getString(R.string.st_brightness)).withIndex()) {
            custom.addView(text(name, 13.5f, Palette.secondary).apply { setPadding(0, dp(10), 0, 0) })
            val bar = SeekBar(this).apply {
                max = if (i == 0) 359 else 100
                Palette.tint(this)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        if (syncing || !fromUser) return
                        when (i) { 0 -> hsv[0] = p.toFloat(); 1 -> hsv[1] = p / 100f; else -> hsv[2] = p / 100f }
                        set(Color.HSVToColor(hsv))
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) { refreshColors() }
                })
            }
            bars.add(bar)
            custom.addView(bar)
        }
        box.addView(custom)
        if (prefs.accent !in Accent.PRESETS) { custom.visibility = View.VISIBLE; syncBars() }
        mark()
        return box
    }

    private fun styleChooser(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
        box.addView(text(getString(R.string.st_style), 17f, Palette.text))
        box.addView(text(getString(R.string.st_style_hint), 13.5f, Palette.secondary).apply { setPadding(0, dp(4), 0, 0) })
        val cards = ArrayList<LinearLayout>()
        fun mark() {
            for ((i, c) in cards.withIndex()) {
                val on = i == prefs.styleIndex
                c.background = GradientDrawable().apply {
                    setColor(Palette.surface2); cornerRadius = dp(14).toFloat()
                    setStroke(dp(2), if (on) Palette.accent else Color.TRANSPARENT)
                }
            }
        }
        for (i in 0..2) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) }
                addView(text(getString(R.string.st_style_n, i + 1), 14f, Palette.text).apply { setPadding(0, 0, 0, dp(8)) })
                addView(StylePreview(context, alt = i >= 1, style3 = i == 2))
                setOnClickListener { prefs.styleIndex = i; mark() }
            }
            cards.add(card)
            box.addView(card)
        }
        mark()
        return box
    }

    private fun slider(min: Int, max: Int, value: Int, hint: String?, title: (Int) -> String,
                       onChange: (Int) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(10)) }
        val label = text(title(value), 17f, Palette.text)
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = value - min
            Palette.tint(this)
            setPadding(dp(4), dp(14), dp(4), dp(6))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    label.text = title(min + p); onChange(min + p)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(label)
        if (hint != null) box.addView(text(hint, 13.5f, Palette.secondary).apply { setPadding(0, dp(4), 0, 0) })
        box.addView(bar)
        return box
    }

    private fun timeSlider(title: String, minMs: Int, maxMs: Int, value: Int, off: Boolean, hint: String,
                           onChange: (Int) -> Unit): View {
        var ms = value
        fun label(v: Int) = "$title: " + when {
            off && v == 0 -> getString(R.string.st_off)
            off -> getString(R.string.st_after, seconds(v))
            else -> seconds(v)
        }
        val shift = if (off) 1 else 0
        fun pos(v: Int) = if (off && v == 0) 0 else shift + ((v.coerceIn(minMs, maxMs) - minMs) + TIME_STEP / 2) / TIME_STEP
        fun msAt(p: Int) = if (off && p == 0) 0 else minMs + (p - shift) * TIME_STEP

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(10)) }
        val lbl = text(label(ms), 17f, Palette.text).apply { layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }
        val bar = SeekBar(this).apply {
            max = shift + (maxMs - minMs) / TIME_STEP
            progress = pos(ms)
            Palette.tint(this)
            setPadding(dp(4), dp(14), dp(4), dp(6))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    // Программную подстановку после ввода числом не округляем обратно к шагу.
                    if (!fromUser) return
                    ms = msAt(p); lbl.text = label(ms); onChange(ms)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        val edit = text(getString(R.string.st_enter_value), 15f, Palette.accent).apply {
            setPadding(dp(12), dp(4), 0, dp(4))
            setOnClickListener {
                askSeconds(title, ms, minMs, maxMs, off) { v ->
                    ms = v; lbl.text = label(v); bar.progress = pos(v); onChange(v)
                }
            }
        }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(lbl); addView(edit)
        })
        box.addView(text(hint, 13.5f, Palette.secondary).apply { setPadding(0, dp(4), 0, 0) })
        box.addView(bar)
        return box
    }

    private fun seconds(ms: Int): String {
        val s = "%.2f".format(java.util.Locale.ROOT, ms / 1000f).trimEnd('0').trimEnd('.', ',')
        return getString(R.string.st_seconds, s)
    }

    // ---- Перенос на другое устройство ----

    private var transferLearned = false
    private var exportPassword: String? = null

    private fun transferBlock(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(12), 0, dp(4))
        addView(text(getString(R.string.st_transfer), 17f, Palette.text))
        addView(text(getString(R.string.st_transfer_hint), 14f, Palette.secondary).apply { setPadding(0, dp(4), 0, dp(4)) })
        addView(switch(getString(R.string.st_transfer_learned), getString(R.string.st_transfer_learned_hint), transferLearned) { transferLearned = it })
        addView(actionRow(getString(R.string.st_export), Palette.accent) { startExport() })
        addView(actionRow(getString(R.string.st_import), Palette.accent) { startImport() })
    }

    private fun startExport() {
        if (!transferLearned) { exportPassword = null; pickExportFile(); return }
        askPassword(getString(R.string.st_password_title), getString(R.string.st_password_hint, Backup.MIN_PASSWORD),
            confirm = true) { exportPassword = it; pickExportFile() }
    }

    private fun pickExportFile() {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, "kai-$day.klava")
        runCatching { startActivityForResult(i, REQ_EXPORT) }.onFailure { toast(getString(R.string.st_no_file_app)) }
    }

    private fun startImport() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        runCatching { startActivityForResult(i, REQ_IMPORT) }.onFailure { toast(getString(R.string.st_no_file_app)) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) { if (requestCode == REQ_EXPORT) exportPassword = null; return }
        when (requestCode) {
            REQ_EXPORT -> doExport(uri)
            REQ_IMPORT -> readImport(uri)
        }
    }

    private fun doExport(uri: android.net.Uri) {
        val password = exportPassword
        exportPassword = null
        val withLearned = password != null
        val settings = prefs.exportSettings()
        val emoji = if (withLearned) prefs.exportEmoji() else emptyList()
        toast(if (withLearned) getString(R.string.st_encrypting) else getString(R.string.st_saving))
        background {
            val result = runCatching {
                val learned = if (withLearned) (Brain.user.exportLines() ?: return@runCatching getString(R.string.st_learned_unavailable)) + emoji else emptyList()
                val bytes = Backup.encode(Backup.Data(settings, learned), password)
                contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
                if (withLearned) getString(R.string.st_saved_learned, learned.count { it.startsWith("w\t") })
                else getString(R.string.st_saved_settings)
            }.getOrElse { getString(R.string.st_save_failed) }
            runOnUiThread { toast(result) }
        }
    }

    private fun readImport(uri: android.net.Uri) {
        background {
            val bytes = runCatching {
                contentResolver.openInputStream(uri)!!.use { s ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(1 shl 16)
                    while (out.size() <= MAX_IMPORT) {
                        val n = s.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    if (out.size() > MAX_IMPORT) null else out.toByteArray()
                }
            }.getOrNull()
            runOnUiThread {
                when {
                    bytes == null -> toast(getString(R.string.st_read_failed))
                    Backup.isEncrypted(bytes) -> askPassword(getString(R.string.st_password_title), null, confirm = false) { decodeImport(bytes, it) }
                    else -> decodeImport(bytes, null)
                }
            }
        }
    }

    private fun decodeImport(bytes: ByteArray, password: String?) {
        if (password != null) toast(getString(R.string.st_decrypting))
        background {
            val r = Backup.decode(bytes, password)
            runOnUiThread {
                when (r) {
                    is Backup.Read.Ok -> applyImport(r.data)
                    Backup.Read.WrongPassword -> toast(getString(R.string.st_wrong_password))
                    Backup.Read.NeedPassword -> askPassword(getString(R.string.st_password_title), null, confirm = false) { decodeImport(bytes, it) }
                    Backup.Read.NotBackup -> toast(getString(R.string.st_not_backup))
                }
            }
        }
    }

    private fun applyImport(d: Backup.Data) {
        // Выключение истории буфера стирает и закреплённое - молча этого при загрузке не делаем:
        // здесь история остаётся включённой, выключить можно самому (с понятным последствием).
        val keepClips = d.settings[Prefs.CLIPS_OFF] == true && prefs.clipHistory
        val n = prefs.importSettings(d.settings) + prefs.importAi(d.settings)
        if (keepClips) toast(getString(R.string.st_clips_kept))
        if (!d.hasLearned) { toast(getString(R.string.st_loaded_settings, n)); recreate(); return }
        prefs.importEmoji(d.learned)
        background {
            val ok = runCatching { Brain.user.importLines(d.learned) }.getOrDefault(false)
            val words = d.learned.count { it.startsWith("w\t") }
            runOnUiThread {
                toast(if (ok) getString(R.string.st_loaded_words, words)
                      else getString(R.string.st_loaded_no_words))
                recreate()
            }
        }
    }

    private fun askPassword(title: String, message: String?, confirm: Boolean, done: (String) -> Unit) {
        fun pw(hint: String) = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            this.hint = hint
            isSingleLine = true
        }
        val first = pw(getString(R.string.st_pw))
        val second = if (confirm) pw(getString(R.string.st_pw_again)) else null
        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(first, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            if (second != null) addView(second, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val dialog = android.app.AlertDialog.Builder(this, Palette.dialog)
            .setTitle(title)
            .apply { if (message != null) setMessage(message) }
            .setView(frame)
            .setPositiveButton(getString(R.string.cf_done), null)
            .setNegativeButton(getString(R.string.cf_cancel), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val p = first.text.toString()
                when {
                    confirm && p.length < Backup.MIN_PASSWORD -> first.error = getString(R.string.st_pw_too_short, Backup.MIN_PASSWORD)
                    !confirm && p.isEmpty() -> first.error = getString(R.string.st_pw_empty)
                    second != null && second.text.toString() != p -> second.error = getString(R.string.st_pw_mismatch)
                    else -> { dialog.dismiss(); done(p) }
                }
            }
        }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        first.requestFocus()
    }

    private fun background(job: () -> Unit) = Thread { runCatching(job) }.start()

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()

    private fun askSeconds(title: String, ms: Int, minMs: Int, maxMs: Int, off: Boolean, done: (Int) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (off && ms == 0) "0" else "%.2f".format(java.util.Locale.ROOT, ms / 1000f).trimEnd('0').trimEnd('.'))
            selectAll()
        }
        val range = getString(R.string.st_range, seconds(minMs), seconds(maxMs)) +
            if (off) getString(R.string.st_range_off) else ""
        val frame = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val dialog = android.app.AlertDialog.Builder(this, Palette.dialog)
            .setTitle(title)
            .setMessage(getString(R.string.st_seconds_title, range))
            .setView(frame)
            .setPositiveButton(getString(R.string.cf_done)) { _, _ ->
                val sec = input.text.toString().replace(',', '.').toFloatOrNull() ?: return@setPositiveButton
                val v = Math.round(sec * 100) * 10 // точность - 10 мс
                done(if (off && v <= 0) 0 else v.coerceIn(minMs, maxMs))
            }
            .setNegativeButton(getString(R.string.cf_cancel), null)
            .create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    private companion object {
        const val MASCOT_RATIO = 1f
        const val TIME_STEP = 50
        const val REQ_EXPORT = 41
        const val REQ_IMPORT = 42
        const val MAX_IMPORT = 32 shl 20
        const val STATE_SECTION = "section"
    }
}
