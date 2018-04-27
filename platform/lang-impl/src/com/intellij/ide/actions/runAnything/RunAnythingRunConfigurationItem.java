// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.actions.runAnything;

import com.intellij.execution.Executor;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.actions.ChooseRunConfigurationPopup;
import com.intellij.icons.AllIcons;
import com.intellij.ide.actions.runAnything.items.RunAnythingItem;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.ObjectUtils;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.Objects;

public class RunAnythingRunConfigurationItem extends RunAnythingItem<ChooseRunConfigurationPopup.ItemWrapper> {
  private static final Logger LOG = Logger.getInstance(RunAnythingRunConfigurationItem.class);
  static final String RUN_ANYTHING_RUN_CONFIGURATION_AD_TEXT = RunAnythingUtil.AD_CONTEXT_TEXT + ", " + RunAnythingUtil.AD_DEBUG_TEXT;
  @NotNull private final ChooseRunConfigurationPopup.ItemWrapper myWrapper;

  public RunAnythingRunConfigurationItem(@NotNull ChooseRunConfigurationPopup.ItemWrapper wrapper) {
    myWrapper = wrapper;
  }

  @Override
  public void run(@NotNull DataContext dataContext) {
    super.run(dataContext);
    Project project = dataContext.getData(CommonDataKeys.PROJECT);
    Executor executor = dataContext.getData(RunAnythingAction.EXECUTOR_KEY);
    LOG.assertTrue(project != null);
    LOG.assertTrue(executor != null);

    if (myWrapper.getValue() instanceof RunnerAndConfigurationSettings) {
      myWrapper.perform(project, executor, dataContext);
    }
  }

  @NotNull
  @Override
  public String getText() {
    return myWrapper.getText();
  }

  @NotNull
  @Override
  public String getAdText() {
    return RUN_ANYTHING_RUN_CONFIGURATION_AD_TEXT;
  }

  @Override
  public void triggerUsage(@NotNull DataContext dataContext) {
    RunAnythingUtil.triggerDebuggerStatistics(dataContext);
  }

  @NotNull
  @Override
  public Icon getIcon() {
    return ObjectUtils.notNull(myWrapper.getIcon(), AllIcons.RunConfigurations.Unknown);
  }

  @NotNull
  @Override
  public ChooseRunConfigurationPopup.ItemWrapper getValue() {
    return myWrapper;
  }

  @NotNull
  @Override
  public Component createComponent(boolean isSelected) {
    return RunAnythingUtil.createRunConfigurationCellRendererComponent(myWrapper, isSelected);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    RunAnythingRunConfigurationItem item = (RunAnythingRunConfigurationItem)o;
    return Objects.equals(myWrapper, item.myWrapper);
  }

  @Override
  public int hashCode() {
    return Objects.hash(myWrapper);
  }
}