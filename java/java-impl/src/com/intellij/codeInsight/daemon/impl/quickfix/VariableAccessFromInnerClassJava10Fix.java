// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInsight.daemon.impl.quickfix;

import com.intellij.codeInsight.FileModificationService;
import com.intellij.codeInsight.daemon.QuickFixBundle;
import com.intellij.codeInsight.intention.impl.BaseIntentionAction;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiTypesUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.refactoring.rename.inplace.MemberInplaceRenamer;
import com.intellij.refactoring.util.RefactoringUtil;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.SmartList;
import com.siyeh.ig.psiutils.ExpressionUtils;
import com.siyeh.ig.psiutils.TypeUtils;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import static com.intellij.util.ObjectUtils.tryCast;
import static java.util.Collections.emptyList;

public class VariableAccessFromInnerClassJava10Fix extends BaseIntentionAction {
  private final static String[] NAMES = new String[]{
    "ref",
    "lambdaContext",
    "context",
    "rContext"
  };
  private static final LinkedHashSet<String> ourSuggestions = new LinkedHashSet<>(Arrays.asList(NAMES));

  private final PsiElement myContext;

  public VariableAccessFromInnerClassJava10Fix(PsiElement context) {
    myContext = context;
  }

  @Nls(capitalization = Nls.Capitalization.Sentence)
  @NotNull
  @Override
  public String getFamilyName() {
    return "Variable accessFromInnerClass";
  }

  @Override
  public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
    if (!PsiUtil.isLanguageLevel10OrHigher(file)) return false;
    if (!myContext.isValid()) return false;
    PsiReferenceExpression reference = tryCast(myContext, PsiReferenceExpression.class);
    if (reference == null) return false;
    PsiLocalVariable variable = tryCast(reference.resolve(), PsiLocalVariable.class);
    if (variable == null) return false;
    String name = variable.getName();
    if (name == null) return false;

    PsiType type = variable.getType();
    if (!PsiTypesUtil.isDenotableType(type) ||
        (variable.getTypeElement().isInferredType() &&
         type instanceof PsiClassType &&
         ((PsiClassType)type).resolve() instanceof PsiAnonymousClass)
      ) {
      return false;
    }
    setText(QuickFixBundle.message("convert.variable.to.field.in.anonymous.class.fix.name", name));
    return true;
  }

  @Override
  public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
    if (!FileModificationService.getInstance().preparePsiElementsForWrite(myContext)) return;
    WriteCommandAction.runWriteCommandAction(project, () -> {
      if (myContext instanceof PsiReferenceExpression && myContext.isValid()) {
        PsiReferenceExpression referenceExpression = (PsiReferenceExpression)myContext;
        PsiLocalVariable variable = tryCast(referenceExpression.resolve(), PsiLocalVariable.class);
        if (variable == null) return;
        PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
        PsiExpression initializer = variable.getInitializer();
        final String variableText = getFieldText(variable, factory, initializer);


        PsiLambdaExpression lambdaExpression = PsiTreeUtil.getParentOfType(myContext, PsiLambdaExpression.class);
        if (lambdaExpression == null) return;
        DeclarationInfo declarationInfo = DeclarationInfo.findExistingAnonymousClass(variable);

        if (declarationInfo != null) {
          replaceReferences(variable, factory, declarationInfo.name);
          declarationInfo.replace(variableText);
          variable.delete();
          return;
        }


        JavaCodeStyleManager codeStyleManager = JavaCodeStyleManager.getInstance(project);
        String boxName = codeStyleManager.suggestUniqueVariableName(NAMES[0], variable, true);
        if (editor != null) {
          String boxDeclarationText = "var " +
                                      boxName +
                                      " = new Object(){" +
                                      variableText +
                                      "};";
          PsiStatement boxDeclaration = factory.createStatementFromText(boxDeclarationText, variable);
          replaceReferences(variable, factory, boxName);
          PsiDeclarationStatement declarationStatement = (PsiDeclarationStatement)variable.replace(boxDeclaration);
          PsiLocalVariable localVariable = (PsiLocalVariable)declarationStatement.getDeclaredElements()[0];
          SmartPointerManager smartPointerManager = SmartPointerManager.getInstance(project);
          SmartPsiElementPointer<PsiLocalVariable> pointer = smartPointerManager.createSmartPsiElementPointer(localVariable);
          PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(editor.getDocument());
          PsiLocalVariable varToChange = pointer.getElement();
          if (varToChange == null) return;
          editor.getCaretModel().moveToOffset(varToChange.getTextOffset());
          editor.getSelectionModel().removeSelection();
          new MemberInplaceRenamer(varToChange, varToChange, editor).performInplaceRefactoring(ourSuggestions);
        }
        else {
          String boxDeclarationText = "var " +
                                      boxName +
                                      " = new Object(){" +
                                      variableText +
                                      "};";
          PsiStatement boxDeclaration = factory.createStatementFromText(boxDeclarationText, variable);
          replaceReferences(variable, factory, boxName);
          variable.replace(boxDeclaration);
        }
      }
    });
  }

  private static void replaceReferences(PsiLocalVariable variable, PsiElementFactory factory, String boxName) {
    List<PsiReferenceExpression> references = findReferences(variable);
    PsiExpression expr = factory.createExpressionFromText(boxName + "." + variable.getName(), null);
    for (PsiReferenceExpression reference : references) {
      reference.replace(expr);
    }
  }

  private static String getFieldText(PsiLocalVariable variable, PsiElementFactory factory, PsiExpression initializer) {
    // var x is not allowed as field
    if (initializer != null && variable.getTypeElement().isInferredType() && initializer.getType() != null) {
      PsiLocalVariable copy = (PsiLocalVariable)variable.copy();
      copy.getTypeElement().replace(factory.createTypeElement(initializer.getType()));
      return copy.getText();
    }
    else {
      return variable.getText();
    }
  }

  private static class DeclarationInfo {
    final boolean isBefore;
    final @NotNull PsiStatement myStatementToReplace;
    final @NotNull PsiAnonymousClass myAnonymousClass;
    final @NotNull PsiNewExpression myNewExpression;
    final @NotNull PsiLocalVariable myVariable;
    final @NotNull String name;

    public DeclarationInfo(boolean isBefore,
                           @NotNull PsiStatement statementToReplace,
                           @NotNull PsiAnonymousClass anonymousClass,
                           @NotNull PsiNewExpression expression, @NotNull PsiLocalVariable variable, @NotNull String name) {
      this.isBefore = isBefore;
      myStatementToReplace = statementToReplace;
      myAnonymousClass = anonymousClass;
      myNewExpression = expression;
      myVariable = variable;
      this.name = name;
    }

    @Nullable
    static DeclarationInfo findExistingAnonymousClass(@NotNull PsiVariable variable) {
      PsiElement varDeclarationStatement = RefactoringUtil.getParentStatement(variable, false);
      if (varDeclarationStatement == null) return null;
      PsiStatement nextStatement = PsiTreeUtil.getNextSiblingOfType(varDeclarationStatement, PsiStatement.class);
      PsiDeclarationStatement nextDeclarationStatement = tryCast(nextStatement, PsiDeclarationStatement.class);
      DeclarationInfo nextDeclaration = findExistingAnonymousClass(variable, nextDeclarationStatement, true);
      if (nextDeclaration != null) return nextDeclaration;
      PsiDeclarationStatement previousDeclarationStatement =
        PsiTreeUtil.getPrevSiblingOfType(varDeclarationStatement, PsiDeclarationStatement.class);
      return findExistingAnonymousClass(variable, previousDeclarationStatement, false);
    }

    void replace(@NotNull String variableText) {
      PsiLocalVariable localVariable = (PsiLocalVariable)myVariable.copy();
      PsiElementFactory factory = JavaPsiFacade.getElementFactory(localVariable.getProject());
      PsiElement lBrace = myAnonymousClass.getLBrace();
      PsiElement rBrace = myAnonymousClass.getRBrace();
      if (lBrace == null || rBrace == null) return;
      PsiElement rBracePrev = rBrace.getPrevSibling();
      if (rBracePrev == null) return;
      StringBuilder sb = new StringBuilder();
      for (PsiElement element : myAnonymousClass.getChildren()) {
        sb.append(element.getText());
        if (isBefore && element == lBrace || !isBefore && element == rBracePrev) {
          sb.append(variableText);
        }
      }
      localVariable.setInitializer(factory.createExpressionFromText("new " + sb.toString(), myVariable));
      myStatementToReplace.replace(factory.createStatementFromText(localVariable.getText() + ";", localVariable));
    }

    @Nullable
    private static DeclarationInfo findExistingAnonymousClass(@NotNull PsiVariable variable,
                                                              @Nullable PsiDeclarationStatement declarationStatement,
                                                              boolean isBefore) {
      if (declarationStatement == null) return null;
      PsiElement[] declaredElements = declarationStatement.getDeclaredElements();
      if (declaredElements.length != 1) return null;
      PsiLocalVariable localVariable = tryCast(declaredElements[0], PsiLocalVariable.class);
      if (localVariable == null) return null;
      String boxName = localVariable.getName();
      if (boxName == null) return null;
      PsiNewExpression newExpression = tryCast(localVariable.getInitializer(), PsiNewExpression.class);
      if (newExpression == null) return null;
      PsiAnonymousClass anonymousClass = newExpression.getAnonymousClass();
      if (anonymousClass == null) return null;
      String variableName = variable.getName();
      if (variableName == null) return null;
      if (!TypeUtils.isJavaLangObject(anonymousClass.getBaseClassType())) return null;
      if (Arrays.stream(anonymousClass.getFields())
                .map(field -> field.getName())
                .filter(Objects::nonNull)
                .anyMatch(name -> name.equals(variableName))) {
        return null;
      }
      return new DeclarationInfo(isBefore, declarationStatement, anonymousClass, newExpression, localVariable, boxName);
    }
  }

  private static List<PsiReferenceExpression> findReferences(@NotNull PsiLocalVariable variable) {
    PsiElement outerCodeBlock = PsiUtil.getVariableCodeBlock(variable, null);
    if (outerCodeBlock == null) return emptyList();
    List<PsiReferenceExpression> references = new SmartList<>();
    outerCodeBlock.accept(new JavaRecursiveElementVisitor() {
      @Override
      public void visitReferenceExpression(PsiReferenceExpression expression) {
        if (ExpressionUtils.isReferenceTo(expression, variable)) {
          references.add(expression);
        }
      }
    });
    return references;
  }

  @Override
  public boolean startInWriteAction() {
    return true;
  }

  private static class StringLookupElement extends LookupElement {
    private String lookupString;

    public StringLookupElement(String lookupString) {
      this.lookupString = lookupString;
    }

    @NotNull
    @Override
    public String getLookupString() {
      return lookupString;
    }
  }
}
