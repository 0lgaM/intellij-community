/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.intellij.diff.actions.impl;

import com.intellij.openapi.actionSystem.AnActionExtensionProvider;
import com.intellij.openapi.actionSystem.DataKey;
import com.intellij.openapi.actionSystem.ExtendableAction;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.project.DumbAware;

public class PrevDifferenceAction extends ExtendableAction implements DumbAware {
  private static final ExtensionPointName<AnActionExtensionProvider> EP_NAME =
    ExtensionPointName.create("com.intellij.diff.actions.impl.PrevDifferenceAction.ExtensionProvider");
  public static final DataKey<AnActionExtensionProvider> DATA_KEY = getDataKey(EP_NAME);

  public PrevDifferenceAction() {
    super(EP_NAME);
    setEnabledInModalContext(true);
  }
}
