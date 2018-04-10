// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.model.search;

import com.intellij.model.ModelElement;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.search.SearchScope;
import org.jetbrains.annotations.NotNull;

public interface SearchWordRequestor {

  /**
   * Sets search scope.<br/>
   * If search scope is left unset then {@link SearchRequestCollector#getParameters() original} scope will be used.
   *
   * @return this object
   */
  @NotNull
  SearchWordRequestor setSearchScope(@NotNull SearchScope searchScope);

  /**
   * Restricts search scope to include only selected file types.
   *
   * @return this object
   */
  @NotNull
  SearchWordRequestor restrictSearchScopeTo(@NotNull FileType... fileTypes);

  /**
   * @return this object
   */
  @NotNull
  SearchWordRequestor setCaseSensitive(boolean caseSensitive);

  /**
   * @return this object
   * @see com.intellij.psi.search.UsageSearchContext
   */
  @NotNull
  SearchWordRequestor setSearchContext(short searchContext);

  /**
   * Sets target hint which is used for optimizing search requests.
   * Target is automatically set when using {@link #search(ModelElement)}.
   *
   * @return this object
   */
  @NotNull
  SearchWordRequestor setTargetHint(@NotNull ModelElement target);

  /**
   * Orders to search for word occurrences and process them with passed requestor. <br/>
   * {@link SearchRequestCollector} is passed into the requestor allowing to order more sub searches.
   */
  void searchRequests(@NotNull OccurenceSearchRequestor occurenceSearchRequestor);

  /**
   * Orders to search for references with word that resolve into target and pass them as is into result processor.
   *
   * @param target
   */
  void search(@NotNull ModelElement target);
}
