// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.execution.configurations;

import com.intellij.execution.ExecutionBundle;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public class UnknownConfigurationType extends ConfigurationTypeBase {
  public static final UnknownConfigurationType INSTANCE = new UnknownConfigurationType();

  protected UnknownConfigurationType() {
    this(AllIcons.RunConfigurations.Unknown);
  }

  protected UnknownConfigurationType(@NotNull Icon icon) {
    super(NAME, NAME, ExecutionBundle.message("run.configuration.unknown.description"), icon);

    addFactory(new ConfigurationFactory(this) {
      @NotNull
      @Override
      public RunConfiguration createTemplateConfiguration(@NotNull Project project) {
        return new UnknownRunConfiguration(this, project);
      }

      @Contract(pure = true)
      @Override
      public boolean canConfigurationBeSingleton() {
        return false;
      }
    });
  }

  public static final String NAME = "Unknown";

  @NotNull
  public static ConfigurationFactory getFactory() {
    return INSTANCE.getConfigurationFactories()[0];
  }
}
