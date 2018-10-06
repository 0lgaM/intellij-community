// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.groovy.lang.psi.impl.statements.expressions

import org.jetbrains.plugins.groovy.lang.psi.api.GroovyResolveResult
import org.jetbrains.plugins.groovy.lang.psi.impl.GroovyReferenceBase

class GrReferenceExpressionReference(
  ref: GrReferenceExpressionImpl,
  private val forceRValue: Boolean
) : GroovyReferenceBase<GrReferenceExpressionImpl>(ref) {

  override fun doResolve(incomplete: Boolean): Collection<GroovyResolveResult> {
    return element.doPolyResolve(incomplete, forceRValue)
  }
}
