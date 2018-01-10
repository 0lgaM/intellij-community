/*
 * Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */
/*
 * Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */
package org.jetbrains.plugins.groovy.lang.psi.uast

import com.intellij.psi.*
import org.jetbrains.plugins.groovy.lang.psi.api.auxiliary.modifiers.GrModifierList
import org.jetbrains.plugins.groovy.lang.psi.api.statements.params.GrParameter
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.GrClassDefinition
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrMethod
import org.jetbrains.uast.*

class GrUClass(val grElement: GrClassDefinition, parentProvider: () -> UElement?) : UClass, JvmDeclarationUElement, PsiClass by grElement {

  override val sourcePsi = grElement

  override val javaPsi: PsiClass = grElement

  override val psi = sourcePsi

  override fun getQualifiedName(): String? = grElement.qualifiedName

  override val uastSuperTypes: List<UTypeReferenceExpression> = emptyList() //not implemented

  override val uastDeclarations: List<UDeclaration> = emptyList() //not implemented

  override val uastAnchor: UElement?
    get() = UIdentifier(psi.nameIdentifier, this)

  override val uastParent by lazy(parentProvider)

  override val annotations: List<UAnnotation> by lazy { grAnnotations(grElement.modifierList, this) }

  override fun getSuperClass(): UClass? = super.getSuperClass()

  override fun getFields(): Array<UField> = super.getFields()

  override fun getInitializers(): Array<UClassInitializer> = super.getInitializers()

  override fun getMethods(): Array<UMethod> = grElement.codeMethods.map { GrUMethod(it, { this }) }.toTypedArray()

  override fun getInnerClasses(): Array<UClass> = super.getInnerClasses()
  override fun getOriginalElement(): PsiElement = grElement.originalElement
}


class GrUMethod(val grElement: GrMethod, parentProvider: () -> UElement?) : UMethod, JvmDeclarationUElement, PsiMethod by grElement {
  override val uastParent: UElement? by lazy(parentProvider)

  override val sourcePsi: PsiElement = grElement
  override val javaPsi: PsiMethod = grElement

  override val psi: PsiMethod = javaPsi

  override val uastBody: UExpression? = null //not implemented

  override val uastParameters: List<UParameter> by lazy { grElement.parameters.map { GrUParameter(it, { this }) } }

  override val isOverride: Boolean by lazy { psi.modifierList.findAnnotation("java.lang.Override") != null }

  override val uastAnchor: UElement?
    get() = UIdentifier((psi.originalElement as? PsiNameIdentifierOwner)?.nameIdentifier ?: psi.nameIdentifier, this)

  override val annotations: List<UAnnotation> by lazy { grAnnotations(grElement.modifierList, this) }

  override fun getBody(): PsiCodeBlock? = grElement.body

  override fun getOriginalElement(): PsiElement? = sourcePsi.originalElement

}

class GrUParameter(val grElement: GrParameter,
                   parentProvider: () -> UElement?) : UParameter, JvmDeclarationUElement, PsiParameter by grElement {
  override val uastParent: UElement? by lazy(parentProvider)

  override val sourcePsi: PsiElement = grElement
  override val javaPsi: PsiParameter = grElement

  override val psi = javaPsi

  override val uastInitializer by lazy {
    val initializer = psi.initializer ?: return@lazy null
    getLanguagePlugin().convertElement(initializer, this) as? UExpression
  }

  override val typeReference: UTypeReferenceExpression? = null //not implemented

  override val uastAnchor: UElement
    get() = UIdentifier(psi.nameIdentifier, this)

  override val annotations: List<UAnnotation> by lazy { grAnnotations(grElement.modifierList, this) }

  override fun getInitializer(): PsiExpression? = grElement.initializer

  override fun getOriginalElement(): PsiElement? = sourcePsi.originalElement
}

private fun grAnnotations(modifierList: GrModifierList?, parent: UElement): List<GrUAnnotation> =
  modifierList?.rawAnnotations?.map { GrUAnnotation(it, { parent }) } ?: emptyList()

