package io.deepmedia.tools.knee.plugin.compiler.functions

import io.deepmedia.tools.knee.plugin.compiler.codec.IrCodecContext
import io.deepmedia.tools.knee.plugin.compiler.functions.DownwardFunctionsIr.irInvoke
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable

/**
 * IR companion of [DownwardFunctionsCodegen].
 */
object DownwardFunctionsIr {

    /**
     * Calls the original, local function from bridge, mapping all inputs.
     * Returns the raw output, not mapped.
     */
    fun IrStatementsBuilder<*>.irInvoke(
        inputs: List<IrValueParameter>,
        local: IrFunction,
        target: IrFunction,
        signature: DownwardFunctionSignature,
        codecContext: IrCodecContext,
    ): IrExpression {
        val logPrefix = "FunctionsIr.irInvoke(${local.fqNameWhenAvailable})"
        codecContext.logger.injectLog(this, "$logPrefix START")
        val targetRegularParameterSlots = target.parameters.withIndex()
            .filter { it.value.kind == IrParameterKind.Regular || it.value.kind == IrParameterKind.Context }
        val targetHasDispatchReceiver = target.parameters.any { it.kind == IrParameterKind.DispatchReceiver }
        val targetRegularBaseIndex = when {
            targetHasDispatchReceiver -> 0
            signature.extraParameters.any { it.first == DownwardFunctionSignature.Extra.ReceiverInstance } -> 1
            else -> 0
        }

        fun targetArgumentIndex(name: org.jetbrains.kotlin.name.Name, fallbackRegularIndex: Int): Int {
            return target.parameters
                .indexOfFirst { parameter ->
                    (parameter.kind == IrParameterKind.Regular || parameter.kind == IrParameterKind.Context) &&
                        parameter.name == name
                }
                .takeIf { it >= 0 }
                ?: targetRegularParameterSlots
                    .getOrNull(fallbackRegularIndex)
                    ?.index
                ?: error(
                    "Could not map parameter '$name' from ${local.fqNameWhenAvailable} to ${target.fqNameWhenAvailable}."
                )
        }

        return irCall(target).apply {
            val hasReceiver = signature.extraParameters.firstOrNull { it.first == DownwardFunctionSignature.Extra.ReceiverInstance }
            hasReceiver?.let { (name, codec) ->
                val param = inputs.first { it.name == name }
                codecContext.logger.injectLog(this@irInvoke, "$logPrefix Decoding dispatch receiver $name with $codec")
                val decoded = with(codec) { irDecode(codecContext, param) }
                when {
                    targetHasDispatchReceiver -> {
                        dispatchReceiver = decoded
                    }
                    else -> {
                        val targetIndex = targetArgumentIndex(name, 0)
                        arguments[targetIndex] = decoded
                    }
                }
            }
            signature.regularParameters.forEachIndexed { index, (param, codec) ->
                with(codec) {
                    // note: targetIndex != index because of copy parameters!
                    val inputIndex = index + signature.knPrefixParameters.size + signature.extraParameters.size
                    val targetIndex = targetArgumentIndex(param, targetRegularBaseIndex + index)
                    codecContext.logger.injectLog(this@irInvoke, "$logPrefix Decoding parameter $param with $codec")
                    arguments[targetIndex] = irDecode(codecContext, inputs[inputIndex])
                }
            }
            /* signature.knCopyParameters.forEach { (param, indexToBeCopied) ->
                val targetIndex = local.valueParameters.indexOfFirst { it.name == param }
                putValueArgument(targetIndex, irGet(inputs[indexToBeCopied]))
            } */
        }
    }

    /**
     * Process the result of [irInvoke] - before returning it to bridge,
     * it might need conversion.
     */
    fun IrStatementsBuilder<*>.irReceive(
        rawValue: IrExpression,
        signature: DownwardFunctionSignature,
        codecContext: IrCodecContext,
        suspendToken: Boolean = false
    ): IrExpression {
        val returnType = if (suspendToken) signature.suspendResult else signature.result
        if (!returnType.needsIrConversion) return rawValue
        return with(returnType) {
            irEncode(codecContext, irTemporary(rawValue, "result"))
        }
    }
}
