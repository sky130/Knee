package io.deepmedia.tools.knee.plugin.compiler.context

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.InternalSymbolFinderAPI
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.declarations.IrParameterKind

class KneeLogger(
    private val kneeContext: KneeContext,
    private val collector: MessageCollector,
    private val verboseLogs: Boolean,
    private val verboseRuntime: Boolean
) {

    fun logWarning(message: String) {
        if (verboseLogs) println(message)
        collector.report(CompilerMessageSeverity.WARNING, message)
    }

    fun logMessage(message: String) {
        if (verboseLogs) println(message)
    }

    private var printlnIrString: IrSimpleFunctionSymbol? = null
    private var printlnIrAny: IrSimpleFunctionSymbol? = null
    private val printlnCodegen = MemberName("kotlin.io", "println")

    fun injectLog(scope: IrStatementsBuilder<*>, message: String) {
        if (!verboseRuntime) return

        @OptIn(InternalSymbolFinderAPI::class)
        if (printlnIrString == null) {
            val builtIns = kneeContext.plugin.irBuiltIns
            val symbolFinder = builtIns.symbolFinder
            val function = symbolFinder.findFunctions(Name.identifier("println"), "kotlin", "io")
            printlnIrString = function.single {
                it.owner.parameters
                    .firstOrNull { it.kind == IrParameterKind.Regular || it.kind == IrParameterKind.Context }
                    ?.type == builtIns.stringType
            }
        }

        with(scope) {
            +irCall(printlnIrString!!).apply {
                arguments[0] = scope.irString("[KNEE_KN] $message")
            }
        }
    }

    fun injectLog(scope: IrStatementsBuilder<*>, objToPrint: IrValueDeclaration) {
        if (!verboseRuntime) return

        @OptIn(InternalSymbolFinderAPI::class)
        if (printlnIrAny == null) {
            val builtIns = kneeContext.plugin.irBuiltIns
            val symbolFinder = builtIns.symbolFinder
            val function = symbolFinder.findFunctions(Name.identifier("println"), "kotlin", "io")
            printlnIrAny = function.single {
                it.owner.parameters
                    .firstOrNull { it.kind == IrParameterKind.Regular || it.kind == IrParameterKind.Context }
                    ?.type == builtIns.anyType.makeNullable()
            }
        }

        with(scope) {
            +irCall(printlnIrAny!!).apply {
                arguments[0] = irGet(objToPrint)
            }
        }
    }

    fun injectLog(scope: CodeBlock.Builder, message: String) {
        if (!verboseRuntime) return
        scope.addStatement("%M(%S)", printlnCodegen, "[KNEE_JVM] $message")
    }
}