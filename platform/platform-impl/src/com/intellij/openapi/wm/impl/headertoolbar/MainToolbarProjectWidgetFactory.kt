// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.wm.impl.headertoolbar

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import javax.swing.JComponent

interface MainToolbarProjectWidgetFactory : MainToolbarWidgetFactory {

  companion object {
    val EP_NAME = ExtensionPointName.create<MainToolbarProjectWidgetFactory>("com.intellij.projectToolbarWidget")
  }

  fun createWidget(project: Project): JComponent

}