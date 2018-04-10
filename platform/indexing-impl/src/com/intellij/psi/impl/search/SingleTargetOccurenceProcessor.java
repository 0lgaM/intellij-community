// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.psi.impl.search;

import com.intellij.model.ModelElement;
import com.intellij.model.ModelReference;
import com.intellij.model.ModelService;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceService;
import com.intellij.psi.PsiReferenceService.Hints;
import com.intellij.psi.ReferenceRange;
import com.intellij.psi.search.TextOccurenceProcessor;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;

import java.util.List;

final class SingleTargetOccurenceProcessor implements TextOccurenceProcessor {

  private static final PsiReferenceService ourReferenceService = PsiReferenceService.getService();
  private final ModelElement myTarget;
  private final Processor<? super ModelReference> myProcessor;

  SingleTargetOccurenceProcessor(@NotNull ModelElement target, @NotNull Processor<? super ModelReference> processor) {
    myTarget = target;
    myProcessor = processor;
  }

  @Override
  public boolean execute(@NotNull PsiElement element, int offsetInElement) {
    if (!myTarget.isValid()) return false;
    final Hints hints = new Hints(ModelService.getPsiElement(myTarget), offsetInElement);
    final List<PsiReference> references = ourReferenceService.getReferences(element, hints);
    for (PsiReference reference : references) {
      ProgressManager.checkCanceled();
      if (!ReferenceRange.containsOffsetInElement(reference, offsetInElement)) continue;
      if (!reference.references(myTarget)) continue;
      if (!myProcessor.process(reference)) return false;
    }
    return true;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    SingleTargetOccurenceProcessor processor = (SingleTargetOccurenceProcessor)o;

    if (!myTarget.equals(processor.myTarget)) return false;
    if (!myProcessor.equals(processor.myProcessor)) return false;

    return true;
  }

  @Override
  public int hashCode() {
    int result = myTarget.hashCode();
    result = 31 * result + myProcessor.hashCode();
    return result;
  }
}
