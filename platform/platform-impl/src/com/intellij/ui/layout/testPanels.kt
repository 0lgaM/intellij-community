// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ui.layout

import com.intellij.CommonBundle
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.CheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.RadioButton
import java.awt.Dimension
import javax.swing.*

fun labelRowShouldNotGrow(): JPanel {
  return panel {
    row("Create Android module") { CheckBox("Android module name:")() }
    row("Android module name:") { JTextField("input")() }
  }
}

fun makeSecondColumnSmaller(): JPanel {
  val selectForkButton = JButton("Select Other Fork")

  val branchCombobox = ComboBox<String>()
  val diffButton = JButton("Show Diff")

  val titleTextField = JBTextField()

  val descriptionTextArea = JTextArea()

  val panel = panel {
    row("Base fork:") {
      JComboBox<String>(arrayOf())(growX, CCFlags.pushX)
      selectForkButton(growX)
    }
    row("Base branch:") {
      branchCombobox(growX, pushX)
      diffButton(growX)
    }
    row("Title:") { titleTextField() }
    row("Description:") {
      scrollPane(descriptionTextArea)
    }
  }

  // test scrollPane
  panel.preferredSize = Dimension(512, 256)
  return panel
}

fun alignFieldsInTheNestedGrid(): JPanel {
  return panel {
    buttonGroup {
      row {
        RadioButton("In KeePass")()
        row("Database:") {
          JTextField()()
          gearButton()
        }
        row("Master Password:") {
          JBPasswordField()(comment = "Stored using weak encryption.")
        }
      }
    }
  }
}

fun noteRowInTheDialog(): JPanel {
  val passwordField = JPasswordField()
  return panel {
    noteRow("Profiler requires access to the kernel-level API.\nEnter the sudo password to allow this. ")
    row("Sudo password:") { passwordField() }
    row { CheckBox(CommonBundle.message("checkbox.remember.password"), true)() }
    noteRow("Should be an empty row above as a gap. <a href=''>Click me</a>.") {
      System.out.println("Hello")
    }
  }
}

fun cellPanel(): JPanel {
  return panel {
    row("Repository:") {
      cell {
        ComboBox<String>()(comment = "Use File -> Settings Repository... to configure")
        JButton("Delete")()
      }
    }
    row {
      // need some pushx/grow component to test label cell grow policy if there is cell with several components
      scrollPane(JTextArea())
    }
  }
}