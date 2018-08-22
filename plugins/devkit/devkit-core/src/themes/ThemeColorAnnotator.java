// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.idea.devkit.themes;

import com.intellij.codeInsight.daemon.LineMarkerSettings;
import com.intellij.json.psi.JsonStringLiteral;
import com.intellij.lang.annotation.Annotation;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import com.intellij.ui.ColorLineMarkerProvider;
import com.intellij.util.ui.ColorIcon;
import com.intellij.util.ui.EmptyIcon;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.Objects;
import java.util.regex.Pattern;

public class ThemeColorAnnotator implements Annotator {
  private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("^#([A-Fa-f0-9]{6})$");


  @Override
  public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
    if (!isColorLineMarkerProviderEnabled() || !isTargetElement(element)) return;

    Annotation annotation = holder.createInfoAnnotation(element, null);
    JsonStringLiteral literal = (JsonStringLiteral)element;
    annotation.setGutterIconRenderer(new MyRenderer(literal.getValue(), literal));
  }

  private static boolean isColorLineMarkerProviderEnabled() {
    return LineMarkerSettings.getSettings().isEnabled(new ColorLineMarkerProvider());
  }

  //TODO review/rework
  private static boolean isTargetElement(@NotNull PsiElement element) {
    if (!(element instanceof JsonStringLiteral)) return false;

    String text = ((JsonStringLiteral)element).getValue();
    if (StringUtil.isEmpty(text)) return false;
    if (!text.startsWith("#")) return false;
    int length = text.length();
    if (length != 7) return false; // '#FFFFFF'
    if (!HEX_COLOR_PATTERN.matcher(text).matches()) return false;

    return true;
  }

  private static class MyRenderer extends GutterIconRenderer {
    private static final int ICON_SIZE = 8;

    private final String myColorHex;
    private final JsonStringLiteral myLiteral;

    private MyRenderer(String colorHex, JsonStringLiteral literal) {
      myColorHex = colorHex;
      myLiteral = literal;
    }

    @NotNull
    @Override
    public Icon getIcon() {
      try {
        //TODO support other color formats?
        Color color = Color.decode(myColorHex);
        return JBUI.scale(new ColorIcon(ICON_SIZE, color));
      } catch (NumberFormatException ignore) {
        return JBUI.scale(EmptyIcon.create(ICON_SIZE));
      }
    }

    @Nullable
    @Override
    public AnAction getClickAction() {
      return new AnAction() {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
          //TODO implement
        }
      };
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      MyRenderer renderer = (MyRenderer)o;
      return myColorHex.equals(renderer.myColorHex) &&
             myLiteral.equals(renderer.myLiteral);
    }

    @Override
    public int hashCode() {
      return Objects.hash(myColorHex, myLiteral);
    }
  }
}
