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
package com.intellij.testGuiFramework.tests.community

import com.intellij.testGuiFramework.framework.RunWithIde
import com.intellij.testGuiFramework.impl.GuiTestCase
import com.intellij.testGuiFramework.launcher.ide.IdeType
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

@RunWithIde(IdeType.IDEA_COMMUNITY)
class CommandLineProjectTest: GuiTestCase() {

  val testProjectName = "test-cmd-template"
  val codeText: String = """package com.company;

public class Main {

  public static void main(String[] args) {
    // write your code here
  }
}"""

  @Test
  fun testProjectCreate() {


    welcomeFrame {
      actionLink("Create New Project").click()
      dialog("New Project") {
        jList("Java").clickItem("Java")
        button("Next").click()
        checkbox("Create project from template").click()
        jList("Command Line App").clickItem("Command Line App")
        button("Next").click()
        typeText(testProjectName)
        button("Finish").click()
      }
    }
    ideFrame {
      toolwindow(id = "Project") {
        projectView {
          path(project.name, "src", "com.company", "Main").doubleClick()
        }
      }
      editor {
        moveTo(118)
        moveTo(121)
      }
      val editorCode = editor.getCurrentFileContents(false)
      assertTrue(codeText.lowerCaseNoSpace() == editorCode!!.lowerCaseNoSpace())
      closeProject()
    }
  }

  fun String.lowerCaseNoSpace() = this.toLowerCase().removeSpaces()

  fun String.removeSpaces(): String {
    val WHITE_SPACE_PATTERN = Pattern.compile("\\s")
    return WHITE_SPACE_PATTERN.matcher(this).replaceAll("")
  }
}