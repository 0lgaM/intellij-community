// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
@file:Suppress("MayBeConstant")

package com.intellij.codeInsight.daemon.impl

import com.intellij.codeInsight.daemon.DaemonBundle
import com.intellij.codeInsight.daemon.impl.tooltips.TooltipActionProvider.isShowActions
import com.intellij.codeInsight.daemon.impl.tooltips.TooltipActionProvider.setShowActions
import com.intellij.codeInsight.hint.HintManagerImpl
import com.intellij.codeInsight.hint.LineTooltipRenderer
import com.intellij.icons.AllIcons
import com.intellij.ide.TooltipEvent
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.actionSystem.impl.ActionMenuItem
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.TooltipAction
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.keymap.KeymapUtil.getActiveKeymapShortcuts
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.HintHint
import com.intellij.ui.HyperlinkAdapter
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.LightweightHint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.Html
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.MouseEvent
import java.util.*
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.MenuSelectionManager
import javax.swing.event.HyperlinkEvent


internal class DaemonTooltipWithActionRenderer(text: String?,
                                               private val fix: TooltipAction?,
                                               width: Int,
                                               comparable: Array<Any>) : DaemonTooltipRenderer(text, width, comparable) {
  

  override fun dressDescription(editor: Editor, tooltipText: String, expand: Boolean): String {
    if (!LineTooltipRenderer.isActiveHtml(myText!!) || expand) {
      return super.dressDescription(editor, tooltipText, expand)
    }

    val problems = getProblems(tooltipText)
    val text = StringBuilder()
    StringUtil.join(problems, { param ->
      val ref = getLinkRef(param)
      if (ref != null) {
        getHtmlForProblemWithLink(param!!)
      }
      else {
        UIUtil.getHtmlBody(Html(param).setKeepFont(true))
      }
    }, UIUtil.BORDER_LINE, text)

    return text.toString()
  }

  override fun getHtmlForProblemWithLink(problem: String): String {
    val html = Html(problem).setKeepFont(true)
    return UIUtil.getHtmlBody(html).replace(DaemonBundle.message("inspection.extended.description"), "")
  }

  override fun createMainPanel(editor: Editor, hintHint: HintHint, pane: JComponent): JComponent {

    val grid = JPanel(GridBagLayout())
    val gridBagConstraints = GridBagConstraints(0, 0, 1, 1, 0.0, 0.0, GridBagConstraints.CENTER, GridBagConstraints.HORIZONTAL,
                                                JBUI.insetsLeft(1), 0, 0)
    grid.add(pane, gridBagConstraints)
    grid.background = hintHint.textBackground
    grid.border = JBUI.Borders.empty()

    return grid
  }

  override fun fillPanel(editor: Editor,
                         grid: JComponent,
                         hint: LightweightHint,
                         hintHint: HintHint,
                         actions: ArrayList<AnAction>,
                         expandCallback: TooltipReloader) {
    super.fillPanel(editor, grid, hint, hintHint, actions, expandCallback)
    if (grid !is JPanel) return

    val settingsComponent = createSettingsComponent(hint, hintHint, expandCallback)

    val settingsConstraints = GridBagConstraints(1, 0, 1, 1, 0.0, 0.0, GridBagConstraints.NORTH, GridBagConstraints.HORIZONTAL,
                                                 JBUI.emptyInsets(), 0, 0)
    grid.add(settingsComponent, settingsConstraints)

    addActionsRow(hintHint, hint, editor, actions, grid)
  }

  private fun addActionsRow(hintHint: HintHint,
                            hint: LightweightHint,
                            editor: Editor,
                            actions: ArrayList<AnAction>,
                            grid: JComponent) {
    if (fix != null) {
      val buttons = JPanel(FlowLayout(FlowLayout.CENTER, 0, 0))
      val wrapper = JPanel(BorderLayout())
      wrapper.background = hintHint.textBackground
      buttons.background = hintHint.textBackground
      wrapper.border = JBUI.Borders.empty()
      buttons.border = JBUI.Borders.empty()

      wrapper.add(buttons, BorderLayout.WEST)
      val runFixAction = Runnable {
        hint.hide()
        fix.execute(editor)
      }

      val shortcutRunActionText = getKeymap(IdeActions.ACTION_RUN_INTENTION_ACTION)
      val shortcutShowAllActionsText = getKeymap(IdeActions.ACTION_SHOW_INTENTION_ACTIONS)

      buttons.add(createActionLabel(fix.text, runFixAction, hintHint.textBackground))
      buttons.add(createHint(shortcutRunActionText))

      val showAllFixes = Runnable {
        hint.hide()
        fix.showAllActions(editor)
      }

      val spacing = JBLabel("  ")
      buttons.add(spacing)
      buttons.add(createActionLabel("More actions...", showAllFixes, hintHint.textBackground))
      buttons.add(createHint(shortcutShowAllActionsText))

      actions.add(object : AnAction() {
        override fun actionPerformed(e: AnActionEvent?) {
          runFixAction.run()
        }

        init {
          registerCustomShortcutSet(getActiveKeymapShortcuts(IdeActions.ACTION_RUN_INTENTION_ACTION), editor.contentComponent)
        }
      })

      actions.add(object : AnAction() {
        override fun actionPerformed(e: AnActionEvent?) {
          showAllFixes.run()
        }

        init {
          registerCustomShortcutSet(getActiveKeymapShortcuts(IdeActions.ACTION_SHOW_INTENTION_ACTIONS), editor.contentComponent)
        }
      })

      val buttonsConstraints = GridBagConstraints(0, 1, 2, 1, 0.0, 0.0, GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL,
                                                  JBUI.insetsTop(3), 0, 0)
      grid.add(wrapper, buttonsConstraints)
    }
  }

  private fun getKeymap(key: String): String {
    val keymapManager = KeymapManager.getInstance()
    if (keymapManager != null) {
      val keymap = keymapManager.activeKeymap
      return KeymapUtil.getShortcutsText(keymap.getShortcuts(key))
    }

    return ""
  }

  private fun createHint(shortcutRunAction: String): JBLabel {
    val fixHint = JBLabel(shortcutRunAction)
    UIUtil.applyStyle(UIUtil.ComponentStyle.SMALL, fixHint)
    fixHint.fontColor = UIUtil.FontColor.BRIGHTER
    return fixHint
  }

  override fun createRenderer(text: String, width: Int): LineTooltipRenderer {
    return DaemonTooltipWithActionRenderer(text, fix, width, equalityObjects)
  }

  override fun canAutoHideOn(event: TooltipEvent): Boolean {
    if (isOwnAction(event.action)) {
      return false
    }
    if (MenuSelectionManager.defaultManager().selectedPath.isNotEmpty()) {
      return false
    }

    val inputEvent = event.inputEvent
    if (inputEvent is MouseEvent) {
      val source = inputEvent.source
      if (source is ActionMenuItem && isOwnAction(source.anAction)) {
        return false
      }
    }


    return super.canAutoHideOn(event)
  }

  private fun isOwnAction(action: AnAction?) = action is ShowDocAction || action is ShowActionsAction || action is SettingsActionGroup

  private class SettingsActionGroup(actions: List<AnAction>) : DefaultActionGroup(actions), HintManagerImpl.ActionToIgnore, DumbAware {
    init {
      isPopup = true
    }
  }

  private fun createSettingsComponent(hint: LightweightHint,
                                      hintHint: HintHint,
                                      reloader: TooltipReloader): JComponent {
    val presentation = Presentation()
    presentation.icon = AllIcons.General.GearPlain
    val actions = mutableListOf<AnAction>()
    actions.add(ShowActionsAction(hint))
    val docAction = ShowDocAction(reloader)
    actions.add(docAction)
    val actionGroup = SettingsActionGroup(actions)

    val settingsButton = object : ActionButton(actionGroup, presentation, ActionPlaces.UNKNOWN, Dimension(18, 18)) {
      override fun paintComponent(g: Graphics) {
        g.color = hintHint.textBackground
        g.fillRect(0, 0, size.width, size.height)
        paintButtonLook(g)
      }
    }

    settingsButton.isOpaque = true
    settingsButton.setNoIconsInPopup(true)
    settingsButton.border = JBUI.Borders.empty()

    val wrapper = JPanel(BorderLayout())
    wrapper.add(settingsButton, BorderLayout.EAST)
    wrapper.border = JBUI.Borders.empty()
    wrapper.background = hintHint.textBackground

    return wrapper
  }

  private inner class ShowActionsAction(val hint: LightweightHint) : ToggleAction("Show Quick fixes"), HintManagerImpl.ActionToIgnore {

    override fun isSelected(e: AnActionEvent): Boolean {
      return isShowActions()
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
      setShowActions(state)
      hint.hide()
    }
  }

  private inner class ShowDocAction(val reloader: TooltipReloader) : ToggleAction(
    "Show Inspection Description"), HintManagerImpl.ActionToIgnore, DumbAware, PopupAction {

    init {
      shortcutSet = getActiveKeymapShortcuts(IdeActions.ACTION_SHOW_ERROR_DESCRIPTION)
    }

    override fun isSelected(e: AnActionEvent): Boolean {
      return myCurrentWidth > 0
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
      reloader.reload(state)
    }
  }

}




fun createActionLabel(text: String, action: Runnable, background: Color): HyperlinkLabel {
  val label = HyperlinkLabel(text, background)
  label.addHyperlinkListener(object : HyperlinkAdapter() {
    override fun hyperlinkActivated(e: HyperlinkEvent) {
      action.run()
    }
  })

  return label
}


