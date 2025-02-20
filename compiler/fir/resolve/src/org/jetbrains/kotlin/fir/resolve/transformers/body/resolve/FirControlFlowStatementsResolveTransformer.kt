/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.transformers.body.resolve

import org.jetbrains.kotlin.fir.FirTargetElement
import org.jetbrains.kotlin.fir.diagnostics.ConeSimpleDiagnostic
import org.jetbrains.kotlin.fir.diagnostics.DiagnosticKind
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.expressions.impl.FirElseIfTrueCondition
import org.jetbrains.kotlin.fir.expressions.impl.FirEmptyExpressionBlock
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.ResolutionMode
import org.jetbrains.kotlin.fir.resolve.calls.isUnitOrFlexibleUnit
import org.jetbrains.kotlin.fir.resolve.transformers.FirSyntheticCallGenerator
import org.jetbrains.kotlin.fir.resolve.transformers.FirWhenExhaustivenessTransformer
import org.jetbrains.kotlin.fir.resolve.withExpectedType
import org.jetbrains.kotlin.fir.resolvedTypeFromPrototype
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.types.builder.buildErrorTypeRef
import org.jetbrains.kotlin.fir.visitors.transformSingle

class FirControlFlowStatementsResolveTransformer(transformer: FirBodyResolveTransformer) :
    FirPartialBodyResolveTransformer(transformer) {

    private val syntheticCallGenerator: FirSyntheticCallGenerator get() = components.syntheticCallGenerator
    private val whenExhaustivenessTransformer = FirWhenExhaustivenessTransformer(components)


    // ------------------------------- Loops -------------------------------

    override fun transformWhileLoop(whileLoop: FirWhileLoop, data: ResolutionMode): FirStatement {
        val context = ResolutionMode.ContextIndependent
        return whileLoop.also(dataFlowAnalyzer::enterWhileLoop)
            .transformCondition(transformer, context).also(dataFlowAnalyzer::exitWhileLoopCondition)
            .transformBlock(transformer, context).also(dataFlowAnalyzer::exitWhileLoop)
            .transformOtherChildren(transformer, context)
    }

    override fun transformDoWhileLoop(doWhileLoop: FirDoWhileLoop, data: ResolutionMode): FirStatement {
        // Do-while has a specific scope structure (its block and condition effectively share the scope)
        return context.forBlock(session) {
            val context = ResolutionMode.ContextIndependent
            doWhileLoop.also(dataFlowAnalyzer::enterDoWhileLoop)
                .also {
                    transformer.expressionsTransformer.transformBlockInCurrentScope(it.block, context)
                }
                .also(dataFlowAnalyzer::enterDoWhileLoopCondition).transformCondition(transformer, context)
                .also(dataFlowAnalyzer::exitDoWhileLoop)
                .transformOtherChildren(transformer, context)
        }
    }

    // ------------------------------- When expressions -------------------------------

    override fun transformWhenExpression(whenExpression: FirWhenExpression, data: ResolutionMode): FirStatement {
        if (whenExpression.calleeReference is FirResolvedNamedReference && whenExpression.resultType !is FirImplicitTypeRef) {
            return whenExpression
        }
        whenExpression.annotations.forEach { it.accept(this, data) }
        dataFlowAnalyzer.enterWhenExpression(whenExpression)
        return context.withWhenExpression(whenExpression, session) with@{
            @Suppress("NAME_SHADOWING")
            var whenExpression = whenExpression.transformSubject(transformer, ResolutionMode.ContextIndependent)

            when {
                whenExpression.branches.isEmpty() -> {}
                whenExpression.isOneBranch() -> {
                    whenExpression = whenExpression.transformBranches(transformer, ResolutionMode.ContextIndependent)
                    whenExpression.resultType = whenExpression.branches.first().result.resultType
                }
                else -> {
                    whenExpression = whenExpression.transformBranches(transformer, ResolutionMode.ContextDependent)

                    whenExpression = syntheticCallGenerator.generateCalleeForWhenExpression(whenExpression, resolutionContext) ?: run {
                        whenExpression = whenExpression.transformSingle(whenExhaustivenessTransformer, null)
                        dataFlowAnalyzer.exitWhenExpression(whenExpression)
                        whenExpression.resultType = buildErrorTypeRef {
                            diagnostic = ConeSimpleDiagnostic("Can't resolve when expression", DiagnosticKind.InferenceError)
                        }
                        return@with whenExpression
                    }

                    val completionResult = callCompleter.completeCall(whenExpression, data)
                    whenExpression = completionResult.result
                }
            }
            whenExpression = whenExpression.transformSingle(whenExhaustivenessTransformer, null)
            dataFlowAnalyzer.exitWhenExpression(whenExpression)
            whenExpression = whenExpression.replaceReturnTypeIfNotExhaustive()
            whenExpression
        }
    }

    private fun FirWhenExpression.replaceReturnTypeIfNotExhaustive(): FirWhenExpression {
        if (!isProperlyExhaustive) {
            resultType = resultType.resolvedTypeFromPrototype(session.builtinTypes.unitType.type)
        }
        return this
    }

    private fun FirWhenExpression.isOneBranch(): Boolean {
        if (branches.size == 1) return true
        if (branches.size > 2) return false
        val lastBranch = branches.last()
        return lastBranch.source != null && lastBranch.condition is FirElseIfTrueCondition && lastBranch.result is FirEmptyExpressionBlock
    }

    override fun transformWhenBranch(whenBranch: FirWhenBranch, data: ResolutionMode): FirWhenBranch {
        return whenBranch.also { dataFlowAnalyzer.enterWhenBranchCondition(whenBranch) }
            .transformCondition(transformer, withExpectedType(session.builtinTypes.booleanType))
            .also { dataFlowAnalyzer.exitWhenBranchCondition(it) }
            .transformResult(transformer, data)
            .also { dataFlowAnalyzer.exitWhenBranchResult(it) }

    }

    override fun transformWhenSubjectExpression(
        whenSubjectExpression: FirWhenSubjectExpression,
        data: ResolutionMode
    ): FirStatement {
        val parentWhen = whenSubjectExpression.whenRef.value
        val subjectType = parentWhen.subject?.resultType ?: parentWhen.subjectVariable?.returnTypeRef
        if (subjectType != null) {
            whenSubjectExpression.resultType = subjectType
        }
        return whenSubjectExpression
    }

    // ------------------------------- Try/catch expressions -------------------------------

    override fun transformTryExpression(tryExpression: FirTryExpression, data: ResolutionMode): FirStatement {
        if (tryExpression.calleeReference is FirResolvedNamedReference && tryExpression.resultType !is FirImplicitTypeRef) {
            return tryExpression
        }

        tryExpression.transformAnnotations(transformer, ResolutionMode.ContextIndependent)
        dataFlowAnalyzer.enterTryExpression(tryExpression)
        tryExpression.transformTryBlock(transformer, ResolutionMode.ContextDependent)
        dataFlowAnalyzer.exitTryMainBlock()
        tryExpression.transformCatches(this, ResolutionMode.ContextDependent)

        var callCompleted: Boolean

        @Suppress("NAME_SHADOWING")
        var result = syntheticCallGenerator.generateCalleeForTryExpression(tryExpression, resolutionContext).let {
            val completionResult = callCompleter.completeCall(it, data)
            callCompleted = completionResult.callCompleted
            completionResult.result
        }
        result = if (result.finallyBlock != null) {
            result.also { dataFlowAnalyzer.enterFinallyBlock() }
                .transformFinallyBlock(transformer, ResolutionMode.ContextIndependent)
                .also { dataFlowAnalyzer.exitFinallyBlock() }
        } else {
            result
        }
        dataFlowAnalyzer.exitTryExpression(callCompleted)
        return result
    }

    override fun transformCatch(catch: FirCatch, data: ResolutionMode): FirCatch {
        dataFlowAnalyzer.enterCatchClause(catch)
        catch.parameter.transformReturnTypeRef(transformer, ResolutionMode.ContextIndependent)
        return context.forBlock(session) {
            catch.transformParameter(transformer, ResolutionMode.ContextIndependent)
            catch.transformBlock(transformer, ResolutionMode.ContextDependent)
        }.also { dataFlowAnalyzer.exitCatchClause(it) }
    }

    // ------------------------------- Jumps -------------------------------

    override fun <E : FirTargetElement> transformJump(jump: FirJump<E>, data: ResolutionMode): FirStatement {
        val result = transformer.transformExpression(jump, data)
        dataFlowAnalyzer.exitJump(jump)
        return result
    }

    override fun transformReturnExpression(
        returnExpression: FirReturnExpression,
        data: ResolutionMode
    ): FirStatement {
        val labeledElement = returnExpression.target.labeledElement
        val expectedTypeRef = labeledElement.returnTypeRef
        @Suppress("IntroduceWhenSubject")
        val mode = when {
            labeledElement.symbol in context.anonymousFunctionsAnalyzedInDependentContext -> {
                ResolutionMode.ContextDependent
            }
            else -> {
                ResolutionMode.WithExpectedType(expectedTypeRef)
            }
        }

        return transformJump(returnExpression, mode)
    }

    override fun transformThrowExpression(
        throwExpression: FirThrowExpression,
        data: ResolutionMode
    ): FirStatement {
        return transformer.transformExpression(throwExpression, ResolutionMode.ContextIndependent).also {
            dataFlowAnalyzer.exitThrowExceptionNode(it as FirThrowExpression)
        }
    }

    // ------------------------------- Elvis -------------------------------

    override fun transformElvisExpression(
        elvisExpression: FirElvisExpression,
        data: ResolutionMode
    ): FirStatement {
        if (elvisExpression.calleeReference is FirResolvedNamedReference) return elvisExpression
        elvisExpression.transformAnnotations(transformer, data)

        val expectedType = data.expectedType?.coneTypeSafe<ConeKotlinType>()
        val mayBeCoercionToUnitApplied = (data as? ResolutionMode.WithExpectedType)?.mayBeCoercionToUnitApplied == true

        val resolutionModeForLhs =
            if (mayBeCoercionToUnitApplied && expectedType?.isUnitOrFlexibleUnit == true)
                withExpectedType(expectedType, mayBeCoercionToUnitApplied = true)
            else
                withExpectedType(expectedType?.withNullability(ConeNullability.NULLABLE, session.typeContext))
        dataFlowAnalyzer.enterElvis(elvisExpression)
        elvisExpression.transformLhs(transformer, resolutionModeForLhs)
        dataFlowAnalyzer.exitElvisLhs(elvisExpression)

        val resolutionModeForRhs = withExpectedType(
            expectedType,
            mayBeCoercionToUnitApplied = mayBeCoercionToUnitApplied
        )
        elvisExpression.transformRhs(transformer, resolutionModeForRhs)

        val result = syntheticCallGenerator.generateCalleeForElvisExpression(elvisExpression, resolutionContext)?.let {
            callCompleter.completeCall(it, data).result
        } ?: elvisExpression.also {
            it.resultType = buildErrorTypeRef {
                diagnostic = ConeSimpleDiagnostic("Can't resolve ?: operator call", DiagnosticKind.InferenceError)
            }
        }

        dataFlowAnalyzer.exitElvis(elvisExpression)
        return result
    }
}
