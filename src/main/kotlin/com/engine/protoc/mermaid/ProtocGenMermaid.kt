package com.engine.protoc.mermaid

import com.engine.protoc.util.compiler.CodeGeneratorRequestWrapper
import com.engine.protoc.util.compiler.Parameters
import com.engine.protoc.util.extensions.wrap
import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.compiler.PluginProtos
import java.io.InputStream

public class ProtocGenMermaid(
    private val request: CodeGeneratorRequestWrapper,
    private val options: Options,
) {

    /**
     * Options that influence the compiler plugin.
     *
     * No options are defined yet.  Add new options as properties here, then wire each one through
     * [Builder] so it can be parsed from the `--mermaid_out=key=value,…:outdir` parameter string.
     */
    public class Options private constructor() {

        public class Builder private constructor(@Suppress("UNUSED_PARAMETER") parameters: Parameters) {

            public companion object {
                public fun from(parameters: Parameters): Builder = Builder(parameters)
            }

            public fun build(): Options = Options()
        }
    }

    public companion object {
        public fun from(
            input: InputStream,
            registry: ExtensionRegistry = ExtensionRegistry.newInstance(),
            block: Options.Builder.() -> Unit = {},
        ): ProtocGenMermaid {
            val cgreq = PluginProtos.CodeGeneratorRequest.parseFrom(input, registry).wrap()
            return ProtocGenMermaid(
                cgreq,
                Options.Builder.from(cgreq.parameters).apply(block).build(),
            )
        }
    }

    public fun compile(): PluginProtos.CodeGeneratorResponse =
        PluginProtos.CodeGeneratorResponse.newBuilder()
            .setSupportedFeatures(PluginProtos.CodeGeneratorResponse.Feature.FEATURE_PROTO3_OPTIONAL.number.toLong())
            .build()
}
