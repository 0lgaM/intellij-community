// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.wm.impl.status

import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.WidgetPresentation
import com.intellij.openapi.wm.WidgetPresentationDataContext
import com.intellij.openapi.wm.WidgetPresentationFactory
import com.intellij.openapi.wm.impl.status.widget.StatusBarEditorBasedWidgetFactory
import com.intellij.ui.UIBundle
import kotlinx.coroutines.CoroutineScope

private class PositionPanelWidgetFactory : StatusBarEditorBasedWidgetFactory(), WidgetPresentationFactory {
  override fun getId(): String = StatusBar.StandardWidgets.POSITION_PANEL

  override fun getDisplayName(): String = UIBundle.message("status.bar.position.widget.name")

  override fun createPresentation(context: WidgetPresentationDataContext, scope: CoroutineScope): WidgetPresentation {
    return PositionPanel(dataContext = context, scope = scope)
  }
}