// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.model.search;

import com.intellij.model.SymbolReference;
import com.intellij.psi.search.SearchScope;
import com.intellij.util.AbstractQuery;
import com.intellij.util.Processor;
import com.intellij.util.Query;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

final class SymbolReferenceQueryImpl extends AbstractQuery<SymbolReference> implements SymbolReferenceQuery {

  private final SearchSymbolReferenceParameters myParameters;

  SymbolReferenceQueryImpl(@NotNull SearchSymbolReferenceParameters parameters) {
    myParameters = parameters;
  }

  @Override
  protected boolean processResults(@NotNull Processor<? super SymbolReference> consumer) {
    return getBaseQuery().forEach(consumer) &&
           SymbolSearchHelper.getInstance(myParameters.getProject()).runSearch(myParameters, consumer);
  }

  @Override
  @Contract(pure = true)
  @NotNull
  public SearchSymbolReferenceParameters getParameters() {
    return myParameters;
  }

  @Override
  @Contract(pure = true)
  @NotNull
  public Query<SymbolReference> getBaseQuery() {
    return SymbolReferenceSearch.INSTANCE.createQuery(myParameters);
  }

  @NotNull
  @Override
  public SymbolReferenceQuery inScope(@NotNull SearchScope scope) {
    if (myParameters.getOriginalSearchScope().equals(scope)) {
      return this;
    }
    return new SymbolReferenceQueryImpl(new DefaultSymbolReferenceSearchParameters(
      myParameters.getProject(),
      myParameters.getTarget(),
      scope,
      myParameters.isIgnoreUseScope()
    ));
  }

  @NotNull
  @Override
  public SymbolReferenceQuery ignoreUseScope() {
    if (myParameters.isIgnoreUseScope()) {
      return this;
    }
    return new SymbolReferenceQueryImpl(new DefaultSymbolReferenceSearchParameters(
      myParameters.getProject(),
      myParameters.getTarget(),
      myParameters.getOriginalSearchScope(),
      true
    ));
  }
}
