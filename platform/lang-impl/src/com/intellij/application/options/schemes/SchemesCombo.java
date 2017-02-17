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
package com.intellij.application.options.schemes;

import com.intellij.openapi.options.Scheme;
import com.intellij.openapi.options.SchemeManager;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.Collection;
import java.util.function.Predicate;

public abstract class SchemesCombo<T extends Scheme> extends ComboBox<SchemesCombo.MySchemeListItem<T>> {
  public static final String PROJECT_LEVEL = "Project";
  public static final String IDE_LEVEL = "IDE";

  public SchemesCombo() {
    super(new MyComboBoxModel<>());
    setRenderer(new MyListCellRenderer());
  }

  public void resetSchemes(@NotNull Collection<T> schemes) {
    final MyComboBoxModel<T> model = (MyComboBoxModel<T>)getModel();
    model.removeAllElements();
    if (supportsProjectSchemes()) {
      model.addElement(new MySeparatorItem(PROJECT_LEVEL));
      addItems(schemes, scheme -> isProjectScheme(scheme));
      model.addElement(new MySeparatorItem(IDE_LEVEL));
      addItems(schemes, scheme -> !isProjectScheme(scheme));
    }
    else {
      addItems(schemes, scheme -> true);
    }
  }

  public void selectScheme(@Nullable T scheme) {
    for (int i = 0; i < getItemCount(); i ++) {
      if (getItemAt(i).getScheme() == scheme) {
        setSelectedIndex(i);
        break;
      }
    }
  }

  @Nullable
  public T getSelectedScheme() {
    SchemesCombo.MySchemeListItem<T> item = getSelectedItem();
    return item != null ? item.getScheme() : null;
  }

  @Nullable
  public SchemesCombo.MySchemeListItem<T> getSelectedItem() {
    int i = getSelectedIndex();
    return i >= 0 ? getItemAt(i) : null;
  }

  protected abstract boolean supportsProjectSchemes();

  protected boolean isProjectScheme(@NotNull T scheme) {
    throw new UnsupportedOperationException();
  }

  @NotNull
  protected abstract SimpleTextAttributes getSchemeAttributes(T scheme);

  private void addItems(@NotNull Collection<T> schemes, Predicate<T> filter) {
    for (T scheme : schemes) {
      if (filter.test(scheme)) {
        ((MyComboBoxModel<T>) getModel()).addElement(new MySchemeListItem<>(scheme));
      }
    }
  }

  static class MySchemeListItem<T extends Scheme> {
    private @Nullable T myScheme;

    public MySchemeListItem(@Nullable T scheme) {
      myScheme = scheme;
    }

    @Nullable
    public String getSchemeName() {
      return myScheme != null ? myScheme.getName() : null;
    }

    @Nullable
    public T getScheme() {
      return myScheme;
    }

    @NotNull
    public String getPresentableText() {
      return myScheme != null ? SchemeManager.getDisplayName(myScheme) : "";
    }

    public boolean isSeparator() {
      return false;
    }
  }

  private class MyListCellRenderer extends ColoredListCellRenderer<MySchemeListItem<T>> {
    private ListCellRendererWrapper<MySchemeListItem> myWrapper = new ListCellRendererWrapper<MySchemeListItem>() {
      @Override
      public void customize(JList list,
                            MySchemeListItem value,
                            int index,
                            boolean selected,
                            boolean hasFocus) {
        if (value.isSeparator()) {
          setText(" Stored in " + value.getPresentableText());
          setSeparator();
        }
      }
    };

    @Override
    public Component getListCellRendererComponent(JList<? extends MySchemeListItem<T>> list,
                                                  MySchemeListItem<T> value,
                                                  int index,
                                                  boolean selected,
                                                  boolean hasFocus) {
      if (value.isSeparator()) {
        Component c = myWrapper.getListCellRendererComponent(list, value, index, selected, hasFocus);
        if (c instanceof TitledSeparator) {
          ((TitledSeparator)c).getLabel().setForeground(JBColor.GRAY);
          return c;
        }
      }
      return super.getListCellRendererComponent(list, value, index, selected, hasFocus);
    }

    @Override
    protected void customizeCellRenderer(@NotNull JList<? extends MySchemeListItem<T>> list,
                                         MySchemeListItem<T> value,
                                         int index,
                                         boolean selected,
                                         boolean hasFocus) {
      T scheme = value.getScheme();
      if (scheme != null) {
        append(value.getPresentableText(), getSchemeAttributes(scheme));
        if (supportsProjectSchemes()) {
          if (index == -1) {
            append("  " + (isProjectScheme(scheme) ? PROJECT_LEVEL : IDE_LEVEL),
                   SimpleTextAttributes.GRAY_ATTRIBUTES);
          }
        }
      }
    }
  }

  private static class MyComboBoxModel<T extends Scheme> extends DefaultComboBoxModel<MySchemeListItem<T>> {
    @Override
    public void setSelectedItem(Object anObject) {
      if (anObject instanceof SchemesCombo.MySchemeListItem && ((MySchemeListItem)anObject).isSeparator()) {
        return;
      }
      super.setSelectedItem(anObject);
    }
  }

  private class MySeparatorItem extends MySchemeListItem<T> {

    private String myTitle;

    public MySeparatorItem(@NotNull String title) {
      super(null);
      myTitle = title;
    }

    @Override
    public boolean isSeparator() {
      return true;
    }

    @NotNull
    @Override
    public String getPresentableText() {
      return myTitle;
    }
  }
}
