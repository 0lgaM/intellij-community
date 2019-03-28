// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.refactoring.extractMethodWithResultObject;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.*;
import com.intellij.psi.controlFlow.ControlFlowUtil;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiTypesUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.refactoring.introduceField.ElementToWorkOn;
import com.intellij.refactoring.util.RefactoringUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.util.containers.ContainerUtil;
import com.siyeh.ig.psiutils.ControlFlowUtils;
import one.util.streamex.EntryStream;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.intellij.util.ObjectUtils.notNull;
import static com.intellij.util.ObjectUtils.tryCast;

/**
 * @author Pavel.Dolgov
 */
public class ExtractMethodWithResultObjectProcessor {
  private static final Logger LOG = Logger.getInstance(ExtractMethodWithResultObjectProcessor.class);

  private final Project myProject;
  private final Editor myEditor;
  private final PsiElement[] myElements;
  private final PsiExpression myExpression;
  private final PsiElement myCodeFragmentMember;
  private final Map<PsiReferenceExpression, PsiVariable> myInputs = new HashMap<>();
  private final Set<PsiVariable> myOutputVariables = new HashSet<>();
  private final Map<PsiStatement, Exit> myExits = new HashMap<>();

  // in the order the expressions appear in the original code
  private final List<Pair.NonNull<String, PsiExpression>> myParameters = new ArrayList<>();

  private PsiClass myTargetClass;
  private PsiElement myAnchor;

  @NonNls static final String REFACTORING_NAME = "Extract Method With Result Object";

  private enum ExitType {
    EXPRESSION,
    RETURN,
    BREAK,
    CONTINUE,
    THROW,
    SEQUENTIAL,
    UNDEFINED
  }

  private static class Exit {
    private final ExitType myType;
    private final PsiElement myExitedElement;

    private Exit(ExitType type, PsiElement element) {
      myType = type;
      myExitedElement = element;
    }

    ExitType getType() {
      return myType;
    }

    PsiElement getExitedElement() {
      return myExitedElement;
    }

    @Override
    public String toString() {
      return myExitedElement != null ? myType + " " + myExitedElement : myType.toString();
    }
  }

  public ExtractMethodWithResultObjectProcessor(@NonNls Project project, @NonNls Editor editor, @NonNls PsiElement[] elements) {
    if (elements.length == 0) {
      throw new IllegalArgumentException("elements.length");
    }
    myProject = project;
    myEditor = editor;

    PsiExpression expression = null;
    if (elements[0] instanceof PsiExpression) {
      expression = (PsiExpression)elements[0];
      if (expression.getParent() instanceof PsiExpressionStatement) {
        elements = new PsiElement[]{expression.getParent()};
        expression = null;
      }
    }

    myElements = elements;
    myExpression = expression;

    PsiElement codeFragment = ControlFlowUtil.findCodeFragment(elements[0]);
    myCodeFragmentMember = getCodeFragmentMember(codeFragment);
  }

  Project getProject() {
    return myProject;
  }

  Editor getEditor() {
    return myEditor;
  }

  private static PsiElement getCodeFragmentMember(@NotNull PsiElement codeFragment) {
    PsiElement codeFragmentMember = codeFragment.getUserData(ElementToWorkOn.PARENT);
    if (codeFragmentMember == null) {
      codeFragmentMember = codeFragment.getParent();
    }
    if (codeFragmentMember == null) {
      PsiElement context = codeFragment.getContext();
      LOG.assertTrue(context != null, "code fragment context is null");
      codeFragmentMember = ControlFlowUtil.findCodeFragment(context).getParent();
    }
    return codeFragmentMember;
  }

  public boolean prepare() {
    LOG.info("prepare");
    if (myElements.length == 0) {
      return false;
    }

    collectInputsAndOutputs();
    collectDeclaredInsideUsedAfter();
    findSequentialExit();

    chooseTargetClass();
    chooseAnchor();

    return true;
  }

  private void collectInputsAndOutputs() {
    // todo take care of surrounding try-catch
    JavaRecursiveElementWalkingVisitor elementsVisitor = new JavaRecursiveElementWalkingVisitor() {
      private boolean myExpressionsOnly;

      @Override
      public void visitReferenceExpression(PsiReferenceExpression expression) {
        super.visitReferenceExpression(expression);

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

      private PsiAssignmentExpression getAssignmentOf(PsiReferenceExpression expression) {
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

      @Override
      public void visitReturnStatement(PsiReturnStatement statement) {
        super.visitReturnStatement(statement);

        if (!myExpressionsOnly) {
          myExits.put(statement, new Exit(ExitType.RETURN, myCodeFragmentMember));
        }
      }

      @Override
      public void visitContinueStatement(PsiContinueStatement statement) {
        super.visitContinueStatement(statement);

        if (!myExpressionsOnly) {
          PsiStatement continuedStatement = statement.findContinuedStatement();
          if (continuedStatement instanceof PsiLoopStatement && !isInside(continuedStatement)) {
            myExits.put(statement, new Exit(ExitType.CONTINUE, ((PsiLoopStatement)continuedStatement).getBody()));
          }
        }
      }

      @Override
      public void visitBreakStatement(PsiBreakStatement statement) {
        super.visitBreakStatement(statement);

        if (!myExpressionsOnly) {
          PsiElement exitedElement = statement.findExitedElement();
          if (exitedElement != null && !isInside(exitedElement)) {
            PsiElement outermostExited = findOutermostExitedElement(statement);
            myExits.put(statement, new Exit(ExitType.BREAK, outermostExited != null ? outermostExited : exitedElement));
          }
        }
      }

      @Override
      public void visitThrowStatement(PsiThrowStatement statement) {
        super.visitThrowStatement(statement);

        if (!myExpressionsOnly) {
          PsiTryStatement throwTarget = findThrowTarget(statement);
          if (throwTarget == null) {
            myExits.put(statement, new Exit(ExitType.THROW, myCodeFragmentMember));
          }
          else if (!isInside(throwTarget)) {
            myExits.put(statement, new Exit(ExitType.THROW, throwTarget));
          }
        }
      }

      @Override
      public void visitClass(PsiClass aClass) {
        myExpressionsOnly = true;
        try {
          super.visitClass(aClass);
        }
        finally {
          myExpressionsOnly = false;
        }
      }

      @Override
      public void visitLambdaExpression(PsiLambdaExpression expression) {
        myExpressionsOnly = true;
        try {
          super.visitLambdaExpression(expression);
        }
        finally {
          myExpressionsOnly = false;
        }
      }
    };

    for (PsiElement element : myElements) {
      element.accept(elementsVisitor);
    }
    if (myExpression != null) {
      myExits.put(null, new Exit(ExitType.EXPRESSION, myExpression));
    }
  }

  private void collectDeclaredInsideUsedAfter() {
    if (myExpression != null) {
      return;
    }
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
                myOutputVariables.add((PsiLocalVariable)resolved);
              }
            }
          }
        });
      }
    }
  }

  @Nullable("null means there's an error in the code")
  private PsiElement findOutermostExitedElement(@NotNull PsiElement startLocation) {
    PsiElement location = startLocation;
    while (true) {
      if (location == null || location instanceof PsiParameterListOwner || location instanceof PsiClassInitializer) {
        return location;
      }
      if (location instanceof PsiBreakStatement) {
        PsiElement exited = ((PsiBreakStatement)location).findExitedElement();
        if (exited == null) {
          return null;
        }
        location = exited;
        continue;
      }
      if (location instanceof PsiContinueStatement) {
        PsiStatement continued = ((PsiContinueStatement)location).findContinuedStatement();
        if (continued instanceof PsiLoopStatement && !ControlFlowUtils.statementMayCompleteNormally(continued)) {
          return ((PsiLoopStatement)continued).getBody();
        }
        if (continued == null) {
          return null;
        }
        location = continued;
        continue;
      }
      if (location instanceof PsiThrowStatement) {
        PsiTryStatement target = findThrowTarget((PsiThrowStatement)location);
        return target != null ? target.getTryBlock() : myCodeFragmentMember;
      }
      if (location instanceof PsiSwitchLabelStatementBase) {
        location = ((PsiSwitchLabelStatementBase)location).getEnclosingSwitchBlock();
        continue;
      }

      PsiElement parent = location.getParent();
      if (parent instanceof PsiLoopStatement && !ControlFlowUtils.statementMayCompleteNormally((PsiLoopStatement)parent)) {
        return ((PsiLoopStatement)parent).getBody();
      }
      if (parent instanceof PsiCodeBlock) {
        PsiStatement next = PsiTreeUtil.getNextSiblingOfType(location, PsiStatement.class);
        if (next != null) {
          return location;
        }
      }
      location = parent;
    }
  }

  @Nullable
  private PsiTryStatement findThrowTarget(@NotNull PsiThrowStatement statement) {
    PsiExpression exception = PsiUtil.skipParenthesizedExprDown(statement.getException());
    if (exception == null) {
      return null;
    }
    PsiClassType exactType = null;
    PsiClassType lowerBoundType = null;
    if (exception instanceof PsiNewExpression) {
      PsiType type = exception.getType();
      if (type instanceof PsiClassType) {
        PsiClass resolved = ((PsiClassType)type).resolve();
        if (resolved != null && !(resolved instanceof PsiAnonymousClass)) {
          exactType = lowerBoundType = (PsiClassType)type;
        }
      }
    }
    if (lowerBoundType == null) {
      lowerBoundType = tryCast(exception.getType(), PsiClassType.class);
    }
    if (lowerBoundType == null) {
      return null;
    }
    for (PsiElement element = statement; element != null && element != myCodeFragmentMember; element = element.getContext()) {
      PsiElement parent = element.getContext();
      if (parent instanceof PsiTryStatement && element == ((PsiTryStatement)parent).getTryBlock()) {
        for (PsiParameter parameter : ((PsiTryStatement)parent).getCatchBlockParameters()) {
          PsiType catchType = parameter.getType();
          if (exactType != null && catchType.isAssignableFrom(exactType)) {
            return ((PsiTryStatement)parent);
          }
          else if (exactType == null && ControlFlowUtil.isCaughtExceptionType(lowerBoundType, catchType)) {
            return ((PsiTryStatement)parent);
          }
        }
      }
    }
    return null;
  }

  private void findSequentialExit() {
    if (myExpression != null) {
      return;
    }
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

    PsiElement exitedElement = findOutermostExitedElement(myElements[myElements.length - 1]);
    if (exitedElement != null) {
      myExits.put(null, new Exit(ExitType.SEQUENTIAL, exitedElement));
    }else {
      myExits.put(null, new Exit(ExitType.UNDEFINED, null));
    }
  }

  private void processPossibleOutput(PsiVariable variable) {
    if (variable instanceof PsiLocalVariable || variable instanceof PsiParameter) {
      if (PsiTreeUtil.isAncestor(myCodeFragmentMember, variable, true)) {
        myOutputVariables.add(variable);
      }
    }
    else if (variable instanceof PsiField && variable.hasModifierProperty(PsiModifier.FINAL)) {
      if (myCodeFragmentMember.getParent() != null && myCodeFragmentMember.getParent() == variable.getParent()) {
        myOutputVariables.add(variable);
      }
    }
  }

  private void processPossibleInput(PsiReferenceExpression expression, PsiVariable variable) {
    if (variable instanceof PsiLocalVariable || variable instanceof PsiParameter) {
      if (!isInside(variable)) {
        myInputs.put(expression, variable);
      }
    }
  }

  private boolean isInside(@NotNull PsiElement element) {
    PsiElement context = PsiTreeUtil.findFirstContext(element, false,
                                                      e -> e == myCodeFragmentMember || ArrayUtil.find(myElements, e) >= 0);
    return context != myCodeFragmentMember;
  }

  boolean showDialog() {
    LOG.info("showDialog");
    return true;
  }

  void doRefactoring() {
    LOG.warn("doRefactoring");

    Set<ExitType> exitTypes = myExits.values().stream().map(e -> e.getType()).collect(Collectors.toSet());
    if (!exitTypes.contains(ExitType.UNDEFINED) && !exitTypes.contains(ExitType.THROW)) {
      List<ResultItem> resultItems = collectResultItems();
      PsiClass resultClass = createResultClass(resultItems);
      createMethod(resultItems, resultClass);
    }
    else {
      //dumpText("exit count: " + exitTypes.size());
      dumpText("too complex exits: " + exitTypes.stream().sorted().collect(Collectors.toList()));
    }

    dumpTexts(myExits.entrySet().stream().map(ExtractMethodWithResultObjectProcessor::getExitText), "exit");
    dumpElements(myOutputVariables, "out");
    dumpElements(new HashSet<>(myInputs.values()), "in");
    dumpText("ins and outs");
  }

  private List<ResultItem> collectResultItems() {
    if (myExpression != null) {
      assert myExits.size() == 1 : "one exit with expression";
      PsiType expressionType = RefactoringUtil.getTypeByExpressionWithExpectedType(myExpression);
      if (expressionType == null) {
        expressionType = PsiType.getJavaLangObject(myExpression.getManager(), GlobalSearchScope.allScope(myProject));
      }
      return Collections.singletonList(new ExpressionResultItem(myExpression, expressionType));
    }

    List<ResultItem> resultItems = new ArrayList<>();

    List<Exit> returnExits = ContainerUtil.filter(myExits.values(), exit -> ExitType.RETURN.equals(exit.getType()));
    if (!returnExits.isEmpty()) {
      PsiType returnType = null;
      if (myCodeFragmentMember instanceof PsiMethod) {
        returnType = ((PsiMethod)myCodeFragmentMember).getReturnType();
      }
      else if (myCodeFragmentMember instanceof PsiLambdaExpression) {
        returnType = LambdaUtil.getFunctionalInterfaceReturnType((PsiLambdaExpression)myCodeFragmentMember);
      }
      if (returnType == null) {
        returnType = PsiType.getJavaLangObject(myCodeFragmentMember.getManager(), GlobalSearchScope.allScope(myProject));
      }
      if (!PsiType.VOID.equals(returnType)) {
        resultItems.add(new ReturnResultItem(returnType));
      }
    }

    StreamEx.of(myOutputVariables)
      .mapToEntry(v -> v.getName(), v -> v)
      .filterKeys(Objects::nonNull)
      .sortedBy(e -> e.getKey())
      .forKeyValue((name, variable) -> resultItems.add(new VariableResultItem(variable, name)));

    return resultItems;
  }

  private PsiClass createResultClass(List<ResultItem> resultItems) {
    PsiElementFactory factory = JavaPsiFacade.getElementFactory(myProject);

    PsiClass resultClass = (PsiClass)myAnchor.getParent().addAfter(factory.createClass("NewMethodResult"), myAnchor);
    for (ResultItem resultItem : resultItems) {
      resultItem.createField(resultClass, factory);
    }

    PsiMethod constructor = (PsiMethod)resultClass.add(factory.createConstructor(notNull(resultClass.getName())));
    PsiParameterList constructorParameterList = notNull(constructor.getParameterList());
    for (ResultItem resultItem : resultItems) {
      resultItem.createConstructorParameter(constructorParameterList, factory);
    }

    PsiCodeBlock constructorBody = notNull(constructor.getBody());
    for (ResultItem resultItem : resultItems) {
      resultItem.createAssignmentInConstructor(constructorBody, factory);
    }

    return resultClass;
  }

  private void createMethod(List<ResultItem> resultItems, PsiClass resultClass) {
    PsiElementFactory factory = JavaPsiFacade.getElementFactory(myProject);

    PsiMethod method = factory.createMethod("newMethod", factory.createType(resultClass), myAnchor);
    PsiParameterList parameterList = method.getParameterList();

    Map<PsiVariable, Pair<PsiExpression, Integer>> orderedInputs = new HashMap<>();
    myInputs.forEach((expression, variable) -> {
      int offset = expression.getTextOffset();
      Pair<PsiExpression, Integer> pair = orderedInputs.get(variable);
      if (pair == null || pair.second > offset) {
        orderedInputs.put(variable, new Pair<>(expression, offset));
      }
    });

    EntryStream.of(orderedInputs)
      .sortedBy(entry -> entry.getValue().second)
      .forKeyValue((variable, pair) -> {
        String name = notNull(variable.getName());
        myParameters.add(Pair.createNonNull(name, pair.first));

        PsiParameter parameter = factory.createParameter(name, variable.getType(), method);
        parameter = (PsiParameter)parameterList.add(parameter);
        notNull(parameter.getModifierList()).setModifierProperty(PsiModifier.FINAL,
                                                                 variable.hasModifierProperty(PsiModifier.FINAL));
      });

    PsiCodeBlock body = method.getBody();
    assert body != null;
    if (myExpression == null) {
      body.addRange(myElements[0], myElements[myElements.length - 1]);
    }
    Set<ExitType> exitTypes = myExits.values().stream().map(Exit::getType).collect(Collectors.toSet());
    PsiStatement[] originalBodyStatements = body.getStatements();

    if (exitTypes.contains(ExitType.RETURN)) {
      List<PsiReturnStatement> statementsToReplace = collectStatements(originalBodyStatements, PsiReturnStatement.class);
      for (PsiReturnStatement statementToReplace : statementsToReplace) {
        PsiReturnStatement returnStatement = createReturnStatement(resultItems, body, statementToReplace.getReturnValue(), factory);
        assert returnStatement != null : "returnStatement";
        statementToReplace.replace(returnStatement);
      }
    }

    if (exitTypes.contains(ExitType.SEQUENTIAL) || exitTypes.contains(ExitType.EXPRESSION)) {
      body.add(createReturnStatement(resultItems, body, null, factory));
    }

    myAnchor.getParent().addAfter(method, myAnchor);
  }

  @NotNull
  private static <T extends PsiStatement> List<T> collectStatements(PsiStatement[] bodyStatements, Class<T> statementClass) {
    List<T> results = new ArrayList<>();
    JavaRecursiveElementWalkingVisitor visitor = new JavaRecursiveElementWalkingVisitor() {
      @Override
      public void visitReturnStatement(PsiReturnStatement statement) {
        super.visitReturnStatement(statement);
        addStatement(statement);
      }

      @Override
      public void visitBreakStatement(PsiBreakStatement statement) {
        super.visitBreakStatement(statement);
        addStatement(statement);
      }

      @Override
      public void visitContinueStatement(PsiContinueStatement statement) {
        super.visitContinueStatement(statement);
        addStatement(statement);
      }

      private void addStatement(PsiStatement statement) {
        if (statementClass.isInstance(statement)) {
          results.add(statementClass.cast(statement));
        }
      }

      @Override
      public void visitClass(PsiClass aClass) {}

      @Override
      public void visitLambdaExpression(PsiLambdaExpression expression) {}
    };
    for (PsiStatement bodyStatement : bodyStatements) {
      bodyStatement.accept(visitor);
    }
    return results;
  }

  private static PsiReturnStatement createReturnStatement(List<ResultItem> resultItems,
                                                          PsiCodeBlock body,
                                                          PsiExpression expression,
                                                          PsiElementFactory factory) {
    PsiReturnStatement returnStatement = (PsiReturnStatement)factory.createStatementFromText("return 0;", body.getRBrace());
    PsiNewExpression returnExpression = (PsiNewExpression)notNull(returnStatement.getReturnValue())
      .replace(factory.createExpressionFromText("new NewMethodResult()", returnStatement));
    PsiExpressionList returnArgumentList = notNull(returnExpression.getArgumentList());
    for (ResultItem resultItem : resultItems) {
      PsiExpression resultObjectArgument = resultItem.createResultObjectArgument(expression, body, factory);
      assert resultObjectArgument != null : "resultObjectArgument";
      returnArgumentList.add(notNull(resultObjectArgument));
    }
    return returnStatement;
  }

  private void dumpElements(Collection<? extends PsiElement> elements, String prefix) {
    dumpTexts(elements.stream().map(PsiElement::toString), prefix);
  }

  private void dumpTexts(Stream<String> elements, String prefix) {
    elements
      .sorted(Comparator.reverseOrder())
      .forEach(text -> dumpText(prefix + ": " + text));
  }

  private void dumpText(String text) {
    PsiElementFactory factory = JavaPsiFacade.getElementFactory(myProject);
    PsiElement comment = factory.createCommentFromText("//" + text, null);
    myAnchor.getParent().addAfter(comment, myAnchor);
    LOG.warn(text);
  }

  private static String getExitText(Map.Entry<PsiStatement, Exit> e) {
    Exit exit = e.getValue();
    PsiStatement statement = e.getKey();
    if (statement instanceof PsiReturnStatement) {
      PsiExpression value = ((PsiReturnStatement)statement).getReturnValue();
      return exit + "<-" + (value != null ? value.toString() : PsiKeyword.VOID);
    }
    if (statement != null) {
      return exit + "<-" + statement;
    }
    return exit.toString();
  }

  private void chooseTargetClass() {
    myTargetClass = myCodeFragmentMember instanceof PsiMember
                    ? ((PsiMember)myCodeFragmentMember).getContainingClass()
                    : PsiTreeUtil.getParentOfType(myCodeFragmentMember, PsiClass.class);
  }

  private void chooseAnchor() {
    myAnchor = myCodeFragmentMember;
    while (!myAnchor.getParent().equals(myTargetClass)) {
      myAnchor = myAnchor.getParent();
    }
  }

  private static abstract class ResultItem {
    protected final String myFieldName; // todo resolve name conflicts
    protected final PsiType myType;

    ResultItem(String fieldName, PsiType type) {
      myFieldName = fieldName;
      myType = type;
    }

    void createField(PsiClass resultClass, PsiElementFactory factory) {
      PsiField field = (PsiField)resultClass.add(factory.createField(myFieldName, myType));
      notNull(field.getModifierList()).setModifierProperty(PsiModifier.PRIVATE, true);
    }

    void createConstructorParameter(PsiParameterList parameterList, PsiElementFactory factory) {
      parameterList.add(factory.createParameter(myFieldName, myType));
    }

    void createAssignmentInConstructor(PsiCodeBlock body, PsiElementFactory factory) {
      body.add(factory.createStatementFromText(PsiKeyword.THIS + '.' + myFieldName + '=' + myFieldName + ';', body.getRBrace()));
    }

    abstract PsiExpression createResultObjectArgument(PsiExpression expression, PsiCodeBlock body, PsiElementFactory factory);
  }

  private static class VariableResultItem extends ResultItem {
    private final PsiVariable myVariable;

    VariableResultItem(@NotNull PsiVariable variable, @NotNull String variableName) {
      super(variableName, variable.getType());
      myVariable = variable;
    }

    @Override
    PsiExpression createResultObjectArgument(PsiExpression expression, PsiCodeBlock body, PsiElementFactory factory) {
      return factory.createExpressionFromText(myFieldName, body.getRBrace());
    }
  }

  private static class ExpressionResultItem extends ResultItem {
    private final PsiExpression myExpression;

    ExpressionResultItem(@NotNull PsiExpression expression, PsiType type) {
      super("expressionResult", type);
      myExpression = expression;
    }

    @Override
    PsiExpression createResultObjectArgument(PsiExpression expression, PsiCodeBlock body, PsiElementFactory factory) {
      return myExpression;
    }
  }

  private static class ReturnResultItem extends ResultItem {
    ReturnResultItem(@NotNull PsiType type) {
      super("returnResult", type);
    }

    @Override
    PsiExpression createResultObjectArgument(PsiExpression expression, PsiCodeBlock body, PsiElementFactory factory) {
      if (expression == null) {
        String text = myType instanceof PsiPrimitiveType ? PsiTypesUtil.getDefaultValueOfType(myType) : PsiKeyword.NULL;
        return factory.createExpressionFromText('(' + text + " /* missing value */" + ')', body.getRBrace());
      }
      return expression;
    }
  }
}
