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
package com.intellij.codeInsight.intention.impl

import com.intellij.codeInsight.daemon.QuickFixBundle
import com.intellij.codeInsight.daemon.impl.quickfix.AddConstructorFix
import com.intellij.codeInsight.daemon.impl.quickfix.ModifierFix
import com.intellij.codeInsight.intention.*
import com.intellij.lang.jvm.*
import com.intellij.lang.jvm.types.JvmType
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.impl.beanProperties.CreateJavaBeanPropertyFix
import com.intellij.psi.util.PsiFormatUtil
import com.intellij.psi.util.PsiFormatUtilBase

class JavaCommonIntentionActionsFactory : JvmCommonIntentionActionsFactory() {

  override fun createChangeJvmModifierAction(declaration: JvmModifiersOwner,
                                             modifier: JvmModifier,
                                             shouldPresent: Boolean): IntentionAction {
    declaration as PsiModifierListOwner
    return ModifierFix(declaration.modifierList, JavaJvmElementRenderer.render(modifier), shouldPresent, false)
  }

  override fun createAddCallableMemberActions(info: MethodInsertionInfo): List<IntentionAction> {
    return when (info) {
      is MethodInsertionInfo.Method -> with(info) {
        createAddMethodAction(targetClass, name, modifiers,
                              resultType, callParameters)
          ?.let { listOf(it) } ?: emptyList()
      }

      is MethodInsertionInfo.Constructor -> {
        val targetClass = info.targetClass.jvmJavaPsi<PsiClass>()
        val factory = JVMElementFactories.getFactory(targetClass.language, targetClass.project)!!
        listOf(AddConstructorFix(targetClass, info.callParameters.mapIndexed { i, it ->
          factory.createParameter(it.name ?: "arg$i", JavaJvmElementMaterializer.materialize(it.type), targetClass)
        }))
      }
    }
  }

  private fun createAddMethodAction(psiClass: JvmClass,
                                    methodName: String,
                                    visibilityModifier: List<JvmModifier>,
                                    returnType: JvmType,
                                    parameters: List<JvmParameter>): IntentionAction? {
    val psiClass = psiClass.jvmJavaPsi<PsiClass>()
    val signatureString = with(JavaJvmElementRenderer) {
      val paramsString = parameters.mapIndexed { i, t -> "${render(t.type)} ${t.name ?: "arg$i"}" }.joinToString()
      "${render(visibilityModifier)} ${render(returnType)} $methodName($paramsString){}"
    }
    val targetClassPointer = SmartPointerManager.getInstance(psiClass.project).createSmartPsiElementPointer(psiClass)
    return object : AbstractIntentionAction() {

      private val text = targetClassPointer.element?.let { psiClass ->
        QuickFixBundle.message("create.method.from.usage.text",
                               PsiFormatUtil.formatMethod(createMethod(psiClass), PsiSubstitutor.EMPTY,
                                                          PsiFormatUtilBase.SHOW_NAME or
                                                            PsiFormatUtilBase.SHOW_TYPE or
                                                            PsiFormatUtilBase.SHOW_PARAMETERS or
                                                            PsiFormatUtilBase.SHOW_RAW_TYPE,
                                                          PsiFormatUtilBase.SHOW_TYPE or PsiFormatUtilBase.SHOW_RAW_TYPE, 2))
      } ?: ""

      override fun getText(): String = text

      override fun invoke(project: Project, editor: Editor?, file: PsiFile) {
        val targetClass = targetClassPointer.element ?: return
        runWriteAction {
          val addedMethod = targetClass.add(createMethod(targetClass))
          JavaCodeStyleManager.getInstance(project).shortenClassReferences(addedMethod)
        }
      }

      private fun createMethod(targetClass: PsiClass): PsiMethod {
        val elementFactory = JVMElementFactories.getFactory(targetClass.language, targetClass.project) // it could be Groovy
                             ?: JavaPsiFacade.getElementFactory(targetClass.project)
        return elementFactory.createMethodFromText(signatureString, targetClass)
      }
    }
  }

  private inline fun <reified T : PsiElement> T.javaPsi(): T =
    when (this) {
      is org.jetbrains.uast.UElement -> this.psi as T
      else -> this
    }

  private inline fun <reified T : PsiElement> JvmElement.jvmJavaPsi(): T {
    return when (this) {
      is PsiElement -> this.javaPsi() as T
      else -> throw UnsupportedOperationException("cant convert $this to ${T::class.java}")
    }
  }

  override fun createAddJvmPropertyActions(psiClass: JvmClass,
                                           propertyName: String,
                                           visibilityModifier: JvmModifier,
                                           propertyType: JvmType,
                                           setterRequired: Boolean,
                                           getterRequired: Boolean): List<IntentionAction> {
    val psiClass = psiClass.jvmJavaPsi<PsiClass>()
    val propertyType = JavaJvmElementMaterializer.materialize(propertyType)
    if (getterRequired && setterRequired)
      return listOf<IntentionAction>(
        CreateJavaBeanPropertyFix(psiClass, propertyName, propertyType, getterRequired, setterRequired,
                                  true),
        CreateJavaBeanPropertyFix(psiClass, propertyName, propertyType, getterRequired, setterRequired,
                                  false))
    if (getterRequired || setterRequired)
      return listOf<IntentionAction>(
        CreateJavaBeanPropertyFix(psiClass, propertyName, propertyType, getterRequired, setterRequired,
                                  true),
        CreateJavaBeanPropertyFix(psiClass, propertyName, propertyType, getterRequired, setterRequired,
                                  false),
        CreateJavaBeanPropertyFix(psiClass, propertyName, propertyType, true, true, true))

    return listOf<IntentionAction>(
      CreateJavaBeanPropertyFix(psiClass, propertyName, propertyType, getterRequired, setterRequired, true))
  }

}