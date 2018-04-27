// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ui.layout.migLayout

import com.intellij.ui.components.noteComponent
import com.intellij.ui.layout.*
import com.intellij.util.containers.ContainerUtil
import net.miginfocom.layout.*
import net.miginfocom.layout.PlatformDefaults.VISUAL_PADDING_PROPERTY
import net.miginfocom.layout.PlatformDefaults.setDefaultVisualPadding
import net.miginfocom.swing.MigLayout
import java.awt.Component
import java.awt.Container
import javax.swing.ButtonGroup
import javax.swing.JDialog
import javax.swing.JLabel

internal class MigLayoutBuilder(val spacing: SpacingConfiguration) : LayoutBuilderImpl {
  companion object {
    init {
      // unset incorrect for our LaF values (todo add ability to provide own provider to MigLayout)
      setDefaultVisualPadding("Button.$VISUAL_PADDING_PROPERTY", null)
      setDefaultVisualPadding("Button.icon.$VISUAL_PADDING_PROPERTY", null)
      setDefaultVisualPadding("Button.square.$VISUAL_PADDING_PROPERTY", null)
      setDefaultVisualPadding("Button.square.icon.$VISUAL_PADDING_PROPERTY", null)
      setDefaultVisualPadding("Button.gradient.$VISUAL_PADDING_PROPERTY", null)
      setDefaultVisualPadding("Button.gradient.icon.$VISUAL_PADDING_PROPERTY", null)
      setDefaultVisualPadding("Button.bevel.$VISUAL_PADDING_PROPERTY", null)
      setDefaultVisualPadding("Button.bevel.icon.$VISUAL_PADDING_PROPERTY", null)
      setDefaultVisualPadding("Button.textured.$VISUAL_PADDING_PROPERTY", null)
      setDefaultVisualPadding("Button.textured.icon.$VISUAL_PADDING_PROPERTY", null)
      setDefaultVisualPadding("Button.roundRect.$VISUAL_PADDING_PROPERTY", null)
      setDefaultVisualPadding("Button.roundRect.icon.$VISUAL_PADDING_PROPERTY", null)
      setDefaultVisualPadding("Button.recessed.$VISUAL_PADDING_PROPERTY", null)
      setDefaultVisualPadding("Button.recessed.icon.$VISUAL_PADDING_PROPERTY", null)
      setDefaultVisualPadding("Button.help.$VISUAL_PADDING_PROPERTY", null)
      setDefaultVisualPadding("Button.help.icon.$VISUAL_PADDING_PROPERTY", null)

      PlatformDefaults.setDefaultVisualPadding("ComboBox.$VISUAL_PADDING_PROPERTY", null)
      PlatformDefaults.setDefaultVisualPadding("ComboBox.isPopDown.$VISUAL_PADDING_PROPERTY", null)
      PlatformDefaults.setDefaultVisualPadding("ComboBox.isSquare.$VISUAL_PADDING_PROPERTY", null)

      PlatformDefaults.setDefaultVisualPadding("ComboBox.editable.$VISUAL_PADDING_PROPERTY", null)
      PlatformDefaults.setDefaultVisualPadding("ComboBox.editable.isSquare.$VISUAL_PADDING_PROPERTY", null)

      PlatformDefaults.setDefaultVisualPadding("TextField.$VISUAL_PADDING_PROPERTY", null)
      PlatformDefaults.setDefaultVisualPadding("TabbedPane.$VISUAL_PADDING_PROPERTY", null)

      PlatformDefaults.setDefaultVisualPadding("Spinner.$VISUAL_PADDING_PROPERTY", null)

      PlatformDefaults.setDefaultVisualPadding("RadioButton.$VISUAL_PADDING_PROPERTY", null)
      PlatformDefaults.setDefaultVisualPadding("RadioButton.small.$VISUAL_PADDING_PROPERTY", null)
      PlatformDefaults.setDefaultVisualPadding("RadioButton.mini.$VISUAL_PADDING_PROPERTY", null)
      PlatformDefaults.setDefaultVisualPadding("CheckBox.$VISUAL_PADDING_PROPERTY", null)
      PlatformDefaults.setDefaultVisualPadding("CheckBox.small.$VISUAL_PADDING_PROPERTY", null)
      PlatformDefaults.setDefaultVisualPadding("CheckBox.mini.$VISUAL_PADDING_PROPERTY", null)
    }
  }

  /**
   * Map of component to constraints shared among rows (since components are unique)
   */
  private val componentConstraints: MutableMap<Component, CC> = ContainerUtil.newIdentityTroveMap()
  private val rootRow = MigLayoutRow(parent = null, componentConstraints = componentConstraints, builder = this, indent = 0)

  val defaultComponentConstraintCreator = DefaultComponentConstraintCreator(spacing)

  val columnConstraints = AC()

  override fun newRow(label: JLabel?, buttonGroup: ButtonGroup?, separated: Boolean): Row {
    return rootRow.createChildRow(label = label, buttonGroup = buttonGroup, separated = separated)
  }

  override fun noteRow(text: String, linkHandler: ((url: String) -> Unit)?) {
    val cc = CC()
    cc.vertical.gapBefore = gapToBoundSize(if (rootRow.subRows == null) spacing.verticalGap else spacing.largeVerticalGap, false)
    cc.vertical.gapAfter = gapToBoundSize(spacing.verticalGap * 2, false)

    val row = rootRow.createChildRow(label = null, noGrid = true)
    row.apply {
      val noteComponent = noteComponent(text, linkHandler)
      componentConstraints.put(noteComponent, cc)
      noteComponent()
    }
  }

  override fun build(container: Container, layoutConstraints: Array<out LCFlags>) {
//    if (spacing.isCompensateVisualPaddings) {
//      (container as JComponent).putClientProperty(JBUI.COMPENSATE_VISUAL_PADDING_KEY, false)
//    }

    val lc = LC()
    lc.gridGapX = gapToBoundSize(0, true)
    lc.gridGapY = gapToBoundSize(spacing.verticalGap, false)
    lc.insets("0px")
    if (layoutConstraints.isEmpty()) {
      lc.fillX()
      // not fillY because it leads to enormously large cells - we use cc `push` in addition to cc `grow` as a more robust and easy solution
    }
    else {
      lc.apply(layoutConstraints)
    }

    lc.isVisualPadding = spacing.isCompensateVisualPaddings
    lc.hideMode = 3

    if (rootRow.subRows!!.any { it.isLabeledIncludingSubRows }) {
      // using columnConstraints instead of component gap allows easy debug (proper painting of debug grid)
      columnConstraints.gap("${spacing.labelColumnHorizontalGap}px!", 0)
    }

    for (i in 1 until columnConstraints.count) {
      columnConstraints.gap("${spacing.horizontalGap}px!", i)
    }

    // if constraint specified only for rows 0 and 1, MigLayout will use constraint 1 for any rows with index 1+ (see LayoutUtil.getIndexSafe - use last element if index > size)
    val rowConstraints = AC()
    rowConstraints.align("top")

    var isLayoutInsetsAdjusted = false
    container.layout = object : MigLayout(lc, columnConstraints, rowConstraints) {
      override fun layoutContainer(parent: Container) {
        if (!isLayoutInsetsAdjusted) {
          isLayoutInsetsAdjusted = true

          var topParent = parent.parent
          while (topParent != null) {
            if (topParent is JDialog) {
              val topBottom = createUnitValue(spacing.dialogTopBottom, false)
              val leftRight = createUnitValue(spacing.dialogLeftRight, true)
              // since we compensate visual padding, child components should be not clipped, so, we do not use content pane DialogWrapper border (returns null),
              // but instead set insets to our content panel (so, child components are not clipped)
              lc.insets = arrayOf(topBottom, leftRight, topBottom, leftRight)
              break
            }
            topParent = topParent.parent
          }
        }

        super.layoutContainer(parent)
      }
    }

    val isNoGrid = layoutConstraints.contains(LCFlags.noGrid)

    var rowIndex = 0
    fun configureComponents(row: MigLayoutRow) {
      val lastComponent = row.components.lastOrNull()
      for ((index, component) in row.components.withIndex()) {
        // MigLayout in any case always creates CC, so, create instance even if it is not required
        val cc = componentConstraints.get(component) ?: CC()

        if (isNoGrid) {
          container.add(component, cc)
          continue
        }

        // we cannot use columnCount as an indicator of whether to use spanX/wrap or not because component can share cell with another component,
        // in any case MigLayout is smart enough and unnecessary spanX/wrap doesn't harm
        if (component === lastComponent) {
          cc.spanX()
          cc.wrap()
        }

        if (row.noGrid) {
          if (component === row.components.first()) {
            rowConstraints.noGrid(rowIndex)
          }
        }
        else if (component === row.components.first()) {
          row.gapAfter?.let {
            rowConstraints.gap(it, rowIndex)
          }
        }

        if (index >= row.rightIndex) {
          cc.horizontal.gapBefore = BoundSize(null, null, null, true, null)
        }

        container.add(component, cc)
      }

      rowIndex++
    }

    fun processRows(rows: List<MigLayoutRow>) {
      for (row in rows) {
        configureComponents(row)
        row.subRows?.let {
          processRows(it)
        }
      }
    }

    rootRow.subRows?.let {
      processRows(it)
    }

    // do not hold components
    componentConstraints.clear()
  }
}

internal fun gapToBoundSize(value: Int, isHorizontal: Boolean): BoundSize {
  val unitValue = createUnitValue(value, isHorizontal)
  return BoundSize(unitValue, unitValue, null, false, null)
}

private fun createUnitValue(value: Int, isHorizontal: Boolean): UnitValue {
  return UnitValue(value.toFloat(), "px", isHorizontal, UnitValue.STATIC, null)
}

private fun LC.apply(flags: Array<out LCFlags>): LC {
  for (flag in flags) {
    when (flag) {
      LCFlags.noGrid -> isNoGrid = true

      LCFlags.flowY -> isFlowX = false

      LCFlags.fill -> fill()
      LCFlags.fillX -> isFillX = true
      LCFlags.fillY -> isFillY = true

      LCFlags.lcWrap -> wrapAfter = 0

      LCFlags.debug -> debug()
    }
  }
  return this
}