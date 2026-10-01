/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * App Matrix modifications: 2026-10-01.
 * Adapted from Markor TextViewUndoRedo.java at
 * 8d657fd20fff71719d782a3bc375c6f982d09b19. Original dedication:
 * THIS CLASS IS PROVIDED TO THE PUBLIC DOMAIN FOR FREE WITHOUT ANY
 * RESTRICTIONS OR ANY WARRANTY.
 * See third_party/MARKOR.md for full provenance and removed functionality.
 */
// Relocated as a reusable Oritwig text capability on 2026-10-01; behavior retained.
package dev.appmatrix.core.text;

import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;

/** One watcher for one notes editor session; never stores text on disk or in logs. */
public final class TextUndoController {
  private final EditHistory history = new EditHistory();
  private EditText editor;
  private Runnable historyChanged;
  private Pending pending;
  private int suspensionDepth;
  private boolean disposed;

  private final TextWatcher watcher =
      new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence text, int start, int count, int after) {
          if (!recording()) return;
          pending =
              new Pending(
                  start,
                  text.subSequence(start, start + count).toString(),
                  text.length(),
                  editor.getSelectionStart(),
                  editor.getSelectionEnd());
        }

        @Override
        public void onTextChanged(CharSequence text, int start, int before, int count) {
          if (!recording() || pending == null) return;
          pending.after = text.subSequence(start, start + count).toString();
        }

        @Override
        public void afterTextChanged(Editable text) {
          if (!recording() || pending == null) return;
          Pending change = pending;
          pending = null;
          if (change.after == null) {
            clearHistory();
            return;
          }
          history.record(
              change.start,
              change.before,
              change.after,
              change.beforeLength,
              change.selectionStart,
              change.selectionEnd,
              editor.getSelectionStart(),
              editor.getSelectionEnd(),
              SystemClock.uptimeMillis());
          notifyChanged();
        }
      };

  private final View.OnAttachStateChangeListener attachment =
      new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(View view) {}

        @Override
        public void onViewDetachedFromWindow(View view) {
          dispose();
        }
      };

  /** Attach after loading the entry's initial text. The callback updates accessible UI state. */
  public TextUndoController(EditText editor, Runnable historyChanged) {
    if (editor == null) throw new IllegalArgumentException("An editor is required");
    this.editor = editor;
    this.historyChanged = historyChanged;
    editor.addTextChangedListener(watcher);
    editor.addOnAttachStateChangeListener(attachment);
  }

  public boolean canUndo() {
    return !disposed && suspensionDepth == 0 && history.canUndo();
  }

  public boolean canRedo() {
    return !disposed && suspensionDepth == 0 && history.canRedo();
  }

  public boolean undo() {
    return replay(false);
  }

  public boolean redo() {
    return replay(true);
  }

  private boolean replay(boolean redo) {
    if (disposed || suspensionDepth > 0) return false;
    try {
      EditHistory.Target target =
          new EditHistory.Target() {
            @Override
            public CharSequence text() {
              return editor.getText();
            }

            @Override
            public void replace(int start, int end, String replacement) {
              // Keep every other watcher attached: undo/redo must mark the draft dirty.
              editor.getText().replace(start, end, replacement);
            }

            @Override
            public void select(int start, int end) {
              editor.setSelection(start, end);
            }
          };
      return redo ? history.redo(target) : history.undo(target);
    } finally {
      pending = null;
      notifyChanged();
    }
  }

  /** Start a clean history boundary; required whenever the entry identity changes. */
  public void clearHistory() {
    pending = null;
    history.clear();
    notifyChanged();
  }

  /**
   * Run a programmatic load/reset without recording it. Clears both directions even on failure, so
   * history can never cross entries. Other listeners remain active.
   */
  public void suspendRecording(Runnable action) {
    if (action == null) throw new IllegalArgumentException("An action is required");
    if (disposed) throw new IllegalStateException("This editor session is closed");
    suspensionDepth++;
    try {
      clearHistory();
      action.run();
    } finally {
      suspensionDepth--;
      clearHistory();
    }
  }

  /** Idempotent teardown, called explicitly on exit and automatically on view detach. */
  public void dispose() {
    if (disposed) return;
    disposed = true;
    EditText oldEditor = editor;
    editor = null;
    oldEditor.removeTextChangedListener(watcher);
    oldEditor.removeOnAttachStateChangeListener(attachment);
    try {
      clearHistory();
    } finally {
      historyChanged = null;
    }
  }

  private boolean recording() {
    return !disposed && suspensionDepth == 0 && !history.isReplaying();
  }

  private void notifyChanged() {
    if (historyChanged != null) historyChanged.run();
  }

  private static final class Pending {
    final int start, beforeLength, selectionStart, selectionEnd;
    final String before;
    String after;

    Pending(int start, String before, int length, int selectionStart, int selectionEnd) {
      this.start = start;
      this.before = before;
      this.beforeLength = length;
      this.selectionStart = selectionStart;
      this.selectionEnd = selectionEnd;
    }
  }
}
