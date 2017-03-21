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
package com.intellij.codeInsight.completion;

import com.intellij.codeInsight.daemon.impl.ParameterHintsPresentationManager;
import com.intellij.codeInsight.hint.ParameterInfoController;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.registry.RegistryValue;
import com.intellij.util.ui.UIUtil;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

public class CompletionHintsTest extends LightFixtureCompletionTestCase {
  private RegistryValue myRegistryValue = Registry.get("java.completion.argument.hints");
  private boolean myStoredRegistryValue;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    myStoredRegistryValue = myRegistryValue.asBoolean();
    myRegistryValue.setValue(true);
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      myRegistryValue.setValue(myStoredRegistryValue);
    }
    finally {
      super.tearDown();
    }
  }

  public void testBasicScenario() throws Exception {
    // check hints appearance on completion
    myFixture.configureByText(JavaFileType.INSTANCE, "class C { void m() { System.setPro<caret> } }");
    complete("setProperty");
    myFixture.checkResultWithInlays("class C { void m() { System.setProperty(<hint text=\"key:\"/>, <hint text=\"value:\"/>) } }");

    // check that hints don't disappear after daemon highlighting passes
    myFixture.doHighlighting();
    myFixture.checkResultWithInlays("class C { void m() { System.setProperty(<hint text=\"key:\"/>, <hint text=\"value:\"/>) } }");

    // test Tab/Shift+Tab navigation
    myFixture.checkResult("class C { void m() { System.setProperty(<caret>, ) } }");
    assertTrue(myFixture.getEditor().getCaretModel().getLogicalPosition().leansForward);
    myFixture.performEditorAction("NextParameter");
    myFixture.checkResult("class C { void m() { System.setProperty(, <caret>) } }");
    assertTrue(myFixture.getEditor().getCaretModel().getLogicalPosition().leansForward);
    myFixture.performEditorAction("PrevParameter");
    myFixture.checkResult("class C { void m() { System.setProperty(<caret>, ) } }");
    assertTrue(myFixture.getEditor().getCaretModel().getLogicalPosition().leansForward);

    // test hints remain shown while entering parameter values
    myFixture.type("\"a");
    myFixture.performEditorAction("NextParameter");
    myFixture.type("\"b");
    myFixture.doHighlighting();
    myFixture.checkResultWithInlays("class C { void m() { System.setProperty(<hint text=\"key:\"/>\"a\", <hint text=\"value:\"/>\"b\") } }");

    // test hints disappearance when caret moves out of parameter list
    myFixture.performEditorAction("EditorRight");
    myFixture.performEditorAction("EditorRight");
    ParameterInfoController.waitForDelayedActions(getEditor(), 10, TimeUnit.SECONDS);

    myFixture.doHighlighting();
    waitTillAnimationCompletes();
    myFixture.checkResultWithInlays("class C { void m() { System.setProperty(\"a\", \"b\") } }");
  }

  public void testSwitchingOverloads() {
    myFixture.configureByText(JavaFileType.INSTANCE, "class C { void m() { Character.to<caret> } }");
    complete("toChars(int codePoint)");
    myFixture.checkResultWithInlays("class C { void m() { Character.toChars(<hint text=\"codePoint:\"/>) } }");
    showParameterInfo();
    myFixture.performEditorAction("MethodOverloadSwitchDown");
    myFixture.checkResultWithInlays("class C { void m() { Character.toChars(<hint text=\"codePoint:\"/>, <hint text=\"dst:\"/>, <hint text=\"dstIndex:\"/>) } }");
  }

  public void testSwitchingOverloadsWithParameterFilled() {
    myFixture.configureByText(JavaFileType.INSTANCE, "class C { void m() { Character.to<caret> } }");
    complete("toChars(int codePoint)");
    type("123");
    myFixture.checkResultWithInlays("class C { void m() { Character.toChars(<hint text=\"codePoint:\"/>123) } }");
    showParameterInfo();
    myFixture.performEditorAction("MethodOverloadSwitchDown");
    myFixture.checkResultWithInlays("class C { void m() { Character.toChars(<hint text=\"codePoint:\"/>123, <hint text=\"dst:\"/>, <hint text=\"dstIndex:\"/>) } }");
    myFixture.checkResult("class C { void m() { Character.toChars(123, <caret>, ) } }");
  }

  private void showParameterInfo() {
    myFixture.performEditorAction("ParameterInfo");
    UIUtil.dispatchAllInvocationEvents();
  }

  private void complete(String partOfItemText) {
    LookupElement[] elements = myFixture.completeBasic();
    LookupElement element = Stream.of(elements).filter(e -> {
      LookupElementPresentation p = new LookupElementPresentation();
      e.renderElement(p);
      return (p.getItemText() + p.getTailText()).contains(partOfItemText);
    }).findAny().get();
    selectItem(element);
  }

  private void waitTillAnimationCompletes() {
    long deadline = System.currentTimeMillis() + 60_000;
    while (ParameterHintsPresentationManager.getInstance().isAnimationInProgress(getEditor())) {
      if (System.currentTimeMillis() > deadline) fail("Too long waiting for animation to finish");
      LockSupport.parkNanos(10_000_000);
      UIUtil.dispatchAllInvocationEvents();
    }
  }
}