/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package org.jetbrains.idea.devkit.inspections;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.application.PluginPathManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.testFramework.fixtures.LightCodeInsightFixtureTestCase;

/**
 * @author Dmitry Avdeev
 */
public class CapitalizationInspectionTest extends LightCodeInsightFixtureTestCase {

  public void testTitleCapitalization() throws Exception {
    doTest();
  }

  public void testSentenceCapitalization() throws Exception {
    doTest();
  }

  public void testMultipleReturns() throws Exception {
    doTest();
  }

  public void doTest() {
    myFixture.testHighlighting(getTestName(false) + ".java");
    final IntentionAction action = myFixture.filterAvailableIntentions("Properly capitalize").get(0);
    new WriteCommandAction.Simple(getProject()) {
      @Override
      protected void run() throws Throwable {
        action.invoke(getProject(), myFixture.getEditor(), getFile());
      }
    }.execute();
    myFixture.checkResultByFile(getTestName(false) + "_after.java");
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    myFixture.addClass("package com.intellij.codeInspection; public class CommonProblemDescriptor {}");
    myFixture.addClass("package com.intellij.codeInspection; public class QuickFix {}");
    myFixture.enableInspections(TitleCapitalizationInspection.class);
  }


  @Override
  protected String getBasePath() {
    return PluginPathManager.getPluginHomePathRelative("devkit") + "/testData/inspections/capitalization";
  }
}
