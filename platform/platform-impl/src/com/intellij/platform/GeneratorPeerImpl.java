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
package com.intellij.platform;

import com.intellij.ide.util.projectWizard.SettingsStep;
import com.intellij.openapi.ui.ValidationInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public class GeneratorPeerImpl<T> implements GeneratorPeer<T> {
  private final T mySettings;
  private final JComponent myComponent;

  public GeneratorPeerImpl(T settings, JComponent component) {
    mySettings = settings;
    myComponent = component;
  }

  @NotNull
  @Override
  public JComponent getComponent() {
    return myComponent;
  }

  @Override
  public void buildUI(@NotNull SettingsStep settingsStep) {

  }

  @NotNull
  @Override
  public T getSettings() {
    return mySettings;
  }

  @Nullable
  @Override
  public ValidationInfo validate() {
    return null;
  }

  @Override
  public boolean isBackgroundJobRunning() {
    return false;
  }

  @Override
  public void addSettingsStateListener(@NotNull WebProjectGenerator.SettingsStateListener listener) {

  }
}
