package io.deepmedia.tools.knee.plugin.compiler.import

import com.squareup.kotlinpoet.TypeVariableName
import io.deepmedia.tools.knee.plugin.compiler.utils.asTypeName
import io.deepmedia.tools.knee.plugin.compiler.utils.simple
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.file

class ImportInfo(
    val type: IrSimpleType,
    private val declaration: IrDeclarationWithName,
) {
    val id: String get() = declaration.name.asString()

    // this is writable!
    val file: IrFile get() = declaration.file

    private val typeParameters = type.classOrNull!!.owner.typeParameters.map { it.symbol }
    private val typeArguments = type.arguments

    val typeVariables = typeParameters.map {
        // type = kotlin.ranges.ClosedRange<T>
        // declaration.name = closedFloatRange
        // type parameter = T
        // super type = kotlin.Comparable
        TypeVariableName(
            name = it.owner.name.asString(),
            bounds = it.owner.superTypes.map {
                it.simple("ImportInfo.typeParameters.map").asTypeName()
            }
        )
    }

    val substitutor = IrTypeSubstitutor(
        typeParameters = typeParameters,
        typeArguments = typeArguments,
        allowEmptySubstitution = false
    )

    // Does the same that substitutor, to be used in function.copyValueParametersFrom...
    val substitutionMap = typeParameters.zip(typeArguments.map { it.typeOrNull!! }).toMap()
}
