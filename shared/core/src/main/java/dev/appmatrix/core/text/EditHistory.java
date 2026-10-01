/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * App Matrix modifications: 2026-10-01.
 * Adapted from Markor TextViewUndoRedo.java (public domain) and findDiff in
 * TextViewUtils.java, Copyright 2018-2025 Gregor Santner (Unlicense chosen).
 * Original TextViewUndoRedo dedication:
 * THIS CLASS IS PROVIDED TO THE PUBLIC DOMAIN FOR FREE WITHOUT ANY
 * RESTRICTIONS OR ANY WARRANTY.
 * See third_party/MARKOR.md for pinned source, notices and changes.
 */
// Relocated as a reusable Oritwig text capability on 2026-10-01; behavior retained.
package dev.appmatrix.core.text;

import java.util.ArrayList;
import java.util.List;

/** Bounded, memory-only range history. All calls belong on the editor's UI thread. */
final class EditHistory {
  static final int DEFAULT_MAX_OPERATIONS = 100;
  static final int DEFAULT_MAX_RETAINED_CHARS = 200_000;
  private static final long COALESCE_MILLIS = 5_000;

  interface Target {
    CharSequence text();

    void replace(int start, int end, String replacement);

    void select(int start, int end);
  }

  private final int maxOperations;
  private final int maxRetainedChars;
  // Markor EditHistory: chronological edits and a cursor separating undo from redo.
  private final List<EditItem> history = new ArrayList<>();
  private int position;
  private int retainedChars;
  private boolean replaying;
  private boolean allowCoalescing;

  EditHistory() {
    this(DEFAULT_MAX_OPERATIONS, DEFAULT_MAX_RETAINED_CHARS);
  }

  EditHistory(int maxOperations, int maxRetainedChars) {
    if (maxOperations < 1 || maxRetainedChars < 1) {
      throw new IllegalArgumentException("History limits must be positive");
    }
    this.maxOperations = maxOperations;
    this.maxRetainedChars = maxRetainedChars;
  }

  boolean canUndo() {
    return position > 0;
  }

  boolean canRedo() {
    return position < history.size();
  }

  boolean isReplaying() {
    return replaying;
  }

  int operationCount() {
    return history.size();
  }

  int retainedCharacters() {
    return retainedChars;
  }

  void clear() {
    history.clear();
    position = 0;
    retainedChars = 0;
    allowCoalescing = false;
  }

  void record(
      int start,
      String before,
      String after,
      int beforeLength,
      int beforeSelectionStart,
      int beforeSelectionEnd,
      int afterSelectionStart,
      int afterSelectionEnd,
      long timeMillis) {
    if (replaying) return;
    EditItem item =
        new EditItem(
            start,
            before,
            after,
            beforeLength,
            beforeSelectionStart,
            beforeSelectionEnd,
            afterSelectionStart,
            afterSelectionEnd,
            timeMillis);
    // Autocorrect/no-op notifications must neither create edits nor destroy redo.
    if (item.before.equals(item.after)) return;
    boolean hadRedo = canRedo();
    while (history.size() > position) removeLast();
    // An unrecordable change is a hard boundary. Never replay across that gap.
    if (item.size() > maxRetainedChars) {
      clear();
      return;
    }
    if (!hadRedo && allowCoalescing && position > 0) {
      EditItem previous = history.get(position - 1);
      EditItem merged = previous.merge(item);
      if (merged != null && merged.size() <= maxRetainedChars) {
        removeLast();
        item = merged;
      }
    }
    history.add(item);
    position++;
    retainedChars += item.size();
    allowCoalescing = true;
    while (history.size() > maxOperations || retainedChars > maxRetainedChars) {
      retainedChars -= history.remove(0).size();
      position--;
    }
  }

  private void removeLast() {
    retainedChars -= history.remove(history.size() - 1).size();
    position = Math.min(position, history.size());
  }

  boolean undo(Target target) {
    return replay(target, false);
  }

  boolean redo(Target target) {
    return replay(target, true);
  }

  private boolean replay(Target target, boolean redo) {
    if (replaying || (redo ? !canRedo() : !canUndo())) return false;
    EditItem item = history.get(redo ? position : position - 1);
    String expected = redo ? item.before : item.after;
    String replacement = redo ? item.after : item.before;
    int expectedLength = redo ? item.beforeLength : item.afterLength;
    if (!matches(target.text(), item.start, expected, expectedLength)) {
      clear();
      return false;
    }
    allowCoalescing = false;
    replaying = true;
    try {
      target.replace(item.start, item.start + expected.length(), replacement);
      // Input filters or another watcher can reject/transform a replacement.
      if (!matches(
          target.text(),
          item.start,
          replacement,
          expectedLength - expected.length() + replacement.length())) {
        clear();
        return false;
      }
      int length = target.text().length();
      target.select(
          clamp(redo ? item.afterSelectionStart : item.beforeSelectionStart, length),
          clamp(redo ? item.afterSelectionEnd : item.beforeSelectionEnd, length));
      position += redo ? 1 : -1;
      return true;
    } catch (RuntimeException | Error failure) {
      // The editor may have changed before the exception. Do not retain stale offsets.
      clear();
      throw failure;
    } finally {
      // Unlike the upstream early-return error path, recording always resumes.
      replaying = false;
    }
  }

  private static boolean matches(CharSequence text, int start, String expected, int length) {
    if (text.length() != length || start < 0 || start > length - expected.length()) return false;
    for (int i = 0; i < expected.length(); i++) {
      if (text.charAt(start + i) != expected.charAt(i)) return false;
    }
    return true;
  }

  private static int clamp(int offset, int length) {
    return Math.max(0, Math.min(offset, length));
  }

  /** Markor's common-prefix/common-suffix range diff, with UTF-16 pair boundaries. */
  static int[] findDiff(String before, String after) {
    int minLength = Math.min(before.length(), after.length());
    int start = 0;
    while (start < minLength && before.charAt(start) == after.charAt(start)) start++;
    if (start == before.length() && start == after.length()) {
      return new int[] {start, start, start};
    }
    if (splitsPair(before, start) || splitsPair(after, start)) start--;
    int end = 0;
    int maxEnd = minLength - start;
    while (end < maxEnd
        && before.charAt(before.length() - end - 1) == after.charAt(after.length() - end - 1))
      end++;
    if (splitsPair(before, before.length() - end) || splitsPair(after, after.length() - end)) end--;
    return new int[] {start, before.length() - end, after.length() - end};
  }

  private static boolean splitsPair(String value, int offset) {
    return offset > 0
        && offset < value.length()
        && Character.isHighSurrogate(value.charAt(offset - 1))
        && Character.isLowSurrogate(value.charAt(offset));
  }

  private static boolean singleCodePoint(String value) {
    return !value.isEmpty() && value.codePointCount(0, value.length()) == 1;
  }

  private static int typeOf(int codePoint) {
    if (codePoint == '\n' || codePoint == '\r') return 2;
    return Character.isWhitespace(codePoint) ? 1 : 0;
  }

  private static boolean compatibleTypes(int previous, int current) {
    return previous != 2 && current != 2 && (previous == current || previous == 0 && current == 1);
  }

  /** Markor EditItem: minimal replacement plus selection before/after the edit. */
  private static final class EditItem {
    final int start;
    final String before, after;
    final int beforeLength, afterLength;
    final int beforeSelectionStart, beforeSelectionEnd;
    final int afterSelectionStart, afterSelectionEnd;
    final long timeMillis;
    final boolean chain;

    EditItem(
        int start,
        String before,
        String after,
        int beforeLength,
        int bs,
        int be,
        int as,
        int ae,
        long timeMillis) {
      int[] diff = findDiff(before, after);
      this.start = start + diff[0];
      this.before = before.substring(diff[0], diff[1]);
      this.after = after.substring(diff[0], diff[2]);
      this.beforeLength = beforeLength;
      this.afterLength = beforeLength - before.length() + after.length();
      this.beforeSelectionStart = clamp(bs < 0 ? start : bs, beforeLength);
      this.beforeSelectionEnd = clamp(be < 0 ? this.beforeSelectionStart : be, beforeLength);
      this.afterSelectionStart = clamp(as < 0 ? start + after.length() : as, afterLength);
      this.afterSelectionEnd = clamp(ae < 0 ? this.afterSelectionStart : ae, afterLength);
      this.timeMillis = timeMillis;
      // Raw changed range, not its minimal diff, distinguishes typing from paste/replace.
      this.chain =
          bs == be
              && (before.isEmpty() && singleCodePoint(after)
                  || after.isEmpty() && singleCodePoint(before));
    }

    private EditItem(
        int start, String before, String after, EditItem first, EditItem last, boolean chain) {
      this.start = start;
      this.before = before;
      this.after = after;
      this.beforeLength = first.beforeLength;
      this.afterLength = last.afterLength;
      this.beforeSelectionStart = first.beforeSelectionStart;
      this.beforeSelectionEnd = first.beforeSelectionEnd;
      this.afterSelectionStart = last.afterSelectionStart;
      this.afterSelectionEnd = last.afterSelectionEnd;
      this.timeMillis = last.timeMillis;
      this.chain = chain;
    }

    int size() {
      return before.length() + after.length();
    }

    EditItem merge(EditItem current) {
      long delta = current.timeMillis - timeMillis;
      if (!chain
          || !current.chain
          || delta < 0
          || delta >= COALESCE_MILLIS
          || afterLength != current.beforeLength
          || afterSelectionStart != current.beforeSelectionStart
          || afterSelectionEnd != current.beforeSelectionEnd) return null;
      if (before.isEmpty() && current.before.isEmpty() && current.start == start + after.length()) {
        int previousType = typeOf(after.codePointBefore(after.length()));
        int currentType = typeOf(current.after.codePointAt(0));
        if (compatibleTypes(previousType, currentType)) {
          return new EditItem(
              start, "", after + current.after, this, current, previousType == currentType);
        }
      }
      if (after.isEmpty() && current.after.isEmpty()) {
        boolean backspace = current.start + current.before.length() == start;
        boolean forward = current.start == start;
        int previousType =
            typeOf(backspace ? before.codePointAt(0) : before.codePointBefore(before.length()));
        int currentType = typeOf(current.before.codePointAt(0));
        if ((backspace || forward) && compatibleTypes(previousType, currentType)) {
          return new EditItem(
              Math.min(start, current.start),
              backspace ? current.before + before : before + current.before,
              "",
              this,
              current,
              previousType == currentType);
        }
      }
      return null;
    }
  }
}
