/*
 * Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */
package com.intellij.ide.actions.searcheverywhere;

import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.progress.ProgressIndicator;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * @author Konstantin Bulenkov
 */
//todo generic? #UX-1
public interface SearchEverywhereContributor<F> {

  ExtensionPointName<SearchEverywhereContributorFactory<?>> EP_NAME = ExtensionPointName.create("com.intellij.searchEverywhereContributor");

  @NotNull
  String getSearchProviderId();

  @NotNull
  String getGroupName();

  String includeNonProjectItemsText();

  int getSortWeight();

  boolean showInFindResults();

  default boolean isShownInSeparateTab() {
    return false;
  }

  default int getElementPriority(Object element, String searchPattern) {
    return 0;
  }

  void fetchElements(String pattern, boolean everywhere, SearchEverywhereContributorFilter<F> filter,
                     ProgressIndicator progressIndicator, Function<Object, Boolean> consumer);

  default ContributorSearchResult<Object> search(String pattern, boolean everywhere, SearchEverywhereContributorFilter<F> filter,
                                         ProgressIndicator progressIndicator, int elementsLimit) {
    ContributorSearchResult.Builder<Object> builder = ContributorSearchResult.builder();
    fetchElements(pattern, everywhere, filter, progressIndicator, element -> {
      if (elementsLimit < 0 || builder.itemsCount() < elementsLimit) {
        builder.addItem(element);
        return true;
      }
      else {
        builder.setHasMore(true);
        return false;
      }
    });

    return builder.build();
  }

  default List<Object> search(String pattern, boolean everywhere, SearchEverywhereContributorFilter<F> filter,
                              ProgressIndicator progressIndicator) {
    List<Object> res = new ArrayList<>();
    fetchElements(pattern, everywhere, filter, progressIndicator, o -> res.add(o));
    return res;
  }

  boolean processSelectedItem(Object selected, int modifiers, String searchText);

  ListCellRenderer getElementsRenderer(JList<?> list);

  Object getDataForItem(Object element, String dataId);

  default String filterControlSymbols(String pattern) {
    return pattern;
  }

  default boolean isMultiselectSupported() {
    return false;
  }

  default boolean isDumbModeSupported() {
    return true;
  }

  static List<SearchEverywhereContributorFactory<?>> getProviders() {
    return Arrays.asList(EP_NAME.getExtensions());
  }
}
