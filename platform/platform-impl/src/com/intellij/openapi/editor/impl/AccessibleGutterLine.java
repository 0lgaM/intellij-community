// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.editor.impl;

import com.intellij.codeInsight.daemon.GutterMark;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.TextAnnotationGutterProvider;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.editor.actionSystem.EditorActionManager;
import com.intellij.openapi.editor.markup.ActiveGutterRenderer;
import com.intellij.openapi.editor.markup.LineMarkerRenderer;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.ui.JBColor;
import com.intellij.ui.paint.LinePainter2D;
import com.intellij.util.Consumer;
import com.intellij.util.ObjectUtils;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.accessibility.SimpleAccessible;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.accessibility.AccessibleContext;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;

/**
 * A panel which provides a11y for the current line in a gutter.
 * The panel contains {@link AccessibleGutterElement} components each of them represents a particular element in the gutter.
 * The panel can be shown and focused via {@code EditorFocusGutter} action.
 *
 * @author tav
 */
class AccessibleGutterLine extends JPanel {
  private final EditorGutterComponentImpl myGutter;
  private AccessibleGutterElement mySelectedElement;
  // [tav] todo: soft-wrap doesn't work correctly
  private final int myLogicalLineNum;
  private final int myVisualLineNum;

  public static AccessibleGutterLine createAndActivate(@NotNull EditorGutterComponentImpl gutter) {
    return new AccessibleGutterLine(gutter);
  }

  public void escape(boolean requestFocusToEditor) {
    myGutter.remove(this);
    myGutter.repaint();
    myGutter.setCurrentAccessibleLine(null);
    if (requestFocusToEditor) {
      IdeFocusManager.getGlobalInstance().requestFocus(myGutter.getEditor().getContentComponent(), true);
    }
  }

  @Override
  public void paint(Graphics g) {
    mySelectedElement.paint(g);
  }

  public static void installListeners(@NotNull EditorGutterComponentImpl gutter) {
    installActionHandler(IdeActions.ACTION_EDITOR_ESCAPE, true, (line) -> line.escape(true));
    installActionHandler(IdeActions.ACTION_EDITOR_MOVE_CARET_RIGHT, false, (line) -> line.moveRight());
    installActionHandler(IdeActions.ACTION_EDITOR_MOVE_CARET_LEFT, false, (line) -> line.moveLeft());
    installActionHandler(IdeActions.ACTION_EDITOR_MOVE_CARET_UP, true, (line) -> line.maybeLineChanged());
    installActionHandler(IdeActions.ACTION_EDITOR_MOVE_CARET_DOWN, true, (line) -> line.maybeLineChanged());
    installActionHandler(IdeActions.ACTION_EDITOR_ENTER, false, (line) -> {});
    installActionHandler("EditorShowGutterIconTooltip", false, (line) -> line.showTooltipIfPresent());

    gutter.addFocusListener(new FocusAdapter() {
      @Override
      public void focusGained(FocusEvent e) {
        gutter.setCurrentAccessibleLine(createAndActivate(gutter));
      }
    });
    gutter.addComponentListener(new ComponentAdapter() {
      @Override
      public void componentResized(ComponentEvent e) {
        gutter.escapeCurrentAccessibleLine(true);
      }
    });
  }

  private static void installActionHandler(String actionId, boolean propagate, Consumer<AccessibleGutterLine> action) {
    EditorActionManager.getInstance().setActionHandler(actionId, new EditorActionHandler() {
      private final EditorActionHandler origHandler = EditorActionManager.getInstance().getActionHandler(actionId);
      @Override
      protected void doExecute(@NotNull Editor editor, @Nullable Caret caret, DataContext dataContext) {
        AccessibleGutterLine line = ((EditorGutterComponentImpl)editor.getGutter()).getCurrentAccessibleLine();
        if (propagate || line == null) {
          origHandler.execute(editor, caret, dataContext);
        }
        if (line != null) {
          action.consume(line);
        }
      }
    });
  }

  private void moveRight() {
    IdeFocusManager.getGlobalInstance().requestFocus(getFocusTraversalPolicy().getComponentAfter(this, mySelectedElement), true);
  }

  private void moveLeft() {
    IdeFocusManager.getGlobalInstance().requestFocus(getFocusTraversalPolicy().getComponentBefore(this, mySelectedElement), true);
  }

  public void showTooltipIfPresent() {
    mySelectedElement.showTooltip();
  }

  private AccessibleGutterLine(@NotNull EditorGutterComponentImpl gutter) {
    super(null);

    EditorImpl editor = gutter.getEditor();
    int lineHeight = editor.getLineHeight();

    myGutter = gutter;
    myLogicalLineNum = editor.getCaretModel().getPrimaryCaret().getLogicalPosition().line;
    myVisualLineNum = editor.getCaretModel().getPrimaryCaret().getVisualPosition().line;

    /* line numbers */
    if (myGutter.isLineNumbersShown()) {
      addNewElement(new SimpleAccessible() {
        @NotNull
        @Override
        public String getAccessibleName() {
          return "line " + String.valueOf(myLogicalLineNum + 1);
        }
        @Override
        public String getAccessibleTooltipText() {
          return null;
        }
      }, myGutter.getLineNumberAreaOffset(), 0, myGutter.getLineNumberAreaWidth(), lineHeight);
    }

    /* annotations */
    if (myGutter.isAnnotationsShown()) {
      int x = myGutter.getAnnotationsAreaOffset();
      int width = 0;
      String tooltipText = null;
      StringBuilder buf = new StringBuilder("annotation: ");
      for (int i = 0; i < myGutter.myTextAnnotationGutters.size(); i++) {
        TextAnnotationGutterProvider gutterProvider = myGutter.myTextAnnotationGutters.get(i);
        if (tooltipText == null) tooltipText = gutterProvider.getToolTip(myLogicalLineNum, editor); // [tav] todo: take first non-null?
        int annotationSize = myGutter.myTextAnnotationGutterSizes.get(i);
        buf.append(ObjectUtils.notNull(gutterProvider.getLineText(myLogicalLineNum, editor), ""));
        width += annotationSize;
      }
      if (buf.length() > 0) {
        String tt = tooltipText;
        addNewElement(new SimpleAccessible() {
          @NotNull
          @Override
          public String getAccessibleName() {
            return buf.toString();
          }
          @Override
          public String getAccessibleTooltipText() {
            return tt;
          }
        }, x, 0, width, lineHeight);
      }
    }

    /* icons */
    if (myGutter.areIconsShown()) {
      List<GutterMark> row = myGutter.getGutterRenderers(myVisualLineNum);
      if (row != null) {
        myGutter.processIconsRow(myVisualLineNum, row, (x, y, renderer) -> {
          Icon icon = myGutter.scaleIcon(renderer.getIcon());
          addNewElement(new SimpleAccessible() {
            @NotNull
            @Override
            public String getAccessibleName() {
              if (renderer instanceof SimpleAccessible) {
                return ((SimpleAccessible)renderer).getAccessibleName();
              }
              return "icon: " + renderer.getClass().getSimpleName();
            }
            @Override
            public String getAccessibleTooltipText() {
              return renderer.getTooltipText();
            }
          }, x, 0, icon.getIconWidth(), lineHeight);
        });
      }
    }

    /* active markers */
    if (myGutter.isLineMarkersShown()) {
      int firstVisibleOffset = editor.visualLineStartOffset(myVisualLineNum);
      int lastVisibleOffset = editor.visualLineStartOffset(myVisualLineNum + 1);
      myGutter.processRangeHighlighters(firstVisibleOffset, lastVisibleOffset, highlighter -> {
        LineMarkerRenderer renderer = highlighter.getLineMarkerRenderer();
        if (renderer instanceof ActiveGutterRenderer) {
          Rectangle rect = myGutter.getLineRendererRectangle(highlighter);
          if (rect != null) {
            Rectangle bounds = ((ActiveGutterRenderer)renderer).calcBounds(editor, myVisualLineNum, rect);
            if (bounds != null) {
              addNewElement((ActiveGutterRenderer)renderer, bounds.x, 0, bounds.width, bounds.height);
            }
          }
        }
      });
    }

    setOpaque(false);
    setFocusable(false);
    setFocusCycleRoot(true);
    setFocusTraversalPolicyProvider(true);
    setFocusTraversalPolicy(new LayoutFocusTraversalPolicy());
    setBounds(0, editor.visibleLineToY(myVisualLineNum), myGutter.getWhitespaceSeparatorOffset(), lineHeight);

    myGutter.add(this);
    mySelectedElement = (AccessibleGutterElement)getFocusTraversalPolicy().getFirstComponent(this);
    if (mySelectedElement == null) {
      Rectangle b = getBounds(); // set above
      mySelectedElement = addNewElement(new SimpleAccessible() {
        @NotNull
        @Override
        public String getAccessibleName() {
          return "empty";
        }
        @Override
        public String getAccessibleTooltipText() {
          return null;
        }
      }, 0, 0, b.width, b.height);
    }

    IdeFocusManager.getGlobalInstance().requestFocus(mySelectedElement, true);
  }

  @SuppressWarnings("SameParameterValue")
  @NotNull
  private AccessibleGutterElement addNewElement(@NotNull SimpleAccessible accessible, int x, int y, int width, int height) {
    AccessibleGutterElement obj = new AccessibleGutterElement(accessible, new Rectangle(x, y, width, height));
    add(obj);
    return obj;
  }

  @Override
  public void removeNotify() {
    repaint();
    super.removeNotify();
  }

  private void maybeLineChanged() {
    if (myLogicalLineNum == myGutter.getEditor().getCaretModel().getPrimaryCaret().getLogicalPosition().line) return;

    myGutter.remove(this);
    myGutter.setCurrentAccessibleLine(createAndActivate(myGutter));
  }

  @Override
  public void repaint() {
    if (myGutter == null) return;
    int y = myGutter.getEditor().visibleLineToY(myVisualLineNum);
    myGutter.repaint(0, y, myGutter.getWhitespaceSeparatorOffset(), y + myGutter.getEditor().getLineHeight());
  }

  private boolean isActive() {
    return this == myGutter.getCurrentAccessibleLine();
  }

  public static boolean isAccessibleGutterElement(Object element) {
    return element instanceof SimpleAccessible;
  }

  /**
   * A component which represents a particular element in the gutter like a line number, an icon, a marker, etc.
   * The component is transparent, placed above the element and is made focusable. It's possible to navigate the
   * components in the current gutter line via the left/right arrow keys. Also, it's possible to show a tooltip
   * (when available) above an element via {@code EditorShowGutterIconTooltip} action.
   *
   * @author tav
   */
  private class AccessibleGutterElement extends JLabel {
    private @NotNull final SimpleAccessible myAccessible;

    AccessibleGutterElement(@NotNull SimpleAccessible accessible, @NotNull Rectangle bounds) {
      myAccessible = accessible;

      setFocusable(true);
      setBounds(bounds.x, bounds.y, bounds.width, myGutter.getEditor().getLineHeight());
      setOpaque(false);

      setText(myAccessible.getAccessibleName());

      addFocusListener(new FocusListener() {
        @Override
        public void focusGained(FocusEvent e) {
          mySelectedElement = AccessibleGutterElement.this;
          getParent().repaint();
        }
        @Override
        public void focusLost(FocusEvent e) {
          if (!(e.getOppositeComponent() instanceof AccessibleGutterElement) && isActive()) {
            escape(false);
          }
        }
      });
    }

    @Override
    public void paint(Graphics g) {
      if (mySelectedElement != this) return;

      Color oldColor = g.getColor();
      try {
        g.setColor(JBColor.blue);
        Point parentLoc = getParent().getLocation();
        Rectangle bounds = getBounds();
        bounds.setLocation(parentLoc.x + bounds.x, parentLoc.y + bounds.y);
        int y = bounds.y + bounds.height - JBUI.scale(1);
        LinePainter2D.paint((Graphics2D)g, bounds.x, y, bounds.x + bounds.width, y,
                            LinePainter2D.StrokeType.INSIDE, JBUI.scale(1));
      } finally {
        g.setColor(oldColor);
      }
    }

    public void showTooltip() {
      if (myAccessible.getAccessibleTooltipText() != null) {
        Rectangle bounds = getBounds();
        Rectangle pBounds = getParent().getBounds();
        int x = pBounds.x + bounds.x + bounds.width / 2;
        int y = pBounds.y + bounds.y + bounds.height / 2;
        MouseEvent e = new MouseEvent(myGutter, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, x, y, 0, false, MouseEvent.BUTTON1);
        myGutter.tooltipAvailable(myAccessible.getAccessibleTooltipText(), e, null);
      }
    }

    @Override
    public AccessibleContext getAccessibleContext() {
      if (accessibleContext == null) {
        accessibleContext = new AccessibleJLabel() {
          @Override
          public String getAccessibleName() {
            return getText();
          }
        };
      }
      return accessibleContext;
    }
  }
}
