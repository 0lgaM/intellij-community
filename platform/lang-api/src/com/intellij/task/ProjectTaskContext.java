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
package com.intellij.task;

import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.util.containers.MultiMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * @author Vladislav.Soroka
 */
public class ProjectTaskContext extends UserDataHolderBase {
  @Nullable
  private final Object mySessionId;
  @Nullable
  private final RunConfiguration myRunConfiguration;
  private final boolean myAutoRun;
  private final MultiMap<String, String> myGeneratedFiles;
  private boolean myCollectGeneratedFiles;

  public ProjectTaskContext() {
    this(null, null, false);
  }

  public ProjectTaskContext(boolean autoRun) {
    this(null, null, autoRun);
  }

  public ProjectTaskContext(@Nullable Object sessionId) {
    this(sessionId, null, false);
  }

  public ProjectTaskContext(@Nullable Object sessionId, @Nullable RunConfiguration runConfiguration) {
    this(sessionId, runConfiguration, false);
  }

  public ProjectTaskContext(@Nullable Object sessionId, @Nullable RunConfiguration runConfiguration, boolean autoRun) {
    mySessionId = sessionId;
    myRunConfiguration = runConfiguration;
    myAutoRun = autoRun;
    myGeneratedFiles = MultiMap.createConcurrentSet();
  }

  @Nullable
  public Object getSessionId() {
    return mySessionId;
  }

  @Nullable
  public RunConfiguration getRunConfiguration() {
    return myRunConfiguration;
  }

  /**
   * @return true indicates that the task was started automatically, e.g. resources compilation on frame deactivation
   */
  public boolean isAutoRun() {
    return myAutoRun;
  }

  public void collectGeneratedFiles() {
    myCollectGeneratedFiles = true;
  }

  @NotNull
  public Collection<String> getGeneratedFilesRoots() {
    return myGeneratedFiles.keySet();
  }

  @NotNull
  public Collection<String> getGeneratedFilesRelativePaths(@NotNull String root) {
    return myGeneratedFiles.get(root);
  }

  public void fileGenerated(@NotNull String root, @NotNull String relativePath) {
    if (myCollectGeneratedFiles) {
      myGeneratedFiles.putValue(root, relativePath);
    }
  }

  public <T> ProjectTaskContext withUserData(@NotNull Key<T> key, @Nullable T value) {
    putUserData(key, value);
    return this;
  }
}
