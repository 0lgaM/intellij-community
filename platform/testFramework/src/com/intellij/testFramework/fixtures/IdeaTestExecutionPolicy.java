// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.testFramework.fixtures;

import com.intellij.TestCaseLoader;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.TestModeFlagListener;
import com.intellij.testFramework.TestModeFlags;
import com.intellij.testFramework.UsefulTestCase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author yole
 */
public abstract class IdeaTestExecutionPolicy implements TestModeFlagListener {
  protected IdeaTestExecutionPolicy() {
    TestModeFlags.addListener(this);
  }

  public abstract void setUp(Project project, Disposable testRootDisposable, String testDataPath);
  public abstract TempDirTestFixture createTempDirTestFixture();
  public abstract boolean runInDispatchThread();
  public void testFileConfigured(@NotNull PsiFile file) {
  }

  public void testDirectoryConfigured(@NotNull PsiDirectory directory) {
  }

  public void beforeCheckResult(@NotNull PsiFile file) {
  }

  public String getHomePath() {
    return null;
  }

  public String getPerTestTempDirName() {
    return null;
  }

  public void waitForHighlighting(@NotNull Project project, @NotNull Editor editor) {
  }

  public void inspectionToolEnabled(@NotNull Project project, @NotNull InspectionToolWrapper<?, ?> toolWrapper, @NotNull Disposable disposable) {
  }

  @Override
  public void testModeFlagChanged(@NotNull Key<?> key, @Nullable Object value) {
  }

  private static IdeaTestExecutionPolicy ourCurrent = null;

  @Nullable
  public static IdeaTestExecutionPolicy current() {
    if (ourCurrent != null) return ourCurrent;
    String policyClassName = System.getProperty("idea.test.execution.policy");
    if (policyClassName == null) return null;
    try {
      Class<?> policyClass = Class.forName(policyClassName);
      ourCurrent = (IdeaTestExecutionPolicy)  policyClass.newInstance();
    } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
    return ourCurrent;
  }

  public static String getHomePathWithPolicy() {
    IdeaTestExecutionPolicy policy = current();
    if (policy != null) {
      String policyHomePath = policy.getHomePath();
      if (policyHomePath != null) {
        return policyHomePath;
      }
    }
    return PathManager.getHomePath();
  }

  public boolean canRun(Class<? extends UsefulTestCase> testCaseClass) {
    IdeaTestExecutionPolicy current = current();
    if (current == null) return true;

    SkipWithExecutionPolicy annotation = TestCaseLoader.getAnnotationInHierarchy(testCaseClass, SkipWithExecutionPolicy.class);
    return annotation == null || !annotation.value().equals(current.getName());
  }

  protected abstract String getName();
}
