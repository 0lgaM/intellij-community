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
package com.intellij.compiler.backwardRefs

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.jps.backwardRefs.LightRef
import org.jetbrains.jps.backwardRefs.SignatureData

class MethodIncompleteSignature(val ref: LightRef.JavaLightMethodRef,
                                private val signatureData: SignatureData,
                                private val refService: CompilerReferenceServiceEx) {
  companion object {
    val CONSTRUCTOR_METHOD_NAME = "<init>"
  }

  val name: String by lazy {
    refService.getName(ref.name)
  }

  val owner: String by lazy {
    refService.getName(ref.owner.name)
  }

  val rawReturnType: String by lazy {
    refService.getName(signatureData.rawReturnType)
  }

  val parameterCount: Int
    get() = ref.parameterCount

  val isStatic: Boolean
    get() = signatureData.isStatic

  fun resolveQualifier(project: Project, resolveScope: GlobalSearchScope) = JavaPsiFacade.getInstance(project).findClass(owner, resolveScope)

  fun resolve(project: Project, resolveScope: GlobalSearchScope): Array<PsiMethod> {
    if (CONSTRUCTOR_METHOD_NAME == name) {
      return PsiMethod.EMPTY_ARRAY
    }
    val aClass = resolveQualifier(project, resolveScope) ?: return PsiMethod.EMPTY_ARRAY
    return aClass.findMethodsByName(name, true)
      .filter { it.hasModifierProperty(PsiModifier.STATIC) == isStatic }
      .filter {
        val returnType = it.returnType
        returnType is PsiClassType && returnType.resolve()?.qualifiedName == rawReturnType
      }
      .sortedBy({ it.parameterList.parametersCount })
      .toTypedArray()
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other?.javaClass != javaClass) return false

    other as MethodIncompleteSignature

    if (ref != other.ref) return false
    if (signatureData != other.signatureData) return false

    return true
  }

  override fun hashCode(): Int {
    var result = ref.hashCode()
    result = 31 * result + signatureData.hashCode()
    return result
  }

  override fun toString(): String {
    return owner + (if (isStatic) "" else "#") + name + "(" + parameterCount + ")"
  }
}