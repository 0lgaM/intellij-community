// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.refactoring.extractMethodWithResultObject;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.ArrayUtil;
import com.siyeh.ig.psiutils.ControlFlowUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * @author Pavel.Dolgov
 */
class ExtractionContextAnalyser extends JavaRecursiveElementWalkingVisitor {
  private final PsiElement[] myElements;
  private final PsiElement myCodeFragmentMember;
  @Nullable private final PsiExpression myExpression;
  private final ExtractionContext myContext;

  ExtractionContextAnalyser(@NotNull PsiElement[] elements, @NotNull PsiElement codeFragmentMember, @Nullable PsiExpression expression) {
    myElements = elements;
    myCodeFragmentMember = codeFragmentMember;
    myExpression = expression;
    myContext = new ExtractionContext();
  }

  @NotNull
  ExtractionContext createContext() {
    collectInputsAndOutputs();
    collectDeclaredInsideUsedAfter();
    if (myExpression != null) {
      myContext.addExit(null, ExitType.EXPRESSION, myExpression);
    }
    else {
      findSequentialExit();
    }
    return myContext;
  }

  private void collectInputsAndOutputs() {
    // todo take care of surrounding try-catch
    ExtractionContextVisitor visitor = new ExtractionContextVisitor();
    for (PsiElement element : myElements) {
      element.accept(visitor);
    }
  }

  private void collectDeclaredInsideUsedAfter() {
    Set<PsiLocalVariable> declaredInside = new HashSet<>();
    for (PsiElement element : myElements) {
      if (element instanceof PsiDeclarationStatement) {
        PsiElement[] declaredElements = ((PsiDeclarationStatement)element).getDeclaredElements();
        for (PsiElement declaredElement : declaredElements) {
          if (declaredElement instanceof PsiLocalVariable) {
            declaredInside.add((PsiLocalVariable)declaredElement);
          }
        }
      }
    }

    if (!declaredInside.isEmpty()) {
      for (PsiElement next = myElements[myElements.length - 1].getNextSibling(); next != null; next = next.getNextSibling()) {
        next.accept(new JavaRecursiveElementWalkingVisitor() {
          @Override
          public void visitReferenceExpression(PsiReferenceExpression expression) {
            super.visitReferenceExpression(expression);

            if (expression.getQualifier() == null) {
              PsiElement resolved = expression.resolve();
              if (resolved instanceof PsiLocalVariable && declaredInside.contains(resolved)) {
                myContext.myOutputVariables.add((PsiLocalVariable)resolved);
              }
            }
          }
        });
      }
    }
  }

  private void findSequentialExit() {
    for (int i = myElements.length - 1; i >= 0; i--) {
      if (!(myElements[i] instanceof PsiStatement)) {
        continue; // skip comments and white spaces
      }
      PsiStatement statement = (PsiStatement)myElements[i];
      if (!ControlFlowUtils.statementMayCompleteNormally(statement)) {
        return; // sequential exit can't happen
      }
      break;
    }

    PsiElement exitedElement = ExitUtil.findOutermostExitedElement(myElements[myElements.length - 1], myCodeFragmentMember);
    if (exitedElement != null) {
      myContext.addExit(null, ExitType.SEQUENTIAL, exitedElement);
    }
    else {
      myContext.addExit(null, ExitType.UNDEFINED, null);
    }
  }


  private void processExpression(PsiReferenceExpression expression) {
    PsiExpression qualifier = expression.getQualifierExpression();
    if (qualifier == null || qualifier instanceof PsiQualifiedExpression) {
      PsiElement resolved = expression.resolve();
      if (resolved instanceof PsiVariable) {
        PsiVariable variable = (PsiVariable)resolved;
        PsiAssignmentExpression assignment = getAssignmentOf(expression);
        if (assignment != null) {
          if (assignment.getOperationTokenType() != JavaTokenType.EQ) {
            processPossibleInput(expression, variable);
          }
          processPossibleOutput(variable);
        }
        else if (PsiUtil.isIncrementDecrementOperation(expression)) {
          processPossibleInput(expression, variable);
          processPossibleOutput(variable);
        }
        else {
          processPossibleInput(expression, variable);
        }
      }
    }
  }

  private void processReturnExit(PsiReturnStatement statement) {
    myContext.addExit(statement, ExitType.RETURN, myCodeFragmentMember);
  }

  private void processBreakExit(PsiBreakStatement statement) {
    PsiElement exitedElement = statement.findExitedElement();
    if (exitedElement != null && !isInside(exitedElement)) {
      PsiElement outermostExited = ExitUtil.findOutermostExitedElement(statement, myCodeFragmentMember);
      myContext.addExit(statement, ExitType.BREAK, outermostExited != null ? outermostExited : exitedElement);
    }
  }

  private void processContinueExit(PsiContinueStatement statement) {
    PsiStatement continuedStatement = statement.findContinuedStatement();
    if (continuedStatement instanceof PsiLoopStatement && !isInside(continuedStatement)) {
      myContext.addExit(statement, ExitType.CONTINUE, ((PsiLoopStatement)continuedStatement).getBody());
    }
  }

  private void processThrowExit(PsiThrowStatement statement) {
    PsiTryStatement throwTarget = ExitUtil.findThrowTarget(statement, myCodeFragmentMember);
    if (throwTarget == null) {
      myContext.addExit(statement, ExitType.THROW, myCodeFragmentMember);
    }
    else if (!isInside(throwTarget)) {
      myContext.addExit(statement, ExitType.THROW, throwTarget);
    }
  }

  private void processPossibleInput(PsiReferenceExpression expression, PsiVariable variable) {
    if (variable instanceof PsiLocalVariable || variable instanceof PsiParameter) {
      if (!isInside(variable)) {
        myContext.myInputs.put(expression, variable);
      }
    }
  }

  private void processPossibleOutput(PsiVariable variable) {
    if (variable instanceof PsiLocalVariable || variable instanceof PsiParameter) {
      if (PsiTreeUtil.isAncestor(myCodeFragmentMember, variable, true)) {
        myContext.myOutputVariables.add(variable);
        if (!isInside(variable)) {
          myContext.myWrittenOuterVariables.add(variable);
        }
      }
    }
    else if (variable instanceof PsiField && variable.hasModifierProperty(PsiModifier.FINAL)) {
      if (myCodeFragmentMember.getParent() != null && myCodeFragmentMember.getParent() == variable.getParent()) {
        myContext.myOutputVariables.add(variable);
      }
    }
  }

  private boolean isInside(@NotNull PsiElement element) {
    PsiElement context = PsiTreeUtil.findFirstContext(element, false,
                                                      e -> e == myCodeFragmentMember || ArrayUtil.find(myElements, e) >= 0);
    return context != myCodeFragmentMember;
  }

  @Nullable
  private static PsiAssignmentExpression getAssignmentOf(@NotNull PsiReferenceExpression expression) {
    PsiElement element = expression;
    while (element.getParent() instanceof PsiParenthesizedExpression) {
      element = element.getParent();
    }
    PsiElement parent = element.getParent();
    if (parent instanceof PsiAssignmentExpression && ((PsiAssignmentExpression)parent).getLExpression() == element) {
      return (PsiAssignmentExpression)parent;
    }
    return null;
  }

  private class ExtractionContextVisitor extends JavaRecursiveElementWalkingVisitor {
    private final Set<PsiElement> mySkippedContexts = new HashSet<>();

    @Override
    public void visitReferenceExpression(PsiReferenceExpression expression) {
      super.visitReferenceExpression(expression);

      processExpression(expression);
    }

    @Override
    public void visitReturnStatement(PsiReturnStatement statement) {
      super.visitReturnStatement(statement);

      if (!isInSkippedContext(statement)) {
        processReturnExit(statement);
      }
    }

    @Override
    public void visitContinueStatement(PsiContinueStatement statement) {
      super.visitContinueStatement(statement);

      if (!isInSkippedContext(statement)) {
        processContinueExit(statement);
      }
    }

    @Override
    public void visitBreakStatement(PsiBreakStatement statement) {
      super.visitBreakStatement(statement);

      if (!isInSkippedContext(statement)) {
        processBreakExit(statement);
      }
    }

    @Override
    public void visitThrowStatement(PsiThrowStatement statement) {
      super.visitThrowStatement(statement);

      if (!isInSkippedContext(statement)) {
        processThrowExit(statement);
      }
    }

    @Override
    public void visitClass(PsiClass aClass) {
      mySkippedContexts.add(aClass);
      super.visitClass(aClass);
    }

    @Override
    public void visitLambdaExpression(PsiLambdaExpression expression) {
      mySkippedContexts.add(expression);
      super.visitLambdaExpression(expression);
    }

    private boolean isInSkippedContext(PsiElement element) {
      while (true) {
        if (element == myCodeFragmentMember) {
          return false;
        }
        if (element == null || mySkippedContexts.contains(element)) {
          return true;
        }
        element = element.getContext();
      }
    }
  }
}
