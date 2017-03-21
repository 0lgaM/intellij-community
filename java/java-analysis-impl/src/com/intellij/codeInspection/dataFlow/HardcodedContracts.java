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
package com.intellij.codeInspection.dataFlow;

import com.intellij.codeInspection.dataFlow.value.DfaValue;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.ThreeState;
import com.intellij.util.containers.ContainerUtil;
import com.siyeh.ig.psiutils.ExpressionUtils;
import com.siyeh.ig.psiutils.TypeUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.intellij.codeInspection.dataFlow.MethodContract.ValueConstraint.*;
import static com.intellij.codeInspection.dataFlow.MethodContract.createConstraintArray;

/**
 * @author peter
 */
public class HardcodedContracts {
  static class OptionalPresenceContract extends MethodContract.QualifierBasedContract {
    private final boolean myPresent;

    public OptionalPresenceContract(boolean mustPresent, ValueConstraint[] valueConstraints, ValueConstraint returnValue) {
      super(valueConstraints, returnValue);
      myPresent = mustPresent;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass() || !super.equals(o)) return false;
      return myPresent == ((OptionalPresenceContract)o).myPresent;
    }

    @Override
    public int hashCode() {
      return 31 * super.hashCode() + (myPresent ? 1 : 0);
    }

    @Override
    boolean applyContract(boolean matches, DfaValue qualifier, DfaMemoryState memoryState) {
      boolean present = !matches ^ myPresent;
      ThreeState state = memoryState.checkOptional(qualifier);
      if(state == ThreeState.fromBoolean(!present)) return false;
      if(state == ThreeState.UNSURE) {
        memoryState.applyIsPresentCheck(present, qualifier);
      }
      return true;
    }

    @Override
    public String toString() {
      return "[" + (myPresent ? "present" : "absent") + "] " + super.toString();
    }
  }

  public static List<MethodContract> getHardcodedContracts(@NotNull PsiMethod method, @Nullable PsiMethodCallExpression call) {
    PsiClass owner = method.getContainingClass();
    if (owner == null ||
        InjectedLanguageManager.getInstance(owner.getProject()).isInjectedFragment(owner.getContainingFile())) {
      return Collections.emptyList();
    }

    final int paramCount = method.getParameterList().getParametersCount();
    String className = owner.getQualifiedName();
    if (className == null) return Collections.emptyList();

    String methodName = method.getName();

    if ("java.lang.System".equals(className)) {
      if ("exit".equals(methodName)) {
        return Collections.singletonList(new MethodContract(createConstraintArray(paramCount), THROW_EXCEPTION));
      }
    }
    else if ("com.google.common.base.Preconditions".equals(className)) {
      if ("checkNotNull".equals(methodName) && paramCount > 0) {
        return failIfNull(0, paramCount);
      }
      if (("checkArgument".equals(methodName) || "checkState".equals(methodName)) && paramCount > 0) {
        MethodContract.ValueConstraint[] constraints = createConstraintArray(paramCount);
        constraints[0] = FALSE_VALUE;
        return Collections.singletonList(new MethodContract(constraints, THROW_EXCEPTION));
      }
    }
    else if ("java.util.Objects".equals(className)) {
      if ("requireNonNull".equals(methodName) && paramCount > 0) {
        return failIfNull(0, paramCount);
      }
    }
    else if ("org.apache.commons.lang.Validate".equals(className) ||
             "org.apache.commons.lang3.Validate".equals(className) ||
             "org.springframework.util.Assert".equals(className)) {
      if (("isTrue".equals(methodName) || "state".equals(methodName)) && paramCount > 0) {
        MethodContract.ValueConstraint[] constraints = createConstraintArray(paramCount);
        constraints[0] = FALSE_VALUE;
        return Collections.singletonList(new MethodContract(constraints, THROW_EXCEPTION));
      }
      if ("notNull".equals(methodName) && paramCount > 0) {
        MethodContract.ValueConstraint[] constraints = createConstraintArray(paramCount);
        constraints[0] = NULL_VALUE;
        return Collections.singletonList(new MethodContract(constraints, THROW_EXCEPTION));
      }
    }
    else if (isJunit(className) || isTestng(className) ||
             className.startsWith("com.google.common.truth.") ||
             className.startsWith("org.assertj.core.api.")) {
      return handleTestFrameworks(paramCount, className, methodName, call);
    }
    else if (TypeUtils.isOptional(owner)) {
      MethodContract.ValueConstraint[] constraints = createConstraintArray(paramCount);
      if (DfaOptionalSupport.isOptionalGetMethodName(methodName) || "orElseThrow".equals(methodName)) {
        return Arrays.asList(new OptionalPresenceContract(false, constraints, THROW_EXCEPTION),
                             new OptionalPresenceContract(true, constraints, NOT_NULL_VALUE));
      }
      else if ("isPresent".equals(methodName)) {
        return Arrays.asList(new OptionalPresenceContract(false, constraints, FALSE_VALUE),
                             new OptionalPresenceContract(true, constraints, TRUE_VALUE));
      }
    }

    return Collections.emptyList();
  }

  private static boolean isJunit(String className) {
    return className.startsWith("junit.framework.") || className.startsWith("org.junit.");
  }

  private static boolean isTestng(String className) {
    return className.startsWith("org.testng.");
  }

  private static boolean isNotNullMatcher(PsiExpression expr) {
    if (expr instanceof PsiMethodCallExpression) {
      String calledName = ((PsiMethodCallExpression)expr).getMethodExpression().getReferenceName();
      if ("notNullValue".equals(calledName)) {
        return true;
      }
      if ("not".equals(calledName)) {
        PsiExpression[] notArgs = ((PsiMethodCallExpression)expr).getArgumentList().getExpressions();
        if (notArgs.length == 1 &&
            notArgs[0] instanceof PsiMethodCallExpression &&
            "equalTo".equals(((PsiMethodCallExpression)notArgs[0]).getMethodExpression().getReferenceName())) {
          PsiExpression[] equalArgs = ((PsiMethodCallExpression)notArgs[0]).getArgumentList().getExpressions();
          if (equalArgs.length == 1 && ExpressionUtils.isNullLiteral(equalArgs[0])) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private static List<MethodContract> handleTestFrameworks(int paramCount, String className, String methodName,
                                                           @Nullable PsiMethodCallExpression call) {
    if (("assertThat".equals(methodName) || "assumeThat".equals(methodName) || "that".equals(methodName)) && call != null) {
      return handleAssertThat(paramCount, call);
    }

    if (!isJunit(className) && !isTestng(className)) {
      return Collections.emptyList();
    }

    boolean testng = isTestng(className);
    if ("fail".equals(methodName)) {
      return Collections.singletonList(new MethodContract(createConstraintArray(paramCount), THROW_EXCEPTION));
    }

    if (paramCount == 0) return Collections.emptyList();

    int checkedParam = testng ? 0 : paramCount - 1;
    MethodContract.ValueConstraint[] constraints = createConstraintArray(paramCount);
    if ("assertTrue".equals(methodName) || "assumeTrue".equals(methodName)) {
      constraints[checkedParam] = FALSE_VALUE;
      return Collections.singletonList(new MethodContract(constraints, THROW_EXCEPTION));
    }
    if ("assertFalse".equals(methodName) || "assumeFalse".equals(methodName)) {
      constraints[checkedParam] = TRUE_VALUE;
      return Collections.singletonList(new MethodContract(constraints, THROW_EXCEPTION));
    }
    if ("assertNull".equals(methodName)) {
      constraints[checkedParam] = NOT_NULL_VALUE;
      return Collections.singletonList(new MethodContract(constraints, THROW_EXCEPTION));
    }
    if ("assertNotNull".equals(methodName) || "assumeNotNull".equals(methodName)) {
      return failIfNull(checkedParam, paramCount);
    }
    return Collections.emptyList();
  }

  @NotNull
  private static List<MethodContract> handleAssertThat(int paramCount, @NotNull PsiMethodCallExpression call) {
    PsiExpression[] args = call.getArgumentList().getExpressions();
    if (args.length == paramCount) {
      for (int i = 1; i < args.length; i++) {
        if (isNotNullMatcher(args[i])) {
          return failIfNull(i - 1, paramCount);
        }
      }
      if (args.length == 1 && hasNotNullChainCall(call)) {
        return failIfNull(0, 1);
      }
    }
    return Collections.emptyList();
  }

  private static boolean hasNotNullChainCall(PsiMethodCallExpression call) {
    Iterable<PsiElement> exprParents = SyntaxTraverser.psiApi().parents(call).
      takeWhile(e -> !(e instanceof PsiStatement) && !(e instanceof PsiMember));
    return ContainerUtil.exists(exprParents, HardcodedContracts::isNotNullCall);
  }

  private static boolean isNotNullCall(PsiElement ref) {
    return ref instanceof PsiReferenceExpression &&
           "isNotNull".equals(((PsiReferenceExpression)ref).getReferenceName()) &&
           ref.getParent() instanceof PsiMethodCallExpression;
  }

  @NotNull
  private static List<MethodContract> failIfNull(int argIndex, int argCount) {
    MethodContract.ValueConstraint[] constraints = createConstraintArray(argCount);
    constraints[argIndex] = NULL_VALUE;
    return Collections.singletonList(new MethodContract(constraints, THROW_EXCEPTION));
  }

  public static boolean isHardcodedPure(PsiMethod method) {
    String qName = PsiUtil.getMemberQualifiedName(method);
    if ("java.lang.System.exit".equals(qName)) {
      return false;
    }

    if ("java.util.Objects.requireNonNull".equals(qName)) {
      PsiParameter[] parameters = method.getParameterList().getParameters();
      if (parameters.length == 2 && parameters[1].getType().getCanonicalText().contains("Supplier")) {
        return false;
      }
    }

    return true;
  }

  public static boolean hasHardcodedContracts(@Nullable PsiElement element) {
    if (element instanceof PsiMethod) {
      return !getHardcodedContracts((PsiMethod)element, null).isEmpty();
    }

    if (element instanceof PsiParameter) {
      PsiElement parent = element.getParent();
      return parent != null && hasHardcodedContracts(parent.getParent());
    }

    return false;
  }
}
