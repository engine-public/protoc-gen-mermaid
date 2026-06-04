package com.engine.protoc.mermaid

import com.engine.protoc.mermaid.compile.Compiler
import com.engine.protoc.util.compiler.CodeGeneratorRequestWrapper
import com.engine.protoc.util.compiler.Parameters
import com.engine.protoc.util.extensions.wrap
import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.compiler.PluginProtos
import org.apache.logging.log4j.core.appender.ConsoleAppender
import org.apache.logging.log4j.core.config.Configurator
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory
import org.slf4j.event.Level
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.logging.log4j.Level as Log4jLevel

public class ProtocGenMermaid(
    private val request: CodeGeneratorRequestWrapper,
    private val options: Options,
) {

    /**
     * Options that influence the compiler plugin.
     *
     * Add new options as properties here, then wire each one through [Builder] so it can be parsed
     * from the `--mermaid_out=key=value,…:outdir` parameter string.
     */
    public data class Options(
        /**
         * When true, each generated `.mermaid` file is prefixed with a YAML config frontmatter
         * block that sets `config.class.hideEmptyMembersBox: true`, telling Mermaid to suppress
         * the empty attribute/method compartment on classes that declare no members.  Default
         * true — most proto descriptors include at least one field, but a message with none
         * renders as a single titled box rather than a stack of empty compartments.
         *
         * Note: this option is currently non-functional in mermaid 11.15.0 such that it
         * doesn't hide fields or methods if either have at least one item. See
         * https://github.com/mermaid-js/mermaid/issues/6192
         */
        public val hideEmptyMembersBox: Boolean,
        /**
         * Forwarded to Mermaid's `config.class.hierarchicalNamespaces` option.  Mermaid wraps every
         * generated `classDiagram` body in `namespace <proto-package> { ... }` (the proto file's
         * dotted package becomes Mermaid's hierarchical namespace name).  With this option true
         * (Mermaid's default), each dot-segment of the package renders as its own nested cluster.
         * With it false, Mermaid switches to "compact mode" and draws a single flat box for the
         * declared namespace, skipping the auto-created intermediate ancestors.  Default false —
         * the plugin opts out of Mermaid's hierarchical mode by default because the rendered
         * stack of single-segment clusters is visually noisy for typical proto packages.
         *
         * Because Mermaid's own default is `true`, this is only emitted into the output's YAML
         * frontmatter when the value is `false` (i.e. the plugin's default), opting into compact
         * mode.  Pass `hierarchicalNamespaces=true` to fall back to Mermaid's hierarchical mode;
         * in that case nothing is emitted into the frontmatter.
         */
        public val hierarchicalNamespaces: Boolean,
        /**
         * When true, the compiler does not wrap a proto file's messages in a `namespace { ... }`
         * block, regardless of the file's `package` declaration.  Useful when the rendered diagram
         * is part of documentation that already establishes the package context (e.g. an embed in
         * a per-package README), so the extra namespace cluster is noise.  Default false — the
         * proto package is preserved as a Mermaid namespace.
         */
        public val suppressNamespaces: Boolean,
        /**
         * When true, field lines are emitted as `<type> <name>` with no leading visibility marker.
         * When false, each field is rendered as `+<type> <name>` — Mermaid's `+` (public) glyph,
         * matching UML attribute conventions.  Default true: protobuf has no field-level visibility
         * concept, so the universal `+` adds noise without conveying anything.
         */
        public val suppressVisibility: Boolean,
        /**
         * Which diagram outputs the compiler emits per compile invocation.  Default is every
         * [DiagramType].  See each enum value for what file(s) it produces and how each is named.
         * Restrict via the protoc parameter string (`--mermaid_out=diagramTypes=COMPLETE:...`) or
         * the DSL block (`diagramTypes = listOf(DiagramType.FILE_OVERVIEW)`).  Empty list = no
         * output (useful only in tests).
         */
        public val diagramTypes: List<DiagramType>,
        /**
         * How `oneof` groups are rendered inside a containing message's class block.  See
         * [OneofRenderingType] for what each value produces; default is
         * [OneofRenderingType.EMBEDDED].
         */
        public val oneofRenderingType: OneofRenderingType,
        /**
         * When true (default), protobuf well-known types (anything under the `google.protobuf.*`
         * namespace — `Timestamp`, `Duration`, `Any`, `Empty`, `Struct`, `FieldMask`, `Value`,
         * the `*Value` wrappers, etc.) are suppressed from the relationship graph: no class block,
         * no association arrow from a field referring to one, no dependency arrow from an RPC
         * accepting or returning one.  The in-class field line still reads `Timestamp created_at`
         * and the RPC method line still reads `Foo(Empty) Empty`, so the schema information is
         * preserved while the visual noise of dangling WKT boxes is removed.  Set false to render
         * WKTs the same as any other referenced type (which, since they are almost always imported
         * rather than generated, means an arrow into an implicitly-created blank class block).
         */
        public val suppressWellKnownTypes: Boolean,
        /**
         * Layout direction passed through to Mermaid as a body-level `direction <value>` statement
         * inside the `classDiagram` block.  See [Direction] for the four cardinal options.  Default
         * is [Direction.TB] (top → bottom), which matches Mermaid's own default; in that case no
         * `direction` line is emitted into the output.  Any other value is emitted verbatim.
         */
        public val direction: Direction,
        /**
         * Threshold at which the plugin emits log records via SLF4J.  Accepts any value of
         * [org.slf4j.event.Level] (`TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`); a record is
         * emitted when its level is greater than or equal to this threshold.  Defaults to
         * `ERROR` so the plugin is quiet by default but still surfaces error-level reports.
         *
         * The option is realised at runtime by programmatically reconfiguring the Log4j 2
         * `Configuration` after [Options] is built, so it controls every logger the plugin
         * (and its dependencies) creates.
         *
         * Passed via `--mermaid_out=logLevel=DEBUG:outdir` (case-insensitive).
         */
        public val logLevel: Level,
        /**
         * Optional path to a file that receives timestamped log records in addition to the
         * stderr console output.  The stderr `Console` appender is always attached — its lines
         * are prefixed with `[protoc-gen-mermaid]` so they stand out from other compiler
         * output protoc may multiplex on the same stream.  When this option is set, a `File`
         * appender is *also* attached at the given path with a `%d{HH:mm:ss.SSS}`-prefixed
         * pattern.
         *
         * Passed via `--mermaid_out=logFile=/tmp/protoc.log:outdir`.
         */
        public val logFile: String?,
    ) {

        /**
         * The diagram-kind taxonomy.  Add a new value here, then handle it in
         * [com.engine.protoc.mermaid.compile.Compiler.compile].
         */
        public enum class DiagramType {
            /**
             * One file per input `.proto`.  Contains a Mermaid `classDiagram` with every top-level
             * enum and message defined in that file, plus an association arrow per message field
             * whose type is another message or enum.  Filename = the proto's relative path with
             * `.proto` swapped for `.mermaid`.
             */
            FILE_OVERVIEW,

            /**
             * One file per top-level message in scope.  Contains the focus message's class block,
             * a class block for every type it references via a field, a class block for every
             * other message in scope that has a field referencing it, and only the association
             * arrows that touch the focus message (no relationships between the surrounding
             * classes are drawn).  Filename = fully-qualified message name + `.mermaid`
             * (e.g. `pkg.sub.Msg.mermaid`).
             */
            MESSAGE,

            /**
             * One file aggregating the entire compile scope.  Contains every top-level enum and
             * message across all input protos plus every association arrow between them.
             * Filename = the largest common dotted-package prefix of the input protos + `.mermaid`
             * (e.g. `pkg.sub.mermaid`); falls back to `complete.mermaid` if the inputs share no
             * package prefix.
             */
            COMPLETE,
        }

        /**
         * The oneof-rendering taxonomy.  Selects how `oneof` groups are rendered.  Add a new value
         * here, then handle it in [com.engine.protoc.mermaid.compile.Compiler] where oneof members
         * are emitted.
         */
        public enum class OneofRenderingType {
            /**
             * Oneof members live inline in the parent message's class block, grouped under an
             * `«oneof <name>»` stereotype header at the position where the oneof appears in the
             * proto source.  Each member field line is visually indented (leading `&nbsp;&nbsp;`)
             * to associate it with the header.  No additional class is introduced; mutual
             * exclusion is conveyed only by the grouping header.
             */
            EMBEDDED,

            /**
             * Each oneof is extracted into its own pseudo-class named `<MessageName>.<oneofName>`
             * with the `<<oneof>>` stereotype, listing the oneof's member fields.  The parent
             * message gains a composition arrow `Parent *-- "0..1" Parent.<oneofName> : <oneofName>`,
             * making the optionality of the whole oneof slot explicit on the arrow.
             */
            SEPARATE,
        }

        /**
         * Layout-flow taxonomy mirroring Mermaid's `direction` directive.  The four cardinal
         * options correspond directly to the strings Mermaid accepts inside a `classDiagram` body.
         */
        public enum class Direction {
            /** Left → right. */
            LR,

            /** Right → left. */
            RL,

            /** Top → bottom.  Mermaid's own default; the compiler omits the `direction` line in this case. */
            TB,

            /** Bottom → top. */
            BT,
        }

        public class Builder private constructor(parameters: Parameters) {

            public var hideEmptyMembersBox: Boolean = parameters.get<Boolean>("hideEmptyMembersBox") ?: true

            public var hierarchicalNamespaces: Boolean = parameters.get<Boolean>("hierarchicalNamespaces") ?: false

            public var suppressNamespaces: Boolean = parameters.get<Boolean>("suppressNamespaces") ?: false

            public var suppressVisibility: Boolean = parameters.get<Boolean>("suppressVisibility") ?: true

            public var diagramTypes: List<DiagramType> =
                parameters.get<List<DiagramType>>("diagramTypes") ?: DiagramType.entries.toList()

            public var oneofRenderingType: OneofRenderingType =
                parameters.get<OneofRenderingType>("oneofRenderingType") ?: OneofRenderingType.EMBEDDED

            public var suppressWellKnownTypes: Boolean = parameters.get<Boolean>("suppressWellKnownTypes") ?: true

            public var direction: Direction = parameters.get<Direction>("direction") ?: Direction.TB

            public var logLevel: Level = parameters.get<Level>("logLevel") ?: Level.ERROR

            public var logFile: String? = parameters.get<String>("logFile")

            public companion object {
                public fun from(parameters: Parameters): Builder = Builder(parameters)
            }

            public fun build(): Options =
                Options(
                    hideEmptyMembersBox = hideEmptyMembersBox,
                    hierarchicalNamespaces = hierarchicalNamespaces,
                    suppressNamespaces = suppressNamespaces,
                    suppressVisibility = suppressVisibility,
                    diagramTypes = diagramTypes,
                    oneofRenderingType = oneofRenderingType,
                    suppressWellKnownTypes = suppressWellKnownTypes,
                    direction = direction,
                    logLevel = logLevel,
                    logFile = logFile,
                )
        }
    }

    public companion object {
        public fun from(
            input: InputStream,
            registry: ExtensionRegistry = ExtensionRegistry.newInstance(),
            block: Options.Builder.() -> Unit = {},
        ): ProtocGenMermaid {
            val cgreq = PluginProtos.CodeGeneratorRequest.parseFrom(input, registry).wrap()
            val options = Options.Builder.from(cgreq.parameters).apply(block).build()
            applyLoggingConfiguration(options)
            return ProtocGenMermaid(cgreq, options)
        }

        /**
         * Reconfigures the Log4j 2 `Configuration` from [Options.logLevel] and [Options.logFile].
         *
         * Appenders are attached to the `com.engine` logger only, so downstream dependencies'
         * loggers stay silent regardless of their own level.  A stderr `Console` appender is
         * always attached, prefixed with `[protoc-gen-mermaid]` so its records stand out from
         * other compiler output protoc may multiplex on the same stream.  When [Options.logFile]
         * is non-null a `File` appender is also attached, writing timestamped records to the
         * given path.  The root logger is silenced with `Level.OFF` to discard anything emitted
         * outside the `com.engine` tree.  Invoked from [from] immediately after [Options] is
         * built so subsequent `LoggerFactory.getLogger` calls observe the resolved configuration.
         */
        private fun applyLoggingConfiguration(options: Options) {
            val cb = ConfigurationBuilderFactory.newConfigurationBuilder()
            cb.setStatusLevel(Log4jLevel.OFF)

            cb.add(
                cb.newAppender("stderr", "Console")
                    .addAttribute("target", ConsoleAppender.Target.SYSTEM_ERR)
                    .add(
                        cb.newLayout("PatternLayout")
                            .addAttribute("pattern", "[protoc-gen-mermaid] %-5level %logger{36} - %msg%n"),
                    ),
            )

            val engine =
                cb.newLogger("com.engine", options.logLevel.toLog4j())
                    .addAttribute("additivity", false)
                    .add(cb.newAppenderRef("stderr"))

            options.logFile?.let { path ->
                cb.add(
                    cb.newAppender("file", "File")
                        .addAttribute("fileName", path)
                        .add(
                            cb.newLayout("PatternLayout")
                                .addAttribute("pattern", "%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n"),
                        ),
                )
                engine.add(cb.newAppenderRef("file"))
            }

            cb.add(engine)
            cb.add(cb.newRootLogger(Log4jLevel.OFF))
            val config = cb.build(false)
            // First call in this JVM: `initialize` so a fresh LoggerContext
            // starts with our configuration directly, bypassing Log4j's default
            // config-file probing (~24 file paths) that breaks under
            // native-image's strict missing-resource registration.
            // Subsequent calls (test harnesses re-entering `from(...)` with
            // different `logLevel` / `logFile`): `reconfigure` swaps in the
            // freshly-built configuration.  Calling `reconfigure` on the same
            // config object that was just installed by `initialize` triggers a
            // start-then-immediate-stop sequence inside log4j2 that leaves
            // every appender in the `stopped` state, silently dropping all
            // subsequent events — the gate below avoids that.
            if (configurationInitialized.compareAndSet(false, true)) {
                Configurator.initialize(config)
            } else {
                Configurator.reconfigure(config)
            }
        }

        private val configurationInitialized = AtomicBoolean(false)

        private fun Level.toLog4j(): Log4jLevel =
            when (this) {
                Level.ERROR -> Log4jLevel.ERROR
                Level.WARN -> Log4jLevel.WARN
                Level.INFO -> Log4jLevel.INFO
                Level.DEBUG -> Log4jLevel.DEBUG
                Level.TRACE -> Log4jLevel.TRACE
            }
    }

    public fun compile(): PluginProtos.CodeGeneratorResponse = Compiler(request, options).compile()
}
