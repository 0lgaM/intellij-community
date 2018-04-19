// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.idea.devkit.inspections;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.util.SpecialAnnotationsUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.siyeh.ig.ui.ExternalizableStringSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.devkit.DevKitBundle;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class UnstableApiUsageInspection extends LocalInspectionTool {
  public final List<String> unstableApiAnnotations = new ExternalizableStringSet(
    "org.jetbrains.annotations.ApiStatus.Experimental",
    "com.google.common.annotations.Beta",
    "io.reactivex.annotations.Beta",
    "io.reactivex.annotations.Experimental"
  );

  @Nullable
  @Override
  public JComponent createOptionsPanel() {
    JPanel panel = new JPanel(new GridBagLayout());
    //TODO in add annotation window "Include non-project items" should be enabled by default
    JPanel annotationsListControl = SpecialAnnotationsUtil.createSpecialAnnotationsListControl(
      unstableApiAnnotations, DevKitBundle.message("inspections.unstable.api.usage.annotations.list"));
    GridBagConstraints constraints = new GridBagConstraints();
    constraints.gridx = 0;
    constraints.gridy = 0;
    constraints.weighty = 1.0;
    constraints.weightx = 1.0;
    constraints.anchor = GridBagConstraints.CENTER;
    constraints.fill = GridBagConstraints.BOTH;
    panel.add(annotationsListControl, constraints);
    return panel;
  }

  @NotNull
  @Override
  public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
    return new PsiElementVisitor() {
      @Override
      public void visitElement(PsiElement element) {
        super.visitElement(element);

        // Java constructors must be handled a bit differently (works fine with Kotlin)
        PsiMethod resolvedConstructor = null;
        PsiElement elementParent = element.getParent();
        if (elementParent instanceof PsiConstructorCall) {
          resolvedConstructor = ((PsiConstructorCall)elementParent).resolveConstructor();
        }

        for (PsiReference reference : element.getReferences()) {
          PsiElement resolvedElement = resolvedConstructor != null ? resolvedConstructor : reference.resolve();
          if (!(resolvedElement instanceof PsiModifierListOwner) || !isLibraryElement(resolvedElement)) {
            continue;
          }

          PsiModifierListOwner modifierListOwner = (PsiModifierListOwner)resolvedElement;
          for (String annotation : unstableApiAnnotations) {
            if (modifierListOwner.hasAnnotation(annotation)) {
              holder.registerProblem(reference,
                                     DevKitBundle.message("inspections.unstable.api.usage.description", getReferenceText(reference)),
                                     ProblemHighlightType.GENERIC_ERROR_OR_WARNING);
            }
          }
        }
      }
    };
  }

  private static boolean isLibraryElement(@NotNull PsiElement element) {
    if (ApplicationManager.getApplication().isUnitTestMode()) {
      return true;
    }

    PsiFile containingPsiFile = element.getContainingFile();
    if (containingPsiFile == null) {
      return false;
    }
    VirtualFile containingVirtualFile = containingPsiFile.getVirtualFile();
    if (containingVirtualFile == null) {
      return false;
    }
    return ProjectFileIndex.getInstance(element.getProject()).isInLibrary(containingVirtualFile);
  }

  @NotNull
  private static String getReferenceText(@NotNull PsiReference reference) {
    if (reference instanceof PsiQualifiedReference) {
      String referenceName = ((PsiQualifiedReference)reference).getReferenceName();
      if (referenceName != null) {
        return referenceName;
      }
    }
    // references are not PsiQualifiedReference for annotation attributes
    return StringUtil.getShortName(reference.getCanonicalText());
  }
}
