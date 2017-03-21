/*
 * Copyright 2000-2017 JetBrains s.r.o.
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
package com.intellij.mock;

import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.StandaloneDocumentEx;
import com.intellij.openapi.util.TextRange;
import com.intellij.util.LocalTimeCounter;
import org.jetbrains.annotations.NotNull;

public class MockDocument extends StandaloneDocumentEx {
  private StringBuffer myText = new StringBuffer();
  private long myModStamp = LocalTimeCounter.currentTime();

  public MockDocument() {
  }

  @NotNull
  @Override
  public String getText() {
    return myText.toString();
  }

  @NotNull
  @Override
  public String getText(@NotNull TextRange range) {
    return range.substring(myText.toString());
  }

  @Override
  public void replaceText(@NotNull CharSequence chars, long newModificationStamp) {
    myText = new StringBuffer();
    myText.append(chars);
    myModStamp = newModificationStamp;
  }

  public CharSequence textToCharArray() {
    return getText();
  }

  @Override
  @NotNull
  public char[] getChars() {
    return getText().toCharArray();
  }

  @Override
  public int getTextLength() {
    return myText.length();
  }

  @Override
  public int getLineCount() {
    return 1;
  }

  @Override
  public int getLineNumber(int offset) {
    return 0;
  }

  @Override
  public int getLineStartOffset(int line) {
    return 0;
  }

  @Override
  public int getLineEndOffset(int line) {
    return myText.length();
  }

  @Override
  public void insertString(int offset, @NotNull CharSequence s) {
    myText.insert(offset, s);
  }

  @Override
  public void deleteString(int startOffset, int endOffset) {
    myText.delete(startOffset, endOffset);
  }

  @Override
  public void replaceString(int startOffset, int endOffset, @NotNull CharSequence s) {
    myText.replace(startOffset, endOffset, s.toString());
    myModStamp = LocalTimeCounter.currentTime();
  }

  @Override
  public long getModificationStamp() {
    return myModStamp;
  }

  @Override
  public void setModificationStamp(long modificationStamp) {
    myModStamp = modificationStamp;
  }

  @Override
  public RangeMarker getRangeGuard(int start, int end) {
    return null;
  }

  @Override
  public RangeMarker getOffsetGuard(int offset) {
    return null;
  }

  @Override
  @NotNull
  public RangeMarker createRangeMarker(@NotNull final TextRange textRange) {
    return createRangeMarker(textRange.getStartOffset(), textRange.getEndOffset());
  }
}
