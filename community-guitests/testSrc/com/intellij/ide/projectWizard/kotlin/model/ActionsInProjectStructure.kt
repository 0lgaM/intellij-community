// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.projectWizard.kotlin.model

import com.intellij.testGuiFramework.fixtures.extended.ExtendedTreeFixture
import com.intellij.testGuiFramework.impl.GuiTestCase
import com.intellij.testGuiFramework.util.*
import org.fest.swing.exception.WaitTimedOutError
import java.awt.Dialog
import java.awt.KeyboardFocusManager

const val localTimeout = 2L // default timeout is 2 minutes and it's too big for most of tasks here

// Attention: it's supposed that Project Structure dialog is open both before the function
// executed and after
fun KotlinGuiTestCase.checkFacetInOneModule(expectedFacet: FacetStructure, vararg path: String){
  dialog("Project Structure", needToKeepDialog = true){
    logUIStep("Click Modules")
    jList("Modules", localTimeout).clickItem("Modules")

    try {
      jTree(path[0], timeout = localTimeout).selectWithKeyboard(this@checkFacetInOneModule, *path)
      logUIStep("Check facet for module `${path.joinToString(" -> ")}`")
      checkFacetState(expectedFacet)
    }
    catch (e: Exception) {
      logError("Kotlin facet for module `${path.joinToString(" -> ")}` not found")
    }
  }
}

fun ExtendedTreeFixture.selectWithKeyboard(testCase: GuiTestCase, vararg path: String) {
  fun currentValue(): String {
    val selectedRow = target().selectionRows.first()
    return valueAt(selectedRow) ?: throw IllegalStateException("Nothing is selected in the tree")
  }
  click()
  testCase.shortcut(Key.HOME) // select the top row
  for((index, step) in path.withIndex()){
    if(currentValue() != step) {
      testCase.typeText(step)
      while (currentValue() != step)
        testCase.shortcut(Key.DOWN)
    }
    if(index < path.size -1) testCase.shortcut(Key.RIGHT)
  }
}

// Attention: it's supposed that Project Structure dialog is open both before the function
// executed and after
fun KotlinGuiTestCase.checkLibrariesFromMavenGradle(buildSystem: BuildSystem,
                                                    kotlinVersion: String,
                                                    expectedJars: Collection<String>){
  if(buildSystem == BuildSystem.IDEA) return
  dialog("Project Structure", needToKeepDialog = true){
    val tabs = jList("Libraries", timeout = localTimeout)
    logUIStep("Click Libraries")
    tabs.clickItem("Libraries")
    expectedJars
      .map { "$buildSystem: $it${if (it.endsWith(":")) kotlinVersion else ""}" }
      .forEach { testTreeItemExist("Library", it) }
  }
}

// Attention: it's supposed that Project Structure dialog is open both before the function
// executed and after
fun KotlinGuiTestCase.checkLibrariesFromIDEA(expectedLibName: String,
                                             expectedJars: Collection<String>){
  dialog("Project Structure", needToKeepDialog = true){
    val tabs = jList("Libraries", timeout = localTimeout)
    logUIStep("Click Libraries")
    tabs.clickItem("Libraries")
    logTestStep("Check that Kotlin libraries are added")
    testTreeItemExist("Library", expectedLibName)
    expectedJars
      .map { arrayOf(if (it.endsWith("-sources.jar")) "Sources" else "Classes", it) }
      .forEach { testTreeItemExist("Library", *it) }
  }
}


fun KotlinGuiTestCase.checkInProjectStructure(actions: KotlinGuiTestCase.() -> Unit) {
  fun getActiveModalDialog(): Dialog? {
    val activeWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
    if (activeWindow is Dialog) {
      if (activeWindow.modalityType == Dialog.ModalityType.APPLICATION_MODAL) {
        return activeWindow
      }
    }
    return null
  }

  val projectStructureTitle = "Project Structure"
  logTestStep("Check structure of the project")
  ideFrame {
    val numberOfAttempts = 5
    var isCorrectDialogOpen = false
    for (currentAttempt in 0..numberOfAttempts) {
      waitAMoment()
      //    invokeMainMenu("ShowProjectStructureSettings")
      logUIStep("Call '$projectStructureTitle' dialog with Ctrl+Shift+Alt+S. Attempt ${currentAttempt + 1}")
      shortcut(Modifier.CONTROL + Modifier.SHIFT + Modifier.ALT + Key.S)
      val activeDialog = getActiveModalDialog()
      logUIStep("Active dialog: ${activeDialog?.title}")
      if (activeDialog?.title == projectStructureTitle) {
        dialog(projectStructureTitle) {
          try {
            isCorrectDialogOpen = true
            actions()
          }
          finally {
            logUIStep("Close Project Structure dialog with Cancel")
            button("Cancel").click()
          }
        }
      }
      else {
        if (activeDialog != null) {
          logUIStep("Active dialog is incorrect, going to close it with Escape")
          shortcut(Key.ESCAPE)
        }
      }
      if (isCorrectDialogOpen) break
    }
  }
}
