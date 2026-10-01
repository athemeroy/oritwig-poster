// SPDX-License-Identifier: GPL-3.0-or-later
package dev.appmatrix.core.text;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;

/** Original tests for the actual production range history, not a duplicate implementation. */
public class EditHistoryTest {
  private static final class Editor implements EditHistory.Target {
    final EditHistory history;
    final StringBuilder value;
    int start, end, dirtyEvents;
    long time;
    boolean throwBefore, throwAfter, throwSelect, rejectReplace;

    Editor(String initial) {
      this(initial, 100, 200_000);
    }

    Editor(String initial, int operations, int chars) {
      history = new EditHistory(operations, chars);
      value = new StringBuilder(initial);
      start = end = initial.length();
    }

    @Override
    public CharSequence text() {
      return value;
    }

    @Override
    public void select(int start, int end) {
      if (throwSelect) throw new IllegalStateException("selection failed");
      this.start = start;
      this.end = end;
    }

    @Override
    public void replace(int from, int to, String replacement) {
      if (throwBefore) throw new IllegalStateException("replacement failed");
      if (rejectReplace) return;
      String before = value.substring(from, to);
      int length = value.length(), bs = start, be = end;
      value.replace(from, to, replacement);
      start = end = from + replacement.length();
      dirtyEvents++;
      // Simulate the same listener still being called during replay.
      history.record(from, before, replacement, length, bs, be, start, end, time);
      if (throwAfter) throw new IllegalStateException("watcher failed after replacement");
    }

    void edit(int from, int to, String replacement) {
      time += 10;
      replace(from, to, replacement);
    }

    void separateEdit(int from, int to, String replacement) {
      time += 6_000;
      edit(from, to, replacement);
    }

    String value() {
      return value.toString();
    }

    void undo(String expected) {
      assertTrue(history.undo(this));
      assertEquals(expected, value());
    }

    void redo(String expected) {
      assertTrue(history.redo(this));
      assertEquals(expected, value());
    }
  }

  @Test
  public void insertsUndoRedoAndRestoreCursor() {
    Editor e = new Editor("ac");
    e.select(1, 1);
    e.edit(1, 1, "b");
    assertEquals("abc", e.value());
    e.undo("ac");
    assertEquals(1, e.start);
    assertEquals(1, e.end);
    e.redo("abc");
    assertEquals(2, e.start);
    assertEquals(2, e.end);
  }

  @Test
  public void deletionRestoresSelectedRange() {
    Editor e = new Editor("a forest");
    e.select(2, 8);
    e.edit(2, 8, "");
    e.undo("a forest");
    assertEquals(2, e.start);
    assertEquals(8, e.end);
    e.redo("a ");
    assertEquals(2, e.start);
  }

  @Test
  public void replacementRestoresReversedSelection() {
    Editor e = new Editor("blue sky");
    e.select(4, 0);
    e.edit(0, 4, "red");
    e.undo("blue sky");
    assertEquals(4, e.start);
    assertEquals(0, e.end);
    e.redo("red sky");
  }

  @Test
  public void pasteStaysSeparateFromLaterTyping() {
    Editor e = new Editor("");
    e.edit(0, 0, "long pasted note");
    e.edit(e.value.length(), e.value.length(), "s");
    assertEquals(2, e.history.operationCount());
    e.undo("long pasted note");
    e.undo("");
  }

  @Test
  public void selectedReplacementDoesNotMergeWithTyping() {
    Editor e = new Editor("cat");
    e.select(0, 3);
    e.edit(0, 3, "d");
    e.edit(1, 1, "o");
    assertEquals(2, e.history.operationCount());
    e.undo("d");
    e.undo("cat");
  }

  @Test
  public void adjacentTypedWordAndTrailingSpaceCoalesce() {
    Editor e = new Editor("");
    for (String letter : new String[] {"a", "b", " ", "c", "d"})
      e.edit(e.value.length(), e.value.length(), letter);
    assertEquals(2, e.history.operationCount());
    e.undo("ab ");
    e.undo("");
    e.redo("ab ");
    e.redo("ab cd");
  }

  @Test
  public void timeoutAndNewlineBreakTypingChains() {
    Editor e = new Editor("");
    e.edit(0, 0, "a");
    e.separateEdit(1, 1, "b");
    e.edit(2, 2, "\n");
    e.edit(3, 3, "c");
    assertEquals(4, e.history.operationCount());
    e.undo("ab\n");
    e.undo("ab");
    e.undo("a");
  }

  @Test
  public void cursorMovementBreaksTypingChain() {
    Editor e = new Editor("");
    e.edit(0, 0, "a");
    e.select(0, 0);
    e.edit(0, 0, "b");
    assertEquals(2, e.history.operationCount());
    e.undo("a");
  }

  @Test
  public void backwardDeletesCoalesceAndRestoreOriginalCursor() {
    Editor e = new Editor("word");
    e.edit(3, 4, "");
    e.edit(2, 3, "");
    e.edit(1, 2, "");
    assertEquals(1, e.history.operationCount());
    e.undo("word");
    assertEquals(4, e.start);
    e.redo("w");
  }

  @Test
  public void forwardDeletesCoalesceInCorrectOrder() {
    Editor e = new Editor("word");
    e.select(0, 0);
    e.edit(0, 1, "");
    e.edit(0, 1, "");
    e.undo("word");
    assertEquals(0, e.start);
    e.redo("rd");
  }

  @Test
  public void newEditInvalidatesRedoWithoutMergingIntoOldBranch() {
    Editor e = new Editor("");
    e.edit(0, 0, "a");
    e.separateEdit(1, 1, "b");
    e.undo("a");
    e.edit(1, 1, "c");
    assertFalse(e.history.canRedo());
    assertEquals(2, e.history.operationCount());
    e.undo("a");
    e.undo("");
  }

  @Test
  public void noOpDoesNotAddHistoryOrInvalidateRedo() {
    Editor e = new Editor("a");
    e.edit(1, 1, "b");
    e.undo("a");
    e.edit(0, 1, "a");
    assertFalse(e.history.canUndo());
    assertTrue(e.history.canRedo());
    assertEquals(1, e.history.operationCount());
    e.redo("ab");
  }

  @Test
  public void operationLimitEvictsOldestUndoBoundary() {
    Editor e = new Editor("", 2, 100);
    e.separateEdit(0, 0, "a");
    e.separateEdit(1, 1, "b");
    e.separateEdit(2, 2, "c");
    assertEquals(2, e.history.operationCount());
    e.undo("ab");
    e.undo("a");
    assertFalse(e.history.undo(e));
    e.redo("ab");
    e.redo("abc");
  }

  @Test
  public void retainedTextCapIncludesDeletedAndInsertedStringsAndRedo() {
    Editor e = new Editor("abc", 100, 6);
    e.edit(0, 3, "def");
    assertEquals(6, e.history.retainedCharacters());
    e.undo("abc");
    assertEquals(6, e.history.retainedCharacters());
    e.redo("def");
    e.separateEdit(3, 3, "ghi");
    assertEquals(3, e.history.retainedCharacters());
    e.undo("def");
    assertFalse(e.history.canUndo());
  }

  @Test
  public void oversizeEditClearsHistoryRatherThanLeavingAnUnsafeGap() {
    Editor e = new Editor("", 100, 4);
    e.edit(0, 0, "a");
    e.edit(1, 1, "12345");
    assertFalse(e.history.canUndo());
    assertEquals(0, e.history.retainedCharacters());
    e.edit(6, 6, "z");
    e.undo("a12345");
    assertFalse(e.history.canUndo());
  }

  @Test
  public void coalescingCannotExceedRetainedTextLimit() {
    Editor e = new Editor("", 10, 3);
    for (String letter : new String[] {"a", "b", "c", "d"})
      e.edit(e.value.length(), e.value.length(), letter);
    assertTrue(e.history.retainedCharacters() <= 3);
    e.undo("abc");
    assertFalse(e.history.canUndo());
  }

  @Test
  public void rangeDiffStoresOnlyChangedText() {
    Editor e = new Editor("prefix old suffix");
    e.select(0, e.value.length());
    e.edit(0, e.value.length(), "prefix new suffix");
    assertEquals(6, e.history.retainedCharacters());
    e.undo("prefix old suffix");
    e.redo("prefix new suffix");
  }

  @Test
  public void surrogatePairDiffNeverSplitsAnEmoji() {
    assertArrayEquals(
        new int[] {1, 3, 3}, EditHistory.findDiff("a\uD83D\uDE00b", "a\uD83D\uDE03b"));
    Editor e = new Editor("a😀b");
    e.select(1, 3);
    e.edit(1, 3, "😃");
    assertEquals(4, e.history.retainedCharacters());
    e.undo("a😀b");
    e.redo("a😃b");
  }

  @Test
  public void unicodeTypingDeletionAndCombiningMarksRoundTrip() {
    Editor e = new Editor("");
    e.edit(0, 0, "🌳");
    e.edit(2, 2, "é");
    e.edit(3, 3, "\u0301");
    e.undo("");
    e.redo("🌳é\u0301");
    e.separateEdit(3, 4, "");
    e.edit(2, 3, "");
    e.edit(0, 2, "");
    e.undo("🌳é\u0301");
  }

  @Test
  public void replayStillNotifiesOtherDirtyListenersWithoutRecordingItself() {
    Editor e = new Editor("");
    e.edit(0, 0, "a");
    e.undo("");
    e.redo("a");
    assertEquals(3, e.dirtyEvents);
    assertEquals(1, e.history.operationCount());
  }

  @Test
  public void replacementExceptionAlwaysReleasesReplayGuardAndDropsStaleHistory() {
    Editor e = new Editor("");
    e.edit(0, 0, "a");
    e.throwBefore = true;
    try {
      e.history.undo(e);
      fail("Expected failure");
    } catch (IllegalStateException expected) {
    }
    assertFalse(e.history.isReplaying());
    assertFalse(e.history.canUndo());
    e.throwBefore = false;
    e.edit(1, 1, "b");
    e.undo("a");
  }

  @Test
  public void watcherExceptionAfterMutationDoesNotLeaveReplayGuardOrStaleOffsets() {
    Editor e = new Editor("");
    e.edit(0, 0, "a");
    e.throwAfter = true;
    try {
      e.history.undo(e);
      fail("Expected failure");
    } catch (IllegalStateException expected) {
    }
    assertEquals("", e.value());
    assertFalse(e.history.isReplaying());
    assertFalse(e.history.canRedo());
    e.throwAfter = false;
    e.edit(0, 0, "b");
    e.undo("");
  }

  @Test
  public void selectionExceptionAlsoClearsReplayGuard() {
    Editor e = new Editor("");
    e.edit(0, 0, "a");
    e.throwSelect = true;
    try {
      e.history.undo(e);
      fail("Expected failure");
    } catch (IllegalStateException expected) {
    }
    assertFalse(e.history.isReplaying());
    assertEquals(0, e.history.operationCount());
  }

  @Test
  public void rejectedReplayDoesNotAdvanceHistory() {
    Editor e = new Editor("");
    e.edit(0, 0, "a");
    e.rejectReplace = true;
    assertFalse(e.history.undo(e));
    assertEquals("a", e.value());
    assertFalse(e.history.isReplaying());
    assertFalse(e.history.canUndo());
  }

  @Test
  public void unexpectedTextLengthOrRangeClearsUnsafeHistory() {
    Editor e = new Editor("");
    e.edit(0, 0, "a");
    e.value.setCharAt(0, 'z');
    assertFalse(e.history.undo(e));
    assertEquals("z", e.value());
    assertFalse(e.history.canUndo());
  }

  @Test
  public void clearPreventsHistoryLeakingToAnotherEntry() {
    Editor e = new Editor("secret");
    e.edit(6, 6, " old");
    e.history.clear();
    e.value.replace(0, e.value.length(), "new entry");
    assertFalse(e.history.canUndo());
    assertFalse(e.history.canRedo());
    assertEquals(0, e.history.retainedCharacters());
    e.select(9, 9);
    e.edit(9, 9, "!");
    e.undo("new entry");
    assertFalse(e.history.canUndo());
  }

  @Test
  public void randomizedRangeEditsRoundTripEveryDocument() {
    Editor e = new Editor("", 1000, 500_000);
    Random random = new Random(8317);
    List<String> snapshots = new ArrayList<>();
    snapshots.add("");
    String[] fragments = {"oak", "🌲", "é", "\n", "", "\u0301", "水"};
    for (int i = 0; i < 300; i++) {
      String previous = e.value();
      int a = random.nextInt(previous.length() + 1), b = random.nextInt(previous.length() + 1);
      int from = Math.min(a, b), to = Math.max(a, b);
      // Select real code-point boundaries, as Android normally does.
      if (from > 0 && from < previous.length() && Character.isLowSurrogate(previous.charAt(from)))
        from--;
      if (to > 0 && to < previous.length() && Character.isLowSurrogate(previous.charAt(to))) to++;
      e.select(from, to);
      e.separateEdit(from, to, fragments[random.nextInt(fragments.length)]);
      if (!previous.equals(e.value())) snapshots.add(e.value());
    }
    for (int i = snapshots.size() - 2; i >= 0; i--) e.undo(snapshots.get(i));
    assertFalse(e.history.canUndo());
    for (int i = 1; i < snapshots.size(); i++) e.redo(snapshots.get(i));
    assertFalse(e.history.canRedo());
  }

  @Test
  public void defaultBoundsAndInvalidLimitsAreExplicit() {
    assertEquals(100, EditHistory.DEFAULT_MAX_OPERATIONS);
    assertEquals(200_000, EditHistory.DEFAULT_MAX_RETAINED_CHARS);
    try {
      new EditHistory(0, 1);
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      new EditHistory(1, 0);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }
}
