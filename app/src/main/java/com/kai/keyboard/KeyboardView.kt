package com.kai.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class Shift { OFF, ON, LOCK }

class ClipRow(val id: Long, val text: String, val thumb: android.graphics.Bitmap? = null, val pinned: Boolean = false,
              /** Запись сейфа ждёт подтверждения удаления (второе нажатие на крестик). */
              val warn: Boolean = false)

class EmojiSection(val id: String, val items: List<String>)

/**
 * Короткое сообщение об ошибке — плашкой прямо на клавиатуре. Отдельным объектом: сколько держать,
 * как идёт анимация и не закрывает ли сообщение клавиши — проверяется юнит-тестом без Android.
 *
 * Зачем своя плашка, а не системный Toast: с Android 12 (при targetSdk ≥ 30) система глушит тосты
 * из процесса, который считает фоновым, а у клавиатуры Activity нет — в чужих приложениях (Telegram,
 * Firefox) системный тост там просто не показывается. Своё окно клавиатуры на экране всегда, поэтому
 * сообщение на нём видно одинаково на всех версиях и без единого разрешения.
 */
object ToastSpec {
    private val BOTTOM_ROW = setOf(3, 4, 5)

    @JvmField val APPEAR_MS = 110L
    @JvmField val HOLD_MS = 2450L
    @JvmField val FADE_MS = 180L

    private const val BG_RGB = 0x303030
    private const val BG_ALPHA = 0xE6

    fun bg(fade: Int): Int = (((BG_ALPHA * fade.coerceIn(0, 255)) / 255) shl 24) or BG_RGB

    fun cover(t: Long): Float = 1f - ran(t, APPEAR_MS)

    fun gone(t: Long): Boolean = t >= APPEAR_MS + HOLD_MS + FADE_MS

    fun alpha(t: Long): Int {
        val k = if (gone(t)) 1f else ran(t - APPEAR_MS - HOLD_MS, FADE_MS)
        return ((1f - k) * 255f).toInt().coerceIn(0, 255)
    }

    private fun ran(t: Long, ms: Long): Float = when {
        t <= 0L -> 0f
        t >= ms -> 1f
        else -> {
            val k = t / ms.toFloat()
            k * k * (3f - 2f * k)
        }
    }

    /**
     * Клавиша накрыта сообщением, если её центр попал в плашку. Верх окаймляем краем клавиатуры
     * (выше начинается чужое приложение — туда сообщение не поднимаем).
     */
    fun coversKey(key: Key, plankTop: Float, plankBottom: Float, kbTop: Float): Boolean =
        key.cy >= plankTop - kbTop && key.cy <= plankBottom - kbTop

    fun hidden(keys: List<Key>, kbTop: Float, plankTop: Float, plankH: Float): List<Key> =
        keys.filter { coversKey(it, plankTop, plankTop + plankH, kbTop) }

    fun bottomRow(keys: List<Key>): List<Key> = keys.filter { it.row in BOTTOM_ROW }

    fun otherRows(keys: List<Key>): List<Key> = keys.filter { it.row !in BOTTOM_ROW }
}

@SuppressLint("ViewConstructor")
class KeyboardView(ctx: Context, private val listener: Listener) : View(ctx) {

    interface Listener {
        /** @param tap где и когда нажали букву — для исправления порядка при быстром наборе двумя пальцами */
        fun onKey(key: Key, dx: Float, dy: Float, tap: Reorder.Tap?)
        fun onText(text: String)
        /** @param repeat номер повтора при удержании (0 — само нажатие), words — стереть целое слово */
        fun onDelete(repeat: Int, words: Boolean)
        fun onShift()
        fun onCursor(steps: Int)
        fun onSuggestion(cand: Cand)
        fun onGlobeLongPress()
        fun onEmoji(e: String)
        fun onFeedback(key: Key?)
        /** @param times время каждой точки пути, мс от начала жеста */
        /** Свайп ещё идёт: путь до сих пор (не чаще SWIPE_PREVIEW_MS) — для подсказки «что сейчас выходит». */
        fun onSwipeProgress(pts: FloatArray, times: FloatArray, centers: Map<Char, FloatArray>, aspect: Float)
        /** @param aspect высота ряда / ширина клавиши */
        fun onSwipe(pts: FloatArray, times: FloatArray, centers: Map<Char, FloatArray>, aspect: Float)
        fun onOneHand(mode: Int)
        fun onLangSwipe()
        /** Нажатие на запись истории буфера обмена (delete — на крестик). */
        /** @param action CLIP_PASTE, CLIP_DELETE или CLIP_PIN (закрепить/открепить) */
        fun onClip(index: Int, action: Int)
        fun onSuggestionLongPress(cand: Cand)
        /** Открыть (true) или закрыть поиск эмодзи. */
        fun onEmojiSearch(open: Boolean)
        /** Строку поиска очистили крестиком. */
        fun onEmojiSearchClear()
        /** Шапка ПИНа сейфа: SAFE_BACK — назад к буферу, SAFE_BIO — открыть отпечатком. */
        fun onSafeHeader(action: Int)
        /** Строка новой записи сейфа: DRAFT_CANCEL или DRAFT_SAVE. */
        fun onSafeDraft(action: Int)
        /** Нажата кнопка «ИИ» в левом углу полосы подсказок (видна только при aiMode). */
        fun onAiClick()
        /** В форме ИИ выбрано действие: [tab] и [col] — модель (по две на вкладку), [row] — действие. */
        fun onAiAction(tab: Int, col: Int, row: Int)
        /**
         * В форме ИИ удержали название модели: [col] — показать только этот столбец вкладки [tab],
         * -1 — вернуть обратно все столбцы. Выбор владельца сохраняется.
         */
        fun onAiSolo(tab: Int, col: Int)
        /** Боковые шорткаты формы (режим одного столбца) изменились: [right] — правая сторона, [ids] — id из [AiShortcut]. */
        fun onAiSideChanged(right: Boolean, ids: List<String>)
        /** Нажат боковой шорткат (буфер, сейф, настройки, смайлики); листание модели view делает сам. */
        fun onAiShortcut(id: String)
    }

    // ---------- состояние, которое задаёт сервис ----------
    var layout: KbLayout = Layouts.letters(Langs.RU)
        set(v) {
            val top = topH
            val wasAi = field.mode == Mode.AI
            val modeChanged = v.mode != field.mode
            field = v
            if (modeChanged) {
                // Пальцы на панели, на полосе подсказок и свайп относятся к старому режиму:
                // отпустит — ничего не делаем, долгое нажатие не сработает. Клавиши (⌫, буква) — живут.
                cancelTones()
                cancelLong()
                cancelClipPreview()
                if (field.mode == Mode.AI || v.mode == Mode.AI) {
                    aiScroller.forceFinished(true)
                    resetAiScroll()
                    cancelAiHold()
                    aiPopupSide = -1
                    aiOffset = 0f; aiSwitchTo = null
                }
                ptrs.removeAll { it.key == null || it.swiping }
            }
            // У формы ИИ своя высота: места под системную панель она занимает больше — иначе окно
            // станет ниже и форма уедет вниз, оставив над собой пустую полосу (см. bottomInset).
            if (topH != top || wasAi != (v.mode == Mode.AI)) requestLayout()
            layoutKeys(); invalidate()
        }
    var lang: Lang = Langs.RU
    var shift = Shift.OFF
        set(v) { field = v; invalidate() }
    var suggestions: Suggestions? = null
        set(v) { field = v; invalidate() }
    var swipePreview: String? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    val swipeActive get() = ptrs.any { it.swiping }
    var enterLabel: String? = null
    var enterAccent = false
    var letterProbs: FloatArray? = null
    /** Насколько модель уверена в следующей букве (0 — новое слово, 1 — уверена). */
    var letterConfidence = 1f
    /** Режим одной рукой: 0 — выкл, 1 — у левого края, 2 — у правого. */
    var oneHand = 0
        set(v) { if (field != v) { field = v; layoutKeys(); invalidate() } }
    private val sideRect = RectF()
    var showZones = false
    var showPreview = true
    var heightScale = 1f
        set(v) { if (field != v) { field = v; requestLayout() } }
    var emojiSections: List<EmojiSection> = emptyList()
        set(v) { field = v; gridCols = 0; emojiScroll = emojiScroll.coerceIn(0f, maxEmojiScroll()); invalidate() }
    var emojiTones: (String) -> List<String> = { emptyList() }
    var searchQuery = ""
        set(v) { field = v; invalidate() }
    var searchResults: List<String> = emptyList()
        set(v) { field = v; resultsScroll = 0f; invalidate() }
    var searchHint = "Поиск эмодзи"
    var clipList: List<ClipRow> = emptyList()
        set(v) { field = v; invalidate() }

    // ---------- форма ИИ ----------
    /** Вкладка формы: модели этой вкладки (по две) — их названия в заголовках столбцов; "" — столбец пуст. */
    class AiTab(cols: List<String>) {
        val cols: List<String> = cols
        constructor(cols: Array<String>) : this(cols.toList())
    }
    var aiTabs: List<AiTab> = emptyList()
        set(v) {
            field = v
            aiFlatCache = null
            aiTab = aiTab.coerceIn(0, maxOf(0, v.size - 1))
            aiSync()
            resetAiScroll()
            aiOffset = 0f
            invalidate()
        }
    private var aiFlatCache: List<Pair<Int, Int>>? = null
    /** Номер модели в сквозном списке всех моделей (режим одного столбца листает по нему). */
    private var aiPos = 0
    /** Боковые шорткаты: слева и справа, id из [AiShortcut]. Видны только в режиме одного столбца. */
    var aiSideLeft: List<String> = emptyList()
        set(v) { field = v; invalidate() }
    var aiSideRight: List<String> = emptyList()
        set(v) { field = v; invalidate() }
    /** Какая сторона показывает окно выбора шортката (-1 — никакая). */
    private var aiPopupSide = -1
    var aiActions: List<String> = emptyList()
        set(v) { field = v; resetAiScroll(); invalidate() }
    /**
     * Какие действия стоят в каждом столбце: вкладка → столбец → номера строк в [aiActions].
     * У модели со своим набором действий столбец свой; у модели без действий столбец пустой,
     * но место в паре остаётся за ней (модели не съезжают).
     */
    var aiColActions: List<List<IntArray>> = emptyList()
        set(v) { field = v; resetAiScroll(); invalidate() }
    /**
     * Показать только один столбец (владелец удержал название модели): по значению на вкладку,
     * -1 — показывать все. Один столбец встаёт по центру, второй убирается.
     */
    var aiSoloCols: List<Int> = emptyList()
        set(v) { field = v; aiSync(); resetAiScroll(); invalidate() }
    var aiTab = 0
        set(v) {
            val c = if (aiTabs.isEmpty()) 0 else v.coerceIn(0, aiTabs.size - 1)
            if (field != c) { field = c; invalidate() }
        }
    var aiEmptyText = ""
        set(v) { field = v; invalidate() }
    /** Сдвиг панели при свайпе между вкладками: 0 — текущая по центру, ±width — соседняя. */
    private var aiOffset = 0f
    /** Вкладка, на которую доезжаем после свайпа (null — едем обратно на текущую). */
    private var aiSwitchTo: Int? = null
    private val aiScroller = OverScroller(ctx)
    /** Прокрутка — своя у каждого столбца формы: [0] — левый, [1] — правый. */
    private val aiScroll = FloatArray(AI_COLS)
    /** Инерция после отпускания — тоже своя у каждого столбца. */
    private val aiFling = Array(AI_COLS) { OverScroller(ctx) }
    private var aiHoldRun: Runnable? = null
    var swipeEnabled = true
    /**
     * Включён ИИ и в этом поле он разрешён: слева в полосе подсказок вместо кнопки поиска эмодзи — надпись «ИИ».
     * Сами эмодзи (панель 😊 на нижнем ряду) не меняются.
     */
    var aiMode = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /**
     * Кнопка ИИ/AI (или 😊, когда ИИ выключен) у правого края полосы подсказок, а не у левого.
     * В режиме одной рукой эта настройка не действует: кнопка идёт за клавишами.
     */
    var aiKeyRight = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Язык — свайпом по пробелу; курсор тогда — удержать пробел и вести. */
    var langSwipe = false
    /** Через сколько мс удержания ⌫ стирать словами, 0 — только буквами. */
    var deleteWordsAfterMs = 1000
    var deleteWordPauseMs = 500

    // ---------- метрики ----------
    private val dm = resources.displayMetrics
    private fun dp(v: Float) = v * dm.density
    private fun spx(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, dm)

    var showStrip = true
        set(v) { if (field != v) { field = v; requestLayout(); invalidate() } }
    private val stripH get() = if (showStrip) dp(44f) else 0f
    /**
     * Над клавишами: полоса подсказок. У эмодзи — одна и та же шапка в обоих состояниях: строка поиска
     * и лента под ней (вкладки разделов или найденное), — меняется только то, что ниже: сетка или буквы.
     */
    private val tabH get() = dp(44f)
    private val topH get() = when (layout.mode) {
        Mode.EMOJI, Mode.EMOJI_SEARCH -> tabH * 2
        Mode.SAFE_PIN, Mode.CLIPBOARD, Mode.SAFE, Mode.AI -> tabH
        else -> if (draftShown()) tabH else stripH
    }

    /**
     * Новая запись сейфа набирается (null — нет): её текст — в строке над клавишами вместо подсказок,
     * слева ×, справа ✓.
     */
    var draftBar: String? = null
        set(v) { val was = field != null; field = v; if (was != (v != null)) requestLayout(); invalidate() }

    /** Подсказка пустой строки набора (null — как у записи сейфа): для инструкции ИИ — свой текст. */
    var draftHint: String? = null

    private fun draftShown() = draftBar != null && layout.mode != Mode.CLIPBOARD && layout.mode != Mode.SAFE &&
        layout.mode != Mode.EMOJI && layout.mode != Mode.EMOJI_SEARCH && layout.mode != Mode.AI
    private val rowH get() = dp(54f) * heightScale * orientScale()
    private val padX = dp(3f)
    /** Промежуток между клавишами по горизонтали, dp: меньше — сами клавиши шире (зоны касания те же). */
    var keyGapDp = 6f
        set(v) { if (field != v) { field = v; layoutKeys(); invalidate() } }
    private val gapX get() = dp(keyGapDp)
    /** Ряд цифр над буквами: клавиатура выше на него во всех режимах — без прыжков при смене раскладки. */
    var numberRow = false
        set(v) { if (field != v) { field = v; requestLayout(); invalidate() } }
    private val numH get() = if (numberRow) dp(42f) * heightScale * orientScale() else 0f
    private val gapY get() = dp(11f) * heightScale.coerceAtMost(1f)
    private val bottomPad = dp(4f)
    private var navInset = 0
    /**
     * Высота системной панели, которую занимают её кнопки (3 кнопки). При жестовой навигации
     * кнопок нет — 0, и полосу под клавиатурой можно отдать содержимому.
     */
    private var navTap = 0
    /**
     * Высота полосы с кнопкой выбора клавиатуры (caption bar) над системной панелью.
     * На форме ИИ мы её скрываем (см. KlavaIME.applyCaptionBar) — система тогда отдаёт её место
     * окну клавиатуры, и без неё окно становится ниже на эту высоту: форма «съезжала» вниз.
     * Читаем её из инсетов ещё и скрытой (`getInsetsIgnoringVisibility`) — так её отдаёт сам
     * InputMethodService (InsetsSource ID_IME_CAPTION_BAR, bottom-инсет), и форма может
     * растянуться ровно на неё.
     */
    private var captionInset = 0
    private var pitchX = 1f

    // ---------- цвета: тема и акцент ----------
    var theme: Theme = Theme.BLACK
        set(v) { field = v; invalidate() }
    var accent: Int = Accent.DEFAULT
        set(v) { field = v; trailPaint.color = v; trailPaint.alpha = 200; invalidate() }
    private val cBg get() = theme.bg
    private val cKey get() = theme.key
    private val cFn get() = theme.fn
    private val cPressed get() = theme.pressed
    private val cText get() = theme.text
    private val cTextDim get() = theme.textDim
    private val cAccent get() = accent
    private val cOnAccent get() = Accent.onAccent(accent)
    private val cBubble get() = theme.bubble
    private val cDanger = Color.rgb(0xFF, 0x45, 0x3A)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.6f); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(6f); strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND; color = cAccent; alpha = 200
    }
    private val rect = RectF()
    private val path = Path()
    private val radius = dp(6f)

    // ---------- касания ----------
    private class Ptr(val id: Int, var key: Key?, val downX: Float, val downY: Float) {
        var dx = 0f; var dy = 0f
        var longPressed = false
        var popupIndex = 0
        var popupLeft = 0f
        /** Всплывашка у правого края — в обратном порядке: первый вариант всё равно под пальцем. */
        var popupMirror = false
        /** Палец съехал на другую клавишу — время нажатия уже не про эту букву (для Reorder). */
        var slid = false
        var cursorMode = false
        var cursorAnchor = 0f
        /** Время нажатия — из самого события касания (а не когда до него дошла очередь). */
        var downAt = SystemClock.uptimeMillis()
        var flick = false
        var wordsDeleted = 0
        var cursorMoved = false
        var stripSlot = -1
        var emoji = false
        var emojiScrolling = false
        var lastY = 0f
        var lastX = 0f
        /** Вкладка эмодзи под пальцем (0 — поиск). */
        var tab = -1
        /** Форма ИИ: нажатие в верхней строке — кнопка «ИИ» (head = -2) или номер вкладки. */
        var aiHead = -1
        var aiCol = -1
        var aiPanel = false
        var aiScrolled = false
        var aiSwiped = false
        /** Заголовок столбца под пальцем (для удержания) и сработавшее удержание. */
        var aiHoldCol = -1
        var aiHeld = false
        /** Боковая кнопка под пальцем: сторона (0 — левая, 1 — правая) и номер в стопке. */
        var aiSide = -1
        var aiSideIdx = -1
        var aiPop = false
        var searchBar = false
        var results = false
        var pressedEmoji: String? = null
        var downScroll = 0f
        var tonesTimer: Runnable? = null
        var stripEmoji = false
        var tones: List<String> = emptyList()
        var repeat = 0
        /** Удержание записи буфера или сейфа уже открыло предпросмотр — отпускание не вставляет. */
        var clipHeld = false
        var done = false
        var swiping = false
        var progressAt = 0L
        val trail = ArrayList<Float>()
        /** Время каждой точки следа (мс, время события касания): где палец тормозил — там буквы. */
        val trailT = ArrayList<Long>()
    }

    private val ptrs = ArrayList<Ptr>()
    private val handler = Handler(Looper.getMainLooper())
    private var pendingLong: Runnable? = null
    private var pendingRepeat: Runnable? = null

    private var toastText = ""
    private var toastAt = 0L
    private val toastEnd = Runnable { finishToast() }

    fun showToast(s: String) {
        toastText = s
        toastAt = SystemClock.uptimeMillis()
        handler.removeCallbacks(toastEnd)
        handler.postDelayed(toastEnd, ToastSpec.APPEAR_MS + ToastSpec.HOLD_MS + ToastSpec.FADE_MS + 30L)
        invalidate()
    }

    private fun finishToast() {
        if (toastText.isEmpty()) return
        toastText = ""
        invalidate()
    }

    fun hideToast() {
        handler.removeCallbacks(toastEnd)
        finishToast()
    }

    private fun maybeTickToast() {
        if (toastText.isNotEmpty()) postInvalidateOnAnimation()
    }

    // ---------- эмодзи ----------
    private val scroller = OverScroller(ctx)
    private var emojiScroll = 0f
    private var velocity: android.view.VelocityTracker? = null

    init {
        isHapticFeedbackEnabled = true
    }

    // ================= геометрия =================

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val tap = if (Build.VERSION.SDK_INT >= 30)
            insets.getInsets(WindowInsets.Type.tappableElement()).bottom else 0
        val nav = if (Build.VERSION.SDK_INT >= 30) {
            maxOf(insets.getInsets(WindowInsets.Type.navigationBars()).bottom, tap)
        } else 0
        // Полоса с кнопкой выбора клавиатуры. Скрытая, она всё равно известна по инсетам
        // (getInsetsIgnoringVisibility — тот же способ, что и в самом InputMethodService).
        // Вторая оценка — разница полосы системных панелей и полосы навигации: если первая
        // считает полосу выбора клавиатуры, она здесь вылезет. Берём большее.
        val caption = if (Build.VERSION.SDK_INT >= 30) {
            val bar = insets.getInsetsIgnoringVisibility(WindowInsets.Type.captionBar()).bottom
            val sys = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()).bottom
            val navOnly = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom
            maxOf(bar, sys - navOnly, 0)
        } else 0
        if (nav != navInset || tap != navTap || caption != captionInset) {
            navInset = nav
            navTap = tap
            captionInset = caption
            requestLayout()
            invalidate()
        }
        return insets
    }

    /**
     * Сколько места под содержимым занимает системная панель. У клавиатуры её закрывают её же ряды.
     *
     * У формы ИИ — своё. Раньше под ней оставалась пустая полоса высотой в системную панель,
     * теперь форму ведём до самого низа окна: там же лежит и полоса с кнопкой выбора клавиатуры,
     * которую форма ИИ скрывает (KlavaIME.applyCaptionBar). Если её место не занять, окно
     * окажется ниже на эту высоту и форма съедет вниз, оставив над собой пустоту, — поэтому
     * возвращаем высоту панели (инсетом, а если система о ней молчит — по ресурсу системы).
     */
    private fun bottomInset(): Int {
        if (layout.mode != Mode.AI) return navInset
        return maxOf(navInset, captionInset, captionFallback()).coerceAtMost(dp(CAPTION_MAX_DP).toInt())
    }

    /**
     * Высота полосы с кнопкой выбора клавиатуры по системным ресурсам — если инсеты о ней молчат
     * (система не отдаёт её клавиатуре). Так её измеряет и сам InputMethodService.
     */
    private fun captionFallback(): Int {
        if (navInset > 0 || captionInset > 0) return 0
        val r = resources
        for (name in CAPTION_DIMENS) {
            val id = r.getIdentifier(name, "dimen", "android")
            if (id != 0) runCatching { r.getDimensionPixelSize(id) }.getOrNull()?.let { if (it > 0) return it }
        }
        return 0
    }

    /**
     * Клавиатуру открыли, когда приложение KAI уже закрыто: окно меряется раньше, чем приходят инсеты,
     * navInset ещё 0 — и нижний ряд уезжал под кнопки системной панели. Берём инсеты окна сами.
     */
    private fun syncInsets() {
        if (Build.VERSION.SDK_INT < 30) return
        val ins = rootWindowInsets ?: return
        val tap = ins.getInsets(WindowInsets.Type.tappableElement()).bottom
        val nav = maxOf(ins.getInsets(WindowInsets.Type.navigationBars()).bottom, tap)
        if (nav > navInset) navInset = nav
        if (tap > navTap) navTap = tap
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
        syncInsets()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
        super.onConfigurationChanged(newConfig)
        // Повернули экран: высота рядов считается от ориентации — перемеряемся.
        requestLayout(); layoutKeys(); invalidate()
    }

    /**
     * В горизонтальной ориентации экран низкий: клавиатура обычной высоты закрывала почти всё.
     * Ряды сжимаем так, чтобы клавиатура занимала не больше половины высоты экрана.
     */
    private fun orientScale(): Float {
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels
        if (sw <= sh) return 1f
        val rows = dp(54f) * heightScale * 4 + (if (numberRow) dp(42f) * heightScale else 0f)
        val fixed = topH + bottomPad
        val target = sh * LANDSCAPE_MAX - fixed
        return if (rows <= 0f) 1f else (target / rows).coerceIn(0.5f, 1f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        syncInsets()
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (topH + numH + rowH * 4 + bottomPad).toInt() + bottomInset()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutKeys()
        if (panel()) emojiScroll = emojiScroll.coerceIn(0f, maxEmojiScroll())
        resultsScroll = resultsScroll.coerceIn(0f, maxResultsScroll())
    }

    private fun layoutKeys() {
        val w = width.toFloat()
        if (w <= 0f) return
        val rows = layout.rows
        val hand = activeHand()
        val kbW = if (hand == 0) w else w * ONE_HAND_WIDTH
        val x0 = if (hand == 2) w - kbW else 0f
        val top = topH
        val keysBottom = top + numH + rowH * 4
        for ((r, row) in rows.withIndex()) for (k in row) k.row = r
        if (hand == 1) sideRect.set(kbW, top, w, keysBottom)
        else if (hand == 2) sideRect.set(0f, top, x0, keysBottom)
        else sideRect.setEmpty()
        pitchX = KeyGeometry.place(layout, x0, kbW, height.toFloat(), top, rowH, padX, gapX, gapY, numH)
    }

    private fun panel() = layout.mode == Mode.EMOJI || layout.mode == Mode.CLIPBOARD || layout.mode == Mode.SAFE
    private fun activeHand() = if (panel()) 0 else oneHand

    // ================= выбор клавиши =================

    private fun plainHit(x: Float, y: Float): Key? = TouchModel.plainHit(layout, x, y, topH)

    private fun resolve(x: Float, y: Float): Key? =
        TouchModel.resolve(layout, lang, x, y, topH, letterProbs, letterConfidence, pitchX, rowH, gapX) {
            Brain.user.offset(it)
        }

    // ================= касания =================

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // Предпросмотр держит жест: второй палец не должен вставить другую запись или напечатать букву.
                if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN && clipPreview != null) return true
                val i = e.actionIndex
                // Новый жест — значит, все прежние пальцы уже подняты: если UP потерялся, не тащим их дальше.
                if (e.actionMasked == MotionEvent.ACTION_DOWN && ptrs.isNotEmpty()) {
                    cancelTimers(); cancelTones(); cancelClipPreview(); ptrs.clear()
                }
                // Быстрая печать двумя пальцами: предыдущая буква фиксируется сразу.
                for (p in ptrs.toList()) {
                    val k = p.key
                    // Пробел тоже: иначе при быстрой печати он встанет после следующей буквы.
                    if (!p.longPressed && !p.cursorMode && !p.flick && !p.swiping && k != null &&
                        (k.kind == KeyKind.LETTER || k.kind == KeyKind.CHAR || k.kind == KeyKind.SPACE)) {
                        commit(p); ptrs.remove(p)
                    }
                }
                down(e.getPointerId(i), e.getX(i), e.getY(i))
                ptrs.lastOrNull()?.let { if (it.id == e.getPointerId(i)) it.downAt = e.eventTime }
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    velocity?.recycle(); velocity = android.view.VelocityTracker.obtain()
                }
                velocity?.addMovement(e)
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                // Для свайпа — и промежуточные точки, которые система собрала между кадрами (с их временем).
                for (i in 0 until e.pointerCount) {
                    val p = ptrs.firstOrNull { it.id == e.getPointerId(i) } ?: continue
                    if (p.swiping) for (h in 0 until e.historySize)
                        addTrail(p, e.getHistoricalX(i, h), e.getHistoricalY(i, h), e.getHistoricalEventTime(h))
                }
                for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i), e.eventTime)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                velocity?.addMovement(e)
                val i = e.actionIndex
                up(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelTimers(); cancelTones(); cancelClipPreview(); ptrs.clear(); swipePreview = null; invalidate()
            }
        }
        return true
    }

    private fun down(id: Int, x: Float, y: Float) {
        if ((layout.mode == Mode.EMOJI || layout.mode == Mode.EMOJI_SEARCH) && y < topH) {
            val p = Ptr(id, null, x, y)
            when {
                y < tabH -> p.searchBar = true
                layout.mode == Mode.EMOJI -> p.tab = tabAt(x)
                else -> { p.results = true; p.lastX = x }
            }
            ptrs.add(p); invalidate(); return
        }
        if ((layout.mode == Mode.SAFE_PIN || layout.mode == Mode.CLIPBOARD || layout.mode == Mode.SAFE) && y < topH) {
            val p = Ptr(id, null, x, y)
            p.searchBar = true
            ptrs.add(p); invalidate(); return
        }
        if (layout.mode == Mode.AI) {
            if (!sideRect.isEmpty && sideRect.contains(x, y)) {
                // Одна рукой в форме ИИ: столбцы стоят по ту же сторону, а пустое место — та же панель.
                listener.onFeedback(null)
                oneHand = if (y < sideRect.centerY()) 3 - oneHand else 0
                listener.onOneHand(oneHand)
                return
            }
            val p = Ptr(id, null, x, y)
            if (y < tabH) {
                // верхняя строка: кнопка «ИИ» (вернуться к буквам) или точка-вкладка
                p.aiHead = aiDotAt(x)
            } else if (aiSideOn() && (aiPopupSide >= 0 || sideItemAt(x, y) != null)) {
                p.aiHead = -3
                if (aiPopupSide >= 0) p.aiPop = true
                else {
                    val hit = sideItemAt(x, y)!!
                    p.aiSide = hit.first; p.aiSideIdx = hit.second
                    // Удержание шортката убирает его с формы (у «+» удерживать нечего).
                    if (hit.second < sideShown(hit.first).size) scheduleSideRemove(p)
                }
            } else {
                p.aiPanel = true
                val c = aiCellAt(x, y)
                p.aiCol = c?.first ?: -1
                p.lastY = y
                p.downScroll = aiScrollOf(p.aiCol)
                // Название модели: удержание оставляет один столбец, пока кнопки на него не наехали.
                if (c != null && c.second < 0 && c.second != -2) { p.aiHoldCol = c.first; scheduleAiHold(p) }
            }
            ptrs.add(p); invalidate(); return
        }
        if (draftShown() && y < topH) {
            val p = Ptr(id, null, x, y)
            p.searchBar = true
            ptrs.add(p); invalidate(); return
        }
        if (y < stripH && atStripIcon(x)) {
            val p = Ptr(id, null, x, y)
            p.stripEmoji = true
            ptrs.add(p); invalidate(); return
        }
        if (y < stripH) {
            val p = Ptr(id, null, x, y)
            p.stripSlot = slotAt(x)
            ptrs.add(p); invalidate()
            // Долгое нажатие на подсказку — предложить забыть слово.
            val cand = candidateAt(p.stripSlot)
            if (cand != null && cand.kind != Cand.FORGET && cand.kind != Cand.TYPED && cand.kind != Cand.CLIP &&
                cand.kind != Cand.EMOJI) {
                cancelLong()
                val r = Runnable {
                    if (!p.done && ptrs.contains(p)) {
                        p.done = true
                        listener.onFeedback(null)
                        listener.onSuggestionLongPress(cand)
                        invalidate()
                    }
                }
                pendingLong = r
                handler.postDelayed(r, 450L)
            }
            return
        }
        if (!sideRect.isEmpty && sideRect.contains(x, y)) {
            // Боковая панель режима одной рукой: сверху — на другую сторону, снизу — выйти.
            listener.onFeedback(null)
            oneHand = if (y < sideRect.centerY()) 3 - oneHand else 0
            listener.onOneHand(oneHand)
            return
        }
        // Низ клавиатуры как у emojiBottom: ряда может не быть (форма ИИ) — тогда вся высота.
        val bottom = layout.rows.firstOrNull()?.firstOrNull()?.y ?: height.toFloat()
        if (panel() && y < bottom) {
            scroller.forceFinished(true)
            val p = Ptr(id, null, x, y)
            p.emoji = true; p.lastY = y; p.downScroll = emojiScroll
            if (layout.mode == Mode.EMOJI) p.pressedEmoji = emojiAt(x, y)
            ptrs.add(p)
            if (layout.mode == Mode.EMOJI) scheduleTones(p)
            else if (x < width - clipBtnW) scheduleClipPreview(p)
            return
        }
        val key = resolve(x, y) ?: return
        val p = Ptr(id, key, x, y)
        p.dx = (x - key.cx) / pitchX
        p.dy = (y - key.cy) / rowH
        ptrs.add(p)
        listener.onFeedback(key)
        when (key.code) {
            Code.DELETE -> {
                listener.onDelete(0, false)
                // Порог слов короче задержки автоповтора — первым сработает сразу слово.
                scheduleRepeat(p, if (deleteWordsAfterMs > 0) minOf(REPEAT_DELAY_MS, deleteWordsAfterMs.toLong()) else REPEAT_DELAY_MS)
            }
            Code.SHIFT -> listener.onShift()
            else -> if (key.popup.isNotEmpty()) scheduleLong(p)
        }
        invalidate()
    }

    private fun addTrail(p: Ptr, x: Float, y: Float, t: Long) {
        val n = p.trail.size
        // Точки ещё нет (след только начали) — сравнивать не с чем, просто запоминаем точку.
        if (n < 2 || hypot(x - p.trail[n - 2], y - p.trail[n - 1]) > dp(3f)) {
            p.trail.add(x); p.trail.add(y); p.trailT.add(t)
        }
    }

    private fun move(id: Int, x: Float, y: Float, t: Long = SystemClock.uptimeMillis()) {
        val p = ptrs.firstOrNull { it.id == id } ?: return
        if (p.aiPanel) {
            // Горизонтальное движение — свайп между вкладками, вертикальное — прокрутка списка действий.
            val dx = x - p.downX
            val dy = y - p.downY
            if (!p.aiSwiped && !p.aiScrolled && (abs(dx) > dp(6f) || abs(dy) > dp(6f))) cancelAiHold()
            val cur = aiCurIdx()
            val count = if (aiSoloOn()) aiFlat().size else aiTabs.size
            if (!p.aiSwiped && !p.aiScrolled && abs(dx) > dp(16f) && abs(dx) > abs(dy) && count > 1) {
                p.aiSwiped = true
                aiSwitchTo = if (dx < 0f) (cur + 1).coerceAtMost(count - 1) else (cur - 1).coerceAtLeast(0)
            } else if (!p.aiScrolled && !p.aiSwiped && abs(dy) > dp(8f) && abs(dy) > abs(dx)) {
                p.aiScrolled = true
                cancelAiHold()
                cancelAiSlide()
            }
            if (p.aiSwiped) {
                val to = aiSwitchTo
                aiOffset = when {
                    to == null -> rubberAi(dx)
                    to > cur -> dx.coerceIn(-width.toFloat(), 0f)
                    else -> dx.coerceIn(0f, width.toFloat())
                }
                invalidate(); return
            }
            if (p.aiScrolled) {
                // Прокрутка — у столбца, в котором началось движение: соседний не едет.
                val col = p.aiCol
                if (col in 0 until AI_COLS) {
                    aiScroll[col] = (p.downScroll - dy).coerceIn(0f, aiMaxScroll())
                    invalidate()
                }
            }
            return
        }
        if (p.results) {
            if (!p.emojiScrolling && abs(x - p.downX) > dp(8f)) p.emojiScrolling = true
            if (p.emojiScrolling) {
                resultsScroll = (resultsScroll - (x - p.lastX)).coerceIn(0f, maxResultsScroll())
                p.lastX = x; invalidate()
            }
            return
        }
        if (p.emoji && p.tones.isNotEmpty()) {
            p.popupIndex = ((x - p.popupLeft) / tonesCellW()).toInt().coerceIn(0, p.tones.size - 1)
            invalidate(); return
        }
        if (p.emoji) {
            if (clipPreview != null) {
                // Держите запись — ведите вверх-вниз, чтобы прочитать то, что не влезло.
                clipPreviewScroll = (clipPreviewScroll - (y - p.lastY)).coerceIn(0f, clipPreviewMax())
                p.lastY = y; invalidate(); return
            }
            if (!p.emojiScrolling && abs(y - p.downY) > dp(8f)) {
                p.emojiScrolling = true; cancelTones(p)
                cancelClipPreview()
            }
            if (p.emojiScrolling) {
                emojiScroll = (emojiScroll - (y - p.lastY)).coerceIn(0f, maxEmojiScroll())
                p.lastY = y; invalidate()
            }
            return
        }
        val k = p.key ?: return
        if (p.longPressed) {
            val n = k.popup.size
            val cw = popupCellW(k)
            val pos = ((x - p.popupLeft) / cw).toInt().coerceIn(0, n - 1)
            p.popupIndex = if (p.popupMirror) n - 1 - pos else pos
            invalidate(); return
        }
        if (swipeEnabled && k.kind == KeyKind.LETTER && layout.mode == Mode.LETTERS) {
            if (!p.swiping && ptrs.size == 1 && hypot(x - p.downX, y - p.downY) > pitchX * 0.75f) {
                p.swiping = true
                cancelLong()
                p.trail.add(p.downX); p.trail.add(p.downY); p.trailT.add(p.downAt)
            }
            if (p.swiping) {
                val n = p.trail.size
                addTrail(p, x, y, t)
                if (p.trail.size != n) invalidate()
                if (t - p.progressAt >= SWIPE_PREVIEW_MS && p.trail.size >= 8) {
                    p.progressAt = t
                    val (pts, times) = swipeArrays(p)
                    listener.onSwipeProgress(pts, times, letterCenters(), rowH / pitchX)
                }
                return
            }
        }
        if (k.kind == KeyKind.SPACE) {
            if (!p.cursorMode && !p.flick && abs(x - p.downX) > dp(14f)) {
                // Со сменой языка свайпом: сразу повёл — это смахивание, подержал и повёл — курсор.
                if (langSwipe && SystemClock.uptimeMillis() - p.downAt < SPACE_HOLD_MS) p.flick = true
                else { p.cursorMode = true; p.cursorAnchor = x; cancelLong(); invalidate() }
            }
            if (p.cursorMode) {
                val step = dp(11f)
                val steps = ((x - p.cursorAnchor) / step).toInt()
                if (steps != 0) { listener.onCursor(steps); p.cursorAnchor += steps * step; p.cursorMoved = true }
            }
            return
        }
        if (k.kind == KeyKind.LETTER || k.kind == KeyKind.CHAR) {
            val m = pitchX * 0.35f
            if (x < k.x - m || x > k.x + k.w + m || y < k.y - m || y > k.y + k.h + m) {
                val nk = plainHit(x, y)
                if (nk != null && nk != k && (nk.kind == KeyKind.LETTER || nk.kind == KeyKind.CHAR)) {
                    p.key = nk; p.dx = (x - nk.cx) / pitchX; p.dy = (y - nk.cy) / rowH; p.slid = true
                    cancelLong(); if (nk.popup.isNotEmpty()) scheduleLong(p)
                    invalidate()
                }
            }
        }
    }

    private fun up(id: Int, x: Float, y: Float) {
        val p = ptrs.firstOrNull { it.id == id } ?: return
        ptrs.remove(p)
        if (p.aiPanel || p.aiHead != -1) {
            cancelAiHold()
            if (p.aiHead == -3) {
                aiSideUp(p, x, y)
            } else if (p.aiHead == -2) {
                // кнопка «ИИ»: повторное нажатие возвращает буквенную клавиатуру
                if (y < tabH + dp(20f) && atAiBtn(p.downX)) { listener.onFeedback(null); listener.onAiClick() }
            } else if (p.aiHead >= 0) {
                if (y < tabH + dp(20f) && aiDotAt(x) == p.aiHead) { listener.onFeedback(null); aiGoTab(p.aiHead) }
            } else if (p.aiSwiped) {
                // смахнули достаточно — переключаемся; чуть-чуть — возвращаемся на свою вкладку
                val to = aiSwitchTo
                val go = to != null && to != aiCurIdx() && abs(x - p.downX) > width * 0.3f
                if (aiSoloOn()) { if (go) animateSoloSlide(to!!) else animateAiSlide(aiCurIdx()) }
                else animateAiSlide(if (go) to!! else aiTab)
                invalidate(); return
            } else if (!p.aiScrolled && !p.aiHeld) {
                val c = aiCellAt(x, y)
                // Тап — только по строке действия, и только по той же клетке, где нажали.
                if (c != null && c.second >= 0 && c == aiCellAt(p.downX, p.downY)) {
                    listener.onFeedback(null)
                    listener.onAiAction(aiTab, c.first, c.third)
                }
            } else if (p.aiScrolled) {
                // отпустили прокрутку — докатываем по инерции тот же столбец
                val col = p.aiCol
                if (col in 0 until AI_COLS) velocity?.let {
                    it.computeCurrentVelocity(1000)
                    val vy = it.getYVelocity(id)
                    aiScroll[col] = aiScrollOf(col)
                    aiFling[col].forceFinished(true)
                    aiFling[col].fling(0, aiScroll[col].toInt(), 0, (-vy).toInt(),
                        0, 0, 0, aiMaxScroll().toInt())
                    postInvalidateOnAnimation()
                }
            }
            invalidate(); return
        }
        if (p.stripSlot >= 0) {
            cancelLong()
            if (!p.done && slotAt(x) == p.stripSlot && y < stripH + dp(20f)) {
                candidateAt(p.stripSlot)?.let { listener.onFeedback(null); listener.onSuggestion(it) }
            }
            invalidate(); return
        }
        if (p.stripEmoji) {
            val near = if (stripIconRight()) x > width - stripIconW() - dp(12f) else x < stripIconW() + dp(12f)
            if (near && y < stripH + dp(20f)) {
                listener.onFeedback(null)
                if (aiMode) listener.onAiClick() else listener.onEmojiSearch(true)
            }
            invalidate(); return
        }
        if (p.tab >= 0) {
            if (tabAt(x) == p.tab && y > tabH - dp(20f) && y < topH + dp(20f)) {
                listener.onFeedback(null)
                scrollToSection(p.tab)
            }
            invalidate(); return
        }
        if (p.searchBar && draftShown()) {
            if (y < topH + dp(20f)) {
                when {
                    x < tabH && p.downX < tabH -> { listener.onFeedback(null); listener.onSafeDraft(DRAFT_CANCEL) }
                    x > width - tabH && p.downX > width - tabH -> { listener.onFeedback(null); listener.onSafeDraft(DRAFT_SAVE) }
                }
            }
            invalidate(); return
        }
        if (p.searchBar && (layout.mode == Mode.CLIPBOARD || layout.mode == Mode.SAFE)) {
            if (y < topH + dp(20f)) {
                if (layout.mode == Mode.CLIPBOARD) {
                    // замок справа — открыть сейф
                    if (x > width - tabH && p.downX > width - tabH) { listener.onFeedback(null); listener.onSafeHeader(SAFE_OPEN) }
                } else when {
                    // сейф: ← слева — назад к буферу (и запереть); поле или ✓ — новая запись
                    x < tabH && p.downX < tabH -> { listener.onFeedback(null); listener.onSafeHeader(SAFE_CLOSE) }
                    x > tabH && p.downX > tabH -> { listener.onFeedback(null); listener.onSafeHeader(SAFE_NEW) }
                }
            }
            invalidate(); return
        }
        if (p.searchBar && layout.mode == Mode.SAFE_PIN) {
            if (y < topH + dp(20f)) {
                if (x < tabH && p.downX < tabH) { listener.onFeedback(null); listener.onSafeHeader(SAFE_BACK) }
                else if (safeBio && x > width - tabH && p.downX > width - tabH) { listener.onFeedback(null); listener.onSafeHeader(SAFE_BIO) }
            }
            invalidate(); return
        }
        if (p.searchBar) {
            if (y < tabH + dp(20f)) {
                if (layout.mode == Mode.EMOJI) {
                    // Тап по строке поиска в сетке — сразу печатаем запрос: та же шапка, внизу — буквы.
                    listener.onFeedback(null); listener.onEmojiSearch(true)
                } else if (searchQuery.isNotEmpty() && x > width - tabH && p.downX > width - tabH) {
                    listener.onFeedback(null); listener.onEmojiSearchClear()
                }
            }
            invalidate(); return
        }
        if (p.results) {
            if (!p.emojiScrolling && y > tabH - dp(20f) && y < topH + dp(20f)) {
                val i = ((x + resultsScroll) / tabH).toInt()
                searchResults.getOrNull(i)?.let { listener.onFeedback(null); listener.onEmoji(it) }
            }
            invalidate(); return
        }
        if (p.emoji && p.tones.isNotEmpty()) {
            cancelTones(p)
            p.tones.getOrNull(p.popupIndex)?.let { listener.onEmoji(it) }
            invalidate(); return
        }
        if (p.emoji) {
            cancelTones(p)
            if (p.clipHeld) {
                // Предпросмотр уже показан — отпускание только закрывает его, ничего не вставляя.
                cancelClipPreview(); invalidate(); return
            }
            cancelClipPreview()
            if (p.emojiScrolling) {
                velocity?.let {
                    it.computeCurrentVelocity(1000)
                    val vy = it.getYVelocity(id)
                    scroller.fling(0, emojiScroll.toInt(), 0, (-vy).toInt(), 0, 0, 0, maxEmojiScroll().toInt())
                    postInvalidateOnAnimation()
                }
            } else if (layout.mode == Mode.EMOJI) {
                // Сетку за это время прокрутил другой палец (или вкладка) — под пальцем уже другое: не вставляем.
                if (abs(emojiScroll - p.downScroll) < dp(8f)) {
                    p.pressedEmoji?.let { listener.onFeedback(null); listener.onEmoji(it) }
                }
            } else {
                clipAt(y)?.let {
                    val action = when {
                        !clipButtons -> if (x > width - clipCloseW) CLIP_DELETE else CLIP_PASTE // сейф: вставить или удалить
                        x > width - clipCloseW -> CLIP_DELETE
                        x > width - 2 * clipCloseW -> CLIP_PIN
                        x > width - 3 * clipCloseW -> CLIP_TO_SAFE
                        else -> CLIP_PASTE
                    }
                    listener.onFeedback(null); listener.onClip(it, action)
                }
            }
            return
        }
        if (p.flick) {
            val dx = abs(x - p.downX)
            if (dx > pitchX * 1.2f && dx > 2 * abs(y - p.downY)) {
                p.done = true
                listener.onLangSwipe()
                invalidate(); return
            }
            // Не дотянул до смахивания — это был обычный (чуть смазанный) пробел.
            p.flick = false
        }
        // Палец съехал с пробела, но курсор так и не сдвинулся — тоже обычный пробел.
        if (p.cursorMode && !p.cursorMoved) p.cursorMode = false
        commit(p)
        if (ptrs.none { it.key?.code == Code.DELETE }) cancelRepeat()
        invalidate()
    }

    private fun commit(p: Ptr) {
        if (p.done) return
        p.done = true
        cancelLong()
        val k = p.key ?: return
        when {
            p.swiping -> {
                swipePreview = null
                val (pts, times) = swipeArrays(p)
                listener.onSwipe(pts, times, letterCenters(), rowH / pitchX)
            }
            p.longPressed && k.popup == Layouts.GLOBE_POPUP -> when (p.popupIndex) {
                0 -> listener.onGlobeLongPress()
                else -> {
                    // ◀ — к левому краю, ▣ — во всю ширину, ▶ — к правому
                    oneHand = when (p.popupIndex) { 1 -> 1; 3 -> 2; else -> 0 }
                    listener.onOneHand(oneHand)
                }
            }
            p.longPressed -> k.popup.getOrNull(p.popupIndex)?.let {
                listener.onText(if (k.kind == KeyKind.LETTER && shift != Shift.OFF) upper(it) else it)
            }
            p.cursorMode -> {}
            k.code == Code.DELETE || k.code == Code.SHIFT -> {}
            else -> listener.onKey(k, p.dx, p.dy, if (k.kind == KeyKind.LETTER && !p.slid) tapOf(p, k) else null)
        }
    }

    /** Касание буквы: точка (с учётом того, куда съехал палец), время нажатия и рука — половина клавиатуры. */
    private fun tapOf(p: Ptr, k: Key): Reorder.Tap {
        val x = k.cx + p.dx * pitchX
        val y = k.cy + p.dy * rowH
        val keys = layout.letterKeys
        val mid = if (keys.isEmpty()) width / 2f else (keys.minOf { it.vx } + keys.maxOf { it.vx + it.vw }) / 2
        return Reorder.Tap(x, y, p.downAt, x < mid)
    }

    fun resolveTap(t: Reorder.Tap, probs: FloatArray?, conf: Float): Key? =
        TouchModel.resolve(layout, lang, t.x, t.y, topH, probs, conf, pitchX, rowH, gapX) { Brain.user.offset(it) }

    /** Насколько касание похоже на нажатие клавиши — с учётом привычного смещения пальца владельца. */
    fun tapFit(t: Reorder.Tap, k: Key): Float {
        val off = Brain.user.offset(k.ch)
        return TouchModel.fit((t.x - k.cx - (off?.get(0) ?: 0f) * pitchX) / pitchX,
            (t.y - k.cy - (off?.get(1) ?: 0f) * rowH) / rowH)
    }

    /** Центры букв в единицах клавиш (ъ живёт на ь, ё — на е). */
    /** След пальца в единицах клавиш и время его точек (мс от начала). */
    private fun swipeArrays(p: Ptr): Pair<FloatArray, FloatArray> {
        val pts = FloatArray(p.trail.size) { i -> if (i % 2 == 0) p.trail[i] / pitchX else p.trail[i] / rowH }
        val t0 = p.trailT.firstOrNull() ?: 0L
        return pts to FloatArray(p.trailT.size) { i -> (p.trailT[i] - t0).toFloat() }
    }

    private fun letterCenters(): Map<Char, FloatArray> {
        val m = HashMap<Char, FloatArray>()
        for (k in layout.letterKeys) m[k.ch] = floatArrayOf(k.cx / pitchX, k.cy / rowH)
        m['ь']?.let { m['ъ'] = it }
        return m
    }

    private fun scheduleLong(p: Ptr) {
        cancelLong()
        val r = Runnable {
            val k = p.key ?: return@Runnable
            if (p.done || p.cursorMode) return@Runnable
            p.longPressed = true
            p.popupLeft = popupLeft(k)
            p.popupMirror = popupMirrored(k)
            // Выбрано то, что под пальцем (если меню пришлось сдвинуть к краю — не обязательно первое).
            val n = k.popup.size
            val pos = ((k.cx - p.popupLeft) / popupCellW(k)).toInt().coerceIn(0, n - 1)
            p.popupIndex = if (p.popupMirror) n - 1 - pos else pos
            listener.onFeedback(null)
            invalidate()
        }
        pendingLong = r
        handler.postDelayed(r, 330L)
    }

    private fun cancelLong() { pendingLong?.let { handler.removeCallbacks(it) }; pendingLong = null }

    private fun scheduleRepeat(p: Ptr, delay: Long) {
        cancelRepeat()
        val r = object : Runnable {
            override fun run() {
                if (p.done || !ptrs.contains(p)) return
                p.repeat++
                // Сначала стираем буквами, а после порога удержания — словами: слово, вибрация и пауза,
                // чтобы успеть отпустить. Держит дольше трёх слов — значит, стирает много, паузы короче.
                val held = SystemClock.uptimeMillis() - p.downAt
                val words = deleteWordsAfterMs > 0 && held >= deleteWordsAfterMs
                if (words) { p.wordsDeleted++; listener.onFeedback(p.key) }
                listener.onDelete(p.repeat, words)
                handler.postDelayed(this, if (words) wordPause(p.wordsDeleted) else CHAR_REPEAT_MS)
            }
        }
        pendingRepeat = r
        handler.postDelayed(r, delay)
    }

    private fun wordPause(n: Int): Long {
        val full = deleteWordPauseMs.toDouble()
        return if (n <= 3) deleteWordPauseMs.toLong() else maxOf(full / 2, full * Math.pow(0.85, (n - 3).toDouble())).toLong()
    }

    private fun cancelRepeat() { pendingRepeat?.let { handler.removeCallbacks(it) }; pendingRepeat = null }
    private fun cancelTimers() { cancelLong(); cancelRepeat() }

    override fun onDetachedFromWindow() {
        cancelTimers(); cancelTones(); cancelClipPreview(); ptrs.clear(); super.onDetachedFromWindow()
    }

    // ================= подсказки =================

    private fun stripIconW() = if (layout.mode == Mode.LETTERS && showStrip) dp(44f) else 0f

    /**
     * Где кнопка: у правого края — true. Настройка владельца, а в режиме одной рукой — та сторона,
     * в которую сдвинуты клавиши (см. StripSide).
     */
    private fun stripIconRight() = StripSide.right(oneHand, aiKeyRight, panel())

    private fun stripIconX() = if (stripIconRight()) width - stripIconW() else 0f

    private fun atStripIcon(x: Float) = if (stripIconRight()) x > width - stripIconW() else x < stripIconW()

    private fun slotW() = (width - stripIconW()) / 3f
    private fun slotAt(x: Float) = ((x - (if (stripIconRight()) 0f else stripIconW())) / slotW()).toInt().coerceIn(0, 2)

    private fun candidateAt(slot: Int): Cand? {
        val items = suggestions?.items ?: return null
        // Плашка «вставить скопированное» и «забыть слово?» — во всю ширину.
        items.firstOrNull()?.let { if (it.kind == Cand.CLIP || it.kind == Cand.FORGET) return it }
        val idx = when (slot) { 1 -> 0; 0 -> 1; else -> 2 }
        return items.getOrNull(idx)?.takeIf { it.kind != Cand.EMPTY }
    }

    // ================= эмодзи =================

    private val emojiCell get() = dp(46f)
    private fun emojiCols() = max(6, (width / emojiCell).toInt())
    private fun emojiTop() = topH
    /**
     * Низ сетки эмодзи — верх нижнего ряда клавиш. Рядов может не быть вовсе (форма ИИ: там клавиш нет),
     * а спрашивают это и в те моменты, когда режим ещё не сменился (сеттер сетки, смена размера) —
     * поэтому без прямого rows[0][0]: пустые ряды давали IndexOutOfBoundsException.
     */
    private fun emojiBottom() = layout.rows.firstOrNull()?.firstOrNull()?.y ?: height.toFloat()

    // Сетка: каждый раздел — с новой строки; null — пустая клетка в конце раздела.
    private var gridCols = 0
    private var grid: List<String?> = emptyList()
    private var sectionRows = IntArray(0)

    private fun ensureGrid() {
        val cols = emojiCols()
        if (cols == gridCols) return
        gridCols = cols
        val cells = ArrayList<String?>()
        sectionRows = IntArray(emojiSections.size)
        for ((i, sec) in emojiSections.withIndex()) {
            sectionRows[i] = cells.size / cols
            cells.addAll(sec.items)
            while (cells.size % cols != 0) cells.add(null)
        }
        grid = cells
    }

    private fun maxEmojiScroll(): Float {
        if (layout.mode == Mode.CLIPBOARD || layout.mode == Mode.SAFE)
            return max(0f, clipList.size * clipRowH - (emojiBottom() - emojiTop()))
        ensureGrid()
        val cols = emojiCols()
        val rows = (grid.size + cols - 1) / cols
        val cellH = width.toFloat() / cols
        return max(0f, rows * cellH - (emojiBottom() - emojiTop()))
    }

    private fun emojiAt(x: Float, y: Float): String? {
        ensureGrid()
        val cols = emojiCols()
        val cell = width.toFloat() / cols
        val col = (x / cell).toInt().coerceIn(0, cols - 1)
        val row = ((y - emojiTop() + emojiScroll) / cell).toInt()
        return grid.getOrNull(row * cols + col)
    }

    private fun tabCount() = max(1, emojiSections.size)
    private fun tabAt(x: Float) = (x / (width.toFloat() / tabCount())).toInt().coerceIn(0, tabCount() - 1)

    private fun currentSection(): Int {
        ensureGrid()
        if (sectionRows.isEmpty()) return -1
        val cell = width.toFloat() / emojiCols()
        val row = ((emojiScroll + cell * 0.5f) / cell).toInt()
        var cur = 0
        for ((i, r) in sectionRows.withIndex()) if (r <= row) cur = i
        // Докрутили до конца — значит, смотрим последний раздел, даже если он короткий.
        if (emojiScroll >= maxEmojiScroll() - 1f) cur = sectionRows.size - 1
        return cur
    }

    private fun scrollToSection(i: Int) {
        ensureGrid()
        val cell = width.toFloat() / emojiCols()
        val target = (sectionRows.getOrElse(i) { 0 } * cell).coerceIn(0f, maxEmojiScroll())
        scroller.forceFinished(true)
        scroller.startScroll(0, emojiScroll.toInt(), 0, (target - emojiScroll).toInt(), 250)
        postInvalidateOnAnimation()
    }

    private fun tonesCellW() = dp(44f)

    /** Таймер выбора цвета кожи — у каждого пальца свой: другие пальцы (пробел, другое эмодзи) его не сбивают. */
    private fun cancelTones(p: Ptr) { p.tonesTimer?.let { handler.removeCallbacks(it) }; p.tonesTimer = null }
    private fun cancelTones() { for (p in ptrs) cancelTones(p) }

    private var clipPreview: String? = null
    private var clipPreviewScroll = 0f
    private var clipPreviewLayout: StaticLayout? = null
    private var clipPreviewMaxScroll = 0f
    private val clipPreviewPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Долгое нажатие на запись буфера или сейфа — показать её текст целиком: в строке он обрезан.
     * Палец на кнопках справа (замок, булавка, крестик) — не про текст, предпросмотра нет.
     */
    private fun scheduleClipPreview(p: Ptr) {
        cancelClipPreview()
        val r = Runnable {
            if (p.done || p.emojiScrolling || !ptrs.contains(p)) return@Runnable
            val row = clipAt(p.downY)?.let { clipList.getOrNull(it) } ?: return@Runnable
            if (row.text.isBlank()) return@Runnable
            p.clipHeld = true
            clipPreview = row.text
            clipPreviewScroll = 0f
            buildClipPreviewLayout(row.text)
            listener.onFeedback(null)
            invalidate()
        }
        pendingLong = r
        handler.postDelayed(r, 450L)
    }

    private fun cancelClipPreview() {
        clipPreview = null
        clipPreviewLayout = null
        pendingLong?.let { handler.removeCallbacks(it) }
        pendingLong = null
    }

    private fun clipPreviewBounds() {
        rect.set(dp(8f), emojiTop() + dp(6f), width - dp(8f), emojiBottom() - dp(6f))
    }

    private fun clipPreviewTextWidth() = (width - dp(8f) * 2 - dp(16f) * 2 - dp(8f)).toInt().coerceAtLeast(1)

    private fun clipPreviewVisible() = (emojiBottom() - emojiTop() - dp(6f) * 2 - dp(16f) * 2).coerceAtLeast(0f)

    private fun buildClipPreviewLayout(s: String) {
        clipPreviewPaint.color = cText
        clipPreviewPaint.textSize = spx(16f)
        clipPreviewPaint.typeface = Typeface.DEFAULT
        val sl = StaticLayout.Builder.obtain(s, 0, s.length, clipPreviewPaint, clipPreviewTextWidth())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .build()
        clipPreviewLayout = sl
        clipPreviewMaxScroll = max(0f, sl.height - clipPreviewVisible())
    }

    private fun clipPreviewMax() = clipPreviewMaxScroll

    private fun scheduleTones(p: Ptr) {
        cancelTones(p)
        val e = p.pressedEmoji ?: return
        val r = Runnable {
            p.tonesTimer = null
            if (p.done || p.emojiScrolling || !ptrs.contains(p) || layout.mode != Mode.EMOJI) return@Runnable
            val tones = emojiTones(e)
            if (tones.size < 2) return@Runnable
            p.tones = tones
            p.popupIndex = tones.indexOf(e).coerceAtLeast(0)
            val w = tonesCellW() * tones.size
            p.popupLeft = (p.downX - w / 2).coerceAtMost(width - w - dp(2f)).coerceAtLeast(dp(2f))
            listener.onFeedback(null)
            invalidate()
        }
        p.tonesTimer = r
        handler.postDelayed(r, 350L)
    }

    private var resultsScroll = 0f
    private fun maxResultsScroll() = max(0f, searchResults.size * tabH - width)

    override fun computeScroll() {
        var again = false
        if (layout.mode == Mode.AI) {
            if (aiSwitchTo != null && aiScroller.computeScrollOffset()) {
                aiOffset = aiScroller.currY.toFloat()
                if (aiScroller.isFinished) finishAiSlide()
                again = true
            }
            for (i in 0 until AI_COLS) {
                if (aiFling[i].computeScrollOffset()) {
                    aiScroll[i] = aiFling[i].currY.toFloat().coerceIn(0f, aiMaxScroll())
                    again = true
                }
            }
        }
        if (again) { postInvalidateOnAnimation(); return }
        if (panel() && scroller.computeScrollOffset()) {
            emojiScroll = scroller.currY.toFloat().coerceIn(0f, maxEmojiScroll())
            postInvalidateOnAnimation()
        }
    }

    fun resetEmojiScroll() { scroller.forceFinished(true); emojiScroll = 0f }

    // ================= отрисовка =================

    override fun onDraw(c: Canvas) {
        c.drawColor(cBg)
        when (layout.mode) {
            Mode.EMOJI -> { drawEmoji(c); drawEmojiHeader(c, false); drawTabs(c) }
            Mode.EMOJI_SEARCH -> { drawEmojiHeader(c, true); drawResults(c) }
            Mode.CLIPBOARD, Mode.SAFE -> { drawClips(c); drawClipPreview(c) }
            Mode.SAFE_PIN -> drawSafeHeader(c)
            Mode.AI -> { drawAiPanel(c); drawAiSide(c); drawAiHeader(c) }
            else -> if (draftShown()) drawDraftBar(c) else if (showStrip) drawStrip(c)
        }
        for (k in layout.keys) drawKey(c, k)
        if (!sideRect.isEmpty) drawSide(c)
        if (showZones) drawZones(c)
        for (p in ptrs) {
            if (p.tones.isNotEmpty()) { drawTones(c, p); continue }
            val k = p.key ?: continue
            if (p.swiping) { drawTrail(c, p); continue }
            if (p.longPressed) drawPopup(c, k, p)
            // ПИН сейфа — без увеличенных цифр над пальцем: их видно со стороны.
            else if (showPreview && layout.mode != Mode.SAFE_PIN && !p.cursorMode &&
                (k.kind == KeyKind.LETTER || k.kind == KeyKind.CHAR)) drawPreview(c, k)
        }
        drawToast(c)
        maybeTickToast()
    }

    /**
     * Точки плашки: где нарисовано сообщение и куда её подгоняет расчёт под клавиши.
     * Сдвиг — от начального положения, а не от края клавиатуры (в draw цифры уже в координатах экрана).
     */
    class ToastPlank(val top: Float, val height: Float) {
        fun above(limit: Float) = ToastPlank(minOf(top, limit), height)
        fun moved(dy: Float) = ToastPlank(top + dy, height)
        override fun toString() = "ToastPlank(top=$top, height=$height)"
    }

    /**
     * Подгонка положения сообщения: точки, на которые смотрит юнит-тест (без Android-разметки).
     * Те же расчёты нужны и при отрисовке — иначе проверять было бы нечего.
     */
    object FitToast {
        /**
         * Сообщение не должно закрывать клавиши нижнего ряда и пробел. Поднимаем его, пока под ним
         * не останутся эти клавиши; выше края клавиатуры не поднимаем.
         * @param kbTop верх клавиатуры (в тех же координатах, что и плашка)
         * @param kbBottom низ клавиатуры (без системной панели)
         */
        fun fit(plank: ToastPlank, keys: List<Key>, kbTop: Float, kbBottom: Float): ToastPlank {
            val step = (kbBottom - kbTop) * 0.045f
            val bottom = ToastSpec.bottomRow(keys)
            if (bottom.isEmpty()) {
                // Клавиш под сообщением нет (панель без нижнего ряда, форма ИИ) — поднимать не над чем.
                return plank.moved(maxOf(0f, kbTop - plank.top))
            }
            var top = plank.top
            var guard = 0
            while (guard++ < 64 && hidden(bottom, kbTop, top, plank.height) && top - step >= kbTop) top -= step
            if (hidden(bottom, kbTop, top, plank.height)) top = kbTop
            return plank.moved(top - plank.top)
        }

        private fun hidden(keys: List<Key>, kbTop: Float, top: Float, h: Float) =
            ToastSpec.hidden(keys, kbTop, top, h).isNotEmpty()
    }

    private fun drawToast(c: Canvas) {
        val s = toastText
        if (s.isEmpty()) return
        val t = SystemClock.uptimeMillis() - toastAt
        if (ToastSpec.gone(t)) { finishToast(); return }
        val h = dp(36f)
        val kbBottom = height.toFloat() - bottomPad - navInset
        val startTop = kbBottom - dp(52f) - h
        val fitted = FitToast.fit(ToastPlank(startTop, h), layout.keys, topH, kbBottom)
        val y = fitted.moved(ToastSpec.cover(t) * h).top
        val fade = (ToastSpec.alpha(t) * (1f - ToastSpec.cover(t))).toInt().coerceIn(0, 255)
        text.color = Color.WHITE
        text.typeface = Typeface.DEFAULT_BOLD
        fitText(s, width - dp(48f), spx(14.5f))
        val w = min(width.toFloat() - dp(16f), text.measureText(s) + dp(32f))
        rect.set((width - w) / 2f, y, (width + w) / 2f, y + h)
        fill.color = ToastSpec.bg(fade)
        c.drawRoundRect(rect, h / 2, h / 2, fill)
        text.alpha = fade
        drawCentered(c, s, width / 2f, y + h / 2)
        text.typeface = Typeface.DEFAULT
        text.alpha = 255
    }

    private fun isPressed(k: Key) = ptrs.any { it.key === k && !it.done && !it.swiping }

    private fun drawSide(c: Canvas) {
        val cx = sideRect.centerX()
        val r = min(sideRect.width() * 0.32f, dp(22f))
        val y1 = sideRect.top + sideRect.height() * 0.3f
        val y2 = sideRect.top + sideRect.height() * 0.7f
        fill.color = cFn
        c.drawCircle(cx, y1, r, fill)
        c.drawCircle(cx, y2, r, fill)
        stroke.color = cText
        val dir = if (oneHand == 1) 1f else -1f
        val a = r * 0.35f
        path.reset()
        path.moveTo(cx - dir * a * 0.6f, y1 - a)
        path.lineTo(cx + dir * a * 0.6f, y1)
        path.lineTo(cx - dir * a * 0.6f, y1 + a)
        c.drawPath(path, stroke)
        rect.set(cx - r * 0.55f, y2 - r * 0.32f, cx + r * 0.55f, y2 + r * 0.32f)
        c.drawRoundRect(rect, dp(2f), dp(2f), stroke)
        c.drawLine(cx - r * 0.3f, y2, cx + r * 0.3f, y2, stroke)
    }

    private fun drawTrail(c: Canvas, p: Ptr) {
        val t = p.trail
        if (t.size < 4) return
        path.reset()
        path.moveTo(t[0], t[1])
        var i = 2
        while (i < t.size) { path.lineTo(t[i], t[i + 1]); i += 2 }
        c.drawPath(path, trailPaint)
    }

    /** Надпись кнопки ИИ: на английской раскладке — «AI», на русской — «ИИ». Отличие только визуальное. */
    private fun aiButtonLabel() = StripSide.label(lang)

    private fun drawStrip(c: Canvas) {
        val w = stripIconW()
        // Кнопка в углу полосы: слева (по умолчанию) или справа (настройка либо режим одной рукой).
        val iconX = stripIconX()
        val x0 = if (stripIconRight()) 0f else w
        val right = if (stripIconRight()) width.toFloat() - w else width.toFloat()
        if (w > 0f) {
            // 😊 или «ИИ» — сразу в поиск эмодзи или в форму ИИ.
            if (ptrs.any { it.stripEmoji }) {
                fill.color = cPressed
                rect.set(iconX + dp(4f), dp(5f), iconX + w - dp(2f), stripH - dp(5f))
                c.drawRoundRect(rect, dp(8f), dp(8f), fill)
            }
            if (aiMode) {
                // Только текст, без значков: «ИИ» / «AI» — по языку раскладки, а не по локали приложения.
                text.color = cText; text.textSize = spx(17f); text.typeface = Typeface.DEFAULT_BOLD
                drawCentered(c, aiButtonLabel(), iconX + w / 2 + dp(1f), stripH / 2)
                text.typeface = Typeface.DEFAULT
            } else {
                drawSmiley(c, iconX + w / 2 + dp(1f), stripH / 2, dp(9f))
            }
        }
        swipePreview?.let { pv ->
            text.color = cText; text.textSize = spx(18f); text.typeface = Typeface.DEFAULT_BOLD
            drawCentered(c, ellipsize(pv, right - x0 - dp(24f)), x0 + (right - x0) / 2, stripH / 2)
            text.typeface = Typeface.DEFAULT
            return
        }
        val s = suggestions ?: return
        val slotW = slotW()
        val pressedSlot = ptrs.firstOrNull { it.stripSlot >= 0 }?.stripSlot ?: -1
        for (slot in 0..2) {
            val cand = candidateAt(slot) ?: continue
            val left = x0 + slot * slotW
            if (cand.kind == Cand.CLIP) {
                fill.color = if (pressedSlot >= 0) cPressed else cBubble
                rect.set(x0 + dp(4f), dp(5f), right - dp(8f), stripH - dp(5f))
                c.drawRoundRect(rect, dp(8f), dp(8f), fill)
                drawClipboard(c, x0 + dp(24f), stripH / 2, dp(8f))
                text.color = cText; text.textSize = spx(15f)
                val l = ellipsize(oneLine(cand.text), right - x0 - dp(60f))
                text.textAlign = Paint.Align.LEFT
                c.drawText(l, x0 + dp(42f), stripH / 2 - (text.descent() + text.ascent()) / 2, text)
                text.textAlign = Paint.Align.CENTER
                return
            }
            if (cand.kind == Cand.FORGET) {
                // Плашка во всю ширину: нажатие где угодно — подсветка целиком.
                fill.color = if (pressedSlot >= 0) cPressed else cBubble
                rect.set(x0 + dp(4f), dp(5f), right - dp(8f), stripH - dp(5f))
                c.drawRoundRect(rect, dp(8f), dp(8f), fill)
                text.color = cDanger; text.typeface = Typeface.DEFAULT_BOLD
                val l = "Забыть «${cand.text}»? Нажмите, чтобы подтвердить"
                fitText(l, right - x0 - dp(24f), spx(16f))
                c.drawText(l, (x0 + right) / 2f, stripH / 2 - (text.descent() + text.ascent()) / 2, text)
                text.typeface = Typeface.DEFAULT
                return
            }
            if (slot == pressedSlot || cand.kind == Cand.CORRECTION || cand.kind == Cand.RELAYOUT) {
                fill.color = if (slot == pressedSlot) cPressed else cBubble
                rect.set(left + dp(4f), dp(5f), left + slotW - dp(4f), stripH - dp(5f))
                c.drawRoundRect(rect, dp(8f), dp(8f), fill)
            }
            val label = when (cand.kind) {
                Cand.TYPED -> "«${cand.text}»"
                else -> cand.text
            }
            text.color = if (cand.kind == Cand.TYPED) cTextDim else cText
            text.typeface = if (slot == 1 && s.items.isNotEmpty()) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            fitText(label, slotW - dp(12f), if (cand.kind == Cand.EMOJI) spx(22f) else spx(17f))
            c.drawText(label, left + slotW / 2, stripH / 2 - (text.descent() + text.ascent()) / 2, text)
        }
        fill.color = cBubble
        for (slot in 1..2) {
            val x = x0 + slot * slotW
            if (candidateAt(slot - 1) != null && candidateAt(slot) != null) {
                c.drawRect(x - dp(0.5f), dp(12f), x + dp(0.5f), stripH - dp(12f), fill)
            }
        }
        // «ИИ» — такая же кнопка, как слоты подсказок, поэтому и отделяется от них тем же разделителем.
        if (aiMode && w > 0f && (0..2).any { candidateAt(it) != null }) {
            val x = if (stripIconRight()) width - w else w
            c.drawRect(x - dp(0.5f), dp(12f), x + dp(0.5f), stripH - dp(12f), fill)
        }
        text.typeface = Typeface.DEFAULT
    }

    private fun fitText(s: String, maxW: Float, size: Float) {
        text.textSize = size
        val w = text.measureText(s)
        if (w > maxW) text.textSize = max(spx(10f), size * maxW / w)
    }

    private fun drawKey(c: Canvas, k: Key) {
        val pressed = isPressed(k)
        val fn = k.kind == KeyKind.FUNCTION || (k.kind == KeyKind.ENTER && !enterAccent)
        // ⇧ и ⌫ — просто иконки, без подложки (она появляется только под пальцем).
        val bare = k.code == Code.SHIFT || k.code == Code.DELETE
        fill.color = when {
            bare && !pressed -> Color.TRANSPARENT
            pressed -> cPressed
            k.kind == KeyKind.ENTER && enterAccent -> cAccent
            fn -> cFn
            else -> cKey
        }
        val onAccent = k.kind == KeyKind.ENTER && enterAccent && !pressed
        if (!bare || pressed) {
            if (theme.shadow != 0 && !pressed) {
                // светлая тема: тонкая тень снизу, чтобы белые клавиши не сливались с фоном
                val f = fill.color
                fill.color = theme.shadow
                rect.set(k.vx, k.vy + dp(1f), k.vx + k.vw, k.vy + k.vh + dp(1f))
                c.drawRoundRect(rect, radius, radius, fill)
                fill.color = f
            }
            rect.set(k.vx, k.vy, k.vx + k.vw, k.vy + k.vh)
            c.drawRoundRect(rect, radius, radius, fill)
        }

        val cx = k.cx
        val cy = k.cy
        text.color = if (onAccent) cOnAccent else cText
        when (k.code) {
            Code.SHIFT -> drawShift(c, cx, cy, k.vh * 0.44f)
            Code.DELETE -> drawBackspace(c, cx, cy, k.vh * 0.44f)
            Code.LANG -> drawGlobe(c, cx, cy, k.vh * 0.22f)
            Code.EMOJI -> drawSmiley(c, cx, cy, k.vh * 0.22f)
            Code.CLIPBOARD -> drawClipboard(c, cx, cy, k.vh * 0.22f)
            Code.ENTER -> {
                val l = enterLabel
                if (l == null) drawEnter(c, cx, cy, k.vh * 0.42f, text.color)
                else { fitText(l, k.vw - dp(8f), spx(15f)); drawCentered(c, l, cx, cy) }
            }
            Code.SPACE -> {
                if (layout.mode == Mode.LETTERS || layout.mode == Mode.EMOJI_SEARCH) {
                    text.color = cTextDim; text.textSize = spx(14f); drawCentered(c, lang.title, cx, cy)
                }
            }
            else -> {
                val upper = k.kind == KeyKind.LETTER && shift != Shift.OFF
                val label = if (upper) k.label.uppercase() else k.label
                when {
                    k.kind == KeyKind.FUNCTION -> { fitText(label, k.vw - dp(8f), spx(16f)); drawCentered(c, label, cx, cy) }
                    layout.mode == Mode.NUMBER || layout.mode == Mode.PHONE -> { text.textSize = spx(24f); drawCentered(c, label, cx, cy) }
                    k.h < rowH * 0.9f -> { text.textSize = spx(19f); drawCentered(c, label, cx, cy) } // ряд цифр ниже обычного
                    k.kind == KeyKind.LETTER -> drawLetter(c, label, cx, cy, spx(LETTER_SP), upper)
                    else -> { text.textSize = spx(LETTER_SP); drawCentered(c, label, cx, cy) }
                }
                if (k.kind == KeyKind.LETTER && k.popup.isNotEmpty() && layout.mode == Mode.LETTERS) {
                    text.color = cTextDim; text.textSize = spx(9f)
                    c.drawText(k.popup[0], k.vx + k.vw - dp(6f), k.vy + dp(11f), text)
                }
            }
        }
    }

    private fun drawCentered(c: Canvas, s: String, cx: Float, cy: Float) {
        c.drawText(s, cx, cy - (text.descent() + text.ascent()) / 2, text)
    }

    /**
     * Буква на клавише. Заглавные — мельче строчных по кеглю: так заглавная лишь чуть выше строчной
     * (видно, что включён Shift), а не вдвое больше. По высоте центрируем сам «глаз» буквы: строчные —
     * по высоте «х», заглавные — по высоте «Х», чтобы при Shift буквы не прыгали вверх-вниз.
     */
    private fun drawLetter(c: Canvas, s: String, cx: Float, cy: Float, size: Float, upper: Boolean) {
        text.textSize = size
        if (upper) {
            // Кегль заглавных — по самому шрифту (у разных телефонов разные): высота «Х» = высота «х» · UPPER_RATIO.
            val k = (glyphHeight("х") * UPPER_RATIO / glyphHeight("Х")).coerceIn(0.65f, 1f)
            text.textSize = size * k
        }
        val h = if (upper) glyphHeight("Х") else glyphHeight("х")
        c.drawText(s, cx, cy + h / 2, text)
    }

    private val glyphBounds = android.graphics.Rect()
    private val glyphCache = HashMap<String, Float>()

    private fun glyphHeight(ref: String): Float = glyphCache.getOrPut(ref + text.textSize) {
        text.getTextBounds(ref, 0, ref.length, glyphBounds)
        glyphBounds.height().toFloat()
    }

    private fun drawPreview(c: Canvas, k: Key) {
        val w = max(k.vw * 1.25f, dp(44f))
        val h = k.vh * 1.35f
        val left = (k.cx - w / 2).coerceIn(0f, width - w)
        // Над клавишей нет места (верхний ряд без полосы подсказок) — пузырь закрыл бы саму клавишу,
        // хватит подсветки нажатия.
        if (k.vy - h - dp(4f) < -h / 2) return
        val top = max(0f, k.vy - h - dp(4f))
        fill.color = cBubble
        rect.set(left, top, left + w, top + h)
        c.drawRoundRect(rect, dp(10f), dp(10f), fill)
        val upper = k.kind == KeyKind.LETTER && shift != Shift.OFF
        val label = if (upper) k.label.uppercase() else k.label
        text.color = cText
        if (k.kind == KeyKind.LETTER) drawLetter(c, label, left + w / 2, top + h / 2, spx(32f), upper)
        else { text.textSize = spx(32f); drawCentered(c, label, left + w / 2, top + h / 2) }
    }

    private fun popupCellW(k: Key) = max(k.vw, dp(36f))

    /** Не влезает вправо — раскладываем влево от пальца, в обратном порядке: первый вариант под пальцем. */
    private fun popupMirrored(k: Key): Boolean {
        val cw = popupCellW(k)
        // Меню глобуса — со стрелками ◀ ▶: их порядок переворачивать нельзя.
        return k.popup.size > 1 && k.popup !== Layouts.GLOBE_POPUP && k.cx - cw / 2 + cw * k.popup.size > width - dp(2f)
    }

    private fun popupLeft(k: Key): Float {
        val cw = popupCellW(k)
        val total = cw * k.popup.size
        val left = if (popupMirrored(k)) k.cx + cw / 2 - total else k.cx - cw / 2
        return left.coerceAtMost(width - total - dp(2f)).coerceAtLeast(dp(2f))
    }

    private fun upper(s: String) = upperChars(s)

    private fun drawPopup(c: Canvas, k: Key, p: Ptr) {
        val cw = popupCellW(k)
        val h = k.vh * 1.15f
        val top = max(0f, k.vy - h - dp(6f))
        val left = p.popupLeft
        fill.color = cBubble
        rect.set(left - dp(4f), top - dp(4f), left + cw * k.popup.size + dp(4f), top + h + dp(4f))
        c.drawRoundRect(rect, dp(10f), dp(10f), fill)
        for ((i, s) in k.popup.withIndex()) {
            val x = left + (if (p.popupMirror) k.popup.size - 1 - i else i) * cw
            if (i == p.popupIndex) {
                fill.color = cAccent
                rect.set(x + dp(1f), top, x + cw - dp(1f), top + h)
                c.drawRoundRect(rect, dp(7f), dp(7f), fill)
            }
            text.color = if (i == p.popupIndex) cOnAccent else cText; text.textSize = spx(24f)
            val label = if (k.kind == KeyKind.LETTER && shift != Shift.OFF) upper(s) else s
            drawCentered(c, label, x + cw / 2, top + h / 2)
        }
    }

    private fun drawZones(c: Canvas) {
        val probs = letterProbs
        if (probs != null && layout.mode == Mode.LETTERS) {
            val maxP = probs.take(lang.size).maxOrNull() ?: 1f
            for (k in layout.letterKeys) {
                val i = lang.index(k.ch)
                if (i < 0) continue
                fill.color = cAccent
                fill.alpha = (sqrt(probs[i] / maxP) * 140 * letterConfidence).toInt().coerceIn(0, 140)
                rect.set(k.vx, k.vy, k.vx + k.vw, k.vy + k.vh)
                c.drawRoundRect(rect, radius, radius, fill)
                fill.alpha = 255
            }
        }
    }

    // ================= буфер обмена =================

    private val clipRowH get() = dp(52f)
    private val clipCloseW get() = dp(48f)
    /** У строк истории — кнопки (в сейф, закрепить, удалить); у записей сейфа — только удалить. */
    var clipButtons = true
        set(v) { field = v; invalidate() }
    private val clipBtnW get() = if (clipButtons) 3 * clipCloseW else clipCloseW
    var clipEmpty = "Скопируйте текст или картинку — они появятся здесь"
    /** Шапка ПИНа: точки или сообщение; есть ли вход по отпечатку. */
    var safeStatus = ""
        set(v) { field = v; invalidate() }
    var safeBio = false
        set(v) { field = v; invalidate() }

    private fun drawLock(c: Canvas, cx: Float, cy: Float, r: Float, col: Int) {
        stroke.color = col
        rect.set(cx - r, cy - r * 0.1f, cx + r, cy + r * 1.3f)
        c.drawRoundRect(rect, r * 0.25f, r * 0.25f, stroke)
        rect.set(cx - r * 0.6f, cy - r * 1.2f, cx + r * 0.6f, cy + r * 0.4f)
        c.drawArc(rect, 180f, 180f, false, stroke)
        c.drawLine(cx - r * 0.6f, cy - r * 0.4f, cx - r * 0.6f, cy - r * 0.1f, stroke)
        c.drawLine(cx + r * 0.6f, cy - r * 0.4f, cx + r * 0.6f, cy - r * 0.1f, stroke)
        stroke.color = cText
    }

    private fun drawFingerprint(c: Canvas, cx: Float, cy: Float, r: Float) {
        stroke.color = cText
        val w = stroke.strokeWidth
        stroke.strokeWidth = maxOf(dp(1.5f), r * 0.1f)
        Icons.fingerprint(c, cx, cy, r, stroke)
        stroke.strokeWidth = w
    }

    private fun drawDraftBar(c: Canvas) { drawEntryBar(c, draftBar ?: return, lock = false) }

    /**
     * Строка записи сейфа. В сейфе (lock) — слева ← (назад; сейф при этом запирается), поле «Новая запись» и бледная ✓:
     * нажатие — начать набор. При наборе — слева ×, в поле текст, ✓ — сохранить.
     */
    private fun drawEntryBar(c: Canvas, d: String, lock: Boolean) {
        val h = tabH
        val pressed = ptrs.any { it.searchBar && it.downX > h }
        if (lock) {
            stroke.color = cText
            val ax = h / 2; val ay = h / 2; val a = h * 0.16f
            c.drawLine(ax - a, ay, ax + a, ay, stroke)
            c.drawLine(ax - a, ay, ax - a * 0.3f, ay - a * 0.7f, stroke)
            c.drawLine(ax - a, ay, ax - a * 0.3f, ay + a * 0.7f, stroke)
        } else {
            stroke.color = cTextDim
            val a = h * 0.13f
            c.drawLine(h / 2 - a, h / 2 - a, h / 2 + a, h / 2 + a, stroke)
            c.drawLine(h / 2 - a, h / 2 + a, h / 2 + a, h / 2 - a, stroke)
        }
        fill.color = if (pressed) cPressed else cKey
        val right = width - h
        rect.set(h, dp(5f), right, h - dp(5f))
        c.drawRoundRect(rect, radius, radius, fill)
        text.textAlign = Paint.Align.LEFT; text.textSize = spx(15f)
        val tx = h + dp(10f)
        val ty = h / 2 - (text.descent() + text.ascent()) / 2
        if (d.isEmpty()) {
            text.color = cTextDim
            val hint = if (lock) "Новая запись" else draftHint ?: "Новая запись: напишите или вставьте из 📋"
            c.drawText(ellipsize(hint, right - tx - dp(6f)), tx, ty, text)
        } else {
            text.color = cText
            c.drawText(ellipsizeStart(oneLine(d), right - tx - dp(6f)), tx, ty, text)
        }
        text.textAlign = Paint.Align.CENTER
        val cx = width - h / 2
        if (lock) { stroke.color = cTextDim; c.drawCircle(cx, h / 2, h * 0.32f, stroke) }
        else { fill.color = cAccent; c.drawCircle(cx, h / 2, h * 0.32f, fill); stroke.color = cOnAccent }
        val s = h * 0.32f
        c.drawLine(cx - s * 0.4f, h / 2, cx - s * 0.1f, h / 2 + s * 0.3f, stroke)
        c.drawLine(cx - s * 0.1f, h / 2 + s * 0.3f, cx + s * 0.45f, h / 2 - s * 0.3f, stroke)
        stroke.color = cText
        text.color = cText
    }

    private fun drawSafeHeader(c: Canvas) {
        val h = tabH
        stroke.color = cText
        val ax = h / 2; val ay = h / 2; val a = h * 0.16f
        c.drawLine(ax - a, ay, ax + a, ay, stroke)
        c.drawLine(ax - a, ay, ax - a * 0.3f, ay - a * 0.7f, stroke)
        c.drawLine(ax - a, ay, ax - a * 0.3f, ay + a * 0.7f, stroke)
        drawLock(c, h + dp(10f), h / 2, dp(6f), cTextDim)
        text.color = cText; text.textSize = spx(16f)
        fitText(safeStatus, width - 3 * h, spx(16f))
        drawCentered(c, safeStatus, width / 2f, h / 2)
        if (safeBio) drawFingerprint(c, width - h / 2, h / 2, h * 0.26f)
    }
    var clipTitle = "Буфер обмена · только в памяти"
    var clipLock = true
        set(v) { field = v; invalidate() }

    private fun drawPin(c: Canvas, cx: Float, cy: Float, r: Float, pinned: Boolean) {
        val col = if (pinned) cAccent else cTextDim
        stroke.color = col; fill.color = col
        c.drawLine(cx - r * 0.2f, cy + r * 0.2f, cx - r, cy + r, stroke) // игла
        path.reset()
        path.moveTo(cx - r * 0.75f, cy - r * 0.05f)
        path.lineTo(cx + r * 0.05f, cy + r * 0.75f)
        path.lineTo(cx + r * 0.35f, cy + r * 0.15f)
        path.lineTo(cx + r * 0.95f, cy - r * 0.45f)
        path.lineTo(cx + r * 0.45f, cy - r * 0.95f)
        path.lineTo(cx - r * 0.15f, cy - r * 0.35f)
        path.close()
        if (pinned) c.drawPath(path, fill) else c.drawPath(path, stroke)
        stroke.color = cText; fill.color = cText
    }

    private fun clipAt(y: Float): Int? {
        val i = ((y - emojiTop() + emojiScroll) / clipRowH).toInt()
        return i.takeIf { y >= emojiTop() && it in clipList.indices }
    }

    private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()

    private fun ellipsize(s: String, maxW: Float): String {
        if (text.measureText(s) <= maxW) return s
        val n = text.breakText(s, true, maxW - text.measureText("…"), null)
        return s.take(n).trimEnd() + "…"
    }

    private fun drawClips(c: Canvas) {
        text.color = cTextDim; text.textSize = spx(14f)
        // Шапка. В буфере — подпись и неприметный замок справа (открыть сейф); в сейфе — строка «Новая запись»
        // со стрелкой назад слева (сейф при этом запирается).
        val h0 = tabH
        if (layout.mode == Mode.SAFE) drawEntryBar(c, "", lock = true)
        else {
            if (clipTitle.isNotEmpty()) {
                fitText(clipTitle, width - 2 * h0 - dp(8f), spx(14f))
                drawCentered(c, clipTitle, width / 2f, h0 / 2)
            }
            if (clipLock) drawLock(c, width - h0 / 2, h0 / 2 + dp(1f), dp(6.5f), cTextDim)
        }
        text.color = cTextDim; text.textSize = spx(14f)
        val top = emojiTop()
        val bottom = emojiBottom()
        if (clipList.isEmpty()) {
            text.color = cTextDim; text.textSize = spx(15f)
            drawCentered(c, clipEmpty, width / 2f, (top + bottom) / 2)
            return
        }
        c.save()
        c.clipRect(0f, top, width.toFloat(), bottom)
        val h = clipRowH
        val first = (emojiScroll / h).toInt()
        val last = min(clipList.size - 1, ((emojiScroll + bottom - top) / h).toInt())
        text.textSize = spx(15f)
        for (i in first..last) {
            val y = top + i * h - emojiScroll
            fill.color = cKey
            rect.set(dp(8f), y + dp(4f), width - dp(8f), y + h - dp(4f))
            c.drawRoundRect(rect, radius, radius, fill)
            text.color = cText
            text.textAlign = Paint.Align.LEFT
            val row = clipList[i]
            var tx = dp(20f)
            row.thumb?.let { bm ->
                val s = h - dp(14f)
                val k = min(s / bm.width, s / bm.height)
                val bw = bm.width * k; val bh = bm.height * k
                rect.set(dp(14f) + (s - bw) / 2, y + (h - bh) / 2, dp(14f) + (s + bw) / 2, y + (h + bh) / 2)
                c.drawBitmap(bm, null, rect, null)
                tx = dp(14f) + s + dp(12f)
            }
            text.color = when { row.warn -> cDanger; row.thumb != null -> cTextDim; else -> cText }
            val l = ellipsize(oneLine(row.text), width - tx - dp(4f) - clipBtnW)
            c.drawText(l, tx, y + h / 2 - (text.descent() + text.ascent()) / 2, text)
            text.textAlign = Paint.Align.CENTER
            if (!clipButtons) {
                val xc = width - clipCloseW / 2 - dp(4f)
                val r = dp(5f)
                stroke.color = if (row.warn) cDanger else cTextDim
                c.drawLine(xc - r, y + h / 2 - r, xc + r, y + h / 2 + r, stroke)
                c.drawLine(xc - r, y + h / 2 + r, xc + r, y + h / 2 - r, stroke)
                stroke.color = cText
                continue
            }
            drawLock(c, width - clipCloseW * 2.5f - dp(4f), y + h / 2, dp(6f), cTextDim)
            drawPin(c, width - clipCloseW * 1.5f - dp(4f), y + h / 2, dp(7f), row.pinned)
            val xc = width - clipCloseW / 2 - dp(4f)
            val r = dp(5f)
            stroke.color = cTextDim
            c.drawLine(xc - r, y + h / 2 - r, xc + r, y + h / 2 + r, stroke)
            c.drawLine(xc - r, y + h / 2 + r, xc + r, y + h / 2 - r, stroke)
            stroke.color = cText
        }
        c.restore()
    }

    private fun drawClipPreview(c: Canvas) {
        val sl = clipPreviewLayout ?: return
        clipPreviewBounds()
        val cardLeft = rect.left
        val cardTop = rect.top
        val cardRight = rect.right
        val cardBottom = rect.bottom
        fill.color = cBg
        fill.alpha = 245
        c.drawRoundRect(rect, dp(12f), dp(12f), fill)
        fill.alpha = 255
        stroke.color = cBubble
        c.drawRoundRect(rect, dp(12f), dp(12f), stroke)
        stroke.color = cText
        val pad = dp(16f)
        c.save()
        c.clipRect(cardLeft, cardTop, cardRight, cardBottom)
        val maxScroll = clipPreviewMax()
        val scroll = clipPreviewScroll.coerceIn(0f, maxScroll)
        c.translate(cardLeft + pad, cardTop + pad - scroll)
        sl.paint.color = cText
        sl.draw(c)
        c.restore()
        // Тонкая полоска прокрутки справа — видно, что текст длиннее окна.
        if (maxScroll > 0f) {
            val trackTop = cardTop + dp(6f)
            val trackH = (cardBottom - cardTop) - dp(12f)
            val visible = clipPreviewVisible()
            val total = sl.height + visible
            val thumbH = max(dp(18f), trackH * visible / total)
            val thumbTop = trackTop + (trackH - thumbH) * (scroll / maxScroll)
            fill.color = cTextDim
            rect.set(cardRight - dp(5f), thumbTop, cardRight - dp(2f), thumbTop + thumbH)
            c.drawRoundRect(rect, dp(1.5f), dp(1.5f), fill)
        }
    }

    private fun drawClipboard(c: Canvas, cx: Float, cy: Float, s: Float) {
        stroke.color = cText
        rect.set(cx - s * 0.8f, cy - s, cx + s * 0.8f, cy + s * 1.1f)
        c.drawRoundRect(rect, s * 0.2f, s * 0.2f, stroke)
        rect.set(cx - s * 0.4f, cy - s * 1.25f, cx + s * 0.4f, cy - s * 0.75f)
        fill.color = cText
        c.drawRoundRect(rect, s * 0.15f, s * 0.15f, fill)
        c.drawLine(cx - s * 0.4f, cy - s * 0.1f, cx + s * 0.4f, cy - s * 0.1f, stroke)
        c.drawLine(cx - s * 0.4f, cy + s * 0.4f, cx + s * 0.2f, cy + s * 0.4f, stroke)
    }

    // ================= форма ИИ =================

    private val aiHeadH get() = dp(36f)
    private val aiActionH get() = dp(48f)
    private val aiBtnW get() = tabH
    private val aiDotW get() = dp(16f)
    private val aiDotGap get() = dp(5f)
    private val aiDotPad get() = dp(10f)

    private fun aiHeadRight() = StripSide.right(oneHand, aiKeyRight, panel())
    private fun aiBtnX() = if (aiHeadRight()) width - aiBtnW else 0f
    private fun atAiBtn(x: Float) = x >= aiBtnX() && x < aiBtnX() + aiBtnW

    /**
     * Точки-вкладки стоят у края, противоположного кнопке «ИИ»: у кнопки своя роль, а точки видны
     * там, куда палец приходит за действиями. Между собой они идут вплотную (узкие плашки с малым зазором) —
     * так их ряд читается одним компактным блоком, а не размазанными по всей шапке метками.
     */
    private fun aiDotsRight() = !aiHeadRight()
    private fun aiDotsW() = aiTabs.size * aiDotW + maxOf(0, aiTabs.size - 1) * aiDotGap
    private fun aiDotsX() = if (aiDotsRight()) width - aiDotPad - aiDotsW() else aiDotPad
    /** Точка под пальцем: -1 — не по точкам, -2 — кнопка «ИИ». */
    private fun aiDotAt(x: Float): Int {
        if (atAiBtn(x)) return -2
        if (aiTabs.size < 2) return -1
        val i = ((x - aiDotsX() + aiDotGap / 2) / (aiDotW + aiDotGap)).toInt()
        return if (i in aiTabs.indices) i else -1
    }

    // ---------- режим одного столбца: сквозное листание и боковые шорткаты ----------

    /** Показанные столбцы всех вкладок подряд: (вкладка, столбец). Свайп в режиме одного столбца листает по ним. */
    private fun aiFlat(): List<Pair<Int, Int>> = aiFlatCache ?: buildList {
        for ((t, tab) in aiTabs.withIndex()) for (c in tab.cols.indices) if (tab.cols[c].isNotEmpty()) add(t to c)
    }.also { aiFlatCache = it }

    private fun aiCurIdx() = if (aiSoloOn()) aiPosSafe() else aiTab
    private fun aiSoloOn() = aiFlat().isNotEmpty() && (aiSoloCols.any { it >= 0 } || aiFlat().size == 1)
    private fun aiPosSafe() = aiPos.coerceIn(0, max(0, aiFlat().size - 1))

    private fun aiSync() {
        if (!aiSoloOn()) { aiPopupSide = -1; return }
        val flat = aiFlat()
        val t = if (aiSoloCols.getOrElse(aiTab) { -1 } >= 0) aiTab else aiSoloCols.indexOfFirst { it >= 0 }
        val i = if (t < 0) 0 else flat.indexOf(t to aiSoloCols.getOrElse(t) { -1 })
        aiPos = if (i >= 0) i else aiPosSafe()
        aiTab = flat[aiPosSafe()].first
    }

    /** Показываемый столбец принудительно (рисуем соседнюю модель при листании). */
    private var aiDrawPos = -1

    private fun aiGoTab(i: Int) {
        if (aiSoloOn()) {
            val k = aiFlat().indexOfFirst { it.first == i }
            if (k >= 0) { aiPos = k; resetAiScroll() }
        }
        aiTab = i
    }

    private fun animateSoloSlide(to: Int) {
        val end = when { to > aiPosSafe() -> -width.toFloat(); to < aiPosSafe() -> width.toFloat(); else -> 0f }
        aiSwitchTo = to
        aiScroller.forceFinished(true)
        aiScroller.startScroll(0, aiOffset.toInt(), 0, (end - aiOffset).toInt(), 180)
        postInvalidateOnAnimation()
    }

    private fun pageBy(d: Int) {
        val to = aiPosSafe() + d
        if (to !in aiFlat().indices || aiSwitchTo != null) return
        animateSoloSlide(to)
    }

    private val sideBtn get() = dp(40f)
    private val sideGap get() = dp(8f)
    private val sideMargin get() = dp(6f)

    private fun aiSideOn() = layout.mode == Mode.AI && aiSoloOn()
    private fun sideIds(s: Int) = if (s == 0) aiSideLeft else aiSideRight
    private fun sideAreaTop() = aiTop() + dp(4f)
    private fun sideAreaBottom() = aiBottom() - dp(4f)
    private fun sideCap() = AiSideLayout.capacity(sideAreaBottom() - sideAreaTop(), sideBtn, sideGap)
    private fun sideShown(s: Int) = sideIds(s).take(sideCap())
    private fun sideHasPlus(s: Int) = sideShown(s).size < sideCap() && AiShortcut.ALL.any { it !in sideIds(s) }

    /** Кнопки стороны сверху вниз: шорткаты, затем «+» (если место и шорткаты ещё есть). */
    private fun sideRects(s: Int): List<RectF> {
        val n = sideShown(s).size + if (sideHasPlus(s)) 1 else 0
        val tops = AiSideLayout.tops(sideAreaTop(), sideAreaBottom(), sideBtn, sideGap, n)
        val x = if (s == 0) aiKbX() + sideMargin else aiKbX() + aiKbW() - sideMargin - sideBtn
        return tops.map { RectF(x, it, x + sideBtn, it + sideBtn) }
    }

    /** Кнопка под точкой: (сторона, номер); номер == число шорткатов — это «+». */
    private fun sideItemAt(x: Float, y: Float): Pair<Int, Int>? {
        for (s in 0..1) sideRects(s).forEachIndexed { i, r -> if (r.contains(x, y)) return s to i }
        return null
    }

    private fun setSide(s: Int, ids: List<String>) {
        if (s == 0) aiSideLeft = ids else aiSideRight = ids
        listener.onAiSideChanged(s == 1, ids)
    }

    private fun popupIds() = AiShortcut.ALL.filter { it !in sideIds(aiPopupSide) }
    private fun popupRect(): RectF {
        val rows = popupIds().size
        val w = min(dp(240f), width - dp(24f))
        val h = rows * dp(46f) + dp(8f)
        val cx = aiKbX() + aiKbW() / 2f
        val cy = (aiTop() + aiBottom()) / 2f
        return RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }
    private fun popupRowAt(x: Float, y: Float): Int? {
        val r = popupRect()
        if (!r.contains(x, y)) return null
        return ((y - r.top - dp(4f)) / dp(46f)).toInt().takeIf { it in popupIds().indices }
    }

    private fun aiSideUp(p: Ptr, x: Float, y: Float) {
        if (p.aiPop) {
            val i = popupRowAt(x, y)
            if (i != null && i == popupRowAt(p.downX, p.downY)) {
                val s = aiPopupSide
                val id = popupIds()[i]
                listener.onFeedback(null)
                setSide(s, sideIds(s) + id)
            }
            aiPopupSide = -1
            return
        }
        if (p.aiHeld) return
        val hit = sideItemAt(x, y) ?: return
        if (hit != (p.aiSide to p.aiSideIdx)) return
        listener.onFeedback(null)
        val ids = sideShown(hit.first)
        if (hit.second >= ids.size) { aiPopupSide = hit.first; return }
        when (val id = ids[hit.second]) {
            AiShortcut.LEFT -> pageBy(1)
            AiShortcut.RIGHT -> pageBy(-1)
            else -> listener.onAiShortcut(id)
        }
    }

    private fun scheduleSideRemove(p: Ptr) {
        cancelAiHold()
        val r = Runnable {
            if (!ptrs.contains(p)) return@Runnable
            val ids = sideShown(p.aiSide)
            val id = ids.getOrNull(p.aiSideIdx) ?: return@Runnable
            p.aiHeld = true
            listener.onFeedback(null)
            setSide(p.aiSide, sideIds(p.aiSide).filter { it != id })
            invalidate()
        }
        aiHoldRun = r
        handler.postDelayed(r, AI_HOLD_MS)
    }

    private fun sideLabel(id: String): String = context.getString(when (id) {
        AiShortcut.CLIP -> R.string.ai_sc_clip
        AiShortcut.SAFE -> R.string.ai_sc_safe
        AiShortcut.SETTINGS -> R.string.ai_sc_settings
        AiShortcut.EMOJI -> R.string.ai_sc_emoji
        AiShortcut.LEFT -> R.string.ai_sc_left
        else -> R.string.ai_sc_right
    })

    private fun drawSideIcon(c: Canvas, id: String, cx: Float, cy: Float, r: Float) {
        stroke.color = cText
        when (id) {
            AiShortcut.CLIP -> drawClipboard(c, cx, cy, r * 0.8f)
            AiShortcut.SAFE -> drawLock(c, cx, cy - r * 0.15f, r * 0.7f, cText)
            AiShortcut.EMOJI -> drawSmiley(c, cx, cy, r * 0.9f)
            AiShortcut.SETTINGS -> {
                c.drawCircle(cx, cy, r * 0.42f, stroke)
                c.drawCircle(cx, cy, r * 0.85f, stroke)
                for (i in 0 until 8) {
                    val a = i * Math.PI / 4
                    val ca = Math.cos(a).toFloat(); val sa = Math.sin(a).toFloat()
                    c.drawLine(cx + ca * r * 0.85f, cy + sa * r * 0.85f, cx + ca * r * 1.1f, cy + sa * r * 1.1f, stroke)
                }
            }
            AiShortcut.LEFT, AiShortcut.RIGHT -> {
                val d = if (id == AiShortcut.LEFT) -1f else 1f
                c.drawLine(cx + d * r * 0.4f, cy - r * 0.7f, cx - d * r * 0.4f, cy, stroke)
                c.drawLine(cx - d * r * 0.4f, cy, cx + d * r * 0.4f, cy + r * 0.7f, stroke)
            }
            else -> { // «+»
                stroke.color = cTextDim
                c.drawLine(cx - r * 0.6f, cy, cx + r * 0.6f, cy, stroke)
                c.drawLine(cx, cy - r * 0.6f, cx, cy + r * 0.6f, stroke)
                stroke.color = cText
            }
        }
    }

    private fun drawAiSide(c: Canvas) {
        if (!aiSideOn()) return
        val pressed = ptrs.firstOrNull { it.aiHead == -3 && !it.aiPop && !it.aiHeld }
        for (s in 0..1) {
            val ids = sideShown(s)
            sideRects(s).forEachIndexed { i, r ->
                val plus = i >= ids.size
                val down = pressed != null && pressed.aiSide == s && pressed.aiSideIdx == i
                fill.color = if (down) cPressed else if (plus) cBubble else cKey
                c.drawRoundRect(r, radius, radius, fill)
                drawSideIcon(c, if (plus) "+" else ids[i], r.centerX(), r.centerY(), sideBtn * 0.26f)
            }
        }
        if (aiPopupSide < 0) return
        val r = popupRect()
        fill.color = cBg; fill.alpha = 250
        c.drawRoundRect(r, dp(12f), dp(12f), fill)
        fill.alpha = 255
        stroke.color = cBubble
        c.drawRoundRect(r, dp(12f), dp(12f), stroke)
        stroke.color = cText
        text.textAlign = Paint.Align.LEFT
        popupIds().forEachIndexed { i, id ->
            val cy = r.top + dp(4f) + i * dp(46f) + dp(23f)
            drawSideIcon(c, id, r.left + dp(30f), cy, dp(10f))
            text.color = cText; text.textSize = spx(15f)
            c.drawText(sideLabel(id), r.left + dp(56f), cy - (text.descent() + text.ascent()) / 2, text)
        }
        text.textAlign = Paint.Align.CENTER
    }

    private fun aiTop() = topH
    /**
     * Низ формы. Место кнопок системной навигации под ней не занимаем (их не закрываем); когда
     * кнопок нет (жестовая навигация), форма доходит до самого низа окна: полосу с кнопкой выбора
     * клавиатуры она и закрывает — на её высоту окно выше (см. bottomInset).
     */
    private fun aiBottom() = height.toFloat() - bottomPad - (if (navTap > 0) navInset.toFloat() else 0f)
    private fun aiMaxScroll(): Float = aiTabScrollMax()
    private fun aiScrollOf(col: Int): Float = aiScroll.getOrElse(col) { 0f }
    private fun resetAiScroll() {
        for (i in 0 until AI_COLS) { aiFling[i].forceFinished(true); aiScroll[i] = 0f }
    }

    /**
     * Столбцы вкладки: одна модель — один столбец, две — два. В паре место закреплено за моделью:
     * у модели без своих действий столбец остаётся пустым (заголовка нет), второй столбец на месте.
     */
    private fun aiFullCols(tab: Int = aiTab): List<String> = aiTabs.getOrNull(tab)?.cols ?: emptyList()

    private fun aiColRows(tab: Int, col: Int): IntArray = aiColActions.getOrNull(tab)?.getOrNull(col) ?: IntArray(0)

    private fun aiColShown(tab: Int, col: Int): Boolean = aiFullCols(tab).getOrNull(col).orEmpty().isNotEmpty()

    private fun aiTabScrollMax(tab: Int = aiTab): Float {
        var rows = 0
        for (col in aiFullCols(tab).indices) rows = maxOf(rows, aiColRows(tab, col).size)
        if (rows == 0) return 0f
        return max(0f, aiHeadH + rows * aiActionH - (aiBottom() - aiTop()))
    }

    /**
     * Видимые столбцы вкладки — их номера в полном списке. Владелец может оставить один
     * (удержав название): тогда второй не показываем совсем. Пустой столбец в одиночку не оставляем.
     */
    private fun aiVisible(tab: Int = aiTab): List<Int> {
        val n = aiFullCols(tab).size
        if (aiSoloOn()) {
            val (t, c) = aiFlat()[if (aiDrawPos >= 0) aiDrawPos.coerceIn(0, aiFlat().size - 1) else aiPosSafe()]
            return if (tab == t) listOf(c) else emptyList()
        }
        val solo = aiSoloCols.getOrElse(tab) { -1 }
        if (n > 1 && solo in 0 until n && aiColShown(tab, solo)) return listOf(solo)
        return (0 until n).toList()
    }

    /**
     * Ширина столбца. Две модели: столбцы съезжают в сторону выбранной руки и сужаются вместе с ней.
     * Одна модель: кнопки той же ширины, что и в двух столбцах, — столбец просто встаёт по центру
     * (в режиме одной рукой — по центру своей половины, то есть смещается в нужную сторону).
     */
    private fun aiColW(tab: Int = aiTab): Float {
        val n = aiVisible(tab).size
        return if (n <= 1) width.toFloat() / AI_COLS else aiKbW() / n
    }
    private fun aiColLeft(tab: Int = aiTab): Float {
        val w = aiColW(tab)
        return if (aiVisible(tab).size > 1) aiKbX() else aiKbX() + (aiKbW() - w) / 2f
    }

    private fun aiKbW(): Float = if (oneHand == 0) width.toFloat() else width * ONE_HAND_WIDTH
    private fun aiKbX(): Float = if (oneHand == 2) width - aiKbW() else 0f

    private fun aiColAt(x: Float, tab: Int = aiTab): Int {
        val w = aiColW(tab)
        val dx = x - aiColLeft(tab)
        // Левее столбца: toInt() округляет к нулю, и пустое место слева считалось первым столбцом.
        if (dx < 0f) return -1
        val i = (dx / w).toInt()
        return if (i in aiVisible(tab).indices) i else -1
    }

    private fun aiFullCol(tab: Int, pos: Int): Int =
        aiVisible(tab).getOrElse(pos) { -1 }

    /**
     * Что под пальцем в панели: столбец и строка действия, либо название модели (row = -1).
     * Столбец — номер в полном списке вкладки (у него своя прокрутка); row — номер строки в столбце,
     * -2 — строки в этом столбце нет (пустой столбец или ниже списка).
     */
    private fun aiCellAt(x: Float, y: Float): Triple<Int, Int, Int>? {
        val pos = aiColAt(x)
        if (pos < 0) return null
        val col = aiFullCol(aiTab, pos)
        if (col < 0) return null
        if (!aiColShown(aiTab, col)) return Triple(col, -2, -2)
        val yy = y - aiTop() + aiScrollOf(col)
        if (yy < aiHeadH) return Triple(col, -1, -1)
        val i = ((yy - aiHeadH) / aiActionH).toInt()
        val row = aiColRows(aiTab, col).getOrNull(i) ?: return Triple(col, -2, -2)
        return Triple(col, i, row)
    }

    private fun aiCovered(col: Int): Float =
        aiScrollOf(col).coerceIn(0f, aiHeadH)

    private fun aiHeadCovered(col: Int): Boolean = aiScrollOf(col) > 1f

    /** Задержали название модели — оставляем только этот столбец (или возвращаем все, если он уже один). */
    private fun scheduleAiHold(p: Ptr) {
        cancelAiHold()
        if (p.aiCol < 0 || aiHeadCovered(p.aiCol) || !aiColShown(aiTab, p.aiCol)) return
        val r = Runnable {
            if (p.done || p.aiScrolled || p.aiSwiped || !ptrs.contains(p) || p.aiHoldCol != p.aiCol) return@Runnable
            p.aiHeld = true
            listener.onFeedback(null)
            if (aiSoloCols.any { it >= 0 }) {
                // Уже один столбец: удержание возвращает все столбцы на всех вкладках.
                for (t in aiSoloCols.indices) if (aiSoloCols[t] >= 0) listener.onAiSolo(t, -1)
            } else if (aiFullCols(aiTab).size > 1) listener.onAiSolo(aiTab, p.aiCol)
            invalidate()
        }
        aiHoldRun = r
        handler.postDelayed(r, AI_HOLD_MS)
    }

    private fun cancelAiHold() {
        aiHoldRun?.let { handler.removeCallbacks(it) }
        aiHoldRun = null
    }

    private fun rubberAi(dx: Float): Float = when {
        aiSoloOn() -> when {
            aiFlat().size < 2 -> 0f
            dx > 0f && aiPosSafe() == 0 -> dx * 0.25f
            dx < 0f && aiPosSafe() == aiFlat().size - 1 -> dx * 0.25f
            else -> dx
        }
        aiTabs.size < 2 -> 0f
        dx > 0f && aiTab == 0 -> dx * 0.25f
        dx < 0f && aiTab == aiTabs.size - 1 -> dx * 0.25f
        else -> dx
    }

    private fun finishAiSlide() {
        val to = aiSwitchTo
        aiSwitchTo = null
        aiOffset = 0f
        if (aiSoloOn()) {
            if (to != null && to != aiPosSafe()) {
                aiPos = to.coerceIn(0, aiFlat().size - 1)
                aiTab = aiFlat()[aiPos].first
                resetAiScroll()
            }
        } else if (to != null && to != aiTab) aiTab = to
        invalidate()
    }

    private fun cancelAiSlide() {
        if (aiSwitchTo == null && aiOffset == 0f) return
        animateAiSlide(aiTab)
    }

    private fun animateAiSlide(to: Int) {
        aiSwitchTo = to
        aiScroller.forceFinished(true)
        aiScroller.startScroll(0, aiOffset.toInt(), 0, (-aiOffset).toInt(), 180)
        postInvalidateOnAnimation()
    }

    private fun drawAiHeader(c: Canvas) {
        val h = tabH
        val bx = aiBtnX()
        if (ptrs.any { it.aiHead == -2 }) {
            fill.color = cPressed
            rect.set(bx + dp(4f), dp(5f), bx + aiBtnW - dp(2f), h - dp(5f))
            c.drawRoundRect(rect, dp(8f), dp(8f), fill)
        }
        text.color = cText; text.textSize = spx(17f); text.typeface = Typeface.DEFAULT_BOLD
        drawCentered(c, aiButtonLabel(), bx + aiBtnW / 2 + dp(1f), h / 2)
        text.typeface = Typeface.DEFAULT
        val n = aiTabs.size
        if (n < 2) return
        val x0 = aiDotsX()
        val pressed = ptrs.firstOrNull { it.aiHead >= 0 }?.aiHead ?: -1
        for (i in 0 until n) {
            val cx = x0 + i * (aiDotW + aiDotGap) + aiDotW / 2
            val on = i == aiTab && aiSwitchTo == null
            fill.color = when {
                i == pressed -> cPressed
                on -> cAccent
                else -> cBubble
            }
            rect.set(cx - aiDotW / 2, h / 2 - dp(3f), cx + aiDotW / 2, h / 2 + dp(3f))
            c.drawRoundRect(rect, dp(3f), dp(3f), fill)
        }
    }

    private fun drawAiPanel(c: Canvas) {
        c.save()
        c.clipRect(0f, aiTop(), width.toFloat(), aiBottom())
        c.save(); c.translate(aiOffset, 0f); drawAiTab(c, aiTab); c.restore()
        val to = aiSwitchTo
        if (aiSoloOn()) {
            if (to != null && to != aiPosSafe() && to in aiFlat().indices) {
                val dx = if (to > aiPosSafe()) aiOffset + width else aiOffset - width
                aiDrawPos = to
                c.save(); c.translate(dx, 0f); drawAiTab(c, aiFlat()[to].first); c.restore()
                aiDrawPos = -1
            }
        } else if (to != null && to != aiTab) {
            val dx = if (to > aiTab) aiOffset + width else aiOffset - width
            c.save(); c.translate(dx, 0f); drawAiTab(c, to); c.restore()
        }
        c.restore()
    }

    private fun drawAiTab(c: Canvas, tab: Int) {
        val full = aiFullCols(tab)
        val vis = aiVisible(tab)
        val top = aiTop()
        val bottom = aiBottom()
        if (full.isEmpty() || aiActions.isEmpty()) {
            text.color = cTextDim; text.textSize = spx(15f)
            drawCentered(c, aiEmptyText, width / 2f, (top + bottom) / 2)
            return
        }
        val colW = aiColW(tab)
        val left0 = aiColLeft(tab)
        val headCol = pressedHead(tab)
        c.save()
        c.clipRect(0f, top, width.toFloat(), bottom)
        for ((pos, col) in vis.withIndex()) {
            val left = left0 + pos * colW
            if (!aiColShown(tab, col)) continue // у модели нет своих действий — столбец остаётся пустым
            val headBottom = top + aiHeadH - aiCovered(col)
            if (headCol == col && headBottom > top + dp(5f)) {
                fill.color = cPressed
                rect.set(left + dp(4f), top + dp(3f), left + colW - dp(4f), headBottom - dp(3f))
                c.drawRoundRect(rect, dp(8f), dp(8f), fill)
            }
            drawAiColHead(c, full[col], left, colW, top, aiCovered(col))
            if (vis.size > 1 && pos == 0) {
                fill.color = cBubble
                c.drawRect(left + colW - dp(0.5f), top + dp(8f), left + colW + dp(0.5f), bottom - dp(8f), fill)
            }
        }
        val cell = cpressedCell(tab)
        for ((pos, col) in vis.withIndex()) {
            if (!aiColShown(tab, col)) continue
            val left = left0 + pos * colW
            val rows = aiColRows(tab, col)
            for ((i, ri) in rows.withIndex()) {
                val title = aiActions.getOrNull(ri) ?: continue
                val y = top + aiHeadH + i * aiActionH - aiScrollOf(col)
                if (y + aiActionH < top || y > bottom) continue
                fill.color = if (cell != null && cell.first == col && cell.second == i) cPressed else cKey
                rect.set(left + dp(6f), y + dp(3f), left + colW - dp(6f), y + aiActionH - dp(3f))
                c.drawRoundRect(rect, radius, radius, fill)
                text.color = cText; text.textSize = spx(15f)
                fitText(title, colW - dp(24f), spx(15f))
                drawCentered(c, title, left + colW / 2, y + aiActionH / 2)
            }
        }
        c.restore()
    }

    /**
     * Название модели над столбцом. Кнопки при прокрутке наезжают на него — заголовок этого не терпит:
     * плавно бледнеет и чуть сжимается, а когда перекрыт полностью — исчезает (и удержание по нему не работает).
     */
    private fun drawAiColHead(c: Canvas, title: String, left: Float, colW: Float, top: Float, covered: Float) {
        val k = 1f - (covered / aiHeadH).coerceIn(0f, 1f)
        if (k <= 0f) return
        val alpha = (255 * k).toInt().coerceIn(0, 255)
        val scale = AI_HEAD_MIN_SCALE + (1f - AI_HEAD_MIN_SCALE) * k
        c.save()
        c.scale(scale, scale, left + colW / 2f, top + aiHeadH / 2f)
        text.color = cText
        text.alpha = alpha
        text.textSize = spx(AI_HEAD_SP)
        text.typeface = Typeface.DEFAULT_BOLD
        fitText(title, colW - dp(20f), spx(AI_HEAD_SP))
        drawCentered(c, title, left + colW / 2, top + aiHeadH / 2)
        text.typeface = Typeface.DEFAULT
        text.alpha = 255
        c.restore()
    }

    private fun cpressedCell(tab: Int): Pair<Int, Int>? {
        val p = ptrs.firstOrNull { it.aiPanel && !it.aiScrolled && !it.aiSwiped && !it.aiHeld } ?: return null
        if (ptrs.size != 1) return null
        if (tab != aiTab) return null
        val c = aiCellAt(p.downX, p.downY) ?: return null
        return if (c.second >= 0) c.first to c.second else null
    }

    private fun pressedHead(tab: Int): Int {
        val p = ptrs.firstOrNull { it.aiPanel && !it.aiScrolled && !it.aiSwiped && !it.aiHeld } ?: return -1
        if (ptrs.size != 1 || tab != aiTab) return -1
        val c = aiCellAt(p.downX, p.downY) ?: return -1
        return if (c.second == -1) c.first else -1
    }

    private fun drawEmoji(c: Canvas) {
        ensureGrid()
        val cols = emojiCols()
        val cell = width.toFloat() / cols
        val top = emojiTop()
        val bottom = emojiBottom()
        if (grid.isEmpty()) {
            text.color = cTextDim; text.textSize = spx(15f)
            drawCentered(c, "…", width / 2f, (top + bottom) / 2)
            return
        }
        c.save()
        c.clipRect(0f, top, width.toFloat(), bottom)
        text.textSize = min(cell * 0.62f, spx(28f))
        text.color = cText
        val firstRow = (emojiScroll / cell).toInt()
        val lastRow = ((emojiScroll + bottom - top) / cell).toInt() + 1
        for (r in firstRow..lastRow) for (col in 0 until cols) {
            val e = grid.getOrNull(r * cols + col) ?: continue
            val cx = col * cell + cell / 2
            val cy = top + r * cell - emojiScroll + cell / 2
            drawCentered(c, e, cx, cy)
        }
        c.restore()
    }

    private fun drawTabs(c: Canvas) {
        if (emojiSections.isEmpty()) return
        val n = tabCount()
        val w = width.toFloat() / n
        val cur = currentSection()
        val pressed = ptrs.firstOrNull { it.tab >= 0 }?.tab ?: -1
        val cy = tabH * 1.5f
        for (i in 0 until n) {
            val cx = i * w + w / 2
            if (i == cur || i == pressed) {
                fill.color = if (i == pressed) cPressed else cBubble
                val r = min(w, tabH) * 0.42f
                rect.set(cx - r, cy - r, cx + r, cy + r)
                c.drawRoundRect(rect, dp(8f), dp(8f), fill)
            }
            drawTabIcon(c, emojiSections[i].id, cx, cy, min(w * 0.22f, dp(9f)), if (i == cur) cText else cTextDim)
        }
    }

    private fun drawTabIcon(c: Canvas, id: String, cx: Float, cy: Float, r: Float, col: Int) {
        stroke.color = col; fill.color = col
        when (id) {
            "recent" -> { // часы
                c.drawCircle(cx, cy, r, stroke)
                c.drawLine(cx, cy, cx, cy - r * 0.6f, stroke)
                c.drawLine(cx, cy, cx + r * 0.45f, cy + r * 0.2f, stroke)
            }
            "smileys" -> drawSmiley(c, cx, cy, r, col)
            "people" -> { // голова и плечи
                c.drawCircle(cx, cy - r * 0.42f, r * 0.42f, stroke)
                rect.set(cx - r * 0.85f, cy + r * 0.15f, cx + r * 0.85f, cy + r * 1.7f)
                c.drawArc(rect, 180f, 180f, false, stroke)
            }
            "animals" -> { // лапа
                rect.set(cx - r * 0.5f, cy - r * 0.05f, cx + r * 0.5f, cy + r * 0.85f)
                c.drawOval(rect, fill)
                for ((dx, dy) in listOf(-0.78f to -0.25f, -0.3f to -0.75f, 0.3f to -0.75f, 0.78f to -0.25f)) {
                    c.drawCircle(cx + dx * r, cy + dy * r, r * 0.22f, fill)
                }
            }
            "food" -> { // чашка
                path.reset()
                path.moveTo(cx - r * 0.75f, cy - r * 0.35f)
                path.lineTo(cx - r * 0.6f, cy + r * 0.75f)
                path.lineTo(cx + r * 0.4f, cy + r * 0.75f)
                path.lineTo(cx + r * 0.55f, cy - r * 0.35f)
                path.close()
                c.drawPath(path, stroke)
                rect.set(cx + r * 0.35f, cy - r * 0.15f, cx + r * 1.0f, cy + r * 0.45f)
                c.drawArc(rect, -90f, 180f, false, stroke)
                c.drawLine(cx - r * 0.35f, cy - r * 0.65f, cx - r * 0.35f, cy - r * 1.0f, stroke)
                c.drawLine(cx + r * 0.1f, cy - r * 0.65f, cx + r * 0.1f, cy - r * 1.0f, stroke)
            }
            "travel" -> { // машина
                path.reset()
                path.moveTo(cx - r, cy + r * 0.45f)
                path.lineTo(cx - r, cy - r * 0.05f)
                path.lineTo(cx - r * 0.6f, cy - r * 0.15f)
                path.lineTo(cx - r * 0.35f, cy - r * 0.6f)
                path.lineTo(cx + r * 0.4f, cy - r * 0.6f)
                path.lineTo(cx + r * 0.7f, cy - r * 0.15f)
                path.lineTo(cx + r, cy - r * 0.05f)
                path.lineTo(cx + r, cy + r * 0.45f)
                path.close()
                c.drawPath(path, stroke)
                c.drawCircle(cx - r * 0.5f, cy + r * 0.55f, r * 0.22f, fill)
                c.drawCircle(cx + r * 0.5f, cy + r * 0.55f, r * 0.22f, fill)
            }
            "activities" -> { // мяч
                c.drawCircle(cx, cy, r, stroke)
                rect.set(cx - r * 1.6f, cy - r, cx - r * 0.2f, cy + r)
                c.drawArc(rect, -50f, 100f, false, stroke)
                rect.set(cx + r * 0.2f, cy - r, cx + r * 1.6f, cy + r)
                c.drawArc(rect, 130f, 100f, false, stroke)
            }
            "objects" -> { // лампочка
                rect.set(cx - r * 0.7f, cy - r, cx + r * 0.7f, cy + r * 0.4f)
                c.drawArc(rect, 140f, 260f, false, stroke)
                c.drawLine(cx - r * 0.35f, cy + r * 0.35f, cx - r * 0.35f, cy + r * 0.65f, stroke)
                c.drawLine(cx + r * 0.35f, cy + r * 0.35f, cx + r * 0.35f, cy + r * 0.65f, stroke)
                c.drawLine(cx - r * 0.35f, cy + r * 0.65f, cx + r * 0.35f, cy + r * 0.65f, stroke)
                c.drawLine(cx - r * 0.2f, cy + r * 0.95f, cx + r * 0.2f, cy + r * 0.95f, stroke)
            }
            "symbols" -> { // сердце
                path.reset()
                path.moveTo(cx, cy + r * 0.8f)
                path.cubicTo(cx - r * 1.4f, cy - r * 0.1f, cx - r * 0.7f, cy - r * 1.1f, cx, cy - r * 0.4f)
                path.cubicTo(cx + r * 0.7f, cy - r * 1.1f, cx + r * 1.4f, cy - r * 0.1f, cx, cy + r * 0.8f)
                c.drawPath(path, stroke)
            }
            "flags" -> { // флаг
                c.drawLine(cx - r * 0.7f, cy - r, cx - r * 0.7f, cy + r, stroke)
                path.reset()
                path.moveTo(cx - r * 0.7f, cy - r * 0.9f)
                path.lineTo(cx + r * 0.8f, cy - r * 0.9f)
                path.lineTo(cx + r * 0.45f, cy - r * 0.45f)
                path.lineTo(cx + r * 0.8f, cy)
                path.lineTo(cx - r * 0.7f, cy)
                c.drawPath(path, stroke)
            }
            else -> c.drawCircle(cx, cy, r * 0.3f, fill)
        }
        stroke.color = cText; fill.color = cText
    }

    /**
     * Строка поиска — одна и та же в сетке и при наборе. В сетке это приглашение (тап — печатаем запрос),
     * при наборе — запрос с курсором и крестиком.
     */
    private fun drawEmojiHeader(c: Canvas, typing: Boolean) {
        val h = tabH
        val pressed = ptrs.any { it.searchBar }
        fill.color = if (pressed && !typing) cPressed else cKey
        rect.set(dp(8f), dp(5f), width - dp(8f), h - dp(5f))
        c.drawRoundRect(rect, h / 2, h / 2, fill)
        drawMagnifier(c, dp(28f), h / 2, h * 0.13f)
        text.textAlign = Paint.Align.LEFT
        text.textSize = spx(16f)
        val tx = dp(44f)
        val baseY = h / 2 - (text.descent() + text.ascent()) / 2
        if (searchQuery.isEmpty() || !typing) {
            text.color = cTextDim
            c.drawText(searchHint, tx, baseY, text)
            if (typing) {
                fill.color = cAccent
                c.drawRect(tx - dp(3f), h * 0.3f, tx - dp(1f), h * 0.7f, fill)
            }
        } else {
            text.color = cText
            val shown = ellipsizeStart(searchQuery, width - tx - h - dp(8f))
            c.drawText(shown, tx, baseY, text)
            val end = tx + text.measureText(shown) + dp(2f)
            fill.color = cAccent
            c.drawRect(end - dp(1f), h * 0.3f, end + dp(1f), h * 0.7f, fill)
            val xc = width - h / 2 - dp(6f); val r = dp(5f)
            stroke.color = cTextDim
            c.drawLine(xc - r, h / 2 - r, xc + r, h / 2 + r, stroke)
            c.drawLine(xc - r, h / 2 + r, xc + r, h / 2 - r, stroke)
            stroke.color = cText
        }
        text.textAlign = Paint.Align.CENTER
    }

    private fun drawResults(c: Canvas) {
        val h = tabH
        val top = h
        if (searchResults.isEmpty()) {
            text.color = cTextDim; text.textSize = spx(14f)
            val msg = when {
                searchQuery.isNotBlank() -> "Ничего не нашлось"
                else -> "Начните печатать — эмодзи появятся здесь"
            }
            drawCentered(c, msg, width / 2f, top + h / 2)
            return
        }
        c.save()
        c.clipRect(0f, top, width.toFloat(), top + h)
        text.color = cText; text.textSize = spx(26f)
        val first = (resultsScroll / h).toInt()
        val last = min(searchResults.size - 1, ((resultsScroll + width) / h).toInt())
        for (i in first..last) drawCentered(c, searchResults[i], i * h - resultsScroll + h / 2, top + h / 2)
        c.restore()
    }

    private fun ellipsizeStart(s: String, maxW: Float): String {
        if (text.measureText(s) <= maxW) return s
        var t = s
        while (t.isNotEmpty() && text.measureText("…$t") > maxW) t = t.substring(1)
        return "…$t"
    }

    private fun drawTones(c: Canvas, p: Ptr) {
        val cw = tonesCellW()
        val h = dp(52f)
        val top = max(0f, p.downY - h - dp(28f))
        val left = p.popupLeft
        fill.color = cBubble
        rect.set(left - dp(4f), top - dp(4f), left + cw * p.tones.size + dp(4f), top + h + dp(4f))
        c.drawRoundRect(rect, dp(10f), dp(10f), fill)
        text.textSize = spx(26f); text.color = cText
        for ((i, e) in p.tones.withIndex()) {
            val x = left + i * cw
            if (i == p.popupIndex) {
                fill.color = cAccent
                rect.set(x + dp(1f), top, x + cw - dp(1f), top + h)
                c.drawRoundRect(rect, dp(7f), dp(7f), fill)
            }
            drawCentered(c, e, x + cw / 2, top + h / 2)
        }
    }

    private fun drawMagnifier(c: Canvas, cx: Float, cy: Float, r: Float) {
        stroke.color = cText
        c.drawCircle(cx - r * 0.25f, cy - r * 0.25f, r, stroke)
        c.drawLine(cx + r * 0.45f, cy + r * 0.45f, cx + r * 1.2f, cy + r * 1.2f, stroke)
    }

    // ---------- иконки ----------

    private fun drawShift(c: Canvas, cx: Float, cy: Float, s: Float) {
        val on = shift != Shift.OFF
        path.reset()
        path.moveTo(cx, cy - s * 0.55f)
        path.lineTo(cx + s * 0.42f, cy)
        path.lineTo(cx + s * 0.19f, cy)
        path.lineTo(cx + s * 0.19f, cy + s * 0.4f)
        path.lineTo(cx - s * 0.19f, cy + s * 0.4f)
        path.lineTo(cx - s * 0.19f, cy)
        path.lineTo(cx - s * 0.42f, cy)
        path.close()
        val col = cText
        if (on) { fill.color = col; c.drawPath(path, fill) } else { stroke.color = col; c.drawPath(path, stroke) }
        if (shift == Shift.LOCK) {
            fill.color = col
            c.drawRect(cx - s * 0.19f, cy + s * 0.52f, cx + s * 0.19f, cy + s * 0.62f, fill)
        }
    }

    private fun drawBackspace(c: Canvas, cx: Float, cy: Float, s: Float) {
        path.reset()
        path.moveTo(cx - s * 0.55f, cy)
        path.lineTo(cx - s * 0.2f, cy - s * 0.4f)
        path.lineTo(cx + s * 0.5f, cy - s * 0.4f)
        path.lineTo(cx + s * 0.5f, cy + s * 0.4f)
        path.lineTo(cx - s * 0.2f, cy + s * 0.4f)
        path.close()
        stroke.color = cText
        c.drawPath(path, stroke)
        val xc = cx + s * 0.15f
        val r = s * 0.14f
        c.drawLine(xc - r, cy - r, xc + r, cy + r, stroke)
        c.drawLine(xc - r, cy + r, xc + r, cy - r, stroke)
    }

    private fun drawGlobe(c: Canvas, cx: Float, cy: Float, r: Float) {
        stroke.color = cText
        c.drawCircle(cx, cy, r, stroke)
        rect.set(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r)
        c.drawOval(rect, stroke)
        c.drawLine(cx - r, cy, cx + r, cy, stroke)
        c.drawLine(cx, cy - r, cx, cy + r, stroke)
    }

    private fun drawSmiley(c: Canvas, cx: Float, cy: Float, r: Float, col: Int = cText) {
        stroke.color = col
        c.drawCircle(cx, cy, r, stroke)
        fill.color = col
        c.drawCircle(cx - r * 0.35f, cy - r * 0.25f, r * 0.11f, fill)
        c.drawCircle(cx + r * 0.35f, cy - r * 0.25f, r * 0.11f, fill)
        rect.set(cx - r * 0.5f, cy - r * 0.3f, cx + r * 0.5f, cy + r * 0.55f)
        c.drawArc(rect, 20f, 140f, false, stroke)
    }

    private fun drawEnter(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        stroke.color = color
        path.reset()
        path.moveTo(cx + s * 0.5f, cy - s * 0.4f)
        path.lineTo(cx + s * 0.5f, cy + s * 0.12f)
        path.lineTo(cx - s * 0.45f, cy + s * 0.12f)
        c.drawPath(path, stroke)
        c.drawLine(cx - s * 0.45f, cy + s * 0.12f, cx - s * 0.2f, cy - s * 0.12f, stroke)
        c.drawLine(cx - s * 0.45f, cy + s * 0.12f, cx - s * 0.2f, cy + s * 0.36f, stroke)
    }

    companion object {
        const val LETTER_SP = 22f
        /** Заглавная выше строчной во столько раз (как в Gboard): заметно, что Shift, но не на треть. */
        const val UPPER_RATIO = 1.22f
        const val CLIP_PASTE = 0
        const val CLIP_DELETE = 1
        const val CLIP_PIN = 2
        const val CLIP_TO_SAFE = 3
        const val SWIPE_PREVIEW_MS = 120L
        const val SAFE_BACK = 0
        const val SAFE_BIO = 1
        const val SAFE_OPEN = 2
        const val SAFE_CLOSE = 3
        const val SAFE_NEW = 4
        const val DRAFT_CANCEL = 0
        const val DRAFT_SAVE = 2
        private const val REPEAT_DELAY_MS = 400L
        private const val CHAR_REPEAT_MS = 55L
        private const val SPACE_HOLD_MS = 300L
        private const val ONE_HAND_WIDTH = 0.82f
        private const val AI_COLS = 2
        private const val AI_HOLD_MS = 450L
        private const val AI_HEAD_SP = 15f
        /** Насколько заголовок сжимается, когда кнопки наехали на него совсем. */
        private const val AI_HEAD_MIN_SCALE = 0.88f
        /** Выше этого системная панель под клавиатурой не бывает: предохранитель от неверного инсета. */
        private const val CAPTION_MAX_DP = 80f
        /** В горизонтальной ориентации клавиатура — не выше этой доли высоты экрана. */
        private const val LANDSCAPE_MAX = 0.5f
        /** Системные размеры полосы с кнопкой выбора клавиатуры: так её мерит и сам InputMethodService. */
        private val CAPTION_DIMENS = listOf("navigation_bar_frame_height", "navigation_bar_height")
    }
}
