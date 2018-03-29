// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ui.layout

import com.intellij.ide.ui.laf.IntelliJLaf
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.ui.components.dialog
import com.intellij.util.io.write
import net.miginfocom.swing.MigLayout
import java.awt.Dimension
import java.nio.file.Paths
import javax.swing.UIManager
import javax.swing.plaf.metal.MetalLookAndFeel

object MigLayoutTestApp {
  @JvmStatic
  fun main(args: Array<String>) {
//    LayoutUtil.setGlobalDebugMillis(1000)

    runInEdtAndWait {
      UIManager.setLookAndFeel(MetalLookAndFeel())
      UIManager.setLookAndFeel(IntelliJLaf())

      val panel = noteRowInTheDialog()
      val dialog = dialog(
        title = "",
        panel = panel,
        resizable = true,
        okActionEnabled = false
      ) {
        return@dialog null
      }

      panel.preferredSize = Dimension(512, 256)
      dialog.toFront()
      Paths.get(System.getProperty("user.home"), "layout-dump.yml").write(configurationToJson(panel, panel.layout as MigLayout))
      dialog.showAndGet()

//    val frame = JFrame()
//    frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
//    frame.contentPane.add(panel, BorderLayout.CENTER)
//    frame.contentPane.background = Color.WHITE
//    frame.background = Color.WHITE
//    frame.pack()
//    frame.setLocationRelativeTo(null)
//    frame.minimumSize = Dimension(512, 256)
//    frame.isVisible = true
//
    }
  }
}