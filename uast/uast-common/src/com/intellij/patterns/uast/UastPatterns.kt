/*
 * Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */
@file:JvmName("UastPatterns")

package com.intellij.patterns.uast

import com.intellij.patterns.*
import com.intellij.patterns.StandardPatterns.string
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.util.ProcessingContext
import org.jetbrains.annotations.NonNls
import org.jetbrains.uast.*

fun literalExpression(): ULiteralExpressionPattern = ULiteralExpressionPattern()

fun stringLiteralExpression(): ULiteralExpressionPattern = literalExpression().filter(ULiteralExpression::isStringLiteral)

fun callExpression(): UCallExpressionPattern = UCallExpressionPattern()

fun uExpression(): UExpressionPattern<UExpression, *> = expressionCapture(UExpression::class.java)

fun <T : UElement> capture(clazz: Class<T>): UElementPattern.Capture<T> = UElementPattern.Capture(clazz)

fun <T : UExpression> expressionCapture(clazz: Class<T>): UExpressionPattern.Capture<T> = UExpressionPattern.Capture(clazz)

open class UElementPattern<T : UElement, Self : UElementPattern<T, Self>>(clazz: Class<T>) : ObjectPattern<T, Self>(clazz) {
  fun withSourcePsiCondition(pattern: PatternCondition<PsiElement>): Self =
    this.with(object : PatternCondition<T>("withSourcePsiPattern") {
      override fun accepts(t: T, context: ProcessingContext?): Boolean {
        val sourcePsiElement = t.sourcePsiElement ?: return false
        return pattern.accepts(sourcePsiElement, context)
      }
    })

  fun sourcePsiFilter(filter: (PsiElement) -> Boolean): Self =
    withSourcePsiCondition(object : PatternCondition<PsiElement>("sourcePsiFilter") {
      override fun accepts(t: PsiElement, context: ProcessingContext?): Boolean = filter(t)
    })

  fun filterWithContext(filter: (T, ProcessingContext?) -> Boolean): Self =
    with(object : PatternCondition<T>(null) {
      override fun accepts(t: T, context: ProcessingContext?): Boolean = filter.invoke(t, context)
    })

  fun filter(filter: (T) -> Boolean): Self = filterWithContext { t, processingContext -> filter(t) }

  fun inCall(callPattern: ElementPattern<UCallExpression>): Self =
    filter { it.getUCallExpression()?.let { callPattern.accepts(it) } ?: false }

  fun callParameter(parameterIndex: Int, callPattern: ElementPattern<UCallExpression>): Self =
    filter {
      val call = it.uastParent.getUCallExpression() as? UCallExpressionEx ?: return@filter false
      call.getArgumentForParameter(parameterIndex) == it && callPattern.accepts(call)
    }

  fun constructorParameter(parameterIndex: Int, classFQN: String): Self = callParameter(parameterIndex, callExpression().constructor(classFQN))

  fun methodCallParameter(parameterIndex: Int, methodPattern: ElementPattern<out PsiMethod>): Self =
    callParameter(parameterIndex, callExpression().withAnyResolvedMethod(methodPattern))

  class Capture<T : UElement>(clazz: Class<T>) : UElementPattern<T, Capture<T>>(clazz)
}

class UCallExpressionPattern : UElementPattern<UCallExpression, UCallExpressionPattern>(UCallExpression::class.java) {

  fun withReceiver(classPattern: ElementPattern<PsiClass>): UCallExpressionPattern =
    filter { (it.receiverType as? PsiClassType)?.resolve()?.let { classPattern.accepts(it) } ?: false }

  fun withMethodName(methodName : String): UCallExpressionPattern = withMethodName(string().equalTo(methodName))

  fun withAnyResolvedMethod(method: ElementPattern<out PsiMethod>): UCallExpressionPattern = filter { uCallExpression ->
    when (uCallExpression) {
      is UMultiResolvable -> uCallExpression.multiResolve().any { method.accepts(it.element) }
      else -> uCallExpression.resolve().let { method.accepts(it) }
    }
  }

  fun withMethodName(namePattern: ElementPattern<String>): UCallExpressionPattern = filter { it.methodName?.let { namePattern.accepts(it) } ?: false }

  fun constructor(classPattern: ElementPattern<PsiClass>): UCallExpressionPattern = filter {
    val psiMethod = it.resolve() ?: return@filter false;
    psiMethod.isConstructor && classPattern.accepts(psiMethod.containingClass)
  }

  fun constructor(className: String): UCallExpressionPattern = constructor(PsiJavaPatterns.psiClass().withQualifiedName(className))

}

open class UExpressionPattern<T : UExpression, Self : UExpressionPattern<T, Self>>(clazz: Class<T>) : UElementPattern<T, Self>(clazz) {

  fun annotationParam(@NonNls parameterName: String, annotationPattern: ElementPattern<UAnnotation>): Self =
    annotationParams(annotationPattern, StandardPatterns.string().equalTo(parameterName))

  fun annotationParams(annotationPattern: ElementPattern<UAnnotation>, parameterNames: ElementPattern<String>): Self =
    this.with(object : PatternCondition<T>("annotationParam") {

      override fun accepts(uElement: T, context: ProcessingContext?): Boolean {
        val namedExpression = uElement.getParentOfType<UNamedExpression>(true) ?: return false
        if (!parameterNames.accepts(namedExpression.name ?: "value")) return false
        val annotation = namedExpression.getParentOfType<UAnnotation>(true) ?: return false
        return (annotationPattern.accepts(annotation, context))
      }
    })

  fun annotationParam(annotationQualifiedName: ElementPattern<String>, @NonNls parameterName: String): Self =
    annotationParam(parameterName, qualifiedNamePattern(annotationQualifiedName))

  private fun qualifiedNamePattern(annotationQualifiedName: ElementPattern<String>): UElementPattern<UAnnotation, *> =
    capture(UAnnotation::class.java).filter { it.qualifiedName?.let { annotationQualifiedName.accepts(it) } ?: false }

  fun annotationParam(@NonNls annotationQualifiedName: String, @NonNls parameterName: String): Self =
    annotationParam(StandardPatterns.string().equalTo(annotationQualifiedName), parameterName)

  fun annotationParams(@NonNls annotationQualifiedName: String, @NonNls parameterNames: ElementPattern<String>): Self =
    annotationParams(qualifiedNamePattern(StandardPatterns.string().equalTo(annotationQualifiedName)), parameterNames)

  open class Capture<T : UExpression>(clazz: Class<T>) : UExpressionPattern<T, UExpressionPattern.Capture<T>>(clazz)
}

class ULiteralExpressionPattern : UExpressionPattern<ULiteralExpression, ULiteralExpressionPattern>(ULiteralExpression::class.java)