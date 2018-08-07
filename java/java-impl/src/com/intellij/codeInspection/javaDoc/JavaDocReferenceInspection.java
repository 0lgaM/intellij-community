// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInspection.javaDoc;

import com.intellij.codeHighlighting.HighlightDisplayLevel;
import com.intellij.codeInsight.daemon.QuickFixBundle;
import com.intellij.codeInsight.daemon.impl.quickfix.ImportClassFix;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.codeInspection.*;
import com.intellij.ide.DataManager;
import com.intellij.ide.util.FQNameCellRenderer;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.proximity.PsiProximityComparator;
import org.jetbrains.annotations.NotNull;

import java.util.*;

import static com.intellij.codeInspection.ProblemHighlightType.LIKE_UNKNOWN_SYMBOL;

public class JavaDocReferenceInspection extends LocalInspectionTool {
  private static final String SHORT_NAME = "JavadocReference";

  protected LocalQuickFix createAddQualifierFix(PsiJavaCodeReferenceElement reference) {
    List<PsiClass> classesToImport = new ImportClassFix(reference).getClassesToImport();
    return classesToImport.isEmpty() ? null : new AddQualifierFix(classesToImport);
  }

  protected RenameReferenceQuickFix createRenameReferenceQuickFix(Set<String> unboundParams) {
    return new RenameReferenceQuickFix(unboundParams);
  }

  private void visitRefInDocTag(PsiDocTag tag, JavadocManager manager, PsiElement context, ProblemsHolder holder, boolean isOnTheFly) {
    PsiDocTagValue value = tag.getValueElement();
    if (value == null) return;

    String tagName = tag.getName();
    JavadocTagInfo info = manager.getTagInfo(tagName);
    if (info != null && !info.isValidInContext(context)) return;

    if (info != null && info.isInline()) {
      String message = info.checkTagValue(value);
      if (message != null) {
        holder.registerProblem(holder.getManager().createProblemDescriptor(value, message, isOnTheFly, null, LIKE_UNKNOWN_SYMBOL));
      }
    }

    PsiReference reference = value.getReference();
    if (reference == null) return;
    PsiElement element = reference.resolve();
    if (element != null) return;
    int textOffset = value.getTextOffset();
    if (textOffset == value.getTextRange().getEndOffset()) return;
    PsiDocTagValue valueElement = tag.getValueElement();
    if (valueElement == null) return;

    CharSequence paramName = value.getContainingFile().getViewProvider().getContents().subSequence(textOffset, value.getTextRange().getEndOffset());
    String params = "<code>" + paramName + "</code>";
    List<LocalQuickFix> fixes = new ArrayList<>();
    if (isOnTheFly && "param".equals(tagName)) {
      PsiDocCommentOwner commentOwner = PsiTreeUtil.getParentOfType(tag, PsiDocCommentOwner.class);
      if (commentOwner instanceof PsiMethod) {
        PsiMethod method = (PsiMethod)commentOwner;
        PsiParameter[] parameters = method.getParameterList().getParameters();
        PsiDocTag[] tags = tag.getContainingComment().getTags();
        Set<String> unboundParams = new HashSet<>();
        for (PsiParameter parameter : parameters) {
          if (!JavadocHighlightUtil.hasTagForParameter(tags, parameter)) {
            unboundParams.add(parameter.getName());
          }
        }
        if (!unboundParams.isEmpty()) {
          fixes.add(createRenameReferenceQuickFix(unboundParams));
        }
      }
    }
    fixes.add(new RemoveTagFix(tagName, paramName));

    LocalQuickFix[] array = fixes.toArray(LocalQuickFix.EMPTY_ARRAY);
    holder.registerProblem(holder.getManager().createProblemDescriptor(
      valueElement, reference.getRangeInElement(), cannotResolveSymbolMessage(params), LIKE_UNKNOWN_SYMBOL, isOnTheFly, array));
  }

  @Override
  public boolean isEnabledByDefault() {
    return true;
  }

  @NotNull
  @Override
  public String getDisplayName() {
    return InspectionsBundle.message("inspection.javadoc.ref.display.name");
  }

  @NotNull
  @Override
  public String getGroupDisplayName() {
    return InspectionsBundle.message("group.names.javadoc.issues");
  }

  @NotNull
  @Override
  public String getShortName() {
    return SHORT_NAME;
  }

  @NotNull
  @Override
  public HighlightDisplayLevel getDefaultLevel() {
    return HighlightDisplayLevel.ERROR;
  }

  @NotNull
  @Override
  public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
    return new JavaElementVisitor() {
      @Override
      public void visitJavaFile(PsiJavaFile file) {
        if (PsiPackage.PACKAGE_INFO_FILE.equals(file.getName())) {
          checkComment(PsiTreeUtil.getChildOfType(file, PsiDocComment.class), file, holder, isOnTheFly);
        }
      }

      @Override
      public void visitModule(PsiJavaModule module) {
        checkComment(module.getDocComment(), module, holder, isOnTheFly);
      }

      @Override
      public void visitClass(PsiClass aClass) {
        checkComment(aClass.getDocComment(), aClass, holder, isOnTheFly);
      }

      @Override
      public void visitField(PsiField field) {
        checkComment(field.getDocComment(), field, holder, isOnTheFly);
      }

      @Override
      public void visitMethod(PsiMethod method) {
        checkComment(method.getDocComment(), method, holder, isOnTheFly);
      }
    };
  }

  private void checkComment(PsiDocComment comment, PsiElement context, ProblemsHolder holder, boolean isOnTheFly) {
    if (comment == null) return;

    JavadocManager javadocManager = JavadocManager.SERVICE.getInstance(holder.getProject());
    comment.accept(new JavaElementVisitor() {
      @Override
      public void visitReferenceExpression(PsiReferenceExpression expression) {
        visitElement(expression);
      }

      @Override
      public void visitReferenceElement(PsiJavaCodeReferenceElement reference) {
        super.visitReferenceElement(reference);
        JavaResolveResult result = reference.advancedResolve(false);
        if (result.getElement() == null && !result.isPackagePrefixPackageReference()) {
          PsiElement referenceNameElement = reference.getReferenceNameElement();
          PsiElement element = referenceNameElement != null ? referenceNameElement : reference;
          String message = cannotResolveSymbolMessage("<code>" + reference.getText() + "</code>");
          LocalQuickFix fix = isOnTheFly ? createAddQualifierFix(reference) : null;
          holder.registerProblem(holder.getManager().createProblemDescriptor(element, message, fix, LIKE_UNKNOWN_SYMBOL, isOnTheFly));
        }
      }

      @Override
      public void visitDocTag(PsiDocTag tag) {
        super.visitDocTag(tag);
        JavadocTagInfo info = javadocManager.getTagInfo(tag.getName());
        if (info == null || !info.isInline()) {
          visitRefInDocTag(tag, javadocManager, context, holder, isOnTheFly);
        }
      }

      @Override
      public void visitInlineDocTag(PsiInlineDocTag tag) {
        super.visitInlineDocTag(tag);
        visitRefInDocTag(tag, javadocManager, context, holder, isOnTheFly);
      }

      @Override
      public void visitElement(PsiElement element) {
        for (PsiElement child : element.getChildren()) {
          child.accept(this);
        }
      }
    });
  }

  private static String cannotResolveSymbolMessage(String params) {
    return InspectionsBundle.message("inspection.javadoc.problem.cannot.resolve", params);
  }

  private static class RenameReferenceQuickFix implements LocalQuickFix {
    private final Set<String> myUnboundParams;

    public RenameReferenceQuickFix(Set<String> unboundParams) {
      myUnboundParams = unboundParams;
    }

    @Override
    @NotNull
    public String getFamilyName() {
      return "Change to ...";
    }

    @Override
    public void applyFix(@NotNull final Project project, @NotNull final ProblemDescriptor descriptor) {
      DataManager.getInstance().getDataContextFromFocusAsync()
                 .onSuccess(dataContext -> {
                   final Editor editor = CommonDataKeys.EDITOR.getData(dataContext);
                   assert editor != null;
                   final TextRange textRange = ((ProblemDescriptorBase)descriptor).getTextRange();
                   editor.getSelectionModel().setSelection(textRange.getStartOffset(), textRange.getEndOffset());

                   final String word = editor.getSelectionModel().getSelectedText();

                   if (word == null || StringUtil.isEmptyOrSpaces(word)) {
                     return;
                   }
                   final List<LookupElement> items = new ArrayList<>();
                   for (String variant : myUnboundParams) {
                     items.add(LookupElementBuilder.create(variant));
                   }
                   LookupManager.getInstance(project).showLookup(editor, items.toArray(LookupElement.EMPTY_ARRAY));
                 });
    }
  }

  private static class AddQualifierFix implements LocalQuickFix {
    private final List<PsiClass> originalClasses;

    public AddQualifierFix(final List<PsiClass> originalClasses) {
      this.originalClasses = originalClasses;
    }

    @Override
    @NotNull
    public String getFamilyName() {
      return QuickFixBundle.message("add.qualifier");
    }

    @Override
    public boolean startInWriteAction() {
      return false;
    }

    @Override
    public void applyFix(@NotNull final Project project, @NotNull final ProblemDescriptor descriptor) {
      PsiJavaCodeReferenceElement element = PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), PsiJavaCodeReferenceElement.class);
      if (element != null) {
        Collections.sort(originalClasses, new PsiProximityComparator(element.getElement()));
        DataManager.getInstance()
                   .getDataContextFromFocusAsync()
                   .onSuccess(dataContext ->
          JBPopupFactory.getInstance()
            .createPopupChooserBuilder(originalClasses)
            .setTitle(QuickFixBundle.message("add.qualifier.original.class.chooser.title"))
            .setItemChosenCallback((psiClass) -> {
              if (!element.isValid()) return;
              WriteCommandAction.writeCommandAction(project, element.getContainingFile()).run(() -> {
                if (psiClass.isValid()) {
                  PsiDocumentManager.getInstance(project).commitAllDocuments();
                  element.bindToElement(psiClass);
                }
              });
            })
            .setRenderer(new FQNameCellRenderer())
            .createPopup()
            .showInBestPositionFor(dataContext));
      }
    }
  }

  private static class RemoveTagFix implements LocalQuickFix {
    private final String myTagName;
    private final CharSequence myParamName;

    public RemoveTagFix(String tagName, CharSequence paramName) {
      myTagName = tagName;
      myParamName = paramName;
    }

    @NotNull
    @Override
    public String getName() {
      return "Remove @" + myTagName + " " + myParamName;
    }

    @NotNull
    @Override
    public String getFamilyName() {
      return "Remove tag";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
      PsiDocTag myTag = PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), PsiDocTag.class);
      if (myTag != null) {
        myTag.delete();
      }
    }
  }
}
