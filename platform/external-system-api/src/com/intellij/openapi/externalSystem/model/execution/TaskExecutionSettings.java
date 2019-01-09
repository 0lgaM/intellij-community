// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.externalSystem.model.execution;

import com.intellij.openapi.externalSystem.model.ProjectSystemId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps external system task execution parameters. Basically, this is a model class which holds data represented when
 * a user opens run configuration editor for corresponding external system.
 */
public interface TaskExecutionSettings {
  @Nullable
  String getExecutionName();

  void setExecutionName(@Nullable String executionName);

  @Nullable
  ProjectSystemId getExternalSystemId();

  void setExternalSystemId(@NotNull ProjectSystemId projectSystemId);

  String getExternalProjectPath();

  void setExternalProjectPath(@Nullable String externalProjectPath);

  String getVmOptions();

  void setVmOptions(@Nullable String vmOptions);

  @NotNull
  @Unmodifiable List<TaskSettings> getTasksSettings();

  void addTaskSettings(@NotNull TaskSettings taskSettings);

  void addUnorderedParameter(@NotNull String argument);

  @NotNull
  @Unmodifiable Set<String> getUnorderedParameters();

  void removeUnorderedParameter(@NotNull String argument);

  void resetTaskSettings();

  void resetUnorderedParameters();

  @NotNull
  Map<String, String> getEnv();

  void setEnv(@NotNull Map<String, String> env);

  boolean isPassParentEnvs();

  void setPassParentEnvs(boolean passParentEnvs);

  @NotNull
  String toCommandLine();

  TaskExecutionSettings clone();
}
