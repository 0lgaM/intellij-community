/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.python.console;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.FoldRegion;
import com.intellij.openapi.editor.FoldingModel;
import com.intellij.openapi.editor.event.DocumentAdapter;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.ex.FoldingListener;
import com.intellij.util.DocumentUtil;
import com.jetbrains.python.console.pydev.ConsoleCommunicationListener;
import org.jetbrains.annotations.NotNull;

public class PyConsoleStartFolding extends DocumentAdapter implements ConsoleCommunicationListener, FoldingListener {
  private PythonConsoleView myConsoleView;
  private boolean isFirstCommandWasExecuted = false;
  private boolean doNotAddFoldingAgain = false;
  private FoldRegion myStartFoldRegion;
  private static final String DEFAULT_FOLDING_MESSAGE = "Python Console";
  private int startLineOffset = 0;

  public PyConsoleStartFolding(PythonConsoleView consoleView) {
    super();
    myConsoleView = consoleView;
  }

  public void setStartLineOffset(int startLineOffset) {
    this.startLineOffset = startLineOffset;
  }

  @Override
  public void documentChanged(DocumentEvent event) {
    addFolding();
  }

  private void addFolding() {
    Document document = myConsoleView.getEditor().getDocument();
    if (doNotAddFoldingAgain || document.getTextLength() == 0) {
      return;
    }
    FoldingModel foldingModel = myConsoleView.getEditor().getFoldingModel();
    foldingModel.runBatchFoldingOperation(() -> {
      int start = startLineOffset;
      String placeholderText = DEFAULT_FOLDING_MESSAGE;
      int firstLine = document.getLineNumber(startLineOffset);
      for (int line = firstLine; line < document.getLineCount(); line++) {
        String lineText = document.getText(DocumentUtil.getLineTextRange(document, line));
        if (lineText.startsWith("Python")) {
          placeholderText = lineText;
          start = document.getLineStartOffset(line);
          break;
        }
      }

      if (myStartFoldRegion != null) {
        foldingModel.removeFoldRegion(myStartFoldRegion);
      }
      FoldRegion foldRegion = foldingModel.addFoldRegion(start, document.getTextLength() - 1, placeholderText);
      if (foldRegion != null) {
        foldRegion.setExpanded(false);
        myStartFoldRegion = foldRegion;
      }
    });
    if (isFirstCommandWasExecuted) {
      document.removeDocumentListener(this);
    }
  }

  @Override
  public void commandExecuted(boolean more) {
    isFirstCommandWasExecuted = true;
  }

  @Override
  public void inputRequested() {

  }

  @Override
  public void onFoldRegionStateChange(@NotNull FoldRegion region) {
    if (region.equals(myStartFoldRegion) && region.isExpanded()) {
      myConsoleView.getEditor().getComponent().updateUI();
      doNotAddFoldingAgain = true;
    }
  }

  @Override
  public void onFoldProcessingEnd() {

  }
}
