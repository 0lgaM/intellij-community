// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ui.tabs.impl

import com.intellij.ui.tabs.JBTabsPosition
import java.awt.*

class JBEditorTabsBackgroundAndBorder(tabs: JBTabsImpl) : JBBaseTabsBackgroundAndBorder(tabs) {

  override fun getEffectiveBorder(): Insets = Insets(thickness, 0, 0, 0)

  override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
    super.paintBorder(c, g, x, y, width, height)

    val headerRectangle = tabs.lastLayoutPass.headerRectangle;

    val startY = headerRectangle.y - if (tabs.position == JBTabsPosition.bottom) 0 else thickness
    tabs.getTabPainter().paintBorderLine(g as Graphics2D, thickness, Point(x, startY), Point(x + width, startY))

    when(tabs.position) {
      JBTabsPosition.top -> {
        for (eachRow in 1..tabs.lastLayoutPass.rowCount) {
          val yl = (eachRow * tabs.myHeaderFitSize.height) + startY
          tabs.getTabPainter().paintBorderLine(g, thickness, Point(x, yl), Point(x + width, yl))
        }
      }
      JBTabsPosition.bottom -> {
        tabs.getTabPainter().paintBorderLine(g, thickness, Point(x, y), Point(x + width, y))
      }
      JBTabsPosition.right -> {
        val lx = headerRectangle.x
        tabs.getTabPainter().paintBorderLine(g, thickness, Point(lx, y), Point(lx, y + height))
      }
    }
  }
}