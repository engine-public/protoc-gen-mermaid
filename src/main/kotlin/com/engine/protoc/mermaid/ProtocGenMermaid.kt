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
         * Shape of each emitted artifact.  See [OutputType] for the available values; default is
         * [OutputType.STANDALONE_MERMAID] (a bare Mermaid `classDiagram` written to a `.mermaid`
         * file, the original plugin behavior).  Selecting [OutputType.STANDALONE_MARKDOWN] switches
         * the file extension to `.md` and wraps the same diagram body in a fenced ` ```mermaid `
         * code block prefixed by a Markdown `# <title>` heading, so the artifact drops directly
         * into long-form documentation without further wrapping.  Selecting
         * [OutputType.EMBEDDED_MARKDOWN] emits the diagrams as protoc insertion-point entries
         * targeting the `.md` files produced by `protoc-gen-markdown`, splicing each fenced
         * diagram into the host markdown directly under the relevant heading.
         */
        public val outputType: OutputType,
        /**
         * Name of the protoc insertion point a [DiagramType.FILE_OVERVIEW] diagram targets under
         * [OutputType.EMBEDDED_MARKDOWN].  This is the *prefix* only: the compiler appends the
         * runtime scope `:<proto>` (the originating `.proto` path) so each file's overview lands on
         * its own scoped point.  Default `file_header_scope`, matching the `file_header_scope:<proto>`
         * point protoc-gen-markdown emits at the top of each per-file `.md`.  Has no effect under any
         * other [outputType].
         */
        public val fileOverviewInsertionPoint: String,
        /**
         * Name of the protoc insertion point a [DiagramType.MESSAGE] diagram targets under
         * [OutputType.EMBEDDED_MARKDOWN].  This is the *prefix* only: the compiler appends the
         * runtime scope `:<fqn>` (the message's fully-qualified name) so each message diagram lands
         * on its own scoped point.  Default `message_header_scope`, matching the
         * `message_header_scope:<fqn>` point protoc-gen-markdown emits above each message section.
         * Has no effect under any other [outputType].
         */
        public val messageInsertionPoint: String,
        /**
         * Name of the protoc insertion point a [DiagramType.SERVICE] diagram targets under
         * [OutputType.EMBEDDED_MARKDOWN].  This is the *prefix* only: the compiler appends the
         * runtime scope `:<fqn>` (the service's fully-qualified name) so each service diagram lands
         * on its own scoped point.  Default `service_header_scope`, matching the
         * `service_header_scope:<fqn>` point protoc-gen-markdown emits above each service section.
         * Has no effect under any other [outputType].
         */
        public val serviceInsertionPoint: String,
        /**
         * Name of the protoc insertion point an [DiagramType.ENUMERATION] diagram targets under
         * [OutputType.EMBEDDED_MARKDOWN].  This is the *prefix* only: the compiler appends the
         * runtime scope `:<fqn>` (the enum's fully-qualified name) so each enum diagram lands on its
         * own scoped point.  Default `enum_header_scope`, matching the `enum_header_scope:<fqn>`
         * point protoc-gen-markdown emits above each enum section.  Has no effect under any other
         * [outputType].
         */
        public val enumerationInsertionPoint: String,
        /**
         * Name of the protoc insertion point the [DiagramType.COMPLETE] diagram targets under
         * [OutputType.EMBEDDED_MARKDOWN].  Emitted verbatim with no scope suffix.  Default
         * `file_header`, matching the bare `file_header` point protoc-gen-markdown emits in the root
         * `overview.md` navigation file.  Has no effect under any other [outputType].
         */
        public val completeInsertionPoint: String,
        /**
         * Name of the protoc insertion point each [DiagramType.PACKAGE] diagram targets under
         * [OutputType.EMBEDDED_MARKDOWN].  Emitted verbatim with no scope suffix.  Default
         * `file_header`, matching the bare `file_header` point protoc-gen-markdown emits in each
         * package-index navigation file.  Has no effect under any other [outputType].
         */
        public val packageInsertionPoint: String,
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
             *
             * In [OutputType.EMBEDDED_MARKDOWN] this diagram targets the `file_header` insertion
             * point of the `overview.md` navigation file protoc-gen-markdown emits — which requires
             * that plugin's `includeIndices=true` and `outputType != SINGLE_FILE`.
             */
            COMPLETE,

            /**
             * One file per distinct proto `package` in scope.  Contains every top-level enum,
             * message, and service across all in-scope files declaring that package, plus the same
             * association/dependency/nesting arrows [COMPLETE] draws, scoped to the package.
             * Filename = the dotted package name + `.package.mermaid` (e.g. `pkg.sub.package.mermaid`
             * — the flattened form of protoc-gen-markdown's `<pkg-as-dir>/package.md`, whose
             * `.package` leaf keeps it from colliding with the [COMPLETE] file); files with no
             * `package` directive collapse into one `default.package.mermaid` diagram.
             *
             * In [OutputType.EMBEDDED_MARKDOWN] this diagram targets the `file_header` insertion
             * point of each package-index navigation file protoc-gen-markdown emits — which requires
             * that plugin's `includeIndices=true` and `outputType=PER_FILE`.
             */
            PACKAGE,

            /**
             * One file per service in scope.  Contains the focus service's class block, a class
             * block for every in-scope message it references via an RPC input or output, and only
             * the focus service's RPC dependency arrows (`Service ..> "1" Message : rpc`); nothing
             * references a service, and RPC types are always messages, so there is no incoming side
             * and no enum.  Filename = fully-qualified service name + `.mermaid`
             * (e.g. `pkg.sub.FooService.mermaid`).
             *
             * In [OutputType.EMBEDDED_MARKDOWN] this diagram targets the `service_header_scope:<fqn>`
             * insertion point protoc-gen-markdown emits above each service section in the per-file
             * `.md` — see [serviceInsertionPoint].
             */
            SERVICE,

            /**
             * One file per enum in scope, top-level or nested.  Contains the focus enum's class
             * block, a class block for every other in-scope message with a field referencing the
             * enum, and only the association arrows that target the enum (no relationships between
             * the surrounding classes are drawn).  Enums have no outgoing references and cannot be
             * referenced by RPCs.  Filename = fully-qualified enum name + `.mermaid` (e.g.
             * `pkg.sub.Outer.Color.mermaid` for an enum nested in `Outer`).
             *
             * In [OutputType.EMBEDDED_MARKDOWN] this diagram targets the `enum_header_scope:<fqn>`
             * insertion point protoc-gen-markdown emits above each enum section in the per-file
             * `.md` — see [enumerationInsertionPoint].
             */
            ENUMERATION,
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
         * Artifact-shape taxonomy.  Selects the file extension and the surrounding wrapper that
         * the compiler emits around each diagram.  Add a new value here, then handle it in
         * [com.engine.protoc.mermaid.compile.Compiler] where the output extension and rendered
         * content are assembled.
         */
        public enum class OutputType {
            /**
             * One `.mermaid` file per diagram, containing nothing but the Mermaid `classDiagram`
             * (YAML frontmatter + body).  The original plugin behavior; default.
             */
            STANDALONE_MERMAID,

            /**
             * One `.md` file per diagram, wrapping the exact same `classDiagram` content in a
             * fenced ` ```mermaid ` code block prefixed by a Markdown `# <title>` heading derived
             * from the diagram's title.  Suitable for dropping directly into documentation
             * (GitHub, IDEs, static-site generators) without further wrapping.
             */
            STANDALONE_MARKDOWN,

            /**
             * Diagram content emitted as protoc insertion-point entries that splice into the
             * `.md` files produced by protoc-gen-markdown (its `PER_FILE` mode, the default).
             * Content is a fenced ` ```mermaid ` code block with no surrounding heading — the host
             * markdown already supplies the section header directly above the insertion point.
             * Each [com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse.File] in the
             * response carries an `insertion_point` keyed to a diagram kind:
             *
             *  - [DiagramType.FILE_OVERVIEW] → `file_header_scope:<proto>` in the per-file `.md`
             *    (`name` = `<proto>` with `.proto` swapped for `.md`).
             *  - [DiagramType.MESSAGE] → `message_header_scope:<fqn>` in that same per-file `.md`.
             *  - [DiagramType.COMPLETE] → `file_header` in the root `overview.md`.
             *  - [DiagramType.PACKAGE] → `file_header` in each package-index file (`<pkg-as-dir>/
             *    package.md`, `<pkg>.md`, or `default.md`, matching the markdown plugin's naming).
             *
             * Requires the markdown plugin's `generateInsertionPoints` option (default on) and
             * `outputType=PER_FILE` (default).  COMPLETE and PACKAGE additionally require the
             * markdown plugin's `includeIndices=true` so the `overview.md` / package-index files
             * they target actually exist; without them protoc rejects the response with an
             * insertion-point-not-found error.  Restrict [diagramTypes] (e.g.
             * `diagramTypes=FILE_OVERVIEW,MESSAGE`) when the markdown run does not emit those
             * navigation files.
             */
            EMBEDDED_MARKDOWN,
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

            public var outputType: OutputType =
                parameters.get<OutputType>("outputType") ?: OutputType.STANDALONE_MERMAID

            public var fileOverviewInsertionPoint: String =
                parameters.get<String>("fileOverviewInsertionPoint") ?: "file_header_scope"

            public var messageInsertionPoint: String =
                parameters.get<String>("messageInsertionPoint") ?: "message_header_scope"

            public var serviceInsertionPoint: String =
                parameters.get<String>("serviceInsertionPoint") ?: "service_header_scope"

            public var enumerationInsertionPoint: String =
                parameters.get<String>("enumerationInsertionPoint") ?: "enum_header_scope"

            public var completeInsertionPoint: String =
                parameters.get<String>("completeInsertionPoint") ?: "file_header"

            public var packageInsertionPoint: String =
                parameters.get<String>("packageInsertionPoint") ?: "file_header"

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
                    outputType = outputType,
                    fileOverviewInsertionPoint = fileOverviewInsertionPoint,
                    messageInsertionPoint = messageInsertionPoint,
                    serviceInsertionPoint = serviceInsertionPoint,
                    enumerationInsertionPoint = enumerationInsertionPoint,
                    completeInsertionPoint = completeInsertionPoint,
                    packageInsertionPoint = packageInsertionPoint,
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
