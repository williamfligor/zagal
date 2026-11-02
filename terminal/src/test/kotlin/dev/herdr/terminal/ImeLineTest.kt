package dev.herdr.terminal

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ImeLine]'s contract, tested the only way a keyboard can be: drive the
 * connection the way a board does, replay what it sent on a pty line that
 * echoes, and assert the line the shell ends up with is the one the field
 * shows. Gboard cannot be scripted; its calls can.
 */
class ImeLineTest {

  /** A pty line: text appends, DEL erases a character, CR or LF starts a line. */
  private class Shell {
    val current = StringBuilder()
    val lines = mutableListOf<String>()

    fun accept(bytes: String) {
      for (c in bytes) {
        when (c) {
          DEL -> if (current.isNotEmpty()) {
            current.delete(current.length - lastCharLength(), current.length)
          }
          '\r', '\n' -> {
            lines += current.toString()
            current.setLength(0)
          }
          else -> current.append(c)
        }
      }
    }

    private fun lastCharLength(): Int =
      if (current.length >= 2 && Character.isLowSurrogate(current[current.length - 1])) 2 else 1
  }

  private class Harness {
    val shell = Shell()
    val sent = StringBuilder()
    val ime = ImeLine { text ->
      sent.append(text)
      shell.accept(text)
      true
    }
  }

  @Test
  fun typingEchoesAsItIsTyped() {
    val h = Harness()
    h.ime.compose("l")
    h.ime.compose("ls")
    h.ime.commit("ls ")
    // The word showed up as it was typed, and committing added only the space.
    assertEquals("ls ", h.sent.toString())
    assertEquals("ls ", h.shell.current.toString())
    assertEquals("ls ", h.ime.line)
  }

  @Test
  fun autocorrectErasesTheWordItReplaces() {
    val h = Harness()
    h.ime.compose("teh")
    h.ime.commit("the ")
    // "teh" and "the " already agree on the 't', so only 'eh' is erased.
    assertEquals("teh" + DEL + DEL + "he ", h.sent.toString())
    assertEquals("the ", h.shell.current.toString())
    assertEquals("the ", h.ime.line)
  }

  @Test
  fun suggestionOverAnEarlierCommittedWordReplacesIt() {
    val h = Harness()
    h.ime.commit("git ")
    h.ime.commit("status")
    // A tap on "stash": the board marks the committed word and composes over it.
    h.ime.setComposingRegion(4, 10)
    h.ime.compose("stash")
    h.ime.commit("stash")
    assertEquals("git stash", h.shell.current.toString())
    assertEquals("git stash", h.ime.line)
  }

  @Test
  fun deleteAsksForMoreThanTheLineHolds() {
    val h = Harness()
    h.ime.commit("abc")
    h.ime.deleteSurrounding(5, 0)
    assertEquals("", h.ime.line)
    assertEquals("", h.shell.current.toString())
    assertEquals("abc" + DEL + DEL + DEL, h.sent.toString())
  }

  @Test
  fun aNewlineRestartsBothSides() {
    val h = Harness()
    h.ime.commit("ls\n")
    assertEquals(listOf("ls"), h.shell.lines)
    h.ime.commit("pwd")
    assertEquals(listOf("ls"), h.shell.lines)
    assertEquals("pwd", h.shell.current.toString())
    assertEquals("pwd", h.ime.line)
  }

  @Test
  fun enterSendsWhatTheFieldHolds() {
    val h = Harness()
    h.ime.compose("ls")
    h.ime.enter()
    assertEquals(listOf("ls"), h.shell.lines)
    assertEquals("", h.ime.line)
    assertEquals("", h.shell.current.toString())
  }

  @Test
  fun anEmojiIsOneCharacterToADel() {
    val h = Harness()
    h.ime.commit("a👍")
    h.ime.deleteSurroundingCodePoints(1, 0)
    assertEquals("a", h.ime.line)
    assertEquals("a", h.shell.current.toString())
  }

  @Test
  fun aUtf16DeleteDoesNotSplitAnEmoji() {
    val h = Harness()
    h.ime.commit("a👍")
    h.ime.deleteSurrounding(1, 0)
    assertEquals("a", h.ime.line)
    assertEquals("a", h.shell.current.toString())
  }

  @Test
  fun anInvalidatedLineSendsNothingToCorrectIt() {
    val h = Harness()
    h.ime.commit("ab")
    val before = h.sent.length
    // An arrow or a cursor-moving key went to the pane: stop modelling it.
    h.ime.invalidate()
    h.ime.commit("cd")
    assertEquals("abcd", h.shell.current.toString())
    assertEquals("cd", h.sent.substring(before))
  }

  @Test
  fun editingBehindTheCursorRetypesTheTail() {
    val h = Harness()
    h.ime.commit("abc")
    h.ime.setSelection(1, 1)
    h.ime.commit("x")
    assertEquals("axbc", h.ime.line)
    assertEquals("axbc", h.shell.current.toString())
  }

  @Test
  fun aLineRewrittenUnderUsIsNotErased() {
    val h = Harness()
    h.ime.commit("a".repeat(300))
    val before = h.sent.length
    h.ime.deleteSurrounding(300, 0)
    assertTrue(h.ime.diverged)
    assertEquals(before, h.sent.length)
    assertEquals("", h.ime.line)
  }

  @Test
  fun everyEditLeavesThePaneLineMatchingTheField() {
    val random = Random(20261102)
    val h = Harness()
    repeat(3000) { step ->
      when {
        // Keep the line short so no single edit can look like a rewrite.
        step % 20 == 19 -> h.ime.enter()
        else -> when (random.nextInt(100)) {
          in 0..39 -> h.ime.compose(word(random))
          in 40..69 -> h.ime.commit(word(random) + " ")
          in 70..84 -> h.ime.deleteSurrounding(random.nextInt(4), random.nextInt(2))
          in 85..92 -> {
            val from = random.nextInt(0, h.ime.line.length + 1)
            val to = minOf(h.ime.line.length, from + random.nextInt(5))
            h.ime.setComposingRegion(from, to)
            h.ime.compose(word(random))
            h.ime.commit(word(random) + " ")
          }
          in 93..97 -> h.ime.setSelection(random.nextInt(0, h.ime.line.length + 1), 0)
          else -> h.ime.finishComposing()
        }
      }
      assertFalse(h.ime.diverged, "step $step")
      assertEquals(h.shell.current.toString(), h.ime.line, "step $step")
    }
  }

  private fun word(random: Random): String {
    val length = random.nextInt(1, 5)
    return buildString {
      repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }
  }

  private companion object {
    const val DEL = '\u007f'
    const val ALPHABET = "abcdeghilnoprstu"
  }
}
