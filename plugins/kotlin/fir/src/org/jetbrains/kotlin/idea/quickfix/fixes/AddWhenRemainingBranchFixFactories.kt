// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.quickfix.fixes

import org.jetbrains.kotlin.diagnostics.WhenMissingCase
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.codeinsight.api.applicators.KotlinApplicator
import org.jetbrains.kotlin.idea.codeinsight.api.applicators.KotlinApplicatorInput
import org.jetbrains.kotlin.idea.codeinsight.api.applicators.applicator
import org.jetbrains.kotlin.idea.codeinsight.api.applicators.fixes.KotlinApplicatorBasedQuickFix
import org.jetbrains.kotlin.idea.codeinsight.api.applicators.fixes.diagnosticFixFactory
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.ShortenOption
import org.jetbrains.kotlin.analysis.api.fir.diagnostics.KtFirDiagnostic
import org.jetbrains.kotlin.analysis.api.symbols.KtClassKind
import org.jetbrains.kotlin.analysis.api.KtAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.lifetime.allowAnalysisOnEdt
import org.jetbrains.kotlin.idea.util.generateWhenBranches
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtWhenExpression

object AddWhenRemainingBranchFixFactories {
    class Input(val whenMissingCases: List<WhenMissingCase>, val enumToStarImport: ClassId?) : KotlinApplicatorInput

    val applicator: KotlinApplicator<KtWhenExpression, Input> = getApplicator(false)
    val applicatorUsingStarImport: KotlinApplicator<KtWhenExpression, Input> = getApplicator(true)

    @OptIn(KtAllowAnalysisOnEdt::class)
    private fun getApplicator(useStarImport: Boolean = false) = applicator<KtWhenExpression, Input> {
        familyAndActionName(
            if (useStarImport) KotlinBundle.lazyMessage("fix.add.remaining.branches.with.star.import")
            else KotlinBundle.lazyMessage("fix.add.remaining.branches")
        )
        applyTo { whenExpression, input ->
            if (useStarImport) assert(input.enumToStarImport != null)
            generateWhenBranches(whenExpression, input.whenMissingCases)
            val shortenCommand = allowAnalysisOnEdt {
                analyze(whenExpression) {
                    collectPossibleReferenceShorteningsInElement(
                        whenExpression,
                        callableShortenOption = {
                            if (useStarImport && it.callableIdIfNonLocal?.classId == input.enumToStarImport) {
                                ShortenOption.SHORTEN_AND_STAR_IMPORT
                            } else {
                                ShortenOption.DO_NOT_SHORTEN
                            }
                        })

                }
            }
            shortenCommand.invokeShortening()
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    val noElseInWhen = diagnosticFixFactory(KtFirDiagnostic.NoElseInWhen::class) { diagnostic ->
        val whenExpression = diagnostic.psi
        val subjectExpression = whenExpression.subjectExpression ?: return@diagnosticFixFactory emptyList()

        buildList {
            val missingCases = diagnostic.missingWhenCases.takeIf {
                it.isNotEmpty() && it.singleOrNull() != WhenMissingCase.Unknown
            } ?: return@buildList

            add(KotlinApplicatorBasedQuickFix(whenExpression, Input(missingCases, null), applicator))
            val baseClassSymbol = subjectExpression.getKtType()?.expandedClassSymbol ?: return@buildList
            val enumToStarImport = baseClassSymbol.classIdIfNonLocal
            if (baseClassSymbol.classKind == KtClassKind.ENUM_CLASS && enumToStarImport != null) {
                add(KotlinApplicatorBasedQuickFix(whenExpression, Input(missingCases, enumToStarImport), applicatorUsingStarImport))
            }
        }
    }
}