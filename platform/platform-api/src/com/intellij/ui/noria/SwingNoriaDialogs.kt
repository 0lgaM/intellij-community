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
package com.intellij.ui.noria

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

class SwingNoriaDialogs : NoriaDialogs {
  companion object {
    val toolkit = SwingToolkit()
  }

  override fun show(dialogProps: DialogProps): NoriaDialogHandle {
    val roots = arrayListOf<NoriaHandle<JComponent>>()
    val disposable = Disposer.newDisposable()

    fun jPanel(center: Element?): JPanel? {
      if (center == null) return null
      val root = JPanel()
      val handle = mount(disposable, center, root, toolkit)
      roots.add(handle)
      return root
    }

    val northPanel = jPanel(dialogProps.north)
    val centerPanel = jPanel(dialogProps.center)

    val dw = object : DialogWrapper(dialogProps.project, dialogProps.canBeParent) {
      fun closeImpl(exitCode: Int) = close(exitCode)

      val handle = object : NoriaDialogHandle {
        override fun close(exitCode: Int) {
          closeImpl(exitCode)
        }

        override fun shake() {

        }
      }
      init {
        init()
        Disposer.register(myDisposable, disposable)
        track(disposable) {
          setErrorText(dialogProps.errorText.value)
        }
      }

      override fun getPreferredFocusedComponent(): JComponent? =
        roots.map { it.getPreferredFocusedNode() }.filterNotNull().firstOrNull()


      override fun createCenterPanel(): JComponent? {
        return centerPanel
      }

      override fun createNorthPanel(): JComponent? {
        return northPanel
      }

      fun makeAction(a: NoriaAction): Action =
        if (a.isExclusive) {
          object : DialogWrapperAction(a.name) {
            override fun doAction(e: ActionEvent?) {
              a.lambda(handle)
            }
          }
        }
        else {
          object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
              a.lambda(handle)
            }
          }.apply { putValue(Action.NAME, a.name) }
        }.apply {
          putValue(DEFAULT_ACTION, if (a.isDefault) true else null)
          putValue(FOCUSED_ACTION, a.focused)
          putValue(Action.MNEMONIC_KEY, a.mnemonic?.toInt())
          track(myDisposable) {
            isEnabled = a.enabled.value
          }
        }

      override fun createActions(): Array<out Action> {
        var actions = dialogProps.actions.map { makeAction(it) }
        if (dialogProps.hasHelp) {
          actions += helpAction
        }
        if (SystemInfo.isMac) {
          actions = actions.reversed()
        }
        return actions.toTypedArray()
      }

      override fun createLeftSideActions(): Array<out Action> {
        return dialogProps.leftSideActions.map { makeAction(it) }.toTypedArray()
      }
    }

    dw.show()

    return object : NoriaDialogHandle {
      override fun close(exitCode: Int) {
        dw.close(exitCode)
      }

      override fun shake() {

      }
    }
  }
}
