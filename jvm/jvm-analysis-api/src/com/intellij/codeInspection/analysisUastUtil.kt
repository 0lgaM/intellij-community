// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInspection

import com.intellij.psi.LambdaUtil
import com.intellij.psi.PsiType
import org.jetbrains.uast.*

fun ULambdaExpression.getReturnType(): PsiType? {
  val lambdaType = functionalInterfaceType
                   ?: getExpressionType()
                   ?: uastParent?.let {
                     when (it) {
                       is UVariable -> it.type // in Kotlin local functions looks like lambda stored in variable
                       is UCallExpression -> it.getParameterForArgument(this)?.type
                       else -> null
                     }
                   }
  return LambdaUtil.getFunctionalInterfaceReturnType(lambdaType)
}

fun UAnnotated.findAnnotations(fqNames: Collection<String>) = uAnnotations.filter { ann -> fqNames.contains(ann.qualifiedName) }