package com.kai.keyboard

import android.content.ClipboardManager
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.kai.keyboard.data.ai.AiAction
import com.kai.keyboard.data.ai.AiColumn
import com.kai.keyboard.data.ai.AiModel
import com.kai.keyboard.data.ai.AiPrompts
import com.kai.keyboard.data.ai.Builtin
import com.kai.keyboard.data.ai.TextDiff
import com.kai.keyboard.data.ai.aiPages
import com.kai.keyboard.data.ai.aiTabs
import java.util.concurrent.Future

class KlavaIME : InputMethodService(), KeyboardView.Listener {

    private lateinit var prefs: Prefs
    private var kv: KeyboardView? = null
    private var vibrator: Vibrator? = null
    private var audio: AudioManager? = null

    private var lang = Langs.RU
    private var mode = Mode.LETTERS
    private var shift = Shift.OFF
    /** Поле, открытое последним (то же поле после перезапуска — например, чат очистил его после отправки). */
    private var lastField: String? = null
    private var lockField: String? = null
    private var lastShiftTap = 0L

    // свойства текущего поля
    private var allowSuggest = true
    private var allowCorrect = true
    private var allowLearn = true
    private var allowSmart = true
    private var allowAutocap = true
    private var passwordField = false
    /** Поле просит не учиться (инкогнито): ИИ там тоже недоступен, как и обучение. */
    private var incognitoField = false

    private fun aiAllowed() = prefs.aiReady && !passwordField && !incognitoField && draft == null &&
        mode != Mode.SAFE && mode != Mode.SAFE_PIN
    private var addressField = false
    private var emojiStrip = true
    private var reorderField = false
    private var deleteInQuery = false
    private var clipboard: ClipboardManager? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { readClip() }

    private var sugg: Suggestions? = null
    private val probs = FloatArray(40)
    private var lastBefore: String? = null

    /** Последнее автоисправление — чтобы откатить его по ⌫. */
    private class Fix(val original: String, val replacement: String, val sep: String,
                      /** Исправили раскладку и переключили язык — при откате вернуть этот. */
                      val fromLang: Lang? = null,
                      /** Слово заменили эмодзи из полосы: откат — без обучения, исправления не было. */
                      val emoji: Boolean = false,
                      /** «Недавние» до этого эмодзи — при откате вернуть: отменённое не считается использованным. */
                      val recentBefore: List<String>? = null)
    private var lastFix: Fix? = null
    private var autoSpace = false
    private var lastWasSpace = false
    /** Насколько доверять модели в текущем слове (после ⌫ — верим пальцу). */
    private val zone = ZoneState()
    /** Владелец отменил перестановку в этом слове — до конца слова пары не переставляем. */
    private var reorderOff = false

    private fun wordBoundary() {
        zone.onWordBoundary()
        reorderOff = false
    }
    /**
     * Куски записей сейфа, вставленных в это поле (в памяти, пока поле то же). Слово, которое из них
     * взято (курсор у секрета, его стёрли наполовину, вернулись к нему), не учим, не исправляем,
     * не подсказываем по нему и не берём предыдущим словом в пары.
     */
    private val secretParts = HashSet<String>()

    private fun fromSecret(w: String?): Boolean {
        if (w.isNullOrEmpty() || secretParts.isEmpty()) return false
        val l = normalize(w)
        return secretParts.any { p -> p.contains(l) && (l.length >= 4 || l.length * 2 >= p.length) }
    }

    /** Контекст (одно-два предыдущих слова) — без кусков секрета. */
    private fun safeCtx(k: String?) = if (k != null && k.split(' ').any { fromSecret(it) }) null else k
    private class SwipeResult(var word: String, val alts: List<String>)
    private var lastSwipe: SwipeResult? = null
    private val wordTouches = ArrayList<FloatArray>()
    private val main = Handler(Looper.getMainLooper())
    /** Свайп, который ещё распознаётся в фоне. */
    /** Свайп в работе: с каким текстом в поле он начат — чтобы заметить, если поле изменится само. */
    private class PendingSwipe(val shift: Shift, val before: String, val selected: String,
                               val pts: FloatArray, val centers: Map<Char, FloatArray>) {
        var task: Future<*>? = null
        var watchdog: Runnable? = null
    }
    private var pendingSwipe: PendingSwipe? = null
    /** Поле изменилось, пока свайп распознавался: слова не вставлены, ждут в полосе подсказок. */
    private var missedSwipe: List<String>? = null
    private val gate = InputGate { Log.e(TAG, "queued input failed", it) }

    // ================= новая запись сейфа: набирается в самой клавиатуре =================

    /**
     * Текст новой записи сейфа (null — не набираем). Пока он есть, клавиатура печатает в него, а не в поле
     * приложения: приложение ничего не видит. Как поле пароля: без обучения, подсказок и увеличенных букв.
     */
    private var draft: android.text.SpannableStringBuilder? = null
    private var draftIc: InputConnection? = null

    /** Своё «поле» для черновика: всё, что клавиатура делает с полем, работает и здесь. */
    private class DraftConnection(view: View, private val text: android.text.Editable) :
        android.view.inputmethod.BaseInputConnection(view, true) {
        override fun getEditable(): android.text.Editable = text
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            val a = android.text.Selection.getSelectionStart(text).coerceAtLeast(0)
            val b = android.text.Selection.getSelectionEnd(text).coerceAtLeast(0)
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> if (a != b) text.delete(minOf(a, b), maxOf(a, b)) else if (a > 0) text.delete(a - 1, a)
                KeyEvent.KEYCODE_ENTER -> if (text.length < Safe.MAX_SECRET) text.replace(minOf(a, b), maxOf(a, b), "\n")
                KeyEvent.KEYCODE_DPAD_LEFT -> android.text.Selection.setSelection(text, (b - 1).coerceAtLeast(0))
                KeyEvent.KEYCODE_DPAD_RIGHT -> android.text.Selection.setSelection(text, (b + 1).coerceAtMost(text.length))
            }
            return true
        }
        /** Больше, чем влезает в запись сейфа, не принимаем (и вставкой из панели буфера тоже). */
        override fun commitText(t: CharSequence?, newCursorPosition: Int): Boolean {
            val a = android.text.Selection.getSelectionStart(text).coerceAtLeast(0)
            val b = android.text.Selection.getSelectionEnd(text).coerceAtLeast(0)
            val room = (Safe.MAX_SECRET - (text.length - kotlin.math.abs(b - a))).coerceAtLeast(0)
            val s = t ?: ""
            return super.commitText(if (s.length > room) s.subSequence(0, room) else s, newCursorPosition)
        }
        override fun performEditorAction(actionCode: Int) = sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
    }

    override fun getCurrentInputConnection(): InputConnection? = draftIc ?: super.getCurrentInputConnection()

    private fun startDraft() {
        val v = kv ?: return
        val t = android.text.SpannableStringBuilder()
        android.text.Selection.setSelection(t, 0)
        draft = t
        draftIc = DraftConnection(v, t)
        wordBoundary(); wordTouches.clear(); lastFix = null; lastSwipe = null; missedSwipe = null
        autoSpace = false; lastWasSpace = false; lastTap = null
        draftPrefs()
        shift = Shift.OFF
        setMode(Mode.LETTERS)
        v.draftBar = ""
        applyLayout()
        forceRefresh()
    }

    /** Черновик — как поле пароля: не учимся, не подсказываем, не исправляем, буквы над пальцем не показываем. */
    private fun draftPrefs() {
        allowSuggest = false; allowCorrect = false; allowLearn = false; allowSmart = false
        passwordField = true; emojiStrip = false; reorderField = false; allowAutocap = false
        kv?.swipeEnabled = false
        applyViewPrefs()
    }

    private fun endDraft() {
        val t = draft ?: return
        t.clear()
        draft = null; draftIc = null
        kv?.draftBar = null
        kv?.draftHint = null
        aiPending = null
        currentInputEditorInfo?.let { applyInputPrefs(it) }
        applyViewPrefs()
        lastBefore = null
    }

    override fun onSafeDraft(action: Int) {
        if (gate.defer { onSafeDraft(action) }) return
        touched()
        val t = draft ?: return
        if (aiPending != null) {
            // Строка над клавишами сейчас — инструкция для ИИ, а не запись сейфа.
            val snap = aiPending
            val act = aiPendingAction
            val model = aiPendingModel
            if (action == KeyboardView.DRAFT_CANCEL) { aiForget(); endDraft(); return }
            val instr = t.toString().trim()
            if (instr.isEmpty() || snap == null || act == null || model == null) return
            aiForget()
            endDraft()
            aiRun(act, model, snap, instr)
            return
        }
        when (action) {
            KeyboardView.DRAFT_CANCEL -> { endDraft(); openSafe() }
            KeyboardView.DRAFT_SAVE -> {
                val text = t.toString()
                if (text.isBlank()) { toast("Запись пустая"); return }
                // Черновик заканчиваем сразу: второе ✓, × или новые буквы уже ничего не сделают с этой записью.
                val tag = Brain.safe.keyTag()
                // Какой сейф сейчас: запись ляжет только в него (стёрли «ПИНом для удаления», пока писали, — нет).
                val target = Brain.safe.target()
                endDraft()
                openSafe()
                Thread {
                    val ok = runCatching { Brain.safe.put("", text.take(Safe.MAX_SECRET), target) }.getOrDefault(false)
                    Brain.onMain {
                        if (!ok) {
                            // Набранное не теряем: вернём в строку записи сейчас (если сейф на экране) или
                            // при следующем открытии сейфа (ушли в другое приложение, сейф заперся).
                            failedDraft = text; failedDraftTag = tag
                            toast("Не получилось сохранить — запись вернётся в строку сейфа")
                            restoreFailedDraft()
                            return@onMain
                        }
                        if (mode == Mode.SAFE) kv?.clipList = safeRows()
                        // вставили из буфера — ни в истории, ни в самом буфере его больше не держим
                        Brain.clips.list().filter { !it.isImage && it.text == text }.forEach { Brain.clips.remove(it) }
                        clearClipIf(text)
                    }
                }.apply { isDaemon = true }.start()
            }
        }
    }

    private var failedDraft: String? = null
    /** Для какого сейфа (метка ключа): после «ПИНа для удаления» сейф другой — запись в него не возвращаем. */
    private var failedDraftTag: ByteArray? = null

    private fun restoreFailedDraft() {
        val text = failedDraft ?: return
        if (mode != Mode.SAFE || !Brain.safe.isUnlocked || draft != null) return
        val same = failedDraftTag?.contentEquals(Brain.safe.keyTag()) == true
        failedDraft = null; failedDraftTag = null
        if (!same) return // сейф стёрт и создан заново — несохранённое вместе с ним пропадает
        startDraft()
        draftIc?.commitText(text, 1)
        forceRefresh()
    }

    private val screenOff = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: android.content.Intent) {
            Brain.safe.lock(); Brain.safeOpenedAt = 0L; Brain.safeBioPackage = null; pendingToSafe = null
            bioGraceUntil = 0L
            endDraft()
            failedDraft = null; failedDraftTag = null // погасили экран — несохранённое не держим
            hideSafe()
        }
    }

    /**
     * Сейф заперт — уже нарисованные записи убираем сразу (а не при следующем касании): вместо них ПИН,
     * набранный ПИН стирается. Клавиатура может вернуться в то же поле без пересоздания.
     */
    private fun hideSafe() {
        if (mode != Mode.SAFE && mode != Mode.SAFE_PIN) return
        kv?.clipList = emptyList()
        clearPin()
        if (mode == Mode.SAFE) openSafe() else kv?.safeStatus = safeStatusText()
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(screenOff, android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF))
        prefs = Prefs(this)
        lang = Langs.of(prefs.lang)
        Brain.init(this)
        Brain.onReady = { lastBefore = null; refresh() }
        Brain.clips.onChanged = { Brain.onMain { if (mode == Mode.CLIPBOARD) kv?.clipList = clipRows() } }
        Brain.onSafeOpened = { for (ms in longArrayOf(150, 600, 1500)) main.postDelayed({ checkBioReturn() }, ms) }
        AppGate.onLocked = { Brain.onMain { if (mode == Mode.SAFE && !Brain.safe.isUnlocked) hideSafe() } }
        Brain.onEmojiReady = {
            if (mode == Mode.EMOJI || mode == Mode.EMOJI_SEARCH) applyLayout() else forceRefresh()
        }
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        audio = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        prefs.listen(prefsListener)
        clipboard = (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.also {
            it.addPrimaryClipChangedListener(clipListener)
        }
    }

    /**
     * Запоминаем скопированное: текст или картинку. Помеченное «секретным» (пароли из менеджеров) — никогда.
     * Картинку читаем в фоне (она может быть большой) и держим только в памяти.
     */
    private fun readClip() {
        if (!prefs.clipHistory) return
        val clip = runCatching { clipboard?.primaryClip }.getOrNull() ?: return
        if (clip.itemCount == 0) return
        val desc = clip.description
        if (desc?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) return
        val item = clip.getItemAt(0)
        val uri = item.uri
        // Тип — по описанию буфера (без обращения к чужому приложению на основном потоке).
        val descMime = (0 until (desc?.mimeTypeCount ?: 0)).map { desc!!.getMimeType(it) }.firstOrNull { it.startsWith("image/") }
        if (uri != null && (descMime != null || item.text == null)) {
            val at = System.currentTimeMillis() // скопировали сейчас — в истории встанет на это место
            // Чужое приложение может отдавать картинку бесконечно долго: читаем в своём потоке и с таймаутом,
            // чтобы не застопорить ни клавиатуру, ни сохранение выученного.
            Thread {
                val signal = android.os.CancellationSignal()
                val guard = clipWatchdog.schedule({ signal.cancel() }, CLIP_READ_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                val mime = try { descMime ?: runCatching { contentResolver.getType(uri) }.getOrNull() } finally { }
                if (mime == null || !mime.startsWith("image/") || signal.isCanceled) { guard.cancel(false); return@Thread }
                val bytes = readWithTimeout(uri, signal)
                guard.cancel(false)
                if (bytes == null || bytes.size > Clips.MAX_IMAGE_BYTES) return@Thread
                val thumb = thumbnail(bytes)
                Brain.onMain {
                    if (!prefs.clipHistory) return@onMain
                    val added = Brain.clips.addImage(bytes, mime, at) ?: return@onMain
                    if (thumb != null) thumbs[added.id] = thumb
                    if (mode == Mode.CLIPBOARD) kv?.clipList = clipRows()
                    forceRefresh()
                }
            }.apply { isDaemon = true }.start()
            return
        }
        val t = item.text?.toString() ?: return
        Brain.clips.add(t)
        if (mode == Mode.CLIPBOARD) kv?.clipList = clipRows()
        forceRefresh()
    }

    /**
     * Сторож чтения картинок из буфера: каждое чтение — в своём потоке (зависший источник не держит
     * ни остальные копирования, ни сохранение выученного); не успело за CLIP_READ_MS — отменяем и закрываем.
     */
    private val clipWatchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r).apply { isDaemon = true }
    }

    private fun readWithTimeout(uri: android.net.Uri, signal: android.os.CancellationSignal): ByteArray? {
        val pfd = runCatching { contentResolver.openFileDescriptor(uri, "r", signal) }.getOrNull() ?: return null
        signal.setOnCancelListener { runCatching { pfd.close() } }
        return try {
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { readLimited(it, Clips.MAX_IMAGE_BYTES + 1) }
        } catch (e: Exception) {
            null
        }
    }

    private val thumbs = HashMap<Long, android.graphics.Bitmap>()
    private val thumbsPending = HashSet<Long>()
    /** Не декодируется (битая или странная картинка) — больше не пробуем, иначе панель крутила бы попытки. */
    private val thumbsFailed = HashSet<Long>()

    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun thumbnail(bytes: ByteArray): android.graphics.Bitmap? {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        val target = (48 * resources.displayMetrics.density).toInt() * 2
        var sample = 1
        // Уменьшаем, пока и меньшая сторона больше нужного, и картинка в памяти больше ~1 Мпикс:
        // крошечный по байтам PNG бывает огромным по размерам — такой не должен съесть память.
        while ((o.outWidth / (sample * 2) >= target && o.outHeight / (sample * 2) >= target) ||
            o.outWidth.toLong() * o.outHeight / (sample.toLong() * sample) > MAX_THUMB_PIXELS) sample *= 2
        return runCatching {
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    private fun clipRows(): List<ClipRow> {
        Brain.clips.ensurePins() // закреплённое не прочиталось при запуске (ключ был занят) — ещё раз
        val items = Brain.clips.list()
        val ids = items.map { it.id }.toSet()
        thumbs.keys.retainAll(ids)
        thumbsFailed.retainAll(ids)
        // Закреплённые картинки после перезапуска — без миниатюр: досчитываем в фоне.
        for (it in items) if (it.isImage && it.id !in thumbs && it.id !in thumbsFailed && thumbsPending.add(it.id)) {
            val bytes = it.image!!.bytes
            val id = it.id
            Brain.background {
                val t = thumbnail(bytes)
                Brain.onMain {
                    thumbsPending.remove(id)
                    if (t != null) thumbs[id] = t else thumbsFailed.add(id)
                    if (mode == Mode.CLIPBOARD) kv?.clipList = clipRows()
                }
            }
        }
        return items.map {
            if (it.isImage) ClipRow(it.id, imageLabel(it), thumbs[it.id], it.pinned) else ClipRow(it.id, it.text, null, it.pinned)
        }
    }

    private fun imageLabel(it: Clips.Item): String {
        val kb = (it.image!!.bytes.size + 1023) / 1024
        return "Картинка · " + if (kb < 1024) "$kb КБ" else "%.1f МБ".format(kb / 1024f)
    }

    /**
     * Вставить картинку из буфера: стандартным способом Android (как гифки и стикеры) — приложению
     * выдаём разрешение на ссылку, по которой оно прочитает картинку из памяти клавиатуры.
     */
    private fun insertImage(ic: InputConnection, item: Clips.Item): Boolean {
        val img = item.image ?: return false
        val accepts = currentInputEditorInfo?.contentMimeTypes.orEmpty()
            .any { android.content.ClipDescription.compareMimeTypes(img.mime, it) }
        if (!accepts) { toast("Это поле не принимает картинки"); return false }
        val info = android.view.inputmethod.InputContentInfo(ClipImageProvider.uri(item.id),
            android.content.ClipDescription("image", arrayOf(img.mime)))
        val ok = ic.commitContent(info, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
        if (!ok) toast("Не получилось вставить картинку")
        return ok
    }

    private fun ttlText(min: Int) = ttlLabel(min)


    private fun toast(s: String) {
        val v = kv
        if (v != null && v.isAttachedToWindow && v.isShown) v.showToast(s)
        else android.widget.Toast.makeText(applicationContext, s, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        kv?.hideToast()
        runCatching { unregisterReceiver(screenOff) }
        clipWatchdog.shutdownNow()
        Brain.onReady = null
        Brain.onEmojiReady = null
        Brain.onSafeOpened = null
        AppGate.onLocked = null
        Brain.clips.onChanged = null
        prefs.unlisten(prefsListener)
        clipboard?.removePrimaryClipChangedListener(clipListener)
        Brain.saveAsync()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val v = KeyboardView(this, this)
        kv = v
        // Ряды и клавиши задаём до первого измерения: у новой вьюхи раскладка пустая, а
        // измерение и отрисовка лезут в rows[0][0] — без этого был IndexOutOfBoundsException.
        v.layout = Layouts.letters(lang, prefs.altStyle, !prefs.langSwipe, prefs.lpSymbols, prefs.numberRow,
            style3 = prefs.style3, doubles = prefs.doubleSymbols, clipboard = clipBottomKey())
        applyLayout()
        return v
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (prefs.theme == Theme.SYSTEM_ID) { applyViewPrefs(); forceRefresh() }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Чат перезапустил то же поле — набираемая запись сейфа не пропадает; другое поле — пропадает.
        val draftField = "${info.packageName}/${info.fieldId}/${info.fieldName}/" +
            "${info.inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)}/${info.hintText}"
        val keepDraft = draft != null && restarting && draftField == lastField && Brain.safe.isUnlocked
        if (!keepDraft) endDraft()
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        applyInputPrefs(info)

        val kind = info.inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)
        val field = "${info.packageName}/${info.fieldId}/${info.fieldName}/$kind/${info.hintText}"
        // Чат очистил поле после отправки (это перезапуск того же поля) — остаёмся в эмодзи:
        // человек, скорее всего, шлёт следующее.
        val keepEmoji = restarting && field == lastField && (mode == Mode.EMOJI || mode == Mode.EMOJI_SEARCH)
        if (field != lastField) secretParts.clear()
        lastField = field
        if (!keepEmoji) mode = when (cls) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME -> Mode.NUMBER
            InputType.TYPE_CLASS_PHONE -> Mode.PHONE
            else -> Mode.LETTERS
        }
        safeSession++
        // Вернулись из окна отпечатка в то же приложение, экран не заблокирован — сразу показываем записи.
        if (bioReturn(info)) {
            consumeBioReturn()
            mode = Mode.SAFE
            finishPendingToSafe()
        } else if (!keepDraft && !(AppGate.inApp && info.packageName == packageName)) {
            // Своё приложение на экране — исключение только для его же полей (не для соседнего окна).
            // Любое новое поле — сейф заперт (экран сейфа в Klava пользуется им сам и запирает сам).
            Brain.safe.lock()
            Brain.safeOpenedAt = 0L
            pendingToSafe = null
        }
        if (!restarting) {
            lang = if (addressField) Langs.EN else Langs.of(prefs.lang)
        }
        // Caps Lock не сбрасываем в том же поле: после отправки сообщения чат часто пересоздаёт его,
        // а владелец хочет продолжать писать заглавными. Другое поле того же приложения (поиск,
        // имя контакта) — уже другое: там Caps Lock не нужен.
        // Тип — только класс и вид поля: флаги (многострочное, автозаглавные) чаты меняют после отправки.
        // Подсказка поля различает поля там, где нет id (браузер, Compose, Flutter).
        if (shift != Shift.LOCK || field != lockField || mode != Mode.LETTERS) shift = Shift.OFF
        lockField = field
        wordBoundary()
        lastFix = null; autoSpace = false; lastWasSpace = false
        wordTouches.clear()
        lastBefore = null

        lastSwipe = null
        missedSwipe = null
        lastTap = null; lastSwap = null
        dropPendingSwipe()
        gate.reset()
        applyViewPrefs()
        applyLayout()
        applyCaptionBar()
        refresh()
    }

    /**
     * Что клавиатуре можно делать в этом поле: учиться, подсказывать, исправлять, умные зоны, свайп.
     * Считается из настроек и типа поля — при открытии поля и сразу после любого изменения настроек:
     * выключили обучение — уже открытая клавиатура перестаёт учиться с этого же нажатия.
     */
    private fun applyInputPrefs(info: EditorInfo) {
        val type = info.inputType
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        val password = cls == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val emailOrUri = cls == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_URI)
        val noSuggestions = type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
        val incognito = info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0

        allowSuggest = prefs.suggestions && cls == InputType.TYPE_CLASS_TEXT && !password && !noSuggestions
        Brain.autocorrectLevel = prefs.autocorrectLevel
        allowCorrect = Brain.autocorrectLevel > 0 && allowSuggest && !emailOrUri &&
            variation != InputType.TYPE_TEXT_VARIATION_FILTER
        allowLearn = prefs.learn && !password && !incognito && !emailOrUri
        allowSmart = prefs.smartTargets && !password && !emailOrUri
        allowAutocap = prefs.autocap
        passwordField = password
        incognitoField = incognito
        addressField = emailOrUri
        emojiStrip = prefs.emojiSuggest && !emailOrUri && variation != InputType.TYPE_TEXT_VARIATION_FILTER
        // Не зависит от умных зон: решение — по словарю и геометрии клавиш, они есть и без зон.
        reorderField = !password && !emailOrUri && !noSuggestions && variation != InputType.TYPE_TEXT_VARIATION_FILTER
        kv?.swipeEnabled = prefs.swipe && allowSmart && cls == InputType.TYPE_CLASS_TEXT
        Brain.clips.ttlMs = prefs.clipTtlMin * 60_000L
        // Выключили историю — в памяти ничего (закреплённое на диске стирает сам переключатель в настройках).
        if (!prefs.clipHistory) Brain.clips.clear()
        if (!allowLearn) wordTouches.clear()
        if (draft != null) draftPrefs()
    }

    private fun applyViewPrefs() {
        kv?.apply {
            oneHand = prefs.oneHand
            // В полях паролей увеличенная буква над пальцем подсмотрима — не показываем.
            showPreview = prefs.preview && !passwordField
            showStrip = prefs.suggestions
            deleteWordsAfterMs = prefs.deleteWordsAfter
            deleteWordPauseMs = prefs.deleteWordPause
            langSwipe = prefs.langSwipe
            showZones = prefs.showZones
            heightScale = prefs.height / 100f
            keyGapDp = prefs.keyGap / 2f
            numberRow = prefs.numberRow
            SystemTheme.refresh(this@KlavaIME)
            theme = Theme.of(prefs.theme)
            accent = Theme.of(prefs.theme).accentFor(prefs.accent)
            aiMode = aiAllowed()
            aiKeyRight = prefs.aiKeyRight
        }
        applyNavBar(Theme.of(prefs.theme))
    }

    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.RECENT || key == Prefs.EMOJI_TONES) { recentCache = null; tonesCache = null }
        if (key == Prefs.LANG || key == Prefs.RECENT || key == Prefs.EMOJI_TONES) return@OnSharedPreferenceChangeListener
        currentInputEditorInfo?.let { applyInputPrefs(it) }
        applyViewPrefs()
        if (key == Prefs.ALT_STYLE || key == Prefs.LANG_SWIPE || key == Prefs.CLIP_HISTORY || key == Prefs.LP_SYMBOLS ||
            key == Prefs.NUMBER_ROW || key == Prefs.STYLE3 || key == Prefs.DOUBLE_SYMBOLS ||
            key == Prefs.CLIP_BOTTOM_KEY || key == Prefs.AI_KEY_RIGHT) applyLayout()
        forceRefresh()
    }

    @Suppress("DEPRECATION")
    private fun applyNavBar(t: Theme) {
        val w = window?.window ?: return
        w.navigationBarColor = t.bg
        val flag = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        w.decorView.systemUiVisibility =
            if (t.light) w.decorView.systemUiVisibility or flag else w.decorView.systemUiVisibility and flag.inv()
    }

    /**
     * Полоса с кнопкой выбора клавиатуры (caption bar) над клавиатурой.
     * На форме ИИ она не нужна: там своей кнопки смены языка нет, а полоса отъедает строку.
     * Клавиатура вправе убрать её сама — система тогда передаёт кнопку выбора клавиатуры
     * самой клавиатуре (InputMethodService.onCustomImeSwitcherButtonRequestedVisible): её роль играет
     * долгое нажатие на кнопку смены языка, оттуда же открывается выбор клавиатуры.
     * На остальных формах полоса остаётся как есть.
     */
    private fun applyCaptionBar() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        val v = kv ?: return
        val c = v.windowInsetsController ?: return
        if (mode == Mode.AI) {
            c.hide(android.view.WindowInsets.Type.captionBar())
        } else {
            c.show(android.view.WindowInsets.Type.captionBar())
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // Клавиатура закрылась — сейф запирается (кроме окна отпечатка, которое как раз его открывает,
        // и экрана сейфа в Klava — тот запирает сам).
        safeSession++
        commitAdapt()
        endDraft()
        val ownField = AppGate.inApp && currentInputEditorInfo?.packageName == packageName
        // Окно отпечатка «идёт» не дольше SAFE_RETURN_MS: застрявший флаг не отменяет запирание навсегда.
        // И сразу после него — тоже не запираем: возвращаясь в приложение, система может закрыть и снова
        // открыть клавиатуру; сейф ждёт возврата (не дольше SAFE_RETURN_MS, потом запрётся сам).
        val now = android.os.SystemClock.elapsedRealtime()
        val bioPending = Brain.safeBioPending && now - Brain.safeBioStartedAt < SAFE_RETURN_MS
        val bioReturning = Brain.safeOpenedAt != 0L && now - Brain.safeOpenedAt < SAFE_RETURN_MS
        val inGrace = now < bioGraceUntil
        if (!ownField && !bioPending && !bioReturning && !inGrace) { Brain.safe.lock(); pendingToSafe = null }
        // Сразу после возврата (приложение перезапускает поле): ждём чуть-чуть; не показались снова — запираем.
        if (!ownField && inGrace) { main.removeCallbacks(graceLock); main.postDelayed(graceLock, bioGraceUntil - now + 100) }
        clearPin()
        aiCancel()
        if (mode == Mode.SAFE || mode == Mode.SAFE_PIN) mode = Mode.LETTERS
        dropPendingSwipe()
        gate.reset()
        Brain.saveAsync()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val foreign = (oldSelStart != newSelStart || oldSelEnd != newSelEnd) &&
            SystemClock.uptimeMillis() - lastOwnInput > OWN_EDIT_MS
        if (foreign) {
            // Курсор сдвинулся не от наших нажатий: дальше — уже другое место и другое слово,
            // и ни «пробел уже стоит», ни откат, ни касания прежнего слова сюда не относятся.
            wordBoundary()
            lastTap = null; lastSwap = null
            autoSpace = false; lastWasSpace = false; lastFix = null
            wordTouches.clear()
            // Владелец ткнул в поле — из эмодзи обратно к буквам. Но не когда приложение само очистило
            // поле (отправили сообщение): тогда человек, скорее всего, шлёт эмодзи дальше.
            if (mode == Mode.EMOJI || mode == Mode.EMOJI_SEARCH) {
                val cleared = newSelStart == 0 && newSelEnd == 0 &&
                    currentInputConnection?.getTextAfterCursor(1, 0).isNullOrEmpty()
                if (!cleared) setMode(Mode.LETTERS)
            }
        }
        refresh()
    }

    @Deprecated("Deprecated in Java")
    override fun onViewClicked(focusChanged: Boolean) {
        @Suppress("DEPRECATION")
        super.onViewClicked(focusChanged)
        if (mode == Mode.EMOJI || mode == Mode.EMOJI_SEARCH) setMode(Mode.LETTERS)
    }

    /** Когда клавиатура сама последний раз меняла поле: её же правки курсора не считаем тапом владельца. */
    private var lastOwnInput = 0L
    /** Номер события с клавиатуры: «предыдущее нажатие» — это именно предыдущее событие, без ничего между. */
    private var events = 0

    private fun touched() {
        lastOwnInput = SystemClock.uptimeMillis()
        events++
    }

    // ================= порядок букв при быстром наборе двумя пальцами =================

    private var lastTap: Reorder.Tap? = null
    private var lastTapChar = ""
    private var lastTapEvent = -1
    /** Состояние зон до первой буквы пары: её пересчитываем так же строго, как распознавали вживую. */
    private var lastTapZone = ZoneState()
    private class Swap(val asTyped: String, val shown: String, val event: Int)
    private var lastSwap: Swap? = null

    /**
     * Две буквы нажаты почти вместе разными пальцами — может, клавиатура получила их не в том порядке.
     * Распознаём пару в обоих порядках (Reorder) и, если обратный заметно правдоподобнее, ставим его.
     * @return true, если пара переставлена (буква t уже в поле)
     */
    private fun reorder(ic: InputConnection, t: String, tap: Reorder.Tap): Boolean {
        val prev = lastTap ?: return false
        val v = kv ?: return false
        if (lastTapEvent != events - 1 || reorderOff || !prefs.reorder || !reorderField || mode != Mode.LETTERS) return false
        if (!Reorder.close(prev, tap)) return false
        val before = textBefore(ic)
        if (!before.endsWith(lastTapChar)) return false
        val word = TextCtx.currentWord(before)
        if (word.isEmpty()) return false
        val base = word.dropLast(1)
        val ctx = TextCtx.contextKey(before, before.length - word.length)
        val model = object : Reorder.Model {
            // Уверенность — как у обычного распознавания (после ⌫ — строго по пальцу, после неожиданной буквы — скромнее).
            override fun probs(extra: String, out: FloatArray) =
                Brain.letterProbs(lang, base + extra, ctx, out).let {
                    if (it < 0f) it else if (extra.isEmpty()) lastTapZone.apply(it) else zone.apply(it)
                }
            override fun resolve(t: Reorder.Tap, probs: FloatArray?, conf: Float) = v.resolveTap(t, probs, conf)
            override fun fit(t: Reorder.Tap, k: Key) = v.tapFit(t, k)
        }
        val sw = Reorder.swapped(lang, model, prev, tap) ?: return false
        // Только переставить, но не подменить буквы: пара — ровно эти две, в обратном порядке.
        if (sw.lowercase() != (t + lastTapChar).lowercase()) return false
        // Регистр — по местам: заглавная остаётся первой буквой, Caps Lock — у обеих.
        val first = if (lastTapChar[0].isUpperCase()) sw[0].uppercaseChar() else sw[0]
        val second = if (t[0].isUpperCase()) sw[1].uppercaseChar() else sw[1]
        val shown = "$first$second"
        Brain.clips.dismissRecent()
        lastSwipe = null; forgetMissedSwipe()
        lastWasSpace = false; lastFix = null; autoSpace = false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(lastTapChar.length, 0)
        ic.commitText(shown, 1)
        ic.endBatchEdit()
        // Чья буква где — уже не ясно: это слово не учим как попадания пальца (длины касаний и букв разошлись).
        if (wordTouches.isNotEmpty()) wordTouches.removeAt(wordTouches.size - 1)
        zone.onLetter(1f)
        lastSwap = Swap(lastTapChar + t, shown, events)
        lastTap = null // пару не цепляем к следующей букве
        if (shift == Shift.ON) shift = Shift.OFF
        refresh()
        return true
    }

    // ================= раскладки и состояние =================

    private fun applyLayout() {
        val v = kv ?: return
        v.lang = lang
        v.aiKeyRight = prefs.aiKeyRight
        v.layout = when (mode) {
            Mode.LETTERS -> Layouts.letters(lang, prefs.altStyle, !prefs.langSwipe, prefs.lpSymbols, prefs.numberRow,
                style3 = prefs.style3, doubles = prefs.doubleSymbols, clipboard = clipBottomKey())
            Mode.SYMBOLS -> Layouts.symbols(lang, false, prefs.altStyle, clipKey())
            Mode.SYMBOLS2 -> Layouts.symbols(lang, true, prefs.altStyle, clipKey())
            Mode.NUMBER -> Layouts.numpad(lang, false)
            Mode.PHONE -> Layouts.numpad(lang, true)
            Mode.EMOJI -> Layouts.emoji(lang)
            Mode.EMOJI_SEARCH -> Layouts.emojiSearch(lang, !prefs.langSwipe)
            Mode.CLIPBOARD -> Layouts.clipboard(lang)
            Mode.SAFE -> Layouts.safe(lang)
            Mode.SAFE_PIN -> Layouts.safePin()
            Mode.AI -> Layouts.ai()
        }
        v.clipButtons = mode != Mode.SAFE
        v.clipEmpty = when {
            mode == Mode.SAFE -> "Пока пусто — новая запись в строке сверху"
            !prefs.clipHistory -> "История буфера выключена — сейф по кнопке внизу"
            else -> "Скопируйте текст или картинку — они появятся здесь"
        }
        // Сейф на экране — окно клавиатуры не попадает на скриншоты и запись экрана.
        val secure = mode == Mode.SAFE || mode == Mode.SAFE_PIN || draft != null
        window?.window?.let { w ->
            if (secure) w.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            else w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        v.clipLock = mode == Mode.SAFE || Brain.safe.exists() || prefs.clipHistory
        if (mode == Mode.SAFE) {
            if (failedDraft != null) main.post { restoreFailedDraft() }
            v.clipTitle = ""
            v.clipList = safeRows()
            v.resetEmojiScroll()
        }
        if (mode == Mode.SAFE_PIN) {
            v.safeBio = safeBioKnown
            v.safeStatus = safeStatusText()
        }
        if (mode == Mode.CLIPBOARD) {
            v.clipTitle = "Буфер обмена · хранится ${ttlText(prefs.clipTtlMin)} · 📌 — пока не открепите"
            v.clipList = clipRows()
            v.resetEmojiScroll()
        }
        if (mode == Mode.EMOJI) {
            v.emojiTones = { e -> emojiTones(e) }
            panelRecent = prefs.recentEmoji
            v.emojiSections = emojiSections()
            v.resetEmojiScroll()
        }
        v.searchHint = if (lang == Langs.RU) "Поиск эмодзи" else "Search emoji"
        if (mode == Mode.EMOJI_SEARCH) {
            panelRecent = prefs.recentEmoji
            updateEmojiSearch()
        }
        // Эмодзи не загрузились (например, не хватило памяти) — пробуем ещё раз, когда их открыли.
        if ((mode == Mode.EMOJI || mode == Mode.EMOJI_SEARCH) && Emoji.data == null) Brain.loadEmoji(this)
        updateEnter()
    }

    private fun setMode(m: Mode) {
        if (draft != null && (m == Mode.SAFE || m == Mode.SAFE_PIN)) endDraft()
        if (mode == m) return
        if (m == Mode.EMOJI_SEARCH || mode == Mode.EMOJI_SEARCH) emojiQuery = ""
        mode = m
        applyLayout()
        applyCaptionBar()
        lastBefore = null
        refresh()
    }

    private fun updateEnter() {
        val v = kv ?: return
        val info = currentInputEditorInfo
        val action = enterAction(info)
        val ru = lang == Langs.RU
        v.enterLabel = when (action) {
            EditorInfo.IME_ACTION_GO -> if (ru) "Перейти" else "Go"
            EditorInfo.IME_ACTION_SEARCH -> if (ru) "Найти" else "Search"
            EditorInfo.IME_ACTION_SEND -> if (ru) "Отпр." else "Send"
            EditorInfo.IME_ACTION_NEXT -> if (ru) "Далее" else "Next"
            EditorInfo.IME_ACTION_DONE -> if (ru) "Готово" else "Done"
            EditorInfo.IME_ACTION_PREVIOUS -> if (ru) "Назад" else "Prev"
            else -> null
        }
        v.enterAccent = action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_SEARCH ||
            action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_ACTION_DONE
    }

    private fun enterAction(info: EditorInfo?): Int {
        if (info == null) return EditorInfo.IME_ACTION_NONE
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return EditorInfo.IME_ACTION_NONE
        val a = info.imeOptions and EditorInfo.IME_MASK_ACTION
        return if (a == EditorInfo.IME_ACTION_UNSPECIFIED) EditorInfo.IME_ACTION_NONE else a
    }

    private fun textBefore(ic: InputConnection, n: Int = 64): String =
        ic.getTextBeforeCursor(n, 0)?.toString() ?: ""

    private fun refresh() {
        val v = kv ?: return
        draft?.let { v.draftBar = it.toString() }
        val ic = currentInputConnection
        val before = if (ic != null) textBefore(ic) else ""
        if (before == lastBefore) return
        lastBefore = before

        if (shift != Shift.LOCK && mode == Mode.LETTERS) {
            val caps = allowAutocap && ic != null && currentInputEditorInfo != null &&
                ic.getCursorCapsMode(currentInputEditorInfo.inputType) != 0
            shift = if (caps) Shift.ON else Shift.OFF
        }
        v.shift = shift

        val word = TextCtx.currentWord(before)
        val prev = safeCtx(TextCtx.contextKey(before, before.length - word.length))
        val secret = fromSecret(word)
        val conf = if (allowSmart && mode == Mode.LETTERS && !secret) Brain.letterProbs(lang, word, prev, probs) else -1f
        v.letterProbs = if (conf >= 0f) probs else null
        v.letterConfidence = zone.apply(conf.coerceAtLeast(0f))
        sugg = if (allowSuggest && mode == Mode.LETTERS && !secret) {
            Brain.suggest(lang, word, prev, allowCorrect, shift == Shift.ON)
        } else null
        // Только что скопировали — предлагаем вставить (не в полях паролей: не светим содержимое).
        val clip = if (mode == Mode.LETTERS && word.isEmpty() && !passwordField && prefs.clipHistory)
            Brain.clips.recent() else null
        if (clip != null) sugg = Suggestions("", listOf(Cand(if (clip.isImage) "Скопированная картинка" else clip.text, Cand.CLIP, clip.id)), null)
        else if (word.isNotEmpty() && !secret) sugg = withEmoji(sugg, word)
        val ms = missedSwipe
        if (ms != null && allowSuggest) sugg = Suggestions("", ms.take(3).map { Cand(it, Cand.SWIPE) }, null)
        val ls = lastSwipe
        if (ls != null) {
            if (before.endsWith(ls.word + " ")) {
                if (allowSuggest) sugg = Suggestions("",
                    (listOf(ls.word) + ls.alts.filter { it != ls.word }).take(3).map { Cand(it, Cand.SWIPE) }, null)
            } else lastSwipe = null
        }
        if (mode != Mode.LETTERS) sugg = null
        v.suggestions = sugg
        v.invalidate()
    }

    private fun forceRefresh() { lastBefore = null; refresh() }

    // ================= обратная связь =================

    override fun onFeedback(key: Key?) {
        if (prefs.vibrate) {
            val vib = vibrator
            if (vib != null && vib.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= 29) vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                else vib.vibrate(VibrationEffect.createOneShot(12, 80))
            }
        }
        if (prefs.sound) {
            val fx = when (key?.code) {
                Code.DELETE -> AudioManager.FX_KEYPRESS_DELETE
                Code.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                Code.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            audio?.playSoundEffect(fx, -1f)
        }
    }

    // ================= ввод =================

    override fun onKey(key: Key, dx: Float, dy: Float, tap: Reorder.Tap?) {
        if (gate.defer { onKey(key, dx, dy, tap) }) return
        touched()
        val ic = currentInputConnection ?: return
        if (mode == Mode.SAFE_PIN) {
            when {
                key.code == Code.SAFE_OK -> submitPin()
                key.kind == KeyKind.CHAR && key.label[0].isDigit() && safePin.length < 12 && !safeBusy -> {
                    safePin.append(key.label); safeMessage = ""; kv?.safeStatus = safeStatusText()
                }
            }
            return
        }
        if (mode == Mode.EMOJI_SEARCH && (key.kind == KeyKind.LETTER || key.kind == KeyKind.CHAR || key.code == Code.SPACE)) {
            val t = if (key.code == Code.SPACE) " " else key.label
            if (emojiQuery.isNotEmpty() || t != " ") { emojiQuery += t; updateEmojiSearch() }
            if (shift == Shift.ON) { shift = Shift.OFF; kv?.shift = shift }
            return
        }
        when (key.code) {
            Code.SPACE -> onSpace(ic)
            Code.ENTER -> onEnter(ic)
            Code.SYMBOLS -> setMode(Mode.SYMBOLS)
            Code.SYMBOLS2 -> setMode(Mode.SYMBOLS2)
            Code.LETTERS -> setMode(Mode.LETTERS)
            Code.EMOJI -> setMode(Mode.EMOJI)
            Code.CLIPBOARD -> if (clipKey()) setMode(Mode.CLIPBOARD)
            // «Очистить» убирает незакреплённое — закреплённое остаётся и в списке.
            Code.CLIP_CLEAR -> { Brain.clips.clear(); kv?.clipList = clipRows() }
            Code.LANG -> switchLang()
            else -> {
                val letter = key.kind == KeyKind.LETTER
                val t = if (letter && shift != Shift.OFF) key.label.uppercase() else key.label
                if (letter && tap != null && reorder(ic, t, tap)) return
                val zoneBefore = zone.copy()
                if (letter) {
                    wordTouches.add(floatArrayOf(key.ch.code.toFloat(), dx, dy))
                    val i = lang.index(key.ch)
                    zone.onLetter(if (kv?.letterProbs != null && i >= 0) probs[i] else 1f)
                }
                type(ic, t)
                if (letter && tap != null) { lastTap = tap; lastTapChar = t; lastTapEvent = events; lastTapZone = zoneBefore }
            }
        }
    }

    override fun onText(text: String) {
        if (gate.defer { onText(text) }) return
        touched()
        val ic = currentInputConnection ?: return
        if (mode == Mode.EMOJI_SEARCH) {
            emojiQuery += text; updateEmojiSearch()
            if (shift == Shift.ON) { shift = Shift.OFF; kv?.shift = shift }
            return
        }
        if (text.length == 1 && text[0].isLetter()) { wordTouches.clear(); zone.onLetter(1f) }
        if (text.length == 2 && text in Layouts.DOUBLES) { typePair(ic, text); return }
        type(ic, text)
    }

    private fun typePair(ic: InputConnection, pair: String) {
        commitAdapt()
        Brain.clips.dismissRecent()
        lastSwipe = null; forgetMissedSwipe()
        wordBoundary()
        lastWasSpace = false
        lastFix = null
        ic.beginBatchEdit()
        ic.commitText(pair.substring(0, 1), 1)
        // newCursorPosition = 0 — курсор перед вставленным, то есть между открывающим и закрывающим.
        ic.commitText(pair.substring(1), 0)
        ic.endBatchEdit()
        autoSpace = false
        if (shift == Shift.ON) shift = Shift.OFF
        refresh()
    }

    private fun type(ic: InputConnection, t: String) {
        commitAdapt()
        Brain.clips.dismissRecent()
        lastSwipe = null; forgetMissedSwipe()
        if (!(t.length == 1 && t[0].isLetter())) wordBoundary()
        lastWasSpace = false
        lastFix = null
        if (t.length == 1 && t[0] in SENTENCE_PUNCT) {
            ic.beginBatchEdit()
            finishWord(ic, t)
            if (autoSpace && textBefore(ic, 1) == " ") ic.deleteSurroundingText(1, 0)
            ic.commitText(t, 1)
            ic.endBatchEdit()
        } else {
            ic.commitText(t, 1)
        }
        autoSpace = false
        if (shift == Shift.ON) shift = Shift.OFF
        refresh()
    }

    private fun finishWord(ic: InputConnection, sep: String) {
        val before = textBefore(ic)
        val word = TextCtx.currentWord(before)
        // Слово — кусок вставленного из сейфа: не учим и не исправляем.
        if (word.isEmpty() || fromSecret(word)) { wordTouches.clear(); return }
        val prev = safeCtx(TextCtx.contextKey(before, before.length - word.length))
        var final = word
        val s = sugg
        val fix = if (allowCorrect && s != null && s.typed == word) s.autocorrect else null
        val toLang = if (fix != null) s?.autocorrectLang else null
        if (fix != null && fix != word) {
            ic.deleteSurroundingText(word.length, 0)
            ic.commitText(fix, 1)
            final = fix
            lastFix = Fix(word, fix, sep, if (toLang != null) lang else null)
        }
        if (toLang != null) {
            if (allowLearn) Brain.learn(final, prev, toLang)
            setLang(toLang)
        } else if (allowLearn) Brain.onWordFinished(lang, word, final, prev, wordTouches, fix != null)
        wordTouches.clear()
    }

    private fun onSpace(ic: InputConnection) {
        commitAdapt()
        Brain.clips.dismissRecent()
        wordBoundary()
        if (autoSpace) {
            // Пробел после подсказки уже стоит — не дублируем. ⌫ после этого — обычное стирание, не откат.
            autoSpace = false; lastWasSpace = true; lastFix = null
            return
        }
        val before = textBefore(ic, 3)
        // В записи сейфа текст не меняем: два пробела — это два пробела, а не «. ».
        if (prefs.doubleSpace && draft == null && lastWasSpace && before.length >= 2 && before.last() == ' ' &&
            (before[before.length - 2].isLetterOrDigit() || before[before.length - 2] in ")\"»")
        ) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            ic.endBatchEdit()
            lastWasSpace = false; lastFix = null
            refresh()
            return
        }
        lastFix = null
        ic.beginBatchEdit()
        finishWord(ic, " ")
        ic.commitText(" ", 1)
        ic.endBatchEdit()
        lastWasSpace = true
        if (mode == Mode.SYMBOLS || mode == Mode.SYMBOLS2) setMode(Mode.LETTERS)
        refresh()
    }

    private fun onEnter(ic: InputConnection) {
        Brain.clips.dismissRecent()
        wordBoundary()
        lastSwipe = null; forgetMissedSwipe()
        lastWasSpace = false; autoSpace = false
        val info = currentInputEditorInfo
        lastFix = null
        if (mode == Mode.LETTERS) finishWord(ic, "\n")
        lastFix = null
        val action = enterAction(info)
        val multiLine = info != null && info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        when {
            action != EditorInfo.IME_ACTION_NONE -> ic.performEditorAction(action)
            multiLine -> ic.commitText("\n", 1)
            else -> {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
        }
        refresh()
    }

    override fun onDelete(repeat: Int, words: Boolean) {
        if (gate.defer { onDelete(repeat, words) }) return
        touched()
        adaptPending = null // стёр — на этом жесте не учимся
        val ic = currentInputConnection ?: return
        if (mode == Mode.SAFE_PIN) {
            if (safePin.isNotEmpty() && !safeBusy) {
                safePin.setCharAt(safePin.length - 1, '0'); safePin.setLength(safePin.length - 1)
                safeMessage = ""; kv?.safeStatus = safeStatusText()
            }
            return
        }
        // ⌫ сразу после перестановки пары — возвращаем, как было нажато.
        val swap = lastSwap
        lastSwap = null
        if (repeat == 0 && swap != null && swap.event == events - 1 && textBefore(ic, swap.shown.length) == swap.shown) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(swap.shown.length, 0)
            ic.commitText(swap.asTyped, 1)
            ic.endBatchEdit()
            lastTap = null
            // Как обычный ⌫: модель ошиблась в этом слове — дальше зоны скромнее, следующее нажатие —
            // строго по пальцу, и пары в этом слове больше не переставляем.
            zone.onBackspace()
            reorderOff = true
            forceRefresh()
            return
        }
        // В поиске ⌫ стирает запрос; запрос пуст — стирает в поле, как обычно. Но удержание, начатое
        // в запросе, на запросе и останавливается — не уезжает стирать сообщение.
        if (mode == Mode.EMOJI_SEARCH) {
            if (repeat == 0) deleteInQuery = emojiQuery.isNotEmpty()
            if (deleteInQuery) {
                emojiQuery = if (words) emojiQuery.trimEnd().dropLastWhile { it != ' ' } else emojiQuery.dropLast(1)
                updateEmojiSearch()
                return
            }
        }
        autoSpace = false; lastWasSpace = false
        zone.onBackspace()
        val sw = lastSwipe
        lastSwipe = null; forgetMissedSwipe()
        if (repeat == 0 && sw != null) {
            val tail = sw.word + " "
            if (textBefore(ic, tail.length) == tail) {
                ic.deleteSurroundingText(tail.length, 0)
                Brain.unlearnLast(sw.word)
                lastFix = null
                forceRefresh()
                return
            }
        }
        val fix = lastFix
        lastFix = null
        if (repeat == 0 && fix != null) {
            val tail = fix.replacement + fix.sep
            if (textBefore(ic, tail.length) == tail) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(tail.length, 0)
                ic.commitText(fix.original, 1)
                ic.endBatchEdit()
                fix.fromLang?.let { setLang(it) }
                if (!fix.emoji) {
                    Brain.unlearnLast(fix.replacement)
                    if (allowLearn) Brain.learnRejected(fix.original)
                }
                fix.recentBefore?.let { prefs.recentEmoji = it; recentCache = null }
                wordTouches.clear()
                forceRefresh()
                return
            }
        }
        // Стирает пробел после только что набранного слова — значит, слово было с ошибкой:
        // отменяем его обучение (выучится заново, когда допишет правильно).
        if (repeat == 0) {
            val b = textBefore(ic)
            if (b.endsWith(" ")) {
                val w = TextCtx.currentWord(b.dropLast(1))
                if (w.isNotEmpty() && draft == null) Brain.unlearnLast(w)
            }
        }
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else if (words) {
            // Долгое удержание — стираем словами (слово вместе с пробелами после него).
            val before = textBefore(ic, 48)
            var i = before.length
            while (i > 0 && before[i - 1].isWhitespace()) i--
            while (i > 0 && !before[i - 1].isWhitespace()) i--
            val n = before.length - i
            if (n > 0) ic.deleteSurroundingText(n, 0)
            wordTouches.clear()
        } else {
            val before = ic.getTextBeforeCursor(2, 0)
            if (before.isNullOrEmpty()) {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            } else {
                val n = if (before.length == 2 && Character.isSurrogatePair(before[0], before[1])) 2 else 1
                ic.deleteSurroundingText(n, 0)
            }
            if (wordTouches.isNotEmpty()) wordTouches.removeAt(wordTouches.size - 1)
        }
        refresh()
    }

    override fun onShift() {
        if (gate.defer { onShift() }) return
        touched()
        val now = SystemClock.uptimeMillis()
        val quick = now - lastShiftTap < 350
        shift = when {
            shift == Shift.LOCK -> Shift.OFF
            quick -> Shift.LOCK
            shift == Shift.ON -> Shift.OFF
            else -> Shift.ON
        }
        lastShiftTap = if (shift == Shift.LOCK) 0L else now
        kv?.shift = shift
    }

    override fun onCursor(steps: Int) {
        if (gate.defer { onCursor(steps) }) return
        touched()
        val ic = currentInputConnection ?: return
        if (mode == Mode.EMOJI_SEARCH) return
        val code = if (steps > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(kotlin.math.abs(steps)) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        lastFix = null; autoSpace = false; lastSwipe = null; forgetMissedSwipe()
        wordBoundary()
        wordTouches.clear()
    }

    override fun onSuggestion(cand: Cand) {
        if (gate.defer { onSuggestion(cand) }) return
        touched()
        val ic = currentInputConnection ?: return
        if (cand.kind == Cand.FORGET) {
            Brain.forget(cand.text)
            Brain.saveAsync()
            forceRefresh()
            return
        }
        wordBoundary()
        if (cand.kind == Cand.EMOJI) {
            val word = TextCtx.currentWord(textBefore(ic))
            ic.beginBatchEdit()
            if (word.isNotEmpty()) ic.deleteSurroundingText(word.length, 0)
            ic.commitText(cand.text + " ", 1)
            ic.endBatchEdit()
            val recentBefore = prefs.recentEmoji
            rememberEmoji(cand.text)
            lastFix = if (word.isNotEmpty()) Fix(word, cand.text, " ", emoji = true, recentBefore = recentBefore) else null
            lastSwipe = null; forgetMissedSwipe(); wordTouches.clear()
            autoSpace = true; lastWasSpace = false
            forceRefresh()
            return
        }
        if (cand.kind == Cand.CLIP) {
            // По id — ровно та запись, что на плашке (не подпись «Скопированная картинка» текстом).
            val item = Brain.clips.byId(cand.id) ?: run { forceRefresh(); return }
            // Плашку убираем, только если вставилось (поле без картинок — пусть останется для другого поля).
            if (item.isImage) { if (insertImage(ic, item)) Brain.clips.markUsed(item); forceRefresh() }
            else { Brain.clips.markUsed(item); paste(ic, item.text) }
            return
        }
        if (cand.kind == Cand.SWIPE && missedSwipe != null) {
            val found = missedSwipe!!
            missedSwipe = null
            insertSwipe(listOf(cand.text) + found.filter { it != cand.text }, Shift.OFF)
            return
        }
        if (cand.kind == Cand.SWIPE) {
            val sw = lastSwipe ?: return
            if (cand.text == sw.word) return
            val tail = sw.word + " "
            if (textBefore(ic, tail.length) != tail) return
            ic.beginBatchEdit()
            ic.deleteSurroundingText(tail.length, 0)
            ic.commitText(cand.text + " ", 1)
            ic.endBatchEdit()
            Brain.unlearnLast(sw.word)
            if (allowLearn) Brain.learnRejected(cand.text)
            sw.word = cand.text
            adaptPending?.word = cand.text // выбрал другое — значит, жест был им
            forceRefresh()
            return
        }
        lastSwipe = null; forgetMissedSwipe()
        val before = textBefore(ic)
        val word = TextCtx.currentWord(before)
        val prev = safeCtx(TextCtx.contextKey(before, before.length - word.length))
        val secret = fromSecret(word)
        if (cand.kind == Cand.RELAYOUT) {
            ic.beginBatchEdit()
            if (word.isNotEmpty()) ic.deleteSurroundingText(word.length, 0)
            ic.commitText(cand.text + " ", 1)
            ic.endBatchEdit()
            val to = if (lang == Langs.RU) Langs.EN else Langs.RU
            if (allowLearn) Brain.learn(cand.text, prev, to)
            setLang(to)
            wordTouches.clear(); lastFix = null; autoSpace = true; lastWasSpace = false
            forceRefresh()
            return
        }
        ic.beginBatchEdit()
        if (cand.kind == Cand.TYPED) {
            if (allowLearn && !secret) Brain.learnRejected(word)
        } else {
            if (word.isNotEmpty()) ic.deleteSurroundingText(word.length, 0)
            ic.commitText(cand.text, 1)
        }
        ic.commitText(" ", 1)
        ic.endBatchEdit()
        if (allowLearn && !(cand.kind == Cand.TYPED && secret)) Brain.learn(if (cand.kind == Cand.TYPED) word else cand.text, prev, lang)
        wordTouches.clear()
        lastFix = null
        autoSpace = true
        lastWasSpace = false
        refresh()
    }

    override fun onSuggestionLongPress(cand: Cand) {
        touched()
        kv?.suggestions = Suggestions(sugg?.typed ?: "", listOf(Cand(cand.text, Cand.FORGET)), null)
    }

    /**
     * Свайп распознаётся в фоне — интерфейс не замирает, пока перебирается словарь.
     * Всё, что владелец нажимает дальше, ждёт в очереди (gate) и выполняется после вставки слова.
     */
    /** Распознавание для подсказки во время свайпа: пока не закончилось — новое не начинаем. */
    private var previewTask: Future<*>? = null

    override fun onSwipeProgress(pts: FloatArray, times: FloatArray, centers: Map<Char, FloatArray>, aspect: Float) {
        if (!allowSuggest || pendingSwipe != null || previewTask?.isDone == false) return
        val ic = currentInputConnection ?: return
        val before = textBefore(ic)
        val word = TextCtx.currentWord(before)
        val ctxKey = safeCtx(if (word.isNotEmpty()) normalize(word) else TextCtx.contextKey(before, before.length))
        val sh = shift
        previewTask = Brain.decodeSwipeAsync(lang, pts, times, centers, ctxKey, aspect) { found ->
            main.post { kv?.let { v -> if (v.swipeActive && pendingSwipe == null) v.swipePreview = cased(found, sh).firstOrNull() } }
        }
    }

    override fun onSwipe(pts: FloatArray, times: FloatArray, centers: Map<Char, FloatArray>, aspect: Float) {
        previewTask?.cancel(true); previewTask = null
        if (gate.defer { onSwipe(pts, times, centers, aspect) }) return
        touched()
        commitAdapt()
        val ic = currentInputConnection ?: return
        wordBoundary()
        lastFix = null; lastWasSpace = false; forgetMissedSwipe()
        wordTouches.clear()
        val before = textBefore(ic)
        val word = TextCtx.currentWord(before)
        val ctxKey = safeCtx(if (word.isNotEmpty()) normalize(word) else TextCtx.contextKey(before, before.length))
        val p = PendingSwipe(shift, before, selectedText(ic), pts, centers)
        pendingSwipe = p
        gate.begin()
        p.task = Brain.decodeSwipeAsync(lang, pts, times, centers, ctxKey, aspect) { found -> main.post { onSwipeDecoded(p, found) } }
        // Сторож: если декодер завис, очередь не должна ждать вечно — слово теряем, ввод продолжается.
        val watchdog = Runnable {
            if (pendingSwipe !== p) return@Runnable
            Log.w(TAG, "swipe decode timed out")
            Brain.restartSwipeDecoder()
            dropPendingSwipe()
            gate.end()
        }
        p.watchdog = watchdog
        main.postDelayed(watchdog, SWIPE_TIMEOUT_MS)
    }

    /**
     * Новый ввод — потерянный свайп больше не предлагаем. Но нажатия из очереди, сделанные ещё
     * до того, как слово было готово, его не убирают: владелец его даже не успел увидеть.
     */
    private fun forgetMissedSwipe() { if (!gate.replaying) missedSwipe = null }

    private fun selectedText(ic: InputConnection): String = ic.getSelectedText(0)?.toString() ?: ""

    private fun dropPendingSwipe() {
        val p = pendingSwipe ?: return
        pendingSwipe = null
        p.watchdog?.let { main.removeCallbacks(it) }
        p.task?.cancel(true)
    }

    private fun onSwipeDecoded(p: PendingSwipe, found: List<String>) {
        if (pendingSwipe !== p) return // поле ввода сменилось или сработал сторож
        pendingSwipe = null
        p.watchdog?.let { main.removeCallbacks(it) }
        try {
            if (found.isNotEmpty()) placeSwipe(p, found)
        } catch (e: Exception) {
            Log.e(TAG, "swipe insert failed", e)
        } finally {
            gate.end()
        }
    }

    /**
     * Пока слово распознавалось, текст в поле мог измениться без нас — приложение что-то вставило,
     * или владелец переставил курсор. Тогда вставлять слово вслепую нельзя: оно попадёт не туда.
     * Оставляем его в полосе подсказок — тап вставит там, где курсор сейчас.
     */
    private fun placeSwipe(p: PendingSwipe, found: List<String>) {
        val ic = currentInputConnection ?: return
        if (textBefore(ic) == p.before && selectedText(ic) == p.selected) {
            insertSwipe(found, p.shift, p)
        } else if (allowSuggest) {
            missedSwipe = cased(found, p.shift)
            forceRefresh()
        }
    }

    private fun cased(words: List<String>, s: Shift) = words.map {
        when (s) {
            Shift.LOCK -> upperChars(it)
            Shift.ON -> it.replaceFirstChar { c -> c.uppercaseChar() }
            Shift.OFF -> it
        }
    }

    /**
     * Слово из свайпа ждёт подтверждения: набрали дальше (не стерев его) или выбрали другое из подсказок —
     * учимся, куда у владельца уезжает палец (Engine.learnSwipe). Стёрли — не учимся.
     */
    private class AdaptSample(val pts: FloatArray, val centers: Map<Char, FloatArray>, var word: String)
    private var adaptPending: AdaptSample? = null

    private fun commitAdapt() {
        val a = adaptPending ?: return
        adaptPending = null
        if (allowLearn) Brain.learnSwipe(a.pts, a.word, a.centers)
    }

    private fun insertSwipe(found: List<String>, shift: Shift, from: PendingSwipe? = null) {
        val ic = currentInputConnection ?: return
        touched() // своя правка: её отклик в onUpdateSelection не считать чужим
        val before = textBefore(ic)
        val word = TextCtx.currentWord(before)
        val ctxKey = safeCtx(if (word.isNotEmpty()) normalize(word) else TextCtx.contextKey(before, before.length))
        Brain.clips.dismissRecent()
        val cased = cased(found, shift)
        val needSpace = before.isNotEmpty() && !before.last().isWhitespace() && before.last() !in "([«\"'"
        ic.beginBatchEdit()
        if (needSpace) ic.commitText(" ", 1)
        ic.commitText(cased[0] + " ", 1)
        ic.endBatchEdit()
        if (allowLearn) Brain.learn(cased[0], ctxKey, lang)
        lastSwipe = SwipeResult(cased[0], cased)
        adaptPending = from?.let { AdaptSample(it.pts, it.centers, cased[0]) }
        autoSpace = true
        if (this.shift == Shift.ON) this.shift = Shift.OFF
        refresh()
    }

    private fun switchLang() {
        setLang(if (lang == Langs.RU) Langs.EN else Langs.RU)
        forceRefresh()
    }

    private fun setLang(l: Lang) {
        if (lang == l) return
        lang = l
        prefs.lang = l.code
        if (mode == Mode.LETTERS || mode == Mode.EMOJI_SEARCH) applyLayout()
    }

    override fun onLangSwipe() {
        if (gate.defer { onLangSwipe() }) return
        touched()
        if (mode == Mode.LETTERS || mode == Mode.EMOJI_SEARCH) switchLang()
    }

    override fun onOneHand(mode: Int) {
        prefs.oneHand = mode
    }

    override fun onGlobeLongPress() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    override fun onClip(index: Int, action: Int) {
        // Строка — сразу по id: пока ввод ждёт в очереди, список мог измениться.
        val id = kv?.clipList?.getOrNull(index)?.id ?: return
        clipAction(id, action)
    }

    private fun clipAction(id: Long, action: Int) {
        if (gate.defer { clipAction(id, action) }) return
        touched()
        if (mode == Mode.SAFE) {
            // Сейф заперли (например, экран сейфа в Klava ушёл) — снова ПИН.
            if (!Brain.safe.isUnlocked) { openSafe(); return }
            if (action == KeyboardView.CLIP_DELETE) { safeDelete(id); return }
            // Запись сейфа — вставляем в поле (не в историю буфера, не в обучение, не в подсказки).
            val e = Brain.safe.entries().firstOrNull { it.id == id } ?: return
            val ic = currentInputConnection ?: return
            lastSwipe = null; forgetMissedSwipe(); lastFix = null; autoSpace = false; lastWasSpace = false
            wordTouches.clear(); wordBoundary()
            ic.commitText(e.secret, 1)
            secretParts.add(normalize(e.secret))
            e.secret.split(' ', '\n', '\t').filter { it.isNotBlank() }.forEach { secretParts.add(normalize(it)) }
            forceRefresh()
            return
        }
        // Запись могла пропасть (просрочилась, удалена) — тогда просто обновим список.
        val item = Brain.clips.byId(id)
        if (item == null) { kv?.clipList = clipRows(); return }
        if (action == KeyboardView.CLIP_DELETE) {
            Brain.clips.remove(item)
            kv?.clipList = clipRows()
            return
        }
        if (action == KeyboardView.CLIP_TO_SAFE) {
            if (item.isImage) toast("В сейф — только текст") else moveToSafe(item)
            return
        }
        if (action == KeyboardView.CLIP_PIN) {
            if (!Brain.clips.setPinned(item, !item.pinned)) {
                toast(if (item.isImage) "Закрепить можно до ${Clips.MAX_PINNED_IMAGES} картинок"
                      else "Закрепить можно до ${Clips.MAX_PINS} записей")
            }
            kv?.clipList = clipRows()
            return
        }
        val ic = currentInputConnection ?: return
        if (item.isImage) { if (!insertImage(ic, item)) return } else paste(ic, item.text)
        Brain.clips.markUsed(item)
        setMode(Mode.LETTERS)
    }

    private fun paste(ic: InputConnection, t: String) {
        lastSwipe = null; forgetMissedSwipe(); lastFix = null; autoSpace = false; lastWasSpace = false
        wordTouches.clear()
        wordBoundary()
        ic.commitText(t, 1)
        forceRefresh()
    }

    override fun onEmoji(e: String) {
        if (gate.defer { onEmoji(e) }) return
        touched()
        val ic = currentInputConnection ?: return
        ic.commitText(e, 1)
        // В запись сейфа — не запоминаем в «недавних»; строку записи обновляем.
        if (draft != null) { forceRefresh(); return }
        val toneChanged = rememberEmoji(e)
        autoSpace = false; lastWasSpace = false; lastFix = null; lastSwipe = null; forgetMissedSwipe()
        wordBoundary()
        // Выбрали другой цвет кожи — сетка сразу показывает его (не прыгая); «недавние» обновятся при следующем открытии.
        if (toneChanged && mode == Mode.EMOJI) kv?.emojiSections = emojiSections()
        if (toneChanged && mode == Mode.EMOJI_SEARCH) updateEmojiSearch()
    }

    // ================= эмодзи =================

    private var emojiQuery = ""
    /** «Недавние» на момент открытия панели: пока она открыта, раздел не меняется и сетка не прыгает. */
    private var panelRecent: List<String> = emptyList()
    // Разобранные настройки — на каждое нажатие (подсказка эмодзи) не перечитываем строки из prefs.
    private var tonesCache: Map<String, String>? = null
    private var recentCache: Set<String>? = null
    // Ключ ещё не готов — не запоминаем пустое на всю жизнь клавиатуры.
    private val tones: Map<String, String> get() = tonesCache ?: prefs.emojiTones.also { if (prefs.secretsReadable) tonesCache = it }

    /**
     * Недавние и выбор цвета кожи. В полях инкогнито и с выключенным обучением не пишем ничего —
     * ни недавних, ни цвета кожи: это тоже сведения о владельце.
     * @return true, если цвет кожи для этого эмодзи поменялся.
     */
    private fun rememberEmoji(e: String): Boolean {
        if (!allowLearn) return false
        prefs.recentEmoji = (listOf(e) + prefs.recentEmoji.filter { it != e }).take(RECENT_EMOJI)
        recentCache = null
        val data = Emoji.data ?: return false
        val base = data.base(e)
        if (data.item(base)?.tones.isNullOrEmpty()) return false
        val t = tones
        if ((t[base] ?: base) == e) return false
        prefs.emojiTones = if (e == base) t - base else t + (base to e)
        tonesCache = null
        return true
    }

    private fun toned(e: String): String = tones[e] ?: e

    private fun emojiTones(e: String): List<String> {
        val item = Emoji.data?.item(e) ?: return emptyList()
        return if (item.tones.isEmpty()) emptyList() else listOf(item.e) + item.tones
    }

    private fun emojiSections(): List<EmojiSection> {
        val data = Emoji.data ?: return emptyList()
        val out = ArrayList<EmojiSection>()
        if (panelRecent.isNotEmpty()) out.add(EmojiSection("recent", panelRecent))
        for (g in data.groups) out.add(EmojiSection(g.id, g.items.map { toned(it.e) }))
        return out
    }

    private fun recentBonus(): (String) -> Float {
        val recent = recentCache ?: prefs.recentEmoji.mapTo(HashSet()) { Emoji.data?.base(it) ?: it }
            .also { if (prefs.secretsReadable) recentCache = it }
        return { e -> if (e in recent) 0.6f else 0f }
    }

    private fun updateEmojiSearch() {
        val v = kv ?: return
        val data = Emoji.data
        v.searchQuery = emojiQuery
        v.searchResults = when {
            // Пустой запрос — недавние, чтобы было что нажать сразу.
            // Снимок на момент открытия: лента не прыгает, пока по ней нажимают.
            emojiQuery.isBlank() -> panelRecent
            data == null -> emptyList()
            else -> data.search(emojiQuery, bonus = recentBonus()).map { toned(it.e) }
        }
    }

    override fun onEmojiSearch(open: Boolean) {
        if (gate.defer { onEmojiSearch(open) }) return
        touched()
        setMode(if (open) Mode.EMOJI_SEARCH else Mode.EMOJI)
    }

    // ================= ИИ =================

    private class AiSnap(val before: String, val selected: String, val after: String) {
        val hasSel get() = selected.isNotEmpty()
        val text get() = if (hasSel) selected else before + after
    }

    private val aiClient by lazy { com.kai.keyboard.data.ai.OpenAiCompatibleClient() }
    private val aiUi = AiUi(this) { kv }
    private var aiHandle: com.kai.keyboard.data.ai.OpenAiCompatibleClient.Handle? = null
    /** Запрос в работе: ответ чужого (отменённого) запроса не показываем. */
    private var aiToken = 0
    /** Действие с {{instruction}}: поле, текст которого ждёт инструкции (строка над клавишами). */
    private var aiPending: AiSnap? = null
    private var aiPendingAction: AiAction? = null
    private var aiPendingModel: AiModel? = null
    private var aiResultText: String? = null

    private var aiTabs: List<List<AiColumn>> = emptyList()
    private var aiActs: List<AiAction> = emptyList()

    private fun aiTitle(a: AiAction): String = a.builtin?.let {
        when (it) {
            Builtin.SPELL -> getString(R.string.ai_fn_spell)
            Builtin.TRANSLATE -> getString(R.string.ai_fn_translate)
            Builtin.EDIT -> getString(R.string.ai_fn_edit)
        }
    } ?: a.title

    private fun aiBuild() {
        val pages = aiPages(prefs.aiModels, prefs.aiEnabledActions)
        aiTabs = pages.tabs
        aiActs = pages.actions
        val v = kv ?: return
        v.aiSoloCols = aiTabs.indices.map { prefs.aiSolo.getOrElse(it) { -1 } }
        v.aiSideLeft = prefs.aiSideLeft
        v.aiSideRight = prefs.aiSideRight
        v.aiTabs = aiTabs.map { tab ->
            KeyboardView.AiTab(tab.map { c -> c.model?.shown ?: "" }.toTypedArray())
        }
        v.aiActions = aiActs.map { aiTitle(it) }
        v.aiColActions = aiTabs.map { tab -> tab.map { it.actions.toIntArray() } }
        v.aiEmptyText = when {
            aiTabs.all { tab -> tab.all { it.model == null } } -> getString(R.string.ai_not_configured)
            aiActs.isEmpty() -> getString(R.string.ai_no_actions)
            else -> ""
        }
    }

    override fun onAiSolo(tab: Int, col: Int) {
        val tabCols = if (aiTabs.isNotEmpty()) aiTabs.getOrNull(tab)?.size
            else aiTabs(prefs.aiModels).getOrNull(tab)?.size
        val cols = tabCols ?: return
        val solo = if (col in 0 until cols) col else -1
        val cur = prefs.aiSolo.toMutableList()
        while (cur.size <= tab) cur.add(-1)
        cur[tab] = solo
        prefs.aiSolo = cur
        kv?.aiSoloCols = cur
    }

    override fun onAiSideChanged(right: Boolean, ids: List<String>) {
        if (right) prefs.aiSideRight = ids else prefs.aiSideLeft = ids
    }

    override fun onAiShortcut(id: String) {
        if (gate.defer { onAiShortcut(id) }) return
        touched()
        when (id) {
            AiShortcut.CLIP -> if (clipKey()) setMode(Mode.CLIPBOARD) else toast(getString(R.string.ai_sc_clip_off))
            AiShortcut.SAFE -> openSafe()
            AiShortcut.EMOJI -> setMode(Mode.EMOJI)
            AiShortcut.SETTINGS -> startActivity(android.content.Intent(this, SettingsActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun aiForget() {
        aiPending = null; aiPendingAction = null; aiPendingModel = null
    }
    private fun aiSnapshot(): AiSnap? {
        val ic = super.getCurrentInputConnection() ?: return null
        val sel = ic.getSelectedText(0)?.toString().orEmpty()
        if (sel.isNotEmpty()) return AiSnap("", sel, "")
        val b = ic.getTextBeforeCursor(AI_MAX_CHARS, 0)?.toString().orEmpty()
        val a = ic.getTextAfterCursor(AI_MAX_CHARS, 0)?.toString().orEmpty()
        return AiSnap(b, "", a)
    }

    private fun aiCancel() {
        aiToken++
        aiHandle?.cancel(); aiHandle = null
        aiForget()
        aiUi.dismissAll()
    }

    override fun onAiClick() {
        if (gate.defer { onAiClick() }) return
        touched()
        if (!aiAllowed()) return
        if (mode == Mode.AI) { setMode(Mode.LETTERS); return }
        aiBuild()
        if (aiActs.isEmpty() || aiTabs.all { tab -> tab.all { it.model == null } }) {
            toast(getString(if (aiActs.isEmpty()) R.string.ai_no_actions else R.string.ai_not_configured))
            return
        }
        setMode(Mode.AI)
    }

    override fun onAiAction(tab: Int, col: Int, row: Int) {
        if (gate.defer { onAiAction(tab, col, row) }) return
        touched()
        val model = aiTabs.getOrNull(tab)?.getOrNull(col)?.model ?: return
        val act = aiActs.getOrNull(row) ?: return
        if (!model.ready) return
        val snap = aiSnapshot()
        val src = snap?.text.orEmpty()
        val prompt = prefs.aiPrompt(act)
        // Промт должен куда-то вставить текст: иначе действие просто нечего делать.
        if (!AiPrompts.hasRequired(prompt)) {
            toast(getString(R.string.ai_prompt_no_required))
            return
        }
        // {{text}} в промте, а поля ввода нет (или пусто) — отправлять нечего.
        if (AiPrompts.needsText(prompt) && src.isBlank()) {
            toast(getString(R.string.ai_no_text))
            return
        }
        if (snap == null) return
        if (prompt.contains(AiPrompts.INSTRUCTION)) {
            // Инструкцию владелец набирает строкой над клавишами — как запись сейфа.
            aiPending = snap
            aiPendingAction = act
            aiPendingModel = model
            startDraft()
            kv?.draftHint = getString(R.string.ai_instruction_hint)
            aiPending = snap; aiPendingAction = act; aiPendingModel = model
        } else aiRun(act, model, snap, "")
    }

    private fun aiRun(act: AiAction, model: AiModel, snap: AiSnap, instruction: String) {
        val th = Theme.of(prefs.theme)
        val acc = th.accentFor(prefs.accent)
        val src = snap.text
        val lang = prefs.aiTargetLang.ifBlank { AiPrompts.autoTargetLang(src) }
        val prompt = AiPrompts.build(prefs.aiPrompt(act), src, instruction, lang)
        val cfg = com.kai.keyboard.data.ai.OpenAiCompatibleClient.Config(
            prefs.aiBaseUrl, prefs.aiKey, model.model, prefs.aiTemperature.toDouble())
        val token = ++aiToken
        aiUi.showProgress(th, acc) { aiCancel() }
        aiHandle = aiClient.chat(cfg, prompt) { res ->
            main.post {
                if (token != aiToken) return@post
                aiHandle = null
                aiUi.dismissProgress()
                when (res) {
                    is com.kai.keyboard.data.ai.OpenAiCompatibleClient.Result.Fail ->
                        toast(com.kai.keyboard.data.ai.AiErrors.message(this, res.failure))
                    is com.kai.keyboard.data.ai.OpenAiCompatibleClient.Result.Ok ->
                        aiPreview(act, snap, res.text.trim(), th, acc)
                }
            }
        }
    }

    private fun aiPreview(act: AiAction, snap: AiSnap, result: String, th: Theme, acc: Int) {
        if (result.isEmpty()) { toast(getString(R.string.ai_err_bad_response)); return }
        aiResultText = result
        // Действие по инструкции — подсвечиваем только отличия, остальные — весь результат.
        aiUi.showPreview(th, acc, snap.text, result, act.showsDiff,
            onReplace = { aiReplace(snap, result) },
            onInsert = { aiInsert(result) },
            onCopy = {
                (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("", result))
                toast(getString(R.string.ai_copied))
            },
            onCancel = { aiCancel() })
    }

    private fun aiReplace(snap: AiSnap, result: String) {
        val ic = super.getCurrentInputConnection()
        val now = aiSnapshot()
        // Сверяем посимвольно: курсор может стоять где угодно и выделения могло не быть — важно лишь,
        // что в поле сейчас ровно тот текст, из которого мы спрашивали.
        val same = ic != null && now != null && TextDiff.same(now.text, snap.text)
        aiUi.dismissAll()
        if (!same || ic == null) { toast(getString(R.string.ai_replace_failed)); return }
        val fresh = now!!
        ic.beginBatchEdit()
        try {
            // Заменяем весь текст, из которого спрашивали: снимок мог быть и выделением, и целым полем.
            if (fresh.hasSel) ic.commitText(result, 1)
            else {
                ic.deleteSurroundingText(fresh.before.length, fresh.after.length)
                ic.commitText(result, 1)
            }
            ic.finishComposingText()
        } finally { ic.endBatchEdit() }
        aiResultText = null
        lastBefore = null; lastFix = null; lastSwipe = null; autoSpace = false; lastWasSpace = false
        wordTouches.clear()
        refresh()
    }

    private fun aiInsert(result: String) {
        val ic = super.getCurrentInputConnection()
        aiUi.dismissAll()
        if (ic == null) { toast(getString(R.string.ai_insert_failed)); return }
        ic.commitText(result, 1)
        ic.finishComposingText()
        aiResultText = null
        lastBefore = null; lastFix = null; lastSwipe = null; autoSpace = false; lastWasSpace = false
        wordTouches.clear()
        refresh()
    }

    // ================= сейф =================

    private val safePin = StringBuilder()
    private enum class SafeStage { UNLOCK, CREATE, CONFIRM }
    private var safeStage = SafeStage.UNLOCK
    private var safeFirstPin = ""
    private var safeMessage = ""
    @Volatile private var safeBusy = false
    private var pendingToSafe: Long? = null

    private fun clipKey() = prefs.clipHistory || Brain.safe.exists()

    private fun clipBottomKey() = clipKey() && prefs.clipBottomKey

    /** Вход по отпечатку включён (узнаём в фоне: метаданные сейфа читаются с ключом телефона). */
    @Volatile private var safeBioKnown = false

    private fun refreshSafeInfo() {
        val session = safeSession
        Thread {
            val bio = runCatching { SafeBio.available(this) && Brain.safe.biometricBlob() != null }.getOrDefault(false)
            val wait = runCatching { Brain.safe.waitMs() }.getOrDefault(0L)
            val state = runCatching { Brain.safe.state() }.getOrDefault(Safe.State.NOT_NOW)
            Brain.onMain {
                if (session != safeSession || mode != Mode.SAFE_PIN) return@onMain
                safeBioKnown = bio
                kv?.safeBio = bio
                if (safeMessage.isEmpty() && safePin.isEmpty()) safeMessage = when {
                    state == Safe.State.BROKEN -> "Сейф повреждён — удалите его в настройках KAI"
                    wait > 0 -> waitText(wait)
                    else -> ""
                }
                kv?.safeStatus = safeStatusText()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun safeStatusText(): String {
        if (safeBusy) return "Проверяю…"
        if (safeMessage.isNotEmpty()) return safeMessage
        if (safePin.isEmpty()) return when (safeStage) {
            SafeStage.CREATE -> "Придумайте ПИН сейфа (6–12 цифр)"
            SafeStage.CONFIRM -> "Повторите ПИН"
            SafeStage.UNLOCK -> "ПИН сейфа"
        }
        return "●".repeat(safePin.length)
    }

    /** Стереть набранный ПИН и в памяти (не только длину). */
    private fun clearPin() {
        for (i in 0 until safePin.length) safePin.setCharAt(i, '0')
        safePin.setLength(0)
    }

    private fun openSafe() {
        if (Brain.safe.isUnlocked) { setMode(Mode.SAFE); finishPendingToSafe(); return }
        clearPin(); safeMessage = ""
        safeStage = if (Brain.safe.exists()) SafeStage.UNLOCK else SafeStage.CREATE
        setMode(Mode.SAFE_PIN)
        kv?.safeBio = safeBioKnown
        kv?.safeStatus = safeStatusText()
        refreshSafeInfo()
    }

    private fun waitText(ms: Long): String {
        val s = (ms + 999) / 1000
        return "Подождите " + if (s < 120) "$s с" else "${(s + 59) / 60} мин"
    }

    private fun submitPin() {
        if (safeBusy) return
        val pin = safePin.toString()
        clearPin()
        when (safeStage) {
            SafeStage.CREATE -> {
                if (!Safe.validPin(pin)) safeMessage = "Нужно от 6 до 12 цифр"
                else { safeFirstPin = pin; safeStage = SafeStage.CONFIRM; safeMessage = "" }
            }
            SafeStage.CONFIRM -> {
                if (pin != safeFirstPin) { safeStage = SafeStage.CREATE; safeMessage = "ПИНы не совпали — ещё раз" }
                else {
                    val since = Brain.safe.session
                    slowSafe({ Brain.safe.create(pin, since) }) { ok ->
                        when {
                            ok && Brain.safe.isUnlocked -> { ensureInboxLater(); setMode(Mode.SAFE); finishPendingToSafe() }
                            ok -> { safeStage = SafeStage.UNLOCK; safeMessage = "" } // создан, но уже заперт
                            else -> safeMessage = "Не получилось — попробуйте позже"
                        }
                    }
                }
                safeFirstPin = ""
            }
            SafeStage.UNLOCK -> {
                // Слишком короткий — не попытка (и не ошибка в счётчике).
                if (!Safe.canTry(pin)) { safeMessage = if (pin.isEmpty()) "" else "ПИН — от 6 цифр"; kv?.safeStatus = safeStatusText(); return }
                val since = Brain.safe.session
                slowSafe({ Brain.safe.unlock(pin, since) }) { r ->
                    if (r === Safe.Unlock.Ok) ensureInboxLater()
                    when (r) {
                        Safe.Unlock.Ok -> { setMode(Mode.SAFE); finishPendingToSafe() }
                        is Safe.Unlock.Wrong -> safeMessage = "Неверный ПИН" + if (r.waitMs > 0) " · " + waitText(r.waitMs) else ""
                        is Safe.Unlock.Wait -> safeMessage = waitText(r.waitMs)
                        Safe.Unlock.NotNow -> safeMessage = "Сейчас не открыть — попробуйте ещё раз"
                        Safe.Unlock.Busy -> {}
                        Safe.Unlock.Broken -> safeMessage = "Сейф повреждён - удалите его в настройках KAI"
                    }
                }
            }
        }
        kv?.safeStatus = safeStatusText()
    }

    /** Поле или клавиатура сменились: ответ проверки ПИНа, пришедший после этого, уже не применяем. */
    private var safeSession = 0

    /**
     * ПИН проверяется медленно (так задумано, против перебора) — не на основном потоке. Если за это время
     * клавиатура закрылась, сменилось поле или ушли с экрана ПИНа — сейф не открываем.
     */
    private fun <T> slowSafe(work: () -> T, done: (T) -> Unit) {
        safeBusy = true
        kv?.safeStatus = safeStatusText()
        val session = safeSession
        Thread {
            val r = runCatching(work)
            Brain.onMain {
                safeBusy = false
                if (session != safeSession || mode != Mode.SAFE_PIN) { Brain.safe.lock(); return@onMain }
                r.onSuccess(done).onFailure { safeMessage = "Не получилось — попробуйте позже" }
                if (mode == Mode.SAFE_PIN) kv?.safeStatus = safeStatusText()
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Перенести текст в сейф (в фоне: запись на диск). Получилось — убираем его из истории
     * (и из самого буфера, если он там); нет — так и говорим, ничего не удаляя.
     */
    private fun moveTextToSafe(text: String, then: () -> Unit = { clearClipIf(text) }) {
        val target = Brain.safe.target() // именно в этот сейф (стёрли, пока писали, — не кладём)
        Thread {
            val ok = runCatching { Brain.safe.put("", text, target) }.getOrDefault(false)
            Brain.onMain {
                if (!ok) { toast("Не получилось сохранить в сейф — попробуйте ещё раз"); return@onMain }
                Brain.clips.list().filter { !it.isImage && it.text == text }.forEach { Brain.clips.remove(it) }
                then()
                toast("Перенесено в сейф")
                if (mode == Mode.CLIPBOARD) kv?.clipList = clipRows()
                if (mode == Mode.SAFE) kv?.clipList = safeRows()
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Перенести запись истории в сейф — в один тап, даже запертый: класть в сейф можно открытым ключом,
     * а прочитать положенное — только открыв его. Сейфа ещё нет (или он старый, без «входящих») — откроем.
     */
    private fun moveToSafe(item: Clips.Item) {
        if (Brain.safe.isUnlocked) { moveTextToSafe(item.text); return }
        if (!Brain.safe.exists()) { pendingToSafe = item.id; openSafe(); return }
        val session = safeSession
        val target = Brain.safe.target()
        Thread {
            val ok = runCatching { Brain.safe.put("", item.text, target) }.getOrDefault(false)
            val canDeposit = ok || runCatching { Brain.safe.canDeposit() }.getOrDefault(false)
            Brain.onMain {
                if (ok) {
                    // Положено — убираем из истории и из буфера, даже если клавиатура уже в другом поле.
                    Brain.clips.list().filter { !it.isImage && it.text == item.text }.forEach { Brain.clips.remove(it) }
                    clearClipIf(item.text)
                    toast("В сейфе — появится, когда откроете его")
                    if (mode == Mode.CLIPBOARD) kv?.clipList = clipRows()
                    return@onMain
                }
                if (session != safeSession) return@onMain // клавиатура уже в другом поле или закрыта
                if (!Vault.ready) { toast("Хранилище ещё открывается — попробуйте через секунду"); return@onMain }
                if (canDeposit) { toast("Не получилось положить в сейф — попробуйте ещё раз"); return@onMain }
                pendingToSafe = item.id; openSafe() // старый сейф: один раз ПИН — дальше в один тап
            }
        }.apply { isDaemon = true }.start()
    }

    /** Убрать из системного буфера — только если в нём всё ещё именно это (не чужое, скопированное позже). */
    private fun clearClipIf(text: String) {
        if (Build.VERSION.SDK_INT < 28) return
        val cb = clipboard ?: return
        runCatching { if (cb.primaryClip?.getItemAt(0)?.text?.toString() == text) cb.clearPrimaryClip() }
    }

    /** Ключи для «в сейф в один тап» — уже после открытия, в фоне (ПИН для удаления не отличить по времени). */
    private fun ensureInboxLater() {
        Thread { runCatching { Brain.safe.ensureInbox() } }.apply { isDaemon = true }.start()
    }

    private fun finishPendingToSafe() {
        val id = pendingToSafe ?: return
        pendingToSafe = null
        Brain.clips.byId(id)?.let { if (!it.isImage) moveTextToSafe(it.text) }
    }

    private fun safeRows(): List<ClipRow> = Brain.safe.entries().map {
        // Само содержимое (клавиатура в сейфе защищена от скриншотов); название — если его дали.
        val shown = if (it.title.isEmpty()) it.secret else it.title + " · " + it.secret
        if (it.id == deleteAsk) ClipRow(it.id, "Удалить? Нажмите × ещё раз · $shown", warn = true)
        else ClipRow(it.id, shown)
    }

    private var deleteAsk: Long? = null
    private val clearDeleteAsk = Runnable { if (deleteAsk != null) { deleteAsk = null; if (mode == Mode.SAFE) kv?.clipList = safeRows() } }

    private fun safeDelete(id: Long) {
        main.removeCallbacks(clearDeleteAsk)
        if (deleteAsk != id) {
            deleteAsk = id
            kv?.clipList = safeRows()
            main.postDelayed(clearDeleteAsk, 3000)
            return
        }
        deleteAsk = null
        Thread {
            val ok = runCatching { Brain.safe.remove(id) }.getOrDefault(false)
            Brain.onMain {
                if (!ok) toast("Не получилось удалить — попробуйте ещё раз")
                if (mode == Mode.SAFE) { if (Brain.safe.isUnlocked) kv?.clipList = safeRows() else hideSafe() }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun bioReturn(info: EditorInfo?): Boolean {
        val keyguard = (getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager)?.isKeyguardLocked == true
        val now = android.os.SystemClock.elapsedRealtime()
        val window = Brain.safeOpenedAt != 0L && now - Brain.safeOpenedAt < SAFE_RETURN_MS
        return info != null && Brain.safe.isUnlocked && !keyguard && (window || now < bioGraceUntil) &&
            info.packageName == Brain.safeBioPackage
    }

    private var bioGraceUntil = 0L
    private val graceLock = Runnable {
        if (!isInputViewShown && android.os.SystemClock.elapsedRealtime() >= bioGraceUntil) { Brain.safe.lock(); pendingToSafe = null }
    }

    private fun consumeBioReturn() {
        val now = android.os.SystemClock.elapsedRealtime()
        Brain.safeOpenedAt = 0L; Brain.safeBioPending = false
        if (now >= bioGraceUntil) bioGraceUntil = now + BIO_GRACE_MS // повторные перезапуски окно не продлевают
    }

    override fun onWindowShown() {
        super.onWindowShown()
        // Показались заново: система могла пересоздать окно клавиатуры — раскладка тогда пустая,
        // а отрисовка лезет в неё (rows[0][0]). Пустая раскладка = живое приложение.
        if (kv?.layout?.keys.isNullOrEmpty()) applyLayout()
        // Показались снова, а сейф тем временем заперли (погас экран, таймер) — записи не показываем.
        if (mode == Mode.SAFE && !Brain.safe.isUnlocked) hideSafe()
        checkBioReturn()
        applyCaptionBar()
    }

    private fun checkBioReturn() {
        if (!isInputViewShown || mode == Mode.SAFE || draft != null) return
        if (!bioReturn(currentInputEditorInfo)) return
        consumeBioReturn()
        setMode(Mode.SAFE)
        finishPendingToSafe()
    }

    override fun onSafeHeader(action: Int) {
        if (gate.defer { onSafeHeader(action) }) return
        touched()
        when (action) {
            KeyboardView.SAFE_BACK -> { safeSession++; pendingToSafe = null; clearPin(); setMode(Mode.CLIPBOARD) }
            KeyboardView.SAFE_OPEN -> openSafe()
            KeyboardView.SAFE_CLOSE -> { Brain.safe.lock(); setMode(Mode.CLIPBOARD) }
            KeyboardView.SAFE_NEW -> if (Brain.safe.isUnlocked) startDraft() else hideSafe()
            KeyboardView.SAFE_BIO -> {
                // Окно отпечатка — отдельным экраном Klava (у клавиатуры своего нет); потом вернёмся в ЭТО поле.
                Brain.safeBioPackage = currentInputEditorInfo?.packageName
                Brain.safeBioStartedAt = android.os.SystemClock.elapsedRealtime()
                Brain.safeBioPending = true
                startActivity(android.content.Intent(this, SafeUnlockActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION))
            }
        }
    }

    override fun onEmojiSearchClear() {
        if (gate.defer { onEmojiSearchClear() }) return
        touched()
        emojiQuery = ""
        updateEmojiSearch()
    }

    private fun withEmoji(s: Suggestions?, word: String): Suggestions? {
        if (s == null || !emojiStrip) return s
        if (s.items.any { it.kind == Cand.CLIP || it.kind == Cand.FORGET }) return s
        val e = Emoji.data?.suggest(word, recentBonus()) ?: return s
        val em = Cand(toned(e), Cand.EMOJI)
        // Всегда вторым — это левый слот; второй вариант слова уходит вправо.
        val items = when (s.items.size) {
            0 -> listOf(Cand(word, Cand.TYPED), em)
            1 -> listOf(s.items[0], em)
            else -> listOf(s.items[0], em, s.items[1])
        }
        return Suggestions(s.typed, items, s.autocorrect, s.autocorrectLang)
    }

    companion object {
        private const val SENTENCE_PUNCT = ".,!?;:…)"
        private const val TAG = "Klava"
        /** Сколько символов до и после курсора отправляем ИИ (предел, чтобы не слать огромные поля). */
        private const val AI_MAX_CHARS = 8000
        private const val RECENT_EMOJI = 32
        /** Сколько ждём возвращения в поле после отпечатка (иначе сейф запирается). */
        const val SAFE_RETURN_MS = 60_000L
        /** Сколько ждём картинку из чужого приложения при копировании. */
        private const val CLIP_READ_MS = 5000L
        /** Миниатюра в памяти — не больше стольких пикселей до масштабирования при рисовании. */
        private const val MAX_THUMB_PIXELS = 1_000_000L
        /** Сколько после своей правки ждём отклика поля, прежде чем считать сдвиг курсора чужим. */
        private const val OWN_EDIT_MS = 600L
        private const val BIO_GRACE_MS = 5000L
        /** Обычно свайп распознаётся за миллисекунды; дольше — значит, декодер завис. */
        private const val SWIPE_TIMEOUT_MS = 1000L
    }
}
