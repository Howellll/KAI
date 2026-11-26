package com.kai.keyboard

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

object AppGate {
    private var started = 0
    @Volatile var inApp = false
        private set
    private val h = android.os.Handler(android.os.Looper.getMainLooper())

    @Volatile var passed = false

    fun onStart() { started++; inApp = true }

    fun onStop() {
        started = maxOf(0, started - 1)
        if (started == 0) h.postDelayed({
            if (started == 0) { inApp = false; passed = false; Brain.safe.lock(); onLocked?.invoke() }
        }, 400)
    }

    @Volatile var onLocked: (() -> Unit)? = null
}

/**
 * Сейф в приложении: создать, открыть ПИНом (свои кнопки, как экран блокировки телефона) или отпечатком,
 * добавить и изменить записи, сменить ПИН, «ПИНы для удаления», вход по отпечатку. Экран не попадает
 * на скриншоты и в «недавние»; ушли из приложения — сейф заперт.
 */
class SafeActivity : Activity() {
    companion object {
    }

    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private var started = false
    private var busy = false
    private var sub = false
    private var bioOffered = false
    private var renderGen = 0
    private var openShown = false
    /** Сейф заперли откуда угодно (клавиатура в соседнем окне, уход из приложения) — убираем записи с экрана. */
    private val onSafeLocked: () -> Unit = { runOnUiThread { if (started && openShown) render() } }
    private val safe get() = Brain.safe
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        Palette.apply(this) // в тон теме клавиатуры
        Brain.init(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            isFillViewport = true
            setBackgroundColor(Palette.bg)
            addView(root, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        setContentView(scroll)
        // Автозаполнение (менеджеры паролей) не должно ни видеть, ни предлагать «сохранить» записи сейфа.
        if (android.os.Build.VERSION.SDK_INT >= 26)
            window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        // Android 13+ (и жест «назад» нового типа): на входе — выйти, а не пройти мимо ПИНа.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                handleBack()
            }
        }
    }

    override fun onStart() { super.onStart(); started = true; AppGate.onStart(); safe.addLockListener(onSafeLocked) }

    override fun onResume() {
        super.onResume()
        if (sub && safe.isUnlocked && root.childCount > 0) return
        render()
    }

    override fun onStop() {
        super.onStop()
        started = false
        safe.removeLockListener(onSafeLocked)
        screen(true)
        sub = false
        AppGate.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { handleBack() }

    private fun handleBack() {
        when {
            sub -> render()
            else -> leave()
        }
    }

    private fun leave() { safe.lock(); finish() }

    /** Сейф открыт здесь, на экране сейфа. */
    private fun openedHere() { AppGate.passed = true }

    private fun ensureInboxLater() { Thread { runCatching { safe.ensureInbox() } }.apply { isDaemon = true }.start() }

    private fun screen(page: Boolean) {
        renderGen++
        openShown = safe.isUnlocked
        root.removeAllViews()
        root.removeCallbacks(tick)
        root.gravity = if (page) Gravity.NO_GRAVITY else Gravity.CENTER_HORIZONTAL
        root.setPadding(dp(20), dp(12), dp(20), dp(32))
        scroll.scrollTo(0, 0)
    }

    /**
     * Всё о сейфе читается с ключом телефона — в фоне; пока ключ не готов, ждём его (а не показываем
     * неверное «нет отпечатка» или «нет паузы»).
     */
    private fun render() {
        sub = false
        if (!AppGate.passed && safe.isUnlocked) safe.lock()
        if (!Vault.ready) {
            screen(false)
            root.addView(spacer())
            root.addView(centered(getString(R.string.sf_opening)))
            root.addView(spacer())
            waitForKey()
            return
        }
        val gen = renderGen
        slow({ Info(safe.state(), safe.isUnlocked, safe.entries(), safe.biometricBlob() != null,
            safe.duressCount(), safe.waitMs(), safe.forgetLeftMs()) }) { i ->
            if (gen != renderGen) return@slow // пока читали, открыли другой экран
            if (i.unlocked && !AppGate.passed) { safe.lock(); render(); return@slow }
            when {
                i.state == Safe.State.MISSING -> renderCreate()
                i.state == Safe.State.BROKEN -> renderBroken()
                !i.unlocked -> renderLocked(i)
                else -> renderOpen(i)
            }
        }
    }

    private val onKey: () -> Unit = { if (started) render() }
    private var waitingKey = false

    private fun waitForKey() {
        if (!waitingKey) { waitingKey = true; Vault.whenReady(onKey) }
        root.postDelayed({
            if (!started || Vault.ready) return@postDelayed
            screen(false)
            root.addView(spacer())
            root.addView(centered(getString(R.string.sf_nokey)))
            root.addView(primary(getString(R.string.sf_retry)) { Vault.retryNow(); render() })
            root.addView(spacer())
        }, 5000)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (waitingKey) Vault.cancelWait(onKey)
    }

    private class Info(val state: Safe.State, val unlocked: Boolean, val entries: List<Safe.Entry>,
                       val bio: Boolean, val duress: Int, val wait: Long, val forget: Long)

    private fun renderBroken() {
        screen(false)
        root.addView(spacer())
        root.addView(Glyph(this, Glyph.LOCK, Palette.danger), LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(heading(getString(R.string.sf_broken), center = true))
        root.addView(centered(getString(R.string.sf_broken_text)))
        // Обойти ПИН нельзя: повреждённый сейф можно только удалить (записи и так не открыть).
        root.addView(primary(getString(R.string.sf_delete_safe), Palette.danger) { confirmDestroy() })
        root.addView(spacer())
    }

    private fun confirmDestroy() {
        AlertDialog.Builder(this, Palette.dialog)
            .setTitle(getString(R.string.sf_delete_safe_q)).setMessage(getString(R.string.sf_delete_safe_msg))
            .setPositiveButton(getString(R.string.cf_delete)) { _, _ -> slow({ SafeBio.disable(); safe.destroy() }) { render() } }
            .setNegativeButton(getString(R.string.cf_cancel), null).showSecure()
    }

    // ================= ввод ПИНа =================

    private fun pinScreen(title: String, subtitle: String, minLen: Int, cancel: Boolean,
                          onPin: (PinPad, String) -> Unit): PinPad {
        screen(false)
        root.addView(LinearLayout(this).apply {
            if (cancel) { sub = true; addView(link(getString(R.string.cf_cancel)) { render() }) }
            else addView(link(getString(R.string.st_back)) { leave() })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(spacer())
        lateinit var pad: PinPad
        pad = PinPad(this, title, subtitle, minLen) { p -> onPin(pad, p) }
        root.addView(pad, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(spacer())
        return pad
    }

    private fun newPin(title: String, subtitle: String, cancel: Boolean, done: (PinPad, String) -> Unit) {
        var first: String? = null
        pinScreen(title, subtitle, 6, cancel) { pad, p ->
            val f = first
            when {
                f == null -> { first = p; pad.setTitle(getString(R.string.sf_repeat_pin), getString(R.string.sf_repeat_pin_sub)); pad.setStatus("") }
                f != p -> { first = null; pad.setTitle(title, subtitle); pad.setStatus(getString(R.string.sf_pins_differ), error = true) }
                else -> { first = null; done(pad, p) }
            }
        }
    }

    // ================= создание и вход =================

    private fun renderCreate() {
        screen(true)
        root.addView(topBar(getString(R.string.sf_title), toSettings = true))
        root.addView(group(listOf(
            infoRow(getString(R.string.sf_info_saved), getString(R.string.sf_info_saved_sub)),
            infoRow(getString(R.string.sf_info_pin), getString(R.string.sf_info_pin_sub)),
            infoRow(getString(R.string.sf_info_kbd), getString(R.string.sf_info_kbd_sub)),
        )))
        root.addView(primary(getString(R.string.sf_create)) {
            newPin(getString(R.string.sf_new_pin), getString(R.string.sf_pin_range), cancel = true) { pad, p ->
                pad.setPadEnabled(false); pad.setStatus(getString(R.string.sf_creating))
                val since = safe.session
                slow({ safe.create(p, since) }) { ok ->
                    if (ok && safe.isUnlocked) { openedHere(); ensureInboxLater() }
                    if (ok) render() else { pad.setPadEnabled(true); pad.setStatus(getString(R.string.sf_save_failed_later), true) }
                }
            }
        })
    }

    private var tick = Runnable {}

    private fun renderLocked(i: Info) {
        val bio = i.bio && SafeBio.available(this)
        val pad = pinScreen(getString(R.string.sf_title), getString(R.string.sf_enter_pin), 4, cancel = false) { pad, p ->
            if (busy) return@pinScreen
            busy = true
            pad.setPadEnabled(false); pad.setStatus(getString(R.string.sf_checking))
            val since = safe.session
            slow({ safe.unlock(p, since) }) { r ->
                busy = false
                if (r === Safe.Unlock.Ok) { openedHere(); ensureInboxLater() }
                if (!pad.isAttachedToWindow) { render(); return@slow }
                pad.setPadEnabled(true)
                showResult(r, pad)
            }
        }
        if (busy) { pad.setPadEnabled(false); pad.setStatus(getString(R.string.sf_checking)) }
        if (bio) {
            root.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER
                addView(roundIcon(Glyph.FINGERPRINT) { fingerprint() })
            }, root.childCount - 1, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(20) })
            if (!bioOffered && i.wait <= 0) { bioOffered = true; root.post { fingerprint() } }
        }
        // Забыли ПИН: восстановить его нельзя (он нигде не хранится), но можно удалить сам сейф — через час
        // после запроса (взявший телефон не удалит его сразу, а владелец, открыв сейф, запрос отменит).
        val forget = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        when {
            i.forget < 0 -> forget.addView(link(getString(R.string.sf_forgot)) { forgotPin() })
            i.forget > 0 -> {
                forget.addView(centered(getString(R.string.sf_forget_pending, minutes(i.forget))))
                forget.addView(link(getString(R.string.sf_forget_cancel)) { slow({ safe.cancelForget() }) { render() } })
            }
            else -> {
                forget.addView(centered(getString(R.string.sf_forget_ready)))
                forget.addView(link(getString(R.string.sf_delete_safe)) { confirmForget() }.apply { setTextColor(Palette.danger) })
                forget.addView(link(getString(R.string.sf_forget_cancel)) { slow({ safe.cancelForget() }) { render() } })
            }
        }
        root.addView(forget, root.childCount - 1, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        if (i.wait > 0) countdown(pad, i.wait)
    }

    private fun minutes(ms: Long): String {
        val m = ((ms + 59_999) / 60_000).toInt()
        return "$m " + when {
            m % 10 == 1 && m % 100 != 11 -> getString(R.string.sf_minute_one)
            m % 10 in 2..4 && m % 100 !in 12..14 -> getString(R.string.sf_minute_few)
            else -> getString(R.string.sf_minute_many)
        }
    }

    private fun forgotPin() {
        AlertDialog.Builder(this, Palette.dialog)
            .setTitle(getString(R.string.sf_forgot))
            .setMessage(getString(R.string.sf_forgot_msg))
            .setPositiveButton(getString(R.string.sf_request_delete)) { _, _ -> slow({ safe.requestForget() }) { render() } }
            .setNegativeButton(getString(R.string.cf_cancel), null).showSecure()
    }

    private fun confirmForget() {
        AlertDialog.Builder(this, Palette.dialog)
            .setTitle(getString(R.string.sf_delete_forever_q))
            .setMessage(getString(R.string.sf_delete_forever_msg))
            .setPositiveButton(getString(R.string.cf_delete)) { _, _ ->
                slow({ safe.forget().also { if (it) SafeBio.disable() } }) { ok -> if (!ok) toast(getString(R.string.sf_not_yet)); render() }
            }
            .setNegativeButton(getString(R.string.cf_cancel), null).showSecure()
            .getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Palette.danger)
    }

    /** Отсчёт паузы — одной цепочкой (tick): новый экран или новый отсчёт снимает прежний. */
    private fun countdown(pad: PinPad, waitMs: Long, delayMs: Long = 0) {
        root.removeCallbacks(tick)
        val until = android.os.SystemClock.elapsedRealtime() + delayMs + waitMs
        lateinit var r: Runnable
        r = Runnable {
            if (!pad.isAttachedToWindow) return@Runnable
            pad.setPadEnabled(false)
            val left = until - android.os.SystemClock.elapsedRealtime()
            if (left <= 0) { pad.setPadEnabled(true); pad.setStatus(""); return@Runnable }
            pad.setStatus(waitText(left))
            root.postDelayed(r, 1000)
        }
        tick = r
        if (delayMs > 0) { pad.setPadEnabled(false); root.postDelayed(r, delayMs) } else r.run()
    }

    private fun fingerprint() {
        slow({ safe.biometricBlob() }) { blob ->
            val c = blob?.let { SafeBio.decryptCipher(it) }
            if (blob == null || c == null) { toast(getString(R.string.sf_bio_changed)); return@slow }
            SafeBio.prompt(this, getString(R.string.sf_open_safe), c) { done ->
                val k = done?.let { runCatching { it.doFinal(blob, 12, blob.size - 12) }.getOrNull() } ?: return@prompt
                val since = safe.session
                slow({ safe.unlockWithKey(k, since).also { k.fill(0) } }) { ok ->
                    if (ok) { openedHere(); ensureInboxLater(); render() } else toast(getString(R.string.sf_bio_failed))
                }
            }
        }
    }

    private fun showResult(r: Safe.Unlock, pad: PinPad) {
        when (r) {
            Safe.Unlock.Ok -> render()
            is Safe.Unlock.Wrong -> {
                pad.setStatus(getString(R.string.sf_wrong_pin), error = true)
                if (r.waitMs > 0) countdown(pad, r.waitMs, delayMs = 900)
            }
            is Safe.Unlock.Wait -> countdown(pad, r.waitMs)
            Safe.Unlock.NotNow -> pad.setStatus(getString(R.string.sf_not_now), error = true)
            Safe.Unlock.Busy -> render()
            Safe.Unlock.Broken -> render()
        }
    }

    // ================= открыт =================

    private fun renderOpen(i: Info) {
        screen(true)
        root.addView(topBar(getString(R.string.sf_title), toSettings = true))
        root.addView(primary(getString(R.string.sf_entry_new)) { editEntry(null) })
        val entries = i.entries
        if (entries.isEmpty()) root.addView(caption(getString(R.string.sf_empty_list)))
        else root.addView(group(entries.map { entryRow(it) }))

        root.addView(sectionTitle(getString(R.string.sf_protection)))
        val rows = ArrayList<View>()
        rows.add(navRow(getString(R.string.sf_change_pin), null) {
            newPin(getString(R.string.sf_new_pin_title), getString(R.string.sf_pin_range), cancel = true) { pad, p ->
                pad.setPadEnabled(false); pad.setStatus(getString(R.string.sf_saving))
                slow({ safe.changePin(p) }) { r ->
                    when (r) {
                        Safe.PinResult.OK -> { toast(getString(R.string.sf_pin_changed)); render() }
                        Safe.PinResult.CONFLICT -> { pad.setPadEnabled(true); pad.setStatus(getString(R.string.sf_pin_conflict), true) }
                        Safe.PinResult.FAILED -> { toast(getString(R.string.sf_save_failed_open)); render() }
                    }
                }
            }
        })
        rows.add(navRow(getString(R.string.sf_duress), if (i.duress == 0) getString(R.string.sf_none) else "${i.duress}") { renderDuress(i.duress) })
        if (SafeBio.available(this)) rows.add(switchRow(getString(R.string.sf_bio_login), i.bio) { sw, on ->
            if (on) enableBio(sw)
            else slow({ safe.setBiometricBlob(null).also { if (it) SafeBio.disable() } }) { ok -> if (!ok) { toast(getString(R.string.sf_failed)); render() } }
        })
        root.addView(group(rows))
        root.addView(caption(getString(R.string.sf_lock_note)))
        root.addView(group(listOf(actionRow(getString(R.string.sf_delete_safe), Palette.danger) { confirmDestroy() })))
    }

    private fun renderDuress(n: Int) {
        screen(true)
        sub = true
        root.addView(topBar(getString(R.string.sf_duress), back = true))
        root.addView(caption(getString(R.string.sf_duress_note) +
            if (n > 0) getString(R.string.sf_duress_count, n) else ""))
        val rows = ArrayList<View>()
        rows.add(actionRow(getString(R.string.sf_add_duress), Palette.accent) {
            newPin(getString(R.string.sf_duress_title), getString(R.string.sf_duress_sub), cancel = true) { pad, p ->
                pad.setPadEnabled(false); pad.setStatus(getString(R.string.sf_saving))
                slow({ safe.addDuressPin(p) }) { r ->
                    when (r) {
                        Safe.PinResult.OK -> { toast(getString(R.string.sf_added)); render() }
                        Safe.PinResult.CONFLICT -> { pad.setPadEnabled(true); pad.setStatus(getString(R.string.sf_duress_conflict), true) }
                        Safe.PinResult.FAILED -> { toast(getString(R.string.sf_save_failed_open)); render() }
                    }
                }
            }
        })
        if (n > 0) rows.add(actionRow(getString(R.string.sf_remove_all), Palette.danger) {
            slow({ safe.clearDuressPins() }) { ok -> if (!ok) toast(getString(R.string.sf_failed)); render() }
        })
        root.addView(group(rows))
    }

    private fun enableBio(sw: Switch) {
        val k = safe.keyForBiometrics() ?: run { sw.isChecked = false; return }
        val c = runCatching { SafeBio.encryptCipher() }.getOrNull() ?: run { k.fill(0); sw.isChecked = false; toast(getString(R.string.sf_failed)); return }
        SafeBio.prompt(this, getString(R.string.sf_bio_on), c) { done ->
            val blob = done?.let { runCatching { it.iv + it.doFinal(k) }.getOrNull() }
            k.fill(0)
            slow({ safe.setBiometricBlob(blob) }) { ok ->
                if (blob == null || !ok) { sw.isChecked = false; SafeBio.disable(); slow({ safe.setBiometricBlob(null) }) {} }
            }
        }
    }

    /** Запись: название (если есть) и само содержимое — видно целиком (экран защищён от скриншотов). */
    private fun entryRow(e: Safe.Entry): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = pressable(Palette.surface, 0f)
        if (e.title.isNotEmpty()) addView(TextView(context).apply {
            text = e.title; textSize = 13f; setTextColor(Palette.secondary)
        })
        addView(TextView(context).apply {
            text = e.secret; textSize = 16f; setTextColor(Palette.text)
            maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        setOnClickListener { editEntry(e) }
    }

    private fun editEntry(e: Safe.Entry?) {
        screen(true)
        sub = true
        val noLearn = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        val secret = field(getString(R.string.sf_entry_hint)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = imeOptions or noLearn
            isSingleLine = false; minLines = 2; maxLines = 8
            typeface = Typeface.DEFAULT
            setText(e?.secret ?: "")
        }
        val label = field(getString(R.string.sf_entry_name_hint)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = imeOptions or noLearn
            setText(e?.title ?: "")
        }
        fun save() {
            val s = secret.text.toString()
            if (s.isEmpty()) { toast(getString(R.string.sf_entry_empty)); return }
            val l = label.text.toString()
            slow({ if (e == null) safe.add(l, s) else safe.update(e.id, l, s) }) { ok ->
                if (!ok) { toast(if (safe.isUnlocked) getString(R.string.sf_save_failed_retry) else getString(R.string.sf_locked_save)); return@slow }
                secret.setText(""); label.setText("")
                render()
            }
        }
        root.addView(topBar(if (e == null) getString(R.string.sf_entry_title) else getString(R.string.sf_entry_edit),
            back = true, action = getString(R.string.cf_done)) { save() })
        root.addView(sectionTitle(getString(R.string.sf_content)))
        root.addView(secret)
        root.addView(sectionTitle(getString(R.string.sf_name_optional)))
        root.addView(label)
        if (e != null) {
            root.addView(View(this), LinearLayout.LayoutParams(1, dp(16)))
            root.addView(group(listOf(actionRow(getString(R.string.sf_delete_entry), Palette.danger) {
                AlertDialog.Builder(this, Palette.dialog)
                    .setTitle(getString(R.string.sf_delete_entry_q))
                    .setPositiveButton(getString(R.string.cf_delete)) { _, _ -> slow({ safe.remove(e.id) }) { ok -> if (!ok) toast(getString(R.string.sf_failed)); render() } }
                    .setNegativeButton(getString(R.string.cf_cancel), null).showSecure()
            })))
        }
        if (e == null) secret.requestFocus()
    }

    // ================= мелочи =================

    /** Диалог — отдельное окно: защиту от скриншотов и «недавних» он сам не наследует. */
    private fun AlertDialog.Builder.showSecure(): AlertDialog = create().apply {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        show()
    }

    /** ПИН проверяется медленно (так и задумано) — не на основном потоке. */
    private fun <T> slow(work: () -> T, done: (T) -> Unit) {
        Thread {
            val r = runCatching(work)
            runOnUiThread {
                if (!started || isFinishing) {
                    busy = false
                    if (!AppGate.inApp) safe.lock()
                    return@runOnUiThread
                }
                r.onSuccess(done).onFailure { busy = false; toast(getString(R.string.sf_failed_retry)); render() }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun waitText(ms: Long): String {
        val s = (ms + 999) / 1000
        return getString(R.string.sf_too_many) +
            if (s < 60) getString(R.string.sf_wait_s, s)
            else if (s < 3600) "%d:%02d".format(s / 60, s % 60)
            else getString(R.string.sf_wait_hm, s / 3600, (s % 3600) / 60)
    }

    private fun spacer() = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 0, 1f) }

    /**
     * Заголовок экрана. back — «‹ Назад» к главному экрану сейфа; toSettings — «‹ Настройки»: выйти
     * из сейфа (он при этом запирается), как из любого раздела настроек.
     */
    private fun topBar(title: String, back: Boolean = false, action: String? = null, toSettings: Boolean = false,
                       onAction: () -> Unit = {}) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (back || action != null || toSettings) addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                if (back) addView(link(getString(R.string.sf_back)) { render() })
                if (toSettings) addView(link(getString(R.string.st_back)) { leave() })
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                if (action != null) addView(link(action, bold = true) { onAction() })
            }, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))
            addView(heading(title))
        }

    private fun heading(s: String, center: Boolean = false) = TextView(this).apply {
        text = s; textSize = 30f; setTextColor(Palette.text); typeface = Typeface.DEFAULT_BOLD
        if (center) gravity = Gravity.CENTER
        setPadding(0, dp(4), 0, dp(12))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun sectionTitle(s: String) = TextView(this).apply {
        text = s.uppercase(); textSize = 12f; setTextColor(Palette.secondary); letterSpacing = 0.06f
        setPadding(dp(16), dp(24), dp(16), dp(8))
    }

    private fun caption(s: String) = TextView(this).apply {
        text = s; textSize = 13f; setTextColor(Palette.secondary)
        setPadding(dp(16), dp(8), dp(16), dp(8))
    }

    private fun centered(s: String) = TextView(this).apply {
        text = s; textSize = 15f; setTextColor(Palette.secondary); gravity = Gravity.CENTER
        setPadding(dp(16), dp(8), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun link(s: String, bold: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = s; textSize = 17f; setTextColor(Palette.accent)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(10), dp(4), dp(10))
        background = pressable(Color.TRANSPARENT, dp(8).toFloat())
        setOnClickListener { onClick() }
    }

    private fun primary(s: String, color: Int = Palette.accent, onClick: () -> Unit) = TextView(this).apply {
        text = s; textSize = 17f; setTextColor(if (color == Palette.accent) Palette.onAccent else Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = pressable(color, dp(14).toFloat())
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(52)).apply { topMargin = dp(12); bottomMargin = dp(4) }
        setOnClickListener { onClick() }
    }

    /** Строки в одной скруглённой карточке с разделителями — как в настройках телефона. */
    private fun group(rows: List<View>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(Palette.surface); cornerRadius = dp(14).toFloat() }
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
        rows.forEachIndexed { i, r ->
            if (i > 0) addView(View(context).apply { setBackgroundColor(Palette.separator) },
                LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { leftMargin = dp(16) })
            addView(r)
        }
    }

    private fun rowBase() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = pressable(Palette.surface, 0f)
    }

    private fun infoRow(title: String, sub: String) = rowBase().apply {
        background = null
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = title; textSize = 16f; setTextColor(Palette.text) })
            addView(TextView(context).apply { text = sub; textSize = 13f; setTextColor(Palette.secondary) })
        })
    }

    private fun navRow(title: String, value: String?, onClick: () -> Unit) = rowBase().apply {
        addView(TextView(context).apply { text = title; textSize = 16f; setTextColor(Palette.text) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (value != null) addView(TextView(context).apply {
            text = value; textSize = 16f; setTextColor(Palette.secondary); setPadding(0, 0, dp(8), 0)
        })
        addView(TextView(context).apply { text = "›"; textSize = 22f; setTextColor(Palette.secondary) })
        setOnClickListener { onClick() }
    }

    private fun actionRow(title: String, color: Int, onClick: () -> Unit) = rowBase().apply {
        gravity = Gravity.CENTER
        addView(TextView(context).apply { text = title; textSize = 16f; setTextColor(color) })
        setOnClickListener { onClick() }
    }

    private fun switchRow(title: String, on: Boolean, onChange: (Switch, Boolean) -> Unit) = rowBase().apply {
        background = null
        val sw = Switch(context).apply { isChecked = on; Palette.tint(this) }
        addView(TextView(context).apply { text = title; textSize = 16f; setTextColor(Palette.text) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(sw)
        sw.setOnCheckedChangeListener { v, c -> onChange(v as Switch, c) }
    }

    private fun roundIcon(kind: Int, onClick: () -> Unit) = FrameLayout(this).apply {
        background = pressable(Palette.surface, -1f)
        addView(Glyph(context, kind, Palette.accent), FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
        contentDescription = getString(R.string.sf_fingerprint)
        setOnClickListener { onClick() }
    }

    private fun field(hint: String) = EditText(this).apply {
        this.hint = hint
        textSize = 17f; setTextColor(Palette.text); setHintTextColor(Palette.secondary)
        background = GradientDrawable().apply { setColor(Palette.surface); cornerRadius = dp(14).toFloat() }
        setPadding(dp(16), dp(14), dp(16), dp(14))
        isSaveEnabled = false // содержимое не остаётся в сохранённом состоянии экрана
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
