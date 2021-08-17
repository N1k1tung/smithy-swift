/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package software.amazon.smithy.swift.codegen

import software.amazon.smithy.codegen.core.CodegenException
import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.codegen.core.SymbolDependency
import software.amazon.smithy.codegen.core.SymbolDependencyContainer
import software.amazon.smithy.codegen.core.SymbolReference
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.traits.DeprecatedTrait
import software.amazon.smithy.model.traits.DocumentationTrait
import software.amazon.smithy.model.traits.EnumDefinition
import software.amazon.smithy.swift.codegen.integration.SectionId
import software.amazon.smithy.swift.codegen.integration.SectionWriter
import software.amazon.smithy.swift.codegen.model.defaultValue
import software.amazon.smithy.swift.codegen.model.isBoxed
import software.amazon.smithy.swift.codegen.model.isBuiltIn
import software.amazon.smithy.swift.codegen.model.isServiceNestedNamespace
import software.amazon.smithy.utils.CodeWriter
import java.util.function.BiFunction

/**
 * Handles indenting follow on params to a function that takes several params or a builder object
 * i.e.
 * func test(param1: String, param2: String, param3: Int)
 *
 * test(param1: "hi",
 *      param2: "test",
 *      param3: 4)
 */
fun <T : CodeWriter> T.swiftFunctionParameterIndent(block: T.() -> Unit): T {
    this.indent(3)
    block(this)
    this.dedent(3)
    return this
}

fun <T : CodeWriter> T.declareSection(id: SectionId, context: Map<String, Any?> = emptyMap(), block: T.() -> Unit = {}): T {
    putContext(context)
    pushState(id.javaClass.canonicalName)
    block(this)
    popState()
    removeContext(context)
    return this
}
private fun <T : CodeWriter> T.removeContext(context: Map<String, Any?>): Unit =
    context.keys.forEach { key -> removeContext(key) }

fun SwiftWriter.customizeSection(id: SectionId, writer: SectionWriter): SwiftWriter {
    onSection(id.javaClass.canonicalName) { default ->
        require(default is String?) { "Expected Smithy to pass String for previous value but found ${default::class.java}" }
        writer.write(this, default)
    }
    return this
}

// Used for sections, deals with delimiter occurring within set but not trailing or leading.
fun CodeWriter.appendWithDelimiter(previousText: Any?, text: String, delimiter: String = ", ") {
    when {
        previousText !is String -> error("Unexpected type ${previousText?.javaClass?.canonicalName ?: "[UNKNOWN]"}")
        previousText.isEmpty() -> write(text)
        else -> write("$previousText$delimiter$text")
    }
}

class SwiftWriter(private val fullPackageName: String) : CodeWriter() {
    init {
        trimBlankLines()
        trimTrailingSpaces()
        setIndentText("    ")
        // type with default set
        putFormatter('D', SwiftSymbolFormatter(shouldSetDefault = true, shouldRenderOptional = true))
        putFormatter('T', SwiftSymbolFormatter(shouldSetDefault = false, shouldRenderOptional = true))
        putFormatter('N', SwiftSymbolFormatter(shouldSetDefault = false, shouldRenderOptional = false))
    }

    private val imports: ImportDeclarations = ImportDeclarations()
    internal val dependencies: MutableList<SymbolDependency> = mutableListOf()

    companion object {
        val staticHeader: String = "// Code generated by smithy-swift-codegen. DO NOT EDIT!\n\n"
    }

    fun addImport(symbol: Symbol) {

        if (symbol.isBuiltIn || symbol.isServiceNestedNamespace) return
        // always add dependencies
        dependencies.addAll(symbol.dependencies)

        // only add imports for symbols that exist in a certain namespace
        if (symbol.namespace.isNotEmpty()) {
            imports.addImport(symbol.namespace, false)
        }
    }

    fun addImport(packageName: String, isTestable: Boolean = false) {
        imports.addImport(packageName, isTestable)
    }

    fun addFoundationImport() {
        imports.addImport("Foundation", false)
    }

    fun addImportReferences(symbol: Symbol, vararg options: SymbolReference.ContextOption) {
        symbol.references.forEach { reference ->
            for (option in options) {
                if (reference.hasOption(option)) {
                    addImport(reference.symbol)
                    break
                }
            }
        }
    }

    /**
     * Adds one or more dependencies to the generated code.
     *
     *
     * The dependencies of all writers created by the [SwiftDelegator]
     * are merged together to eventually generate a podspec file.
     *
     * @param dependencies Swift dependency to add.
     * @return Returns the writer.
     */
    fun addDependency(dependencies: SymbolDependencyContainer): SwiftWriter? {
        this.dependencies.addAll(dependencies.dependencies)
        return this
    }

    override fun toString(): String {
        val contents = super.toString()
        val imports = "${imports}\n\n"
        return staticHeader + imports + contents
    }

    private class SwiftSymbolFormatter(val shouldSetDefault: Boolean, val shouldRenderOptional: Boolean) : BiFunction<Any, String, String> {
        override fun apply(type: Any, indent: String): String {
            when (type) {
                is Symbol -> {
                    var formatted = type.fullName
                    if (type.isBoxed() && shouldRenderOptional) {
                        formatted += "?"
                    }

                    if (shouldSetDefault) {
                        type.defaultValue()?.let {
                            formatted += " = $it"
                        }
                    }

                    return formatted
                }
                else -> throw CodegenException("Invalid type provided for \$T. Expected a Symbol, but found `$type`")
            }
        }
    }

    /**
     * Configures the writer with the appropriate single-line doc comment and calls the [block]
     * with this writer. Any calls to `write()` inside of block will be escaped appropriately.
     * On return the writer's original state is restored.
     *
     * e.g.
     * ```
     * writer.writeSingleLineDocs() {
     *     write("This is a single-line doc comment")
     * }
     * ```
     *
     * would output
     *
     * ```
     * /// This is a single-line doc comment
     * ```
     */
    fun writeSingleLineDocs(block: SwiftWriter.() -> Unit) {
        pushState("singleLineDocs")
        setNewlinePrefix("/// ")
        block(this)
        popState()
    }

    /**
     * Writes documentation comments from a doc string.
     */
    fun writeDocs(docs: String) {
        writeSingleLineDocs {
            write(sanitizeDocumentation(docs))
        }
    }

    /**
     * This function escapes "$" characters so formatters are not run.
     */
    private fun sanitizeDocumentation(doc: String): String {
        return doc.replace("\$", "\$\$")
    }

    /**
     * Writes shape documentation comments if docs are present.
     */
    fun writeShapeDocs(shape: Shape) {
        shape.getTrait(DocumentationTrait::class.java).ifPresent {
            writeDocs(it.value)
        }
    }

    /*
    * Writes @available attribute if deprecated trait is present
    * */
    fun writeAvailableAttribute(model: Model?, shape: Shape) {
        var deprecatedTrait: DeprecatedTrait? = null
        if (shape.getTrait(DeprecatedTrait::class.java).isPresent) {
            deprecatedTrait = shape.getTrait(DeprecatedTrait::class.java).get()
        } else if (shape.getMemberTrait(model, DeprecatedTrait::class.java).isPresent) {
            deprecatedTrait = shape.getMemberTrait(model, DeprecatedTrait::class.java).get()
        }

        if (deprecatedTrait != null) {
            val messagePresent = deprecatedTrait.message.isPresent
            val sincePresent = deprecatedTrait.since.isPresent
            var message = StringBuilder()
            if (messagePresent) {
                message.append(deprecatedTrait.message.get())
            }
            if (sincePresent) {
                message.append(" API deprecated since ${deprecatedTrait.since.get()}")
            }

            if (messagePresent || sincePresent) {
                write("@available(*, deprecated, message: \"${message}\")")
            } else {
                write("@available(*, deprecated)")
            }
        }
    }

    /**
     * Writes member shape documentation comments if docs are present.
     */
    fun writeMemberDocs(model: Model, member: MemberShape) {
        if (member.getTrait(DocumentationTrait::class.java).isPresent) {
            writeDocs(member.getTrait(DocumentationTrait::class.java).get().value)
        } else if (member.getMemberTrait(model, DocumentationTrait::class.java).isPresent) {
            writeDocs(member.getMemberTrait(model, DocumentationTrait::class.java).get().value)
        }
    }

    /**
     * Writes documentation comments for Enum Definitions if present.
     */
    fun writeEnumDefinitionDocs(enumDefinition: EnumDefinition) {
        enumDefinition.documentation.ifPresent {
            writeDocs(it)
        }
    }
}
