package io.deepmedia.tools.knee.plugin.compiler.functions

import io.deepmedia.tools.knee.plugin.compiler.context.KneeContext
import io.deepmedia.tools.knee.plugin.compiler.context.KneeOrigin
import io.deepmedia.tools.knee.plugin.compiler.features.KneeDownwardFunction
import io.deepmedia.tools.knee.plugin.compiler.import.concrete
import io.deepmedia.tools.knee.plugin.compiler.symbols.RuntimeIds
import io.deepmedia.tools.knee.plugin.compiler.utils.asStringSafeForCodegen
import io.deepmedia.tools.knee.plugin.compiler.utils.simple
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.Name

internal fun KneeDownwardFunction.ensureImportedAdapter(
    context: KneeContext,
    signature: DownwardFunctionSignature
): IrSimpleFunction? {
    val interfaceKind = kind as? KneeDownwardFunction.Kind.InterfaceMember ?: return null
    val importInfo = interfaceKind.importInfo ?: return null
    if (importedAdapter != null) return importedAdapter

    val file = importInfo.file
    val adapterName = Name.identifier(
        "kneeImported_" + signature.jniInfo.name(includeAncestors = true).asStringSafeForCodegen(true)
    )
    val existing = file.findDeclaration<IrSimpleFunction> { it.name == adapterName }
    if (existing != null) {
        importedAdapter = existing
        return existing
    }

    val source = source as IrSimpleFunction
    val target = source.normalizedImportedTarget()
    val helper = source.importedRuntimeHelper(context)
    val receiverType = source.parentAsClass.thisReceiver!!.type.simple("ImportedInterfaceAdapter.receiver")
        .concrete(importInfo)
    val regularParams = source.parameters
        .filter { it.kind == IrParameterKind.Regular || it.kind == IrParameterKind.Context }
    val targetRegularParams = target.parameters
        .filter { it.kind == IrParameterKind.Regular || it.kind == IrParameterKind.Context }

    return context.factory.buildFun {
        startOffset = SYNTHETIC_OFFSET
        endOffset = SYNTHETIC_OFFSET
        origin = KneeOrigin.KNEE
        visibility = DescriptorVisibilities.INTERNAL
        modality = Modality.FINAL
        name = adapterName
        isSuspend = source.isSuspend
        returnType = source.returnType.simple("ImportedInterfaceAdapter.returnType").concrete(importInfo)
    }.apply {
        parent = file
        val receiver = addValueParameter(
            DownwardFunctionSignature.Extra.ReceiverInstance.asString(),
            receiverType
        )
        val params = regularParams.map { param ->
            addValueParameter(
                param.name.asString(),
                param.type.simple("ImportedInterfaceAdapter.parameterType").concrete(importInfo)
            )
        }
        body = DeclarationIrBuilder(context.plugin, symbol, SYNTHETIC_OFFSET, SYNTHETIC_OFFSET).irBlockBody {
            val call = irCall(helper ?: target)
            if (helper != null) {
                source.parentAsClass.thisReceiver!!.type
                    .simple("ImportedInterfaceAdapter.helperType")
                    .arguments
                    .firstOrNull()
                    ?.typeOrNull
                    ?.let { typeArgument ->
                        call.typeArguments[0] = typeArgument
                            .simple("ImportedInterfaceAdapter.helperTypeArgument")
                            .concrete(importInfo)
                }
                call.arguments[0] = irGet(receiver)
                params.forEachIndexed { index, param ->
                    call.arguments[index + 1] = irGet(param)
                }
            } else {
                call.dispatchReceiver = irGet(receiver)
                targetRegularParams.forEachIndexed { index, _ ->
                    call.arguments[index] = irGet(params[index])
                }
            }
            +irReturn(call)
        }
        file.declarations += this
    }.also {
        importedAdapter = it
    }
}

private tailrec fun IrSimpleFunction.normalizedImportedTarget(): IrSimpleFunction {
    if (!isFakeOverride) return this
    val next = overriddenSymbols
        .map { it.owner }
        .filterIsInstance<IrSimpleFunction>()
        .firstOrNull { !it.isFakeOverride }
        ?: return this
    return next.normalizedImportedTarget()
}

private fun IrSimpleFunction.importedRuntimeHelper(context: KneeContext): IrSimpleFunction? {
    val owner = parentClassOrNull?.fqNameWhenAvailable?.asString()
    return when {
        name.asString() == "collect" && owner == "kotlinx.coroutines.flow.Flow" -> {
            context.symbols.functions(RuntimeIds.kneeImportedCollectFlow).single().owner
        }
        name.asString() == "collect" && owner in setOf(
            "kotlinx.coroutines.flow.SharedFlow",
            "kotlinx.coroutines.flow.StateFlow",
            "kotlinx.coroutines.flow.MutableSharedFlow",
            "kotlinx.coroutines.flow.MutableStateFlow",
        ) -> {
            context.symbols.functions(RuntimeIds.kneeImportedCollectSharedFlow).single().owner
        }
        name.asString() == "emit" && owner in setOf(
            "kotlinx.coroutines.flow.FlowCollector",
            "kotlinx.coroutines.flow.MutableSharedFlow",
            "kotlinx.coroutines.flow.MutableStateFlow",
        ) -> {
            context.symbols.functions(RuntimeIds.kneeImportedEmitFlowCollector).single().owner
        }
        else -> null
    }
}
