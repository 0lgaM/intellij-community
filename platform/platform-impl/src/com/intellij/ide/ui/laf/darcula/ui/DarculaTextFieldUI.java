// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.ui.laf.darcula.ui;

import com.intellij.ide.ui.laf.darcula.DarculaUIUtil;
import com.intellij.util.ui.*;

import javax.swing.*;
import javax.swing.plaf.ComponentUI;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/**
 * @author Konstantin Bulenkov
 */
public class DarculaTextFieldUI extends TextFieldWithPopupHandlerUI {
  @SuppressWarnings({"MethodOverridesStaticMethodOfSuperclass", "UnusedDeclaration"})
  public static ComponentUI createUI(JComponent c) {
    return new DarculaTextFieldUI();
  }

  // real height without visual paddings
  protected int getMinimumHeightForTextField() {
    return DarculaUIUtil.DARCULA_INPUT_HEIGHT;
  }

  @Override
  protected int getMinimumHeight() {
    Insets i = getComponent().getInsets();
    JComponent c = getComponent();
    if (DarculaEditorTextFieldBorder.isComboBoxEditor(c) || UIUtil.getParentOfType(JSpinner.class, c) != null) {
      return JBUI.scale(JBUI.getInt("TextFieldUI.spinnerOrComboboxEditorHeight", 22));
    }
    else {
      return JBUI.scale(JBUI.isUseCorrectInputHeight(c) ? getMinimumHeightForTextField() : 22) + i.top + i.bottom;
    }
  }

  @Override
  protected Icon getSearchIcon(boolean hovered, boolean clickable) {
    return IconCache.getIcon(clickable ? "searchWithHistory" : "search");
  }

  @Override
  protected Icon getClearIcon(boolean hovered, boolean clickable) {
    return !clickable ? null : IconCache.getIcon("clear");
  }

  @Override
  protected int getClearIconPreferredSpace() {
    return super.getClearIconPreferredSpace() - getClearIconGap();
  }

  @Override
  protected void paintBackground(Graphics g) {
    JTextComponent component = getComponent();
    if (component != null) {
      Container parent = component.getParent();
      if (parent != null && component.isOpaque()) {
        g.setColor(parent.getBackground());
        g.fillRect(0, 0, component.getWidth(), component.getHeight());
      }

      if (component.getBorder() instanceof DarculaTextBorder) {
        paintDarculaBackground(g, component);
      } else if (component.isOpaque()) {
        super.paintBackground(g);
      }
    }
  }

  @Override
  protected void updatePreferredSize(JComponent c, Dimension size) {
    super.updatePreferredSize(c, size);
    Insets i = c.getInsets();
    size.width += i.left + i.right;
  }

  protected void paintDarculaBackground(Graphics g, JTextComponent component) {
    Graphics2D g2 = (Graphics2D)g.create();
    Rectangle r = new Rectangle(component.getSize());
    JBInsets.removeFrom(r, JBUI.insets(1));

    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                          MacUIUtil.USE_QUARTZ ? RenderingHints.VALUE_STROKE_PURE : RenderingHints.VALUE_STROKE_NORMALIZE);

      g2.translate(r.x, r.y);

      float arc = isSearchField(component) ? JBUI.scale(6f) : 0.0f;
      float bw = bw();

      if (component.isEnabled() && component.isEditable()) {
        g2.setColor(component.getBackground());
      }

      g2.fill(new RoundRectangle2D.Float(bw, bw, r.width - bw * 2, r.height - bw * 2, arc, arc));
    } finally {
      g2.dispose();
    }
  }

  protected float bw() {
    return DarculaUIUtil.bw();
  }
}
