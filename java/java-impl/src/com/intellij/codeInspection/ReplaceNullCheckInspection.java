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
package com.intellij.codeInspection;

import com.intellij.codeInspection.dataFlow.Nullness;
import com.intellij.codeInspection.dataFlow.NullnessUtil;
import com.intellij.codeInspection.ui.SingleIntegerFieldOptionsPanel;
import com.intellij.codeInspection.util.LambdaGenerationUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.siyeh.ig.callMatcher.CallMatcher;
import com.siyeh.ig.psiutils.*;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

import static com.intellij.util.ObjectUtils.tryCast;

public class ReplaceNullCheckInspection extends BaseJavaBatchLocalInspectionTool {
  private static final EquivalenceChecker ourEquivalence = EquivalenceChecker.getCanonicalPsiEquivalence();
  public int MINIMAL_WARN_SIZE = 140;

  private static final CallMatcher STREAM_EMPTY = CallMatcher.staticCall(CommonClassNames.JAVA_UTIL_STREAM_STREAM, "empty")
    .parameterCount(0);
  private static final CallMatcher STREAM_OF = CallMatcher.anyOf(
    CallMatcher.staticCall(CommonClassNames.JAVA_UTIL_STREAM_STREAM, "ofNullable").parameterCount(1),
    CallMatcher.staticCall(CommonClassNames.JAVA_UTIL_STREAM_STREAM, "of").parameterCount(1)
  );

  @Nullable
  @Override
  public JComponent createOptionsPanel() {
    return new SingleIntegerFieldOptionsPanel(
      InspectionsBundle.message("inspection.require.non.null.option.min.size"),
      this, "MINIMAL_WARN_SIZE"
    );
  }

  @NotNull
  @Override
  public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
    PsiFile file = holder.getFile();
    if(!PsiUtil.isLanguageLevel9OrHigher(file)) {
      return PsiElementVisitor.EMPTY_VISITOR;
    }
    return new JavaElementVisitor() {
      @Override
      public void visitIfStatement(PsiIfStatement ifStatement) {
        NotNullContext context = NotNullContext.from(ifStatement);
        if(context == null) return;
        String method = getMethodWithClass(context.getExpression(), context.isStream());

        boolean isInfoLevel = ifStatement.getTextLength() < MINIMAL_WARN_SIZE;
        ProblemHighlightType highlight = getHighlight(context, isInfoLevel);
        TextRange range = getRange(isInfoLevel, ifStatement, context.isStream()).shiftRight(-ifStatement.getTextOffset());
        holder.registerProblem(ifStatement, InspectionsBundle.message("inspection.require.non.null.message", method), highlight, range,
                               new ReplaceWithRequireNonNullFix(method));
      }

      @NotNull
      private ProblemHighlightType getHighlight(NotNullContext context, boolean isInfoLevel) {
        ProblemHighlightType highlight;
        if (isInfoLevel) {
          highlight = ProblemHighlightType.INFORMATION;
        } else {
          highlight = context.isStream() ? ProblemHighlightType.GENERIC_ERROR_OR_WARNING : ProblemHighlightType.WEAK_WARNING;
        }
        return highlight;
      }

      @Override
      public void visitConditionalExpression(PsiConditionalExpression ternary) {
        TernaryNotNullContext context = TernaryNotNullContext.from(ternary);
        if(context == null) return;
        String method = getMethodWithClass(context.getNonNullExpr(), false);
        holder.registerProblem(ternary, InspectionsBundle.message("inspection.require.non.null.message", method),
                               new ReplaceWithRequireNonNullFix(method));
      }
    };
  }

  private static class ReplaceWithRequireNonNullFix implements LocalQuickFix {
    private final @NotNull String myMethod;

    private ReplaceWithRequireNonNullFix(@NotNull String method) {myMethod = method;}

    @Nls
    @NotNull
    @Override
    public String getName() {
      return InspectionsBundle.message("inspection.require.non.null.message", myMethod);
    }

    @Nls
    @NotNull
    @Override
    public String getFamilyName() {
      return InspectionsBundle.message("inspection.require.non.null");
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
      PsiElement element = descriptor.getStartElement();
      final PsiElement result;
      if(element instanceof PsiIfStatement) {
        NotNullContext context = NotNullContext.from((PsiIfStatement)element);
        if (context == null) return;
        CommentTracker tracker = new CommentTracker();
        PsiExpression expression = context.getExpression();
        if(!context.isStream()) {
          PsiExpression requireCall = createRequireExpression(tracker, expression, project, context.getVariable(), context.getReference());
          context.getReference().replace(requireCall);
        } else {
          PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
          String streamOfNullableText = CommonClassNames.JAVA_UTIL_STREAM_STREAM + ".ofNullable(" + context.getVariable().getName() + ")";
          PsiExpression streamOfNullable = factory.createExpressionFromText(streamOfNullableText, expression);
          expression.replace(streamOfNullable);
        }
        result = tracker.replaceAndRestoreComments(context.getIfStatement(), context.getNullBranchStmt());
        if (context.getNextToDelete() != null) {
          context.getNextToDelete().delete();
        }
      } else if(element instanceof PsiConditionalExpression) {
        TernaryNotNullContext context = TernaryNotNullContext.from((PsiConditionalExpression)element);
        if(context == null) return;
        CommentTracker tracker = new CommentTracker();
        PsiExpression requireCall =
          createRequireExpression(tracker, context.getNonNullExpr(), project, context.getVariable(), context.getNonNullExpr());
        result = tracker.replace(context.getTernary(), requireCall);
      } else return;
      LambdaCanBeMethodReferenceInspection.replaceAllLambdasWithMethodReferences(result);
      CodeStyleManager.getInstance(project).reformat(JavaCodeStyleManager.getInstance(project).shortenClassReferences(result));
    }
  }

  private static TextRange getRange(boolean isInfoLevel, @NotNull PsiIfStatement ifStatement, boolean isStream) {
    if(isInfoLevel || isStream) {
      return ifStatement.getTextRange();
    } else {
      PsiExpression condition = ifStatement.getCondition();
      if(condition == null) return ifStatement.getTextRange();
      PsiElement nextSibling = condition.getNextSibling();
      if(nextSibling == null) return ifStatement.getTextRange();
      return new TextRange(ifStatement.getTextOffset(), nextSibling.getTextOffset() + 1);
    }
  }

  @NotNull
  private static PsiExpression createRequireExpression(@NotNull CommentTracker tracker,
                                                       @NotNull PsiExpression expression,
                                                       @NotNull Project project,
                                                       @NotNull PsiVariable variable,
                                                       @NotNull PsiElement context) {
    boolean isSimple = ExpressionUtils.isSimpleExpression(expression);
    String expr = tracker.text(expression);
    if (!isSimple) {
      expr = "()->" + expr;
    }
    String varName = variable.getName();
    String requireCallText = CommonClassNames.JAVA_UTIL_OBJECTS + "." + getMethod(expression) + "(" + varName + "," + expr + ")";
    PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
    return factory.createExpressionFromText(requireCallText, context);
  }

  private static class NotNullContext {
    private final @NotNull PsiExpression myExpression;
    private final @NotNull PsiExpression myReference;
    private final @NotNull PsiStatement myNullBranchStmt;
    private final @NotNull PsiVariable myVariable;
    private final @NotNull PsiIfStatement myIfStatement;
    private final @Nullable PsiStatement myNextToDelete;
    private final boolean myIsStream;

    private NotNullContext(@NotNull PsiExpression expression,
                           @NotNull PsiExpression reference,
                           @NotNull PsiStatement nullBranchStmt,
                           @NotNull PsiVariable variable,
                           @NotNull PsiIfStatement statement,
                           @Nullable PsiStatement nextToDelete,
                           boolean isStream) {
      myExpression = expression;
      myReference = reference;
      myNullBranchStmt = nullBranchStmt;
      myVariable = variable;
      myIfStatement = statement;
      myNextToDelete = nextToDelete;
      myIsStream = isStream;
    }

    @NotNull
    public PsiExpression getExpression() {
      return myExpression;
    }

    @NotNull
    public PsiVariable getVariable() {
      return myVariable;
    }

    @NotNull
    public PsiIfStatement getIfStatement() {
      return myIfStatement;
    }

    @Nullable
    public PsiStatement getNextToDelete() {
      return myNextToDelete;
    }



    @NotNull
    public PsiExpression getReference() {
      return myReference;
    }

    @NotNull
    public PsiStatement getNullBranchStmt() {
      return myNullBranchStmt;
    }

    public boolean isStream() {
      return myIsStream;
    }


    @Nullable
    static NotNullContext from(@NotNull PsiIfStatement ifStatement) {
      PsiExpression condition = ifStatement.getCondition();
      if(condition == null) return null;
      PsiBinaryExpression binOp = tryCast(condition, PsiBinaryExpression.class);
      if(binOp == null) return null;
      PsiVariable variable = extractVariable(binOp);
      if (variable == null) return null;
      if(ClassUtils.isPrimitive(variable.getType())) return null;

      boolean inverted = binOp.getOperationTokenType() == JavaTokenType.NE;
      PsiStatement elseBranch = ControlFlowUtils.stripBraces(ifStatement.getElseBranch());
      PsiStatement thenBranch = ControlFlowUtils.stripBraces(ifStatement.getThenBranch());
      if(elseBranch != null) {
        PsiStatement nullBranch = inverted? thenBranch : elseBranch;
        PsiStatement nonNullBranch = inverted? elseBranch : thenBranch;
        return extractContext(ifStatement, variable, nullBranch, nonNullBranch, null);
      } else {
        PsiReturnStatement nextReturn = tryCast(PsiTreeUtil.skipWhitespacesAndCommentsForward(ifStatement), PsiReturnStatement.class);
        if (nextReturn == null) return null;
        if (thenBranch instanceof PsiReturnStatement) {
          PsiStatement nullBranch = inverted? thenBranch : nextReturn;
          PsiStatement nonNullBranch = inverted? nextReturn : thenBranch;
          return extractContext(ifStatement, variable, nullBranch, nonNullBranch, nextReturn);
        }
      }
      return null;
    }

    @Contract("_, _, null, _, _ -> null")
    private static NotNullContext extractContext(@NotNull PsiIfStatement ifStatement,
                                                 @NotNull PsiVariable variable,
                                                 @Nullable PsiStatement nullBranch,
                                                 @Nullable PsiStatement nonNullBranch,
                                                 @Nullable PsiReturnStatement toDelete) {
      if(nullBranch == null) return null;
      EquivalenceChecker.Match match = ourEquivalence.statementsMatch(nullBranch, nonNullBranch);
      PsiExpression nullDiff = tryCast(match.getLeftDiff(), PsiExpression.class);
      PsiExpression nonNullDiff = tryCast(match.getRightDiff(), PsiExpression.class);
      if(!ExpressionUtils.isReferenceTo(nullDiff, variable)) {
        TopmostQualifierDiff qualifierDiff = TopmostQualifierDiff.from(nullDiff, nonNullDiff);
        if(qualifierDiff == null) {
          PsiMethodCallExpression nullCall = tryCast(nullDiff, PsiMethodCallExpression.class);
          PsiMethodCallExpression nonNullCall = tryCast(nonNullDiff, PsiMethodCallExpression.class);
          if(nullCall == null || nonNullCall == null) return null;
          if(!STREAM_EMPTY.test(nonNullCall) || !STREAM_OF.test(nullCall)) return null;
          PsiExpression maybeRef = nullCall.getArgumentList().getExpressions()[0];
          if (!ExpressionUtils.isReferenceTo(maybeRef, variable)) return null;
          return new NotNullContext(nullCall, maybeRef, nullBranch, variable, ifStatement, null, true);
        }
        nullDiff = qualifierDiff.getLeft();
        nonNullDiff = qualifierDiff.getRight();
        if(!ExpressionUtils.isReferenceTo(nullDiff, variable)) return null;
      }
      if(NullnessUtil.getExpressionNullness(nonNullDiff) != Nullness.NOT_NULL) return null;
      if(!LambdaGenerationUtil.canBeUncheckedLambda(nonNullDiff)) return null;
      return new NotNullContext(nonNullDiff, nullDiff, nullBranch, variable, ifStatement, toDelete, false);
    }
  }

  @NotNull
  static String getMethod(PsiExpression expression) {
    return ExpressionUtils.isSimpleExpression(expression) ? "requireNonNullElse" : "requireNonNullElseGet";
  }

  @NotNull
  static String getMethodWithClass(PsiExpression expression, boolean isStream) {
    return isStream ? "Stream.of" : "Objects." + getMethod(expression);
  }

  @Nullable
  private static PsiVariable extractVariable(@NotNull PsiBinaryExpression binOp) {
    PsiExpression value = ExpressionUtils.getValueComparedWithNull(binOp);
    PsiReferenceExpression referenceExpression = tryCast(value, PsiReferenceExpression.class);
    if(referenceExpression == null) return null;
    PsiVariable variable = tryCast(referenceExpression.resolve(), PsiVariable.class);
    if(variable == null) return null;
    return variable;
  }

  private static class TernaryNotNullContext {
    private final @NotNull PsiConditionalExpression myTernary;
    private final @NotNull PsiExpression myNonNullExpr;
    private final @NotNull PsiVariable myVariable;

    private TernaryNotNullContext(@NotNull PsiConditionalExpression ternary, @NotNull PsiExpression expr, @NotNull PsiVariable variable) {
      myTernary = ternary;
      myNonNullExpr = expr;
      myVariable = variable;
    }

    @NotNull
    public PsiExpression getNonNullExpr() {
      return myNonNullExpr;
    }

    @NotNull
    public PsiConditionalExpression getTernary() {
      return myTernary;
    }

    @NotNull
    public PsiVariable getVariable() {
      return myVariable;
    }

    @Nullable
    static TernaryNotNullContext from(@NotNull PsiConditionalExpression ternary) {
      PsiBinaryExpression binOp = tryCast(ternary.getCondition(), PsiBinaryExpression.class);
      if(binOp == null) return null;
      PsiVariable variable = extractVariable(binOp);
      if(variable == null) return null;
      boolean negated = binOp.getOperationTokenType() == JavaTokenType.NE;
      PsiExpression nonNullBranch = negated ? ternary.getElseExpression() : ternary.getThenExpression();
      if(ClassUtils.isPrimitive(variable.getType())) return null;
      PsiExpression nullBranch = negated ? ternary.getThenExpression() : ternary.getElseExpression();
      if(!ExpressionUtils.isReferenceTo(nullBranch, variable)) return null;
      if(NullnessUtil.getExpressionNullness(nonNullBranch) != Nullness.NOT_NULL) return null;
      if(!LambdaGenerationUtil.canBeUncheckedLambda(nonNullBranch)) return null;
      return new TernaryNotNullContext(ternary, nonNullBranch, variable);
    }
  }

  /**
   * Represents difference between o1.m1().m2() and o2.m1().m2()
   * Relies that call chain and arguments are exactly the same
   */
  private static class TopmostQualifierDiff {
    private final @Nullable PsiExpression myLeft;
    private final @Nullable PsiExpression myRight;

    private TopmostQualifierDiff(@Nullable PsiExpression left, @Nullable PsiExpression right) {
      myLeft = left;
      myRight = right;
    }

    @Nullable
    public PsiExpression getRight() {
      return myRight;
    }

    @Nullable
    public PsiExpression getLeft() {
      return myLeft;
    }

    @Nullable
    static TopmostQualifierDiff from(@Nullable PsiExpression left, @Nullable PsiExpression right) {
      PsiMethodCallExpression leftCall = tryCast(left, PsiMethodCallExpression.class);
      PsiMethodCallExpression rightCall = tryCast(right, PsiMethodCallExpression.class);
      if(leftCall == null || rightCall == null) return null;
      while(true) {
        PsiReferenceExpression leftMethodExpression = leftCall.getMethodExpression();
        PsiReferenceExpression rightMethodExpression = rightCall.getMethodExpression();
        if(tryCast(leftMethodExpression.resolve(), PsiMethod.class) != tryCast(rightMethodExpression.resolve(), PsiMethod.class)) return null;
        PsiExpression[] leftExpressions = leftCall.getArgumentList().getExpressions();
        PsiExpression[] rightExpressions = rightCall.getArgumentList().getExpressions();
        int length = leftExpressions.length;
        if(length != rightExpressions.length) return null;
        for (int i = 0; i < length; i++) {
          if(!ourEquivalence.expressionsAreEquivalent(leftExpressions[i], rightExpressions[i])) return null;
        }
        PsiExpression leftQualifier = leftMethodExpression.getQualifierExpression();
        leftCall = tryCast(leftQualifier, PsiMethodCallExpression.class);
        PsiExpression rightQualifier = rightMethodExpression.getQualifierExpression();
        rightCall = tryCast(rightQualifier, PsiMethodCallExpression.class);
        if(leftCall == null || rightCall == null) {
          return new TopmostQualifierDiff(leftQualifier, rightQualifier);
        }
      }
    }
  }
}
