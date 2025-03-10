// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.debugger.evaluate.variables

import com.intellij.debugger.engine.JavaValue
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl
import com.intellij.debugger.jdi.LocalVariableProxyImpl
import com.intellij.debugger.jdi.StackFrameProxyImpl
import com.sun.jdi.*
import org.jetbrains.kotlin.backend.common.descriptors.synthesizedString
import org.jetbrains.kotlin.name.NameUtils.CONTEXT_RECEIVER_PREFIX
import org.jetbrains.kotlin.codegen.AsmUtil
import org.jetbrains.kotlin.codegen.AsmUtil.getCapturedFieldName
import org.jetbrains.kotlin.codegen.AsmUtil.getLabeledThisName
import org.jetbrains.kotlin.codegen.coroutines.CONTINUATION_VARIABLE_NAME
import org.jetbrains.kotlin.codegen.coroutines.SUSPEND_FUNCTION_COMPLETION_PARAMETER_NAME
import org.jetbrains.kotlin.codegen.inline.INLINE_FUN_VAR_SUFFIX
import org.jetbrains.kotlin.codegen.inline.INLINE_TRANSFORMATION_SUFFIX
import org.jetbrains.kotlin.idea.debugger.base.util.*
import org.jetbrains.kotlin.idea.debugger.base.util.evaluate.ExecutionContext
import org.jetbrains.kotlin.idea.debugger.core.stackFrame.InlineStackFrameProxyImpl
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.CoroutineStackFrameProxyImpl
import org.jetbrains.kotlin.idea.debugger.evaluate.compilation.CodeFragmentParameter
import org.jetbrains.kotlin.idea.debugger.evaluate.compilation.CodeFragmentParameter.Kind
import org.jetbrains.kotlin.idea.debugger.evaluate.compilation.DebugLabelPropertyDescriptorProvider
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import kotlin.coroutines.Continuation
import com.sun.jdi.Type as JdiType
import org.jetbrains.org.objectweb.asm.Type as AsmType

private const val OLD_CONTEXT_RECEIVER_PREFIX = "_context_receiver"

class VariableFinder(val context: ExecutionContext) {
    private val frameProxy = context.frameProxy

    companion object {
        private val USE_UNSAFE_FALLBACK: Boolean
            get() = true

        private fun getCapturedVariableNameRegex(capturedName: String): Regex {
            val escapedName = Regex.escape(capturedName)
            val escapedSuffix = Regex.escape(INLINE_TRANSFORMATION_SUFFIX)
            return Regex("^$escapedName(?:$escapedSuffix)?$")
        }
    }

    val evaluatorValueConverter = EvaluatorValueConverter(context)

    val refWrappers: List<RefWrapper>
        get() = mutableRefWrappers

    private val mutableRefWrappers = mutableListOf<RefWrapper>()

    class RefWrapper(val localVariableName: String, val wrapper: Value?)

    sealed class VariableKind(val asmType: AsmType) {
        abstract fun capturedNameMatches(name: String): Boolean

        class Ordinary(val name: String, asmType: AsmType, val isDelegated: Boolean) : VariableKind(asmType) {
            private val capturedNameRegex = getCapturedVariableNameRegex(getCapturedFieldName(this.name))
            override fun capturedNameMatches(name: String) = capturedNameRegex.matches(name)
        }

        // TODO Support overloaded local functions
        class LocalFunction(val name: String, asmType: AsmType) : VariableKind(asmType) {
            @Suppress("ConvertToStringTemplate")
            override fun capturedNameMatches(name: String) = name == "$" + name
        }

        class UnlabeledThis(asmType: AsmType) : VariableKind(asmType) {
            override fun capturedNameMatches(name: String) =
                (name == AsmUtil.CAPTURED_RECEIVER_FIELD || name.startsWith(getCapturedFieldName(AsmUtil.LABELED_THIS_FIELD)))
        }

        class OuterClassThis(asmType: AsmType) : VariableKind(asmType) {
            override fun capturedNameMatches(name: String) = false
        }

        class FieldVar(val fieldName: String, asmType: AsmType) : VariableKind(asmType) {
            // Captured 'field' are not supported yet
            override fun capturedNameMatches(name: String) = false
        }

        class ExtensionThis(val label: String, asmType: AsmType) : VariableKind(asmType) {
            val parameterName = getLabeledThisName(label, AsmUtil.LABELED_THIS_PARAMETER, AsmUtil.RECEIVER_PARAMETER_NAME)
            val fieldName = getLabeledThisName(label, getCapturedFieldName(AsmUtil.LABELED_THIS_FIELD), AsmUtil.CAPTURED_RECEIVER_FIELD)

            private val capturedNameRegex = getCapturedVariableNameRegex(fieldName)
            override fun capturedNameMatches(name: String) = capturedNameRegex.matches(name)
        }

        class ContextReceiver(asmType: AsmType) : VariableKind(asmType) {
            override fun capturedNameMatches(name: String) =
                name.startsWith(CONTEXT_RECEIVER_PREFIX)
                        || name.startsWith(AsmUtil.CAPTURED_PREFIX + CONTEXT_RECEIVER_PREFIX)
                        || name.startsWith(OLD_CONTEXT_RECEIVER_PREFIX)
                        || name.startsWith(AsmUtil.CAPTURED_PREFIX + OLD_CONTEXT_RECEIVER_PREFIX)
        }
    }

    class Result(val value: Value?)

    private class NamedEntity(val name: String, val lazyType: Lazy<JdiType?>, val lazyValue: Lazy<Value?>) {
        val type: JdiType?
            get() = lazyType.value

        val value: Value?
            get() = lazyValue.value

        companion object {
            fun of(field: Field, owner: ObjectReference): NamedEntity {
                val type = lazy(LazyThreadSafetyMode.PUBLICATION) { field.safeType() }
                val value = lazy(LazyThreadSafetyMode.PUBLICATION) { owner.getValue(field) }
                return NamedEntity(field.name(), type, value)
            }

            fun of(variable: LocalVariableProxyImpl, frameProxy: StackFrameProxyImpl): NamedEntity {
                val type = lazy(LazyThreadSafetyMode.PUBLICATION) { variable.safeType() }
                val value = lazy(LazyThreadSafetyMode.PUBLICATION) { frameProxy.getValue(variable) }
                return NamedEntity(variable.name(), type, value)
            }

            fun of(variable: JavaValue, context: EvaluationContextImpl): NamedEntity {
                val type = lazy(LazyThreadSafetyMode.PUBLICATION) { variable.descriptor.type }
                val value = lazy(LazyThreadSafetyMode.PUBLICATION) { variable.descriptor.safeCalcValue(context) }
                return NamedEntity(variable.name, type, value)
            }
        }
    }

    fun find(parameter: CodeFragmentParameter, asmType: AsmType): Result? {
        return when (parameter.kind) {
            Kind.ORDINARY -> findOrdinary(VariableKind.Ordinary(parameter.name, asmType, isDelegated = false))
            Kind.DELEGATED -> findOrdinary(VariableKind.Ordinary(parameter.name, asmType, isDelegated = true))
            Kind.FAKE_JAVA_OUTER_CLASS -> thisObject()?.let { Result(it) }
            Kind.EXTENSION_RECEIVER -> findExtensionThis(VariableKind.ExtensionThis(parameter.name, asmType))
            Kind.CONTEXT_RECEIVER -> findContextReceiver(VariableKind.ContextReceiver(asmType))
            Kind.LOCAL_FUNCTION -> findLocalFunction(VariableKind.LocalFunction(parameter.name, asmType))
            Kind.DISPATCH_RECEIVER -> findDispatchThis(VariableKind.OuterClassThis(asmType))
            Kind.COROUTINE_CONTEXT -> findCoroutineContext()
            Kind.FIELD_VAR -> findFieldVariable(VariableKind.FieldVar(parameter.name, asmType))
            Kind.DEBUG_LABEL -> findDebugLabel(parameter.name)
        }
    }

    private fun findOrdinary(kind: VariableKind.Ordinary): Result? {
        val variables = frameProxy.safeVisibleVariables()

        // Local variables – direct search
        findLocalVariable(variables, kind, kind.name)?.let { return it }

        // Local variables - synthetic captured local variable (IR Backend)
        findLocalVariable(variables, kind, kind.name.synthesizedString)?.let { return it }

        // Recursive search in local receiver variables
        findCapturedVariableInReceiver(variables, kind)?.let { return it }

        // Recursive search in captured this
        return findCapturedVariableInContainingThis(kind)
    }

    private fun findFieldVariable(kind: VariableKind.FieldVar): Result? {
        val thisObject = thisObject()
        if (thisObject != null) {
            val field = thisObject.referenceType().fieldByName(kind.fieldName) ?: return null
            return Result(thisObject.getValue(field))
        } else {
            val containingType = frameProxy.safeLocation()?.declaringType() ?: return null
            val field = containingType.fieldByName(kind.fieldName) ?: return null
            return Result(containingType.getValue(field))
        }
    }

    private fun findLocalFunction(kind: VariableKind.LocalFunction): Result? {
        val variables = frameProxy.safeVisibleVariables()

        // Local variables – direct search, new convention
        val newConventionName = AsmUtil.LOCAL_FUNCTION_VARIABLE_PREFIX + kind.name
        findLocalVariable(variables, kind, newConventionName)?.let { return it }

        // Local variables – direct search, old convention (before 1.3.30)
        findLocalVariable(variables, kind, kind.name + "$")?.let { return it }

        // Recursive search in local receiver variables
        findCapturedVariableInReceiver(variables, kind)?.let { return it }

        // Recursive search in captured this
        return findCapturedVariableInContainingThis(kind)
    }

    private fun findCapturedVariableInContainingThis(kind: VariableKind): Result? {
        if (frameProxy is CoroutineStackFrameProxyImpl && frameProxy.isCoroutineScopeAvailable()) {
            findCapturedVariable(kind, frameProxy.thisObject())?.let { return it }
            return findCapturedVariable(kind, frameProxy.continuation)
        }

        val containingThis = thisObject() ?: return null
        return findCapturedVariable(kind, containingThis)
    }

    private fun findExtensionThis(kind: VariableKind.ExtensionThis): Result? {
        val variables = frameProxy.safeVisibleVariables()

        // Local variables – direct search
        val namePredicate = fun(name: String) = name == kind.parameterName || name.startsWith(kind.parameterName + '$')
        findLocalVariable(variables, kind, namePredicate)?.let { return it }

        // Recursive search in local receiver variables
        findCapturedVariableInReceiver(variables, kind)?.let { return it }

        // Recursive search in captured this
        findCapturedVariableInContainingThis(kind)?.let { return it }

        if (USE_UNSAFE_FALLBACK) {
            // Find an unlabeled this with the compatible type
            findUnlabeledThis(VariableKind.UnlabeledThis(kind.asmType))?.let { return it }
        }

        return null
    }

    private fun findContextReceiver(kind: VariableKind.ContextReceiver): Result? {
        val variableProxies = frameProxy.visibleVariables().map { LocalVariableProxyImpl(frameProxy, it.variable) }
        findLocalVariable(variableProxies, kind) {
            kind.capturedNameMatches(it) || it.startsWith(AsmUtil.THIS_IN_DEFAULT_IMPLS)
        }?.let { return it }
        return findCapturedVariableInContainingThis(kind)
    }

    private fun findDispatchThis(kind: VariableKind.OuterClassThis): Result? {
        findCapturedVariableInContainingThis(kind)?.let { return it }

        if (isInsideDefaultImpls()) {
            val variables = frameProxy.safeVisibleVariables()
            findLocalVariable(variables, kind, AsmUtil.THIS_IN_DEFAULT_IMPLS)?.let { return it }
        }

        val variables = frameProxy.safeVisibleVariables()
        val inlineDepth = getInlineDepth(variables)

        if (inlineDepth > 0) {
            variables.namedEntitySequence()
                .filter { it.name.matches(INLINED_THIS_REGEX) && getInlineDepth(it.name) == inlineDepth && kind.typeMatches(it.type) }
                .mapNotNull { it.unwrapAndCheck(kind) }
                .firstOrNull()
                ?.let { return it }
        }

        if (USE_UNSAFE_FALLBACK) {
            val unlabeledThisKind = VariableKind.UnlabeledThis(kind.asmType)
            // Find an unlabeled this with the compatible type
            findUnlabeledThis(unlabeledThisKind)?.let { return it }

            // In lambdas local variable for outer this (e.g. with name "this$0") is not in visibleVariables,
            // so try to find it in argumentVariables by type
            frameProxy.safeArgumentValues()
                .firstNotNullOfOrNull { findCapturedVariable(unlabeledThisKind, it) }
                ?.let { return it }
        }

        return null
    }

    private fun findDebugLabel(name: String): Result? {
        val markupMap = DebugLabelPropertyDescriptorProvider.getMarkupMap(context.debugProcess)

        for ((value, markup) in markupMap) {
            if (markup.text == name) {
                return Result(value)
            }
        }

        return null
    }

    private fun findUnlabeledThis(kind: VariableKind.UnlabeledThis): Result? {
        val variables = frameProxy.safeVisibleVariables()

        // Recursive search in local receiver variables
        findCapturedVariableInReceiver(variables, kind)?.let { return it }

        return findCapturedVariableInContainingThis(kind)
    }

    private fun findLocalVariable(variables: List<LocalVariableProxyImpl>, kind: VariableKind, name: String): Result? {
        return findLocalVariable(variables, kind) { it == name }
    }

    private fun findLocalVariable(
        variables: List<LocalVariableProxyImpl>,
        kind: VariableKind,
        namePredicate: (String) -> Boolean
    ): Result? {
        val inlineDepth =
            frameProxy.safeAs<InlineStackFrameProxyImpl>()?.inlineDepth
                ?: getInlineDepth(variables)

        findLocalVariable(variables, kind, inlineDepth, namePredicate)?.let { return it }

        // Try to find variables outside of inline functions as well
        if (inlineDepth > 0 && USE_UNSAFE_FALLBACK) {
            findLocalVariable(variables, kind, 0, namePredicate)?.let { return it }
        }

        return null
    }

    private fun findLocalVariable(
        variables: List<LocalVariableProxyImpl>,
        kind: VariableKind,
        inlineDepth: Int,
        namePredicate: (String) -> Boolean
    ): Result? {
        val actualPredicate: (String) -> Boolean

        if (inlineDepth > 0) {
            actualPredicate = fun(name: String): Boolean {
                var endIndex = name.length
                var depth = 0

                val suffixLen = INLINE_FUN_VAR_SUFFIX.length
                while (endIndex >= suffixLen) {
                    if (name.substring(endIndex - suffixLen, endIndex) != INLINE_FUN_VAR_SUFFIX) {
                        break
                    }

                    depth++
                    endIndex -= suffixLen
                }

                return namePredicate(name.take(endIndex)) && getInlineDepth(name) == inlineDepth
            }
        } else {
            actualPredicate = namePredicate
        }

        val namedEntities = variables.namedEntitySequence() + getCoroutineStackFrameNamedEntities()
        for (item in namedEntities) {
            if (!actualPredicate(item.name) || !kind.typeMatches(item.type)) {
                continue
            }

            val rawValue = item.value
            val result = evaluatorValueConverter.coerce(getUnwrapDelegate(kind, rawValue), kind.asmType) ?: continue

            if (!rawValue.isRefType && result.value.isRefType) {
                // Local variable was wrapped into a Ref instance
                mutableRefWrappers += RefWrapper(item.name, result.value)
            }

            return result
        }

        return null
    }

    private fun getCoroutineStackFrameNamedEntities() =
        if (frameProxy is CoroutineStackFrameProxyImpl) {
            frameProxy.spilledVariables.namedEntitySequence(context.evaluationContext)
        } else {
            emptySequence()
        }

    private fun isInsideDefaultImpls(): Boolean {
        val declaringType = frameProxy.safeLocation()?.declaringType() ?: return false
        return declaringType.name().endsWith(JvmAbi.DEFAULT_IMPLS_SUFFIX)
    }

    private fun findCoroutineContext(): Result? {
        val method = frameProxy.safeLocation()?.safeMethod() ?: return null
        val result = findCoroutineContextForLambda(method) ?: findCoroutineContextForMethod(method) ?: return null
        return Result(result)
    }

    private fun findCoroutineContextForLambda(method: Method): ObjectReference? {
        if (method.name() != "invokeSuspend" || method.signature() != "(Ljava/lang/Object;)Ljava/lang/Object;" ||
            frameProxy !is CoroutineStackFrameProxyImpl
        ) {
            return null
        }

        val continuation = frameProxy.continuation ?: return null
        val continuationType = continuation.referenceType()

        if (SUSPEND_LAMBDA_CLASSES.none { continuationType.isSubtype(it) }) {
            return null
        }

        return findCoroutineContextForContinuation(continuation)
    }

    private fun findCoroutineContextForMethod(method: Method): ObjectReference? {
        if (CONTINUATION_TYPE.descriptor + ")" !in method.signature()) {
            return null
        }

        val continuationVariable = frameProxy.safeVisibleVariableByName(CONTINUATION_VARIABLE_NAME)
            ?: frameProxy.safeVisibleVariableByName(SUSPEND_FUNCTION_COMPLETION_PARAMETER_NAME)
            ?: return null

        val continuation = frameProxy.getValue(continuationVariable) as? ObjectReference ?: return null
        return findCoroutineContextForContinuation(continuation)
    }

    private fun findCoroutineContextForContinuation(continuation: ObjectReference): ObjectReference? {
        val continuationType = (continuation.referenceType() as? ClassType)
            ?.allInterfaces()?.firstOrNull { it.name() == Continuation::class.java.name }
            ?: return null

        val getContextMethod = continuationType
            .methodsByName("getContext", "()Lkotlin/coroutines/CoroutineContext;").firstOrNull()
            ?: return null

        return context.invokeMethod(continuation, getContextMethod, emptyList()) as? ObjectReference
    }

    private fun findCapturedVariableInReceiver(variables: List<LocalVariableProxyImpl>, kind: VariableKind): Result? {
        fun isReceiverOrPassedThis(name: String) =
            name.startsWith(AsmUtil.LABELED_THIS_PARAMETER)
                    || name == AsmUtil.RECEIVER_PARAMETER_NAME
                    || name == AsmUtil.THIS_IN_DEFAULT_IMPLS
                    || INLINED_THIS_REGEX.matches(name)
                    || name == SpecialNames.THIS.asString()

        if (kind is VariableKind.ExtensionThis) {
            variables.namedEntitySequence()
                .filter { kind.capturedNameMatches(it.name) && kind.typeMatches(it.type) }
                .mapNotNull { it.unwrapAndCheck(kind) }
                .firstOrNull()
                ?.let { return it }
        }

        return variables.namedEntitySequence()
            .filter { isReceiverOrPassedThis(it.name) }
            .mapNotNull { findCapturedVariable(kind, it.value) }
            .firstOrNull()
    }

    private fun findCapturedVariable(kind: VariableKind, parentFactory: () -> Value?): Result? {
        val parent = getUnwrapDelegate(kind, parentFactory())
        return findCapturedVariable(kind, parent)
    }

    private fun findCapturedVariable(kind: VariableKind, parent: Value?): Result? {
        val acceptsParentValue = kind is VariableKind.UnlabeledThis || kind is VariableKind.OuterClassThis
        if (parent != null && acceptsParentValue && kind.typeMatches(parent.type())) {
            return Result(parent)
        }

        val fields = (parent as? ObjectReference)?.referenceType()?.fields() ?: return null

        if (kind !is VariableKind.OuterClassThis) {
            // Captured variables - direct search
            fields.namedEntitySequence(parent)
                .filter { kind.capturedNameMatches(it.name) && kind.typeMatches(it.type) }
                .mapNotNull { it.unwrapAndCheck(kind) }
                .firstOrNull()
                ?.let { return it }

            // Recursive search in captured receivers
            fields.namedEntitySequence(parent)
                .filter { isCapturedReceiverFieldName(it.name) }
                .mapNotNull { findCapturedVariable(kind, it.value) }
                .firstOrNull()
                ?.let { return it }
        }

        // Recursive search in outer and captured this
        fields.namedEntitySequence(parent)
            .filter { it.name == AsmUtil.THIS_IN_DEFAULT_IMPLS || it.name == AsmUtil.CAPTURED_THIS_FIELD }
            .mapNotNull { findCapturedVariable(kind, it.value) }
            .firstOrNull()
            ?.let { return it }

        return null
    }

    private fun getUnwrapDelegate(kind: VariableKind, rawValue: Value?): Value? {
        if (kind !is VariableKind.Ordinary || !kind.isDelegated) {
            return rawValue
        }

        val delegateValue = rawValue as? ObjectReference ?: return rawValue
        val getValueMethod = delegateValue.referenceType()
            .methodsByName("getValue", "()Ljava/lang/Object;").firstOrNull()
            ?: return rawValue

        return context.invokeMethod(delegateValue, getValueMethod, emptyList())
    }

    private fun isCapturedReceiverFieldName(name: String): Boolean {
        return name.startsWith(getCapturedFieldName(AsmUtil.LABELED_THIS_FIELD))
                || name == AsmUtil.CAPTURED_RECEIVER_FIELD
    }

    private fun VariableKind.typeMatches(actualType: JdiType?): Boolean {
        if (this is VariableKind.Ordinary && isDelegated) {
            // We can't figure out the actual type of the value yet.
            // No worries: it will be checked again (and more carefully) in `unwrapAndCheck()`.
            return true
        }
        return evaluatorValueConverter.typeMatches(asmType, actualType)
    }

    private fun NamedEntity.unwrapAndCheck(kind: VariableKind): Result? {
        return evaluatorValueConverter.coerce(getUnwrapDelegate(kind, value), kind.asmType)
    }

    private fun List<Field>.namedEntitySequence(owner: ObjectReference): Sequence<NamedEntity> {
        return asSequence().map { NamedEntity.of(it, owner) }
    }

    private fun List<LocalVariableProxyImpl>.namedEntitySequence(): Sequence<NamedEntity> {
        return asSequence().map { NamedEntity.of(it, frameProxy) }
    }

    private fun List<JavaValue>.namedEntitySequence(context: EvaluationContextImpl): Sequence<NamedEntity> {
        return asSequence().map { NamedEntity.of(it, context) }
    }

    private fun thisObject(): ObjectReference? {
        val thisObjectFromEvaluation = context.evaluationContext.computeThisObject() as? ObjectReference
        if (thisObjectFromEvaluation != null) {
            return thisObjectFromEvaluation
        }

        return frameProxy.thisObject()
    }
}
