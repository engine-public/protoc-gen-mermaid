package com.engine.protoc.mermaid.compile

import com.engine.protoc.mermaid.ProtocGenMermaid
import com.engine.protoc.util.compiler.CodeGeneratorRequestWrapper
import com.engine.protoc.util.compiler.CodeGeneratorResponseWrapper
import com.engine.protoc.util.file.FileDescriptorProtoWrapper
import com.engine.protoc.util.message.DescriptorProtoWrapper
import com.engine.protoc.util.message.FieldDescriptorProtoWrapper
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type
import com.google.protobuf.compiler.PluginProtos

/**
 * First-pass compiler.  For each proto file in [CodeGeneratorRequestWrapper.filesToGenerate]
 * that defines at least one top-level message, emits one output file at the same relative path
 * with the `.proto` suffix swapped for `.mermaid`.  The file contains a Mermaid `classDiagram`
 * with one class per top-level message; each field is rendered as `+<type> <name>`.
 *
 * Not yet handled: nested types, enums, oneofs, maps, documentation comments, cross-file
 * references / relationships.
 */
internal class Compiler(
    private val request: CodeGeneratorRequestWrapper,
    @Suppress("UNUSED_PARAMETER") private val options: ProtocGenMermaid.Options,
) {

    internal fun compile(): PluginProtos.CodeGeneratorResponse {
        val response = CodeGeneratorResponseWrapper()
        val filesToGenerate = request.filesToGenerate.toSet()
        for (file in request.protoFiles) {
            val name = file.name ?: continue
            if (name !in filesToGenerate) continue
            if (file.messageTypes.isEmpty()) continue
            response.addFile(outputName(name), render(file))
        }
        return response.build()
    }

    private fun outputName(protoName: String): String = protoName.removeSuffix(".proto") + ".mermaid"

    private fun render(file: FileDescriptorProtoWrapper): String =
        buildString {
            appendLine("classDiagram")
            file.messageTypes.forEachIndexed { index, msg ->
                if (index > 0) appendLine()
                renderMessage(msg)
            }
        }

    private fun StringBuilder.renderMessage(msg: DescriptorProtoWrapper) {
        val name = msg.name?.value ?: return
        if (msg.fields.isEmpty()) {
            appendLine("    class $name")
            return
        }
        appendLine("    class $name {")
        for (field in msg.fields) {
            appendLine("        ${renderField(field)}")
        }
        appendLine("    }")
    }

    private fun renderField(field: FieldDescriptorProtoWrapper): String {
        val type = fieldType(field)
        val name = field.name?.value ?: "?"
        return "+$type $name"
    }

    private fun fieldType(field: FieldDescriptorProtoWrapper): String {
        val base = when (val t = field.type?.value) {
            Type.TYPE_MESSAGE, Type.TYPE_ENUM, Type.TYPE_GROUP ->
                /*
                 * `typeName` is fully qualified ("." + package + ... + leaf).  Take just the
                 * leaf identifier for now — namespacing collisions are a problem for a later
                 * pass to solve.
                 */
                field.typeName?.value?.substringAfterLast('.') ?: "?"

            null -> "?"

            else -> t.name.removePrefix("TYPE_").lowercase()
        }
        return if (field.label?.value == Label.LABEL_REPEATED) "$base[]" else base
    }
}
