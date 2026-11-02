package dev.herdr.terminal

/**
 * The IME's line, and what the pane's line will be once its echo lands.
 *
 * A soft keyboard with suggestions enabled owns a text field and edits it
 * through [android.view.inputmethod.InputConnection]: it composes scratch text
 * (`setComposingText`), accepts it (`commitText`) and, on a correction, erases
 * characters it already committed (`deleteSurroundingText`). The pane is a pty
 * client, so the shell has its own copy of the line and echoes it back.
 *
 * Two copies, one writer: every operation here updates [text] (the IME's field)
 * and then sends only the difference between it and [wire] — what the pane's
 * line holds, optimistically including bytes still in flight. Sending the diff
 * instead of the operation is what makes every correction shape fall out of one
 * rule: "teh" + space becomes three DELs and "the", a suggestion tapped over an
 * earlier word rewrites from there, and dictation interim text costs nothing
 * because it was never sent.
 *
 * Composing text is sent too, so a word appears as it is typed rather than when
 * it is committed. A DEL is one character to a line editor, not one column, so
 * counts are code points. A newline ends the session: both sides restart empty,
 * because the shell draws its next line from scratch. Anything the terminal
 * cannot model — a key bar arrow, a hardware key, a control character — calls
 * [invalidate]: nothing is sent for it and both sides restart empty, which is
 * the only honest state when the cursor moved in a way no client model follows.
 *
 * All methods are called from the main thread and from the IME's own thread, so
 * every public entry point is synchronized.
 */
class ImeLine(private val out: (String) -> Boolean) {

  /** The IME's field: one line of the terminal, with a cursor and a composing span. */
  private val text = StringBuilder()
  private var cursor = 0
  private var composing: IntRange? = null

  /** What the pane's line holds: [text] as last sent, before the echo arrived. */
  private val wire = StringBuilder()

  /** Calls kept for a bug report: "did the board compose or commit?" (see [diagnostics]). */
  private val recent = ArrayDeque<String>()

  /**
   * True once a diff was too large to be an edit ([HEAVY_ERASE]): the line was
   * rewritten under us — a prompt redraw, a program's output — and the erases
   * were dropped rather than sent to the wrong characters.
   */
  @Volatile
  var diverged = false
    private set

  val line: String
    @Synchronized get() = text.toString()

  /** The last [MAX_DIAGNOSTICS] IME calls, oldest first. */
  fun diagnostics(): List<String> = synchronized(this) { recent.toList() }

  @Synchronized
  fun commit(value: String) {
    note("commit:${value.escape()}")
    val replaced = composing
    val at = replaced?.first ?: cursor
    val length = replaced?.let { it.last - it.first + 1 } ?: 0
    replaceRange(at, at + length, value, composingAfter = false)
    reconcile()
    if (value.any { it == '\n' || it == '\r' }) endLine()
  }

  @Synchronized
  fun compose(value: String) {
    note("compose:${value.escape()}")
    val replaced = composing
    val at = replaced?.first ?: cursor
    val length = replaced?.let { it.last - it.first + 1 } ?: 0
    replaceRange(at, at + length, value, composingAfter = true)
    reconcile()
  }

  /** Marks committed text re-editable; a suggestion tapped over an earlier word. Sends nothing. */
  @Synchronized
  fun setComposingRegion(start: Int, end: Int) {
    val from = start.coerceIn(0, text.length)
    val to = end.coerceIn(0, text.length)
    composing = if (from == to) null else minOf(from, to) until maxOf(from, to)
    note("region:$from-$to")
  }

  /** The composing span becomes ordinary text; the bytes were already sent. */
  @Synchronized
  fun finishComposing() {
    note("finish")
    composing = null
  }

  @Synchronized
  fun deleteSurrounding(before: Int, after: Int) {
    note("delete:$before,$after")
    deleteUnits(cursor - before.coerceAtLeast(0), cursor + after.coerceAtLeast(0))
  }

  @Synchronized
  fun deleteSurroundingCodePoints(before: Int, after: Int) {
    note("deleteCp:$before,$after")
    deleteUnits(walkCodePoints(cursor, -before.coerceAtLeast(0)), walkCodePoints(cursor, after.coerceAtLeast(0)))
  }

  @Synchronized
  fun replaceText(start: Int, end: Int, value: String) {
    note("replace:$start-$end:${value.escape()}")
    val from = start.coerceIn(0, text.length)
    val to = end.coerceIn(from, text.length)
    replaceRange(from, to, value, composingAfter = false)
    reconcile()
    if (value.any { it == '\n' || it == '\r' }) endLine()
  }

  /** The pane's cursor is the shell's; moving ours changes nothing there. */
  @Synchronized
  fun setSelection(start: Int, end: Int) {
    cursor = start.coerceIn(0, text.length)
    note("select:$start-$end")
  }

  fun textBefore(length: Int): CharSequence = synchronized(this) {
    text.substring(maxOf(0, cursor - length.coerceAtLeast(0)), cursor)
  }

  fun textAfter(length: Int): CharSequence = synchronized(this) {
    text.substring(cursor, minOf(text.length, cursor + length.coerceAtLeast(0)))
  }

  @Synchronized
  fun selectedText(): CharSequence = ""

  /**
   * The IME's return key: send what the field holds, then `\r`. The shell starts
   * a new line, so both sides restart empty.
   */
  @Synchronized
  fun enter() {
    note("enter")
    reconcile()
    out("\r")
    reset()
  }

  /**
   * A key the client cannot model went to the pane (accessory bar, hardware
   * keyboard, a control character): stop claiming to know what the pane's line
   * holds. Nothing is sent to correct it, and the next edit starts from empty.
   */
  @Synchronized
  fun invalidate() {
    note("invalidated")
    reset()
  }

  /** The pane's line ended on its own (its program redrew it): start over. */
  private fun endLine() {
    reset()
  }

  @Synchronized
  fun clear() {
    note("cleared")
    reset()
  }

  // ── Engine ──

  private fun reset() {
    text.setLength(0)
    cursor = 0
    composing = null
    wire.setLength(0)
  }

  private fun replaceRange(from: Int, to: Int, value: String, composingAfter: Boolean) {
    text.replace(from, to, value)
    cursor = from + value.length
    composing = if (composingAfter && value.isNotEmpty()) from until cursor else null
  }

  private fun deleteUnits(from: Int, to: Int) {
    val a = from.coerceIn(0, text.length)
    val b = to.coerceIn(a, text.length)
    // A DEL erases a character, so never leave half of a surrogate pair behind.
    var start = a
    while (start > 0 && start < text.length && Character.isLowSurrogate(text[start])) start--
    var end = b
    while (end < text.length && end > 0 && Character.isLowSurrogate(text[end])) end++
    if (start == end) return
    text.delete(start, end)
    cursor = start
    composing = null
    reconcile()
  }

  /** [count] code points from [at], clamped to the field. */
  private fun walkCodePoints(at: Int, count: Int): Int {
    if (count >= 0) {
      var i = at
      repeat(count) { if (i < text.length) i += Character.charCount(text.codePointAt(i)) }
      return i
    }
    var i = at
    repeat(-count) {
      if (i > 0) {
        i--
        if (i > 0 && Character.isLowSurrogate(text[i])) i--
      }
    }
    return i
  }

  /**
   * Send the difference between the field and the pane's line: erase the tail
   * the field no longer has, then type what it now has. One rule for every
   * correction, because a pty edits only from its end.
   */
  private fun reconcile() {
    val target = text.toString()
    if (target.contentEquals(wire)) return
    var common = 0
    val shared = minOf(target.length, wire.length)
    while (common < shared && target[common] == wire[common]) common++
    // Never split a surrogate pair: a diff inside one is no edit at all.
    if (common > 0 && common < target.length && Character.isLowSurrogate(target[common])) common--
    val erased = wire.substring(common)
    var dels = erased.codePointCount(0, erased.length)
    if (dels > HEAVY_ERASE) {
      // More erases than an edit can own: the line was rewritten under us, so
      // erasing it would hit characters this field never sent.
      diverged = true
      note("heavy:$dels")
      dels = 0
    }
    val insert = target.substring(common)
    if (dels > 0 || insert.isNotEmpty()) {
      val bytes = StringBuilder(dels + insert.length)
      repeat(dels) { bytes.append(DEL) }
      bytes.append(insert)
      note("send:${dels}${if (insert.isEmpty()) "" else "+" + insert.escape()}")
      out(bytes.toString())
    }
    wire.setLength(0)
    wire.append(target)
  }

  private fun note(entry: String) {
    recent.addLast(entry)
    while (recent.size > MAX_DIAGNOSTICS) recent.removeFirst()
  }

  private fun String.escape(): String = buildString {
    for (c in this@escape) {
      when (c) {
        DEL -> append("<DEL>")
        '\n' -> append("<LF>")
        '\r' -> append("<CR>")
        '\t' -> append("<TAB>")
        else -> if (c.code < 0x20) append("<${c.code.toString(16)}>") else append(c)
      }
    }
  }

  private companion object {
    const val DEL = '\u007f'

    /** Pasting while the board corrects can erase a word; a line rewrite cannot. */
    const val HEAVY_ERASE = 128

    const val MAX_DIAGNOSTICS = 40
  }
}
