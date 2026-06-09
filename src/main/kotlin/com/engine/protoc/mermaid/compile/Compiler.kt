package com.engine.protoc.mermaid.compile

import com.engine.protoc.mermaid.ProtocGenMermaid
import com.engine.protoc.mermaid.ProtocGenMermaid.Options.DiagramType
import com.engine.protoc.mermaid.ProtocGenMermaid.Options.Direction
import com.engine.protoc.mermaid.ProtocGenMermaid.Options.OneofRenderingType
import com.engine.protoc.mermaid.ProtocGenMermaid.Options.OutputType
import com.engine.protoc.util.SyntaxElement
import com.engine.protoc.util.compiler.CodeGeneratorRequestWrapper
import com.engine.protoc.util.compiler.CodeGeneratorResponseWrapper
import com.engine.protoc.util.enums.EnumDescriptorProtoWrapper
import com.engine.protoc.util.file.FileDescriptorProtoWrapper
import com.engine.protoc.util.message.DescriptorProtoWrapper
import com.engine.protoc.util.message.FieldDescriptorProtoWrapper
import com.engine.protoc.util.service.ServiceDescriptorProtoWrapper
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type
import com.google.protobuf.compiler.PluginProtos
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(Compiler::class.java)

/**
 * Per compile invocation, emits one or more `.mermaid` files based on
 * [ProtocGenMermaid.Options.diagramTypes] — see [DiagramType] for what each kind produces and how
 * its filename is formed.  Within every output file, classes are rendered identically: a class
 * block per message (one `<type> <name>` line per field, gated by `suppressVisibility`), a class
 * block per enum (a `<<enumeration>>` annotation followed by one line per value), and a class
 * block per service (a `<<service>>` annotation followed by one `RpcName(Request) Response` line
 * per RPC, with a `stream` marker on either side for client/server streaming RPCs).
 *
 * Association arrows (`Source --> "<card>" Target : fieldName`) are emitted for every message
 * field that references another message or enum, and dependency arrows (`Service ..> "1" Message
 * : rpcName`) are emitted for every RPC's input and output.  The cardinality (always on the
 * target side) follows protobuf's presence model: repeated fields are `*`; non-repeated
 * message/group fields are always `0..1`; non-repeated enum/scalar fields are `0..1` when the
 * field is explicitly `optional` (proto2's `optional` keyword or proto3's `proto3_optional` flag)
 * and `1` otherwise; RPC inputs and outputs are always `1`.  Every repeated field — scalar or
 * reference — also keeps a `Type[]` suffix in its field line, so multiplicity stays visible
 * inside the class block at a glance even when the reader isn't tracing arrows.
 *
 * Nested message and enum types declared inside a message are flattened to siblings (depth-first
 * preorder) and rendered with a *disambiguated* identifier built from the containing-message
 * chain joined with `_` (e.g. `Outer_Inner`), together with a dotted display label
 * (`["Outer.Inner"]`) so the rendered name still reads like the source-level dotted form.
 * Top-level types keep their bare leaf name as both identifier and label.  All arrow endpoints
 * use the identifier; the field-line type text and RPC method line text use the label.
 * Synthetic map-entry messages (the `option map_entry = true` boilerplate the parser injects
 * for `map<K, V>` fields) are filtered out everywhere — they never render as a class, and a map
 * field renders as Mermaid generics `map~K, V~` (with no `[]` suffix) instead of `FooEntry[]`.
 * The association arrow for a map field re-targets through to the value type:
 * `map<string, Foo>` arrows at `Foo` with cardinality `*`, `map<string, string>` emits no arrow
 * at all.  The structural parent/child relationship is drawn as a Mermaid composition arrow
 * `Parent *-- Child : nested` wherever both endpoints appear in the same diagram; this is
 * orthogonal to (and may sit alongside) the field-association arrow for a parent field that
 * happens to be of the nested type.  Nested messages also get their own per-`MESSAGE` file
 * (filename = parent FQN + `.` + leaf), and that file forcibly includes the direct parent class
 * even when no field links them.
 *
 * Oneof groups are rendered per [OneofRenderingType].  In [OneofRenderingType.EMBEDDED] (default),
 * oneof members are listed in the parent's class block under an `«oneof <name>»` stereotype header
 * at the position the oneof appears in source, with `&nbsp;&nbsp;` indentation on each member line
 * to visually group them.  In [OneofRenderingType.SEPARATE], each oneof is extracted into a
 * pseudo-class identified `<Parent>_<oneofName>` (display label `<Parent>.<oneofName>`) with a
 * `<<oneof>>` stereotype listing its member fields; the parent gains a composition arrow
 * `Parent *-- "0..1" <pseudo> : <oneofName>` and the field-association arrows for those members
 * originate from the pseudo-class.  Either way, oneof members get target-side cardinality `0..1`
 * on their association arrows (the slot may hold none of them).  Synthetic single-field oneofs
 * (the wrappers protobuf injects to represent proto3 `optional`) are filtered out so they don't
 * render as headers or pseudo-classes — those fields render as regular fields.
 *
 * Not yet handled: documentation comments.
 */
internal class Compiler(
    private val request: CodeGeneratorRequestWrapper,
    private val options: ProtocGenMermaid.Options,
) {

    internal fun compile(): PluginProtos.CodeGeneratorResponse {
        log.info("compile starting with options: {}", options)
        return when (options.outputType) {
            OutputType.STANDALONE_MERMAID,
            OutputType.STANDALONE_MARKDOWN,
            -> compileStandalone()

            OutputType.EMBEDDED_MARKDOWN -> compileEmbedded()
        }
    }

    private fun compileStandalone(): PluginProtos.CodeGeneratorResponse {
        val response = CodeGeneratorResponseWrapper()
        if (DiagramType.FILE_OVERVIEW in options.diagramTypes) {
            for (file in index.files) response.addFile(overviewFilename(file), render(overviewDiagram(file)))
        }
        if (DiagramType.MESSAGE in options.diagramTypes) {
            for (entry in messageEntries) response.addFile("${entry.fqn}.$outputExtension", render(messageDiagram(entry)))
        }
        if (DiagramType.SERVICE in options.diagramTypes) {
            for (svc in index.files.flatMap { it.services }) {
                response.addFile("${index.fqnOf(svc)}.$outputExtension", render(serviceDiagram(svc)))
            }
        }
        if (DiagramType.ENUMERATION in options.diagramTypes) {
            for (enum in index.allEnums()) response.addFile("${index.fqnOf(enum)}.$outputExtension", render(enumerationDiagram(enum)))
        }
        if (DiagramType.COMPLETE in options.diagramTypes) {
            response.addFile(completeFilename(), render(completeDiagram()))
        }
        if (DiagramType.PACKAGE in options.diagramTypes) {
            for (pkg in distinctPackages()) response.addFile(packageFilename(pkg), render(packageDiagram(pkg)))
        }
        return response.build()
    }

    /**
     * Emits insertion-point entries targeting the `.md` files produced by protoc-gen-markdown in
     * its `PER_FILE` mode.  Each diagram becomes one
     * [PluginProtos.CodeGeneratorResponse.File] with `insertion_point` set; protoc itself splices
     * the content in immediately before the matching `@@protoc_insertion_point(...)` marker.
     *
     * FILE_OVERVIEW and MESSAGE diagrams target the per-file `.md` (name = `<proto>` with the
     * `.proto` suffix swapped for `.md`) at `file_header_scope:<proto>` and
     * `message_header_scope:<fqn>` respectively.  COMPLETE targets the root `overview.md` at the
     * bare `file_header` point, and PACKAGE targets each package-index file (named per
     * [markdownPackageIndexFilename]) at its `file_header` point.  The latter two require the
     * markdown plugin's `includeIndices=true` so those navigation files exist; absent them protoc
     * rejects the response.
     */
    private fun compileEmbedded(): PluginProtos.CodeGeneratorResponse {
        val builder =
            PluginProtos.CodeGeneratorResponse.newBuilder()
                .setSupportedFeatures(
                    PluginProtos.CodeGeneratorResponse.Feature.FEATURE_PROTO3_OPTIONAL.number.toLong(),
                )
        for (file in index.files) {
            val proto = file.name ?: continue
            val hostMd = proto.removeSuffix(".proto") + ".md"
            if (DiagramType.FILE_OVERVIEW in options.diagramTypes) {
                builder.addFile(
                    PluginProtos.CodeGeneratorResponse.File.newBuilder()
                        .setName(hostMd)
                        .setInsertionPoint("${options.fileOverviewInsertionPoint}:$proto")
                        .setContent(render(overviewDiagram(file)))
                        .build(),
                )
            }
            if (DiagramType.MESSAGE in options.diagramTypes) {
                for (msg in index.messages(file)) {
                    val entry = MessageEntry(msg, index.fqnOf(msg), index.parentOf(msg))
                    builder.addFile(
                        PluginProtos.CodeGeneratorResponse.File.newBuilder()
                            .setName(hostMd)
                            .setInsertionPoint("${options.messageInsertionPoint}:${entry.fqn}")
                            .setContent(render(messageDiagram(entry)))
                            .build(),
                    )
                }
            }
            if (DiagramType.SERVICE in options.diagramTypes) {
                for (svc in file.services) {
                    builder.addFile(
                        PluginProtos.CodeGeneratorResponse.File.newBuilder()
                            .setName(hostMd)
                            .setInsertionPoint("${options.serviceInsertionPoint}:${index.fqnOf(svc)}")
                            .setContent(render(serviceDiagram(svc)))
                            .build(),
                    )
                }
            }
            if (DiagramType.ENUMERATION in options.diagramTypes) {
                for (enum in index.enums(file)) {
                    builder.addFile(
                        PluginProtos.CodeGeneratorResponse.File.newBuilder()
                            .setName(hostMd)
                            .setInsertionPoint("${options.enumerationInsertionPoint}:${index.fqnOf(enum)}")
                            .setContent(render(enumerationDiagram(enum)))
                            .build(),
                    )
                }
            }
        }
        if (DiagramType.COMPLETE in options.diagramTypes) {
            builder.addFile(
                PluginProtos.CodeGeneratorResponse.File.newBuilder()
                    .setName("overview.md")
                    .setInsertionPoint(options.completeInsertionPoint)
                    .setContent(render(completeDiagram()))
                    .build(),
            )
        }
        if (DiagramType.PACKAGE in options.diagramTypes) {
            for (pkg in distinctPackages()) {
                val pkgFiles = index.files.filter { index.packageOf(it) == pkg }
                builder.addFile(
                    PluginProtos.CodeGeneratorResponse.File.newBuilder()
                        .setName(markdownPackageIndexFilename(pkg, pkgFiles))
                        .setInsertionPoint(options.packageInsertionPoint)
                        .setContent(render(packageDiagram(pkg)))
                        .build(),
                )
            }
        }
        return builder.build()
    }

    /** File extension derived from [OutputType]: `mermaid` for bare diagrams, `md` for markdown shapes. */
    private val outputExtension: String =
        when (options.outputType) {
            OutputType.STANDALONE_MERMAID -> "mermaid"
            OutputType.STANDALONE_MARKDOWN, OutputType.EMBEDDED_MARKDOWN -> "md"
        }

    // ===== Scope index ===========================================================================

    private val index: TypeIndex by lazy {
        val toGenerate = request.filesToGenerate.toSet()
        TypeIndex(
            request.protoFiles.filter {
                it.name in toGenerate &&
                    (it.messageTypes.isNotEmpty() || it.enumTypes.isNotEmpty() || it.services.isNotEmpty())
            },
        )
    }

    /** A message plus the context the per-`MESSAGE` renderer needs to construct its diagram. */
    private data class MessageEntry(val msg: DescriptorProtoWrapper, val fqn: String, val parent: DescriptorProtoWrapper?)

    private val messageEntries: List<MessageEntry> by lazy {
        index.allMessages().map { MessageEntry(it, index.fqnOf(it), index.parentOf(it)) }
    }

    // ===== Filenames & titles ====================================================================

    private fun overviewFilename(file: FileDescriptorProtoWrapper): String {
        val basename = (file.name ?: "").substringAfterLast('/').removeSuffix(".proto")
        val pkg = index.packageOf(file).takeIf(String::isNotEmpty)
        return if (pkg == null) "$basename.$outputExtension" else "$pkg.$basename.$outputExtension"
    }

    private fun completeFilename(): String = (commonPackagePrefix().ifEmpty { "complete" }) + ".$outputExtension"

    /**
     * Standalone filename for a PACKAGE diagram: the dotted package (or `default` for the
     * no-package group) followed by a `.package` leaf — the flattened form of the
     * `<pkg-as-dir>/package.md` file protoc-gen-markdown emits.  The `.package` leaf keeps these
     * from colliding with the COMPLETE file (`<common-prefix>.$ext`), which for a single-package
     * scope would otherwise share the exact dotted name.
     */
    private fun packageFilename(pkg: String): String = (pkg.ifEmpty { "default" }) + ".package.$outputExtension"

    /**
     * Replicates protoc-gen-markdown's `packageGroup` filename rule so an embedded PACKAGE diagram
     * lands on the `file_header` insertion point of the package-index doc that plugin emits: empty
     * package → `default.md`; "namespaced" (every file declaring [pkg] lives at the directory
     * `<pkg with . → />`) → `<pkg-as-dir>/package.md`; otherwise → `<pkg>.md`.  The `.md` extension
     * is hardcoded — the target is the markdown plugin's own output file, not this plugin's.
     */
    private fun markdownPackageIndexFilename(
        pkg: String,
        pkgFiles: List<FileDescriptorProtoWrapper>,
    ): String {
        if (pkg.isEmpty()) return "default.md"
        val pkgAsDir = pkg.replace('.', '/')
        val namespaced =
            pkgFiles.all { f ->
                val name = f.name ?: return@all false
                name.substringBeforeLast('/', missingDelimiterValue = "") == pkgAsDir
            }
        return if (namespaced) "$pkgAsDir/package.md" else "$pkg.md"
    }

    private fun overviewTitle(file: FileDescriptorProtoWrapper): String = "File overview: ${file.name ?: "(unnamed)"}"

    private fun messageTitle(entry: MessageEntry): String = "Message: ${entry.fqn}"

    private fun completeTitle(): String = commonPackagePrefix().let { if (it.isEmpty()) "Complete schema" else "Complete schema: $it" }

    private fun packageTitle(pkg: String): String = if (pkg.isEmpty()) "Package: (default)" else "Package: $pkg"

    private fun serviceTitle(svc: ServiceDescriptorProtoWrapper): String = "Service: ${index.fqnOf(svc)}"

    private fun enumerationTitle(enum: EnumDescriptorProtoWrapper): String = "Enum: ${index.fqnOf(enum)}"

    /** Distinct package strings across scope files, in first-seen file order; `""` = the no-package group. */
    private fun distinctPackages(): List<String> = index.files.map { index.packageOf(it) }.distinct()

    /** Longest dotted prefix shared by every scope file's package; `""` if none or no packages. */
    private fun commonPackagePrefix(): String {
        val parts = index.files.mapNotNull { index.packageOf(it).takeIf(String::isNotEmpty)?.split('.') }
        if (parts.isEmpty()) return ""
        val limit = parts.minOf { it.size }
        val keep = (0 until limit).takeWhile { i -> parts.all { it[i] == parts[0][i] } }
        return keep.joinToString(".") { parts[0][it] }
    }

    // ===== Diagram model =========================================================================

    /** Pre-built render plan: title + classes + arrows.  The renderer is purely textual. */
    private data class Diagram(
        val title: String,
        val enums: List<EnumDescriptorProtoWrapper>,
        val messages: List<DescriptorProtoWrapper>,
        val services: List<ServiceDescriptorProtoWrapper>,
        val arrows: List<String>,
    )

    private fun overviewDiagram(file: FileDescriptorProtoWrapper): Diagram {
        val ms = index.messages(file)
        val es = index.enums(file)
        val svs = file.services
        return Diagram(overviewTitle(file), es, ms, svs, fullArrows(ms, svs, es))
    }

    private fun completeDiagram(): Diagram {
        val ms = index.allMessages()
        val es = index.allEnums()
        val svs = index.files.flatMap { it.services }
        return Diagram(completeTitle(), es, ms, svs, fullArrows(ms, svs, es))
    }

    private fun packageDiagram(pkg: String): Diagram {
        val pkgFiles = index.files.filter { index.packageOf(it) == pkg }
        val ms = pkgFiles.flatMap { index.messages(it) }
        val es = pkgFiles.flatMap { index.enums(it) }
        val svs = pkgFiles.flatMap { it.services }
        return Diagram(packageTitle(pkg), es, ms, svs, fullArrows(ms, svs, es))
    }

    private fun messageDiagram(entry: MessageEntry): Diagram {
        val focus = entry.msg
        if (focus.name?.value == null) {
            return Diagram(messageTitle(entry), emptyList(), emptyList(), emptyList(), emptyList())
        }
        val focusId = index.refOf(focus).id

        val allMessages = index.allMessages()
        val allServices = index.files.flatMap { it.services }
        val messagesById = allMessages.associateBy { index.refOf(it).id }
        val enumsById = index.allEnums().associateBy { index.refOf(it).id }

        val outgoingTargets = focus.fields.mapNotNull { fieldTargetId(focus, it) }.toSet()
        val incomingMessages = allMessages.filter { msg ->
            index.refOf(msg).id != focusId && msg.fields.any { fieldTargetId(msg, it) == focusId }
        }
        val incomingServices = allServices.filter { svc ->
            svc.methods.any { m ->
                methodTargetId(m.inputType?.value) == focusId || methodTargetId(m.outputType?.value) == focusId
            }
        }

        /*
         * Force the direct parent into the diagram even when no field connects them, so the
         * nesting arrow has somewhere to anchor.  Only the direct parent — not the whole ancestor
         * chain — keeps the diagram focused on one hop in every direction.
         */
        val includedMessages = LinkedHashSet<DescriptorProtoWrapper>().apply {
            add(focus)
            outgoingTargets.forEach { messagesById[it]?.let(::add) }
            addAll(incomingMessages)
            entry.parent?.let(::add)
        }.toList()
        val includedEnums = outgoingTargets.mapNotNull { enumsById[it] }.distinct()
        val included = includedIds(includedEnums, includedMessages)

        /*
         * Only relationships that touch the focus message — secondary edges between the
         * surrounding classes are intentionally elided.
         */
        val arrows = buildList {
            addAll(fieldArrows(listOf(focus)))
            for (src in incomingMessages) {
                for (field in src.fields) {
                    if (fieldTargetId(src, field) != focusId) continue
                    arrowOf(src, field)?.let(::add)
                }
            }
            for (svc in incomingServices) {
                val svcName = svc.name?.value ?: continue
                for (m in svc.methods) {
                    val mname = m.name?.value ?: continue
                    if (methodTargetId(m.inputType?.value) == focusId) add("$svcName ..> \"1\" $focusId : $mname")
                    if (methodTargetId(m.outputType?.value) == focusId) add("$svcName ..> \"1\" $focusId : $mname")
                }
            }
            /*
             * Composition arrows for every message rendered in this diagram — not just
             * focus + incoming.  `renderMessage` emits a oneof pseudo-class for each real
             * oneof on any included message (outgoing field targets, the forced parent, …),
             * so the matching `Parent *-- "0..1" Parent.<oneof>` arrow has to be drawn for all
             * of them or those pseudo-classes float unconnected.
             */
            addAll(oneofCompositionArrows(includedMessages))
            addAll(nestingArrows(allMessages, included))
        }

        return Diagram(messageTitle(entry), includedEnums, includedMessages, incomingServices, arrows)
    }

    /**
     * One service in focus: the service's class block, a class block for every in-scope message it
     * references via an RPC input or output (outgoing, one hop), and only the RPC dependency arrows
     * for this service.  Services have no incoming references, and RPC input/output types are always
     * messages (never enums), so neither an enum list nor an incoming side applies.
     */
    private fun serviceDiagram(svc: ServiceDescriptorProtoWrapper): Diagram {
        val title = serviceTitle(svc)
        if (svc.name?.value == null) {
            return Diagram(title, emptyList(), emptyList(), emptyList(), emptyList())
        }
        val allMessages = index.allMessages()
        val messagesById = allMessages.associateBy { index.refOf(it).id }

        val targetIds =
            svc.methods.flatMap { listOfNotNull(methodTargetId(it.inputType?.value), methodTargetId(it.outputType?.value)) }.toSet()
        val includedMessages =
            LinkedHashSet<DescriptorProtoWrapper>().apply { targetIds.forEach { messagesById[it]?.let(::add) } }.toList()
        val included = includedIds(emptyList(), includedMessages)

        val arrows = buildList {
            addAll(serviceArrows(listOf(svc)))
            addAll(oneofCompositionArrows(includedMessages))
            addAll(nestingArrows(allMessages, included))
        }
        return Diagram(title, emptyList(), includedMessages, listOf(svc), arrows)
    }

    /**
     * One enum in focus: the enum's class block, a class block for every in-scope message with a
     * field referencing it (incoming, one hop), and only the field-association arrows that target
     * the enum.  Enums have no outgoing references and cannot be referenced by RPCs; the parent of a
     * nested enum is not forced in, since [nestingArrows] only draws message→message nesting.
     */
    private fun enumerationDiagram(enum: EnumDescriptorProtoWrapper): Diagram {
        val title = enumerationTitle(enum)
        if (enum.name?.value == null) {
            return Diagram(title, emptyList(), emptyList(), emptyList(), emptyList())
        }
        val focusId = index.refOf(enum).id
        val allMessages = index.allMessages()

        val incomingMessages = allMessages.filter { msg -> msg.fields.any { fieldTargetId(msg, it) == focusId } }
        val included = includedIds(listOf(enum), incomingMessages)

        val arrows = buildList {
            for (src in incomingMessages) {
                for (field in src.fields) {
                    if (fieldTargetId(src, field) != focusId) continue
                    arrowOf(src, field)?.let(::add)
                }
            }
            addAll(oneofCompositionArrows(incomingMessages))
            addAll(nestingArrows(allMessages, included))
        }
        return Diagram(title, listOf(enum), incomingMessages, emptyList(), arrows)
    }

    /** Every arrow for a non-MESSAGE diagram: fields, then oneof composition, then services, then nesting. */
    private fun fullArrows(
        messages: List<DescriptorProtoWrapper>,
        services: List<ServiceDescriptorProtoWrapper>,
        enums: List<EnumDescriptorProtoWrapper>,
    ): List<String> {
        val included = includedIds(enums, messages)
        return fieldArrows(messages) +
            oneofCompositionArrows(messages) +
            serviceArrows(services) +
            nestingArrows(messages, included)
    }

    private fun includedIds(
        enums: List<EnumDescriptorProtoWrapper>,
        messages: List<DescriptorProtoWrapper>,
    ): Set<String> = (enums.map { index.refOf(it).id } + messages.map { index.refOf(it).id }).toSet()

    // ===== Render pipeline =======================================================================

    private fun render(d: Diagram): String {
        val body =
            buildString {
                appendFrontmatter(d.title)
                appendLine("classDiagram")
                if (options.direction != Direction.TB) appendLine("    direction ${options.direction.name}")
                appendTypes(d.enums, d.messages, d.services)
                appendArrows(d.arrows)
                appendFooter(deprecatedClassIds(d))
            }
        return when (options.outputType) {
            OutputType.STANDALONE_MERMAID -> body
            OutputType.STANDALONE_MARKDOWN -> wrapMarkdown(d.title, body)
            OutputType.EMBEDDED_MARKDOWN -> wrapEmbedded(body)
        }
    }

    /**
     * Wraps a rendered Mermaid `classDiagram` body in a Markdown shell: an H1 heading derived from
     * the diagram's [title] followed by a blank line and a ` ```mermaid ` fenced code block
     * containing [body] verbatim.  The fence preserves the diagram's YAML frontmatter and class
     * body so any Mermaid-aware renderer (GitHub, IDEs, static-site generators) still sees a valid
     * diagram inside.
     */
    private fun wrapMarkdown(
        title: String,
        body: String,
    ): String =
        buildString {
            appendLine("# $title")
            appendLine()
            appendLine("```mermaid")
            append(body)
            if (!body.endsWith('\n')) appendLine()
            appendLine("```")
        }

    /**
     * Wraps a rendered Mermaid `classDiagram` body in a bare fenced ` ```mermaid ` block with a
     * leading blank line.  Used by [OutputType.EMBEDDED_MARKDOWN] where the host markdown's own
     * section heading sits directly above the insertion point, so no extra title is added here.
     */
    private fun wrapEmbedded(body: String): String =
        buildString {
            appendLine()
            appendLine("```mermaid")
            append(body)
            if (!body.endsWith('\n')) appendLine()
            appendLine("```")
        }

    private fun StringBuilder.appendFrontmatter(title: String) {
        val cfg = buildList {
            if (options.hideEmptyMembersBox) add("hideEmptyMembersBox: true")
            if (!options.hierarchicalNamespaces) add("hierarchicalNamespaces: false")
        }
        appendLine("---")
        appendLine("  title: \"$title\"")
        if (cfg.isNotEmpty()) {
            appendLine("  config:")
            appendLine("    class:")
            cfg.forEach { appendLine("      $it") }
        }
        appendLine("---")
    }

    private fun StringBuilder.appendArrows(arrows: List<String>) {
        if (arrows.isEmpty()) return
        appendLine()
        arrows.forEach { appendLine("    $it") }
    }

    /**
     * The diagram-body trailer: a `cssClass "id1,id2,…" deprecated` binding plus the
     * `classDef deprecated …` definition that supplies the strikethrough + muted-gray styling.
     * Emitted *after* every class and relationship — the classDiagram grammar only accepts
     * `cssClass`/`classDef` once every node has been declared, and the `color:` property must
     * precede `text-decoration:` (the parser otherwise trips on the dash in the style values).
     */
    private fun StringBuilder.appendFooter(deprecatedIds: List<String>) {
        if (deprecatedIds.isEmpty()) return
        appendLine()
        appendLine("    cssClass \"${deprecatedIds.joinToString(",")}\" $DEPRECATED_CSS_CLASS")
        appendLine("    $DEPRECATED_CLASS_DEF")
    }

    // ===== Class blocks ==========================================================================

    private fun StringBuilder.appendTypes(
        enums: List<EnumDescriptorProtoWrapper>,
        messages: List<DescriptorProtoWrapper>,
        services: List<ServiceDescriptorProtoWrapper>,
    ) {
        if (options.suppressNamespaces) {
            appendBlock(enums, messages, services, "    ")
            return
        }
        val byPkg = LinkedHashMap<String, TypeBucket>()
        fun bucket(p: String) = byPkg.getOrPut(p) { TypeBucket() }
        enums.forEach { bucket(index.packageOf(it)).enums.add(it) }
        messages.forEach { bucket(index.packageOf(it)).messages.add(it) }
        services.forEach { bucket(index.packageOf(it)).services.add(it) }

        var first = true
        for ((pkg, b) in byPkg) {
            if (!first) appendLine()
            first = false
            if (pkg.isEmpty()) {
                appendBlock(b.enums, b.messages, b.services, "    ")
            } else {
                appendLine("    namespace $pkg {")
                appendBlock(b.enums, b.messages, b.services, "        ")
                appendLine("    }")
            }
        }
    }

    private class TypeBucket(
        val enums: MutableList<EnumDescriptorProtoWrapper> = mutableListOf(),
        val messages: MutableList<DescriptorProtoWrapper> = mutableListOf(),
        val services: MutableList<ServiceDescriptorProtoWrapper> = mutableListOf(),
    )

    private fun StringBuilder.appendBlock(
        enums: List<EnumDescriptorProtoWrapper>,
        messages: List<DescriptorProtoWrapper>,
        services: List<ServiceDescriptorProtoWrapper>,
        indent: String,
    ) {
        var first = true
        fun separate() {
            if (!first) appendLine()
            first = false
        }
        enums.forEach {
            separate()
            renderEnum(it, indent)
        }
        messages.forEach {
            separate()
            renderMessage(it, indent)
        }
        services.forEach {
            separate()
            renderService(it, indent)
        }
    }

    private fun StringBuilder.renderEnum(
        enum: EnumDescriptorProtoWrapper,
        indent: String,
    ) {
        if (enum.name?.value == null) return
        appendLine("$indent${classDeclaration(index.refOf(enum))} {")
        appendLine("$indent    <<enumeration>>")
        for (v in enum.values) {
            val name = v.name?.value ?: continue
            appendLine("$indent    $name${deprecatedSuffix(v.options?.deprecated)}")
        }
        appendLine("$indent}")
    }

    private fun StringBuilder.renderService(
        service: ServiceDescriptorProtoWrapper,
        indent: String,
    ) {
        val name = service.name?.value ?: return
        appendLine("${indent}class $name {")
        appendLine("$indent    <<service>>")
        for (m in service.methods) {
            val mname = m.name?.value ?: continue
            val inp = typeLabel(m.inputType?.value).let { if (m.clientStreaming?.value == true) "stream $it" else it }
            val out = typeLabel(m.outputType?.value).let { if (m.serverStreaming?.value == true) "stream $it" else it }
            appendLine("$indent    $mname($inp) $out${deprecatedSuffix(m.options?.deprecated)}")
        }
        appendLine("$indent}")
    }

    private fun StringBuilder.renderMessage(
        msg: DescriptorProtoWrapper,
        indent: String,
    ) {
        if (msg.name?.value == null) return
        val decl = classDeclaration(index.refOf(msg))
        val separate = options.oneofRenderingType == OneofRenderingType.SEPARATE
        /*
         * In SEPARATE mode the real-oneof fields are pulled out of the parent class block into
         * their own pseudo-classes below.  In EMBEDDED mode every field stays in the parent block;
         * oneof grouping is conveyed by an inline `«oneof <name>»` header and indented members.
         * Synthetic single-field oneofs (proto3's `optional` wrappers) render as regular fields
         * in both modes — see [inRealOneof].
         */
        val bodyFields = if (separate) msg.fields.filterNot { inRealOneof(msg, it) } else msg.fields
        if (bodyFields.isEmpty()) {
            appendLine("$indent$decl")
        } else {
            appendLine("$indent$decl {")
            renderFieldsWithOneofGroups(msg, bodyFields, indent)
            appendLine("$indent}")
        }
        if (separate) renderOneofPseudoClasses(msg, indent)
    }

    private fun StringBuilder.renderFieldsWithOneofGroups(
        msg: DescriptorProtoWrapper,
        fields: List<FieldDescriptorProtoWrapper>,
        indent: String,
    ) {
        val embedded = options.oneofRenderingType == OneofRenderingType.EMBEDDED
        var currentGroup: Int? = null
        for (field in fields) {
            val group = field.oneofIndex?.value?.takeIf { embedded && !isSyntheticOneof(msg, it) }
            if (group != null && group != currentGroup) {
                appendLine("$indent    «oneof ${msg.oneofDecls[group].name?.value ?: "?"}»")
            }
            currentGroup = group
            val prefix = if (group != null) "&nbsp;&nbsp;" else ""
            appendLine("$indent    $prefix${renderField(msg, field)}")
        }
    }

    private fun StringBuilder.renderOneofPseudoClasses(
        msg: DescriptorProtoWrapper,
        indent: String,
    ) {
        val parentRef = index.refOf(msg)
        if (parentRef.id.isEmpty()) return
        msg.oneofDecls.forEachIndexed { idx, oneof ->
            if (isSyntheticOneof(msg, idx)) return@forEachIndexed
            val oneofName = oneof.name?.value ?: return@forEachIndexed
            val members = msg.fields.filter { it.oneofIndex?.value == idx }
            if (members.isEmpty()) return@forEachIndexed
            appendLine()
            appendLine("${indent}class ${oneofClassId(parentRef.id, oneofName)}[\"${parentRef.label}.$oneofName\"] {")
            appendLine("$indent    <<oneof>>")
            for (f in members) appendLine("$indent    ${renderField(msg, f)}")
            appendLine("$indent}")
        }
    }

    private fun renderField(
        msg: DescriptorProtoWrapper,
        field: FieldDescriptorProtoWrapper,
    ): String {
        val visibility = if (options.suppressVisibility) "" else "+"
        return "$visibility${fieldTypeLabel(msg, field)} ${field.name?.value ?: "?"}${deprecatedSuffix(field.options?.deprecated)}"
    }

    /** `class Foo` or `class Foo_Bar["Foo.Bar"]` depending on whether label and id diverge. */
    private fun classDeclaration(ref: TypeRef): String = if (ref.id == ref.label) "class ${ref.id}" else "class ${ref.id}[\"${ref.label}\"]"

    // ===== Type-label resolution (field-line / method-line text) =================================

    /** In-class field-line type: `map~K, V~`, `Base[]`, or just `Base`. */
    private fun fieldTypeLabel(
        msg: DescriptorProtoWrapper,
        field: FieldDescriptorProtoWrapper,
    ): String {
        mapEntryOf(msg, field)?.let { entry ->
            val k = entry.fields.getOrNull(0)?.let(::scalarOrRefLabel) ?: "?"
            val v = entry.fields.getOrNull(1)?.let(::scalarOrRefLabel) ?: "?"
            return "map~$k, $v~"
        }
        val base = scalarOrRefLabel(field)
        return if (field.label?.value == Label.LABEL_REPEATED) "$base[]" else base
    }

    /**
     * Bare field-line label: the dotted [TypeRef.label] for message/enum/group references (so
     * nested types render visually disambiguated, e.g. `User.Address`), or the lowercased scalar
     * (`int32`, `string`, …) for scalars.  Used by [fieldTypeLabel] for both the field itself
     * and the K/V of a map entry.  Mermaid doesn't link the field-line type text to any class,
     * so the dotted label is purely cosmetic — the arrow endpoint comes from [fieldTargetId].
     */
    private fun scalarOrRefLabel(field: FieldDescriptorProtoWrapper): String =
        when (val t = field.type?.value) {
            Type.TYPE_MESSAGE, Type.TYPE_ENUM, Type.TYPE_GROUP -> typeLabel(field.typeName?.value)
            null -> "?"
            else -> t.name.removePrefix("TYPE_").lowercase()
        }

    /** Resolve an FQN to its display label, falling back to the leaf-name slice for out-of-scope types. */
    private fun typeLabel(fqn: String?): String = index.resolveFqn(fqn)?.label ?: fqn?.substringAfterLast('.') ?: "?"

    // ===== Map handling ==========================================================================

    /**
     * The synthetic `map_entry` message protobuf injects for a `map<K, V>` field, or null if
     * [field] is not a map field.  Map fields desugar to a `repeated FooEntry` of a nested
     * message named after the field with `option map_entry = true`; that nested message lives
     * inside the containing message ([msg]), so we look it up there by matching name.
     */
    private fun mapEntryOf(
        msg: DescriptorProtoWrapper,
        field: FieldDescriptorProtoWrapper,
    ): DescriptorProtoWrapper? {
        if (field.label?.value != Label.LABEL_REPEATED) return null
        if (field.type?.value != Type.TYPE_MESSAGE) return null
        val leaf = field.typeName?.value?.substringAfterLast('.') ?: return null
        return msg.nestedTypes.firstOrNull { it.options?.mapEntry?.value == true && it.name?.value == leaf }
    }

    // ===== Arrow building ========================================================================

    private fun fieldArrows(messages: List<DescriptorProtoWrapper>): List<String> = messages.flatMap { m -> m.fields.mapNotNull { arrowOf(m, it) } }

    private fun arrowOf(
        msg: DescriptorProtoWrapper,
        field: FieldDescriptorProtoWrapper,
    ): String? {
        val target = fieldTargetId(msg, field) ?: return null
        val name = field.name?.value ?: return null
        val source = arrowSource(msg, field) ?: return null
        return "$source --> \"${cardinality(msg, field)}\" $target : $name"
    }

    /**
     * The Mermaid class identifier the arrow drawn from [field] should target: the message/enum
     * referenced (re-targeted through to the value type for map fields), or null for scalars,
     * scalar-valued maps, and suppressed well-known types.  The in-class field-line *text* still
     * shows the leaf name in all those cases via [fieldTypeLabel], so the schema info stays
     * visible even when the arrow doesn't.
     */
    private fun fieldTargetId(
        msg: DescriptorProtoWrapper,
        field: FieldDescriptorProtoWrapper,
    ): String? {
        mapEntryOf(msg, field)?.let { entry ->
            val v = entry.fields.getOrNull(1) ?: return null
            return referenceId(v.type?.value, v.typeName?.value)
        }
        return referenceId(field.type?.value, field.typeName?.value)
    }

    /** Mermaid identifier for an RPC's input/output type, with WKT suppression applied. */
    private fun methodTargetId(fqn: String?): String? {
        if (isSuppressedWkt(fqn)) return null
        return index.resolveFqn(fqn)?.id ?: fqn?.substringAfterLast('.')
    }

    private fun referenceId(
        type: Type?,
        fqn: String?,
    ): String? {
        if (isSuppressedWkt(fqn)) return null
        return when (type) {
            Type.TYPE_MESSAGE, Type.TYPE_ENUM, Type.TYPE_GROUP ->
                index.resolveFqn(fqn)?.id ?: fqn?.substringAfterLast('.')

            else -> null
        }
    }

    private fun isSuppressedWkt(fqn: String?): Boolean = options.suppressWellKnownTypes && isWellKnownType(fqn)

    private fun serviceArrows(services: List<ServiceDescriptorProtoWrapper>): List<String> =
        buildList {
            for (svc in services) {
                val svcName = svc.name?.value ?: continue
                for (m in svc.methods) {
                    val mname = m.name?.value ?: continue
                    methodTargetId(m.inputType?.value)?.let { add("$svcName ..> \"1\" $it : $mname") }
                    methodTargetId(m.outputType?.value)?.let { add("$svcName ..> \"1\" $it : $mname") }
                }
            }
        }

    /** Composition arrows from a message to each of its real-oneof pseudo-classes (SEPARATE mode only). */
    private fun oneofCompositionArrows(messages: List<DescriptorProtoWrapper>): List<String> {
        if (options.oneofRenderingType != OneofRenderingType.SEPARATE) return emptyList()
        return buildList {
            for (msg in messages) {
                val parentId = index.refOf(msg).id.takeIf { it.isNotEmpty() } ?: continue
                msg.oneofDecls.forEachIndexed { idx, decl ->
                    if (isSyntheticOneof(msg, idx)) return@forEachIndexed
                    val name = decl.name?.value ?: return@forEachIndexed
                    if (msg.fields.none { it.oneofIndex?.value == idx }) return@forEachIndexed
                    add("$parentId *-- \"0..1\" ${oneofClassId(parentId, name)} : $name")
                }
            }
        }
    }

    /**
     * Composition arrows `Parent *-- Child : nested` for every direct nesting pair where both
     * endpoints appear in [includedIds].  Iterates [messages] in preorder and lists each parent's
     * direct nested children — so emission order matches the source declaration order.
     */
    private fun nestingArrows(
        messages: List<DescriptorProtoWrapper>,
        includedIds: Set<String>,
    ): List<String> =
        messages.flatMap { parent ->
            val parentId = index.refOf(parent).id.takeIf { it.isNotEmpty() } ?: return@flatMap emptyList<String>()
            parent.nestedTypes.mapNotNull { child ->
                if (child.options?.mapEntry?.value == true) return@mapNotNull null
                val childId = index.refOf(child).id.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                if (parentId in includedIds && childId in includedIds) "$parentId *-- $childId : nested" else null
            }
        }

    // ===== Cardinality, oneofs, arrow source =====================================================

    /**
     * Protobuf presence model encoded as a Mermaid target-side cardinality:
     *  - `*`     — any `repeated` field
     *  - `0..1`  — non-repeated message/group (always optional outside of RPCs)
     *  - `0..1`  — non-repeated enum/scalar in a real (non-synthetic) oneof or explicitly `optional`
     *  - `1`     — non-repeated enum/scalar otherwise (proto2 `required`, proto3 implicit default)
     */
    private fun cardinality(
        msg: DescriptorProtoWrapper,
        field: FieldDescriptorProtoWrapper,
    ): String {
        if (field.label?.value == Label.LABEL_REPEATED) return "*"
        return when (field.type?.value) {
            Type.TYPE_MESSAGE, Type.TYPE_GROUP -> "0..1"
            else -> if (inRealOneof(msg, field) || isExplicitOptional(field, index.syntaxOf(msg))) "0..1" else "1"
        }
    }

    /**
     * Whether [msg]'s `oneof_decl[oneofIndex]` is a synthetic wrapper protobuf injects to represent
     * a proto3 `optional` field.  Such wrappers always have exactly one member whose
     * `proto3_optional` flag is set; treating them as real oneofs would give every proto3
     * `optional` field its own header/pseudo-class, which is nonsense.
     */
    private fun isSyntheticOneof(
        msg: DescriptorProtoWrapper,
        oneofIndex: Int,
    ): Boolean {
        val members = msg.fields.filter { it.oneofIndex?.value == oneofIndex }
        return members.size == 1 && members.single().proto3Optional?.value == true
    }

    private fun inRealOneof(
        msg: DescriptorProtoWrapper,
        field: FieldDescriptorProtoWrapper,
    ): Boolean {
        val idx = field.oneofIndex?.value ?: return false
        return !isSyntheticOneof(msg, idx)
    }

    /**
     * Mermaid class identifier for the SEPARATE-mode oneof pseudo-class of `<parent>.<oneofName>`.
     * Joined with `_` because Mermaid class identifiers may not contain `.`; the dotted form
     * survives as the pseudo-class's display label.
     */
    private fun oneofClassId(
        parentId: String,
        oneofName: String,
    ): String = "${parentId}_$oneofName"

    /**
     * Identifier the association arrow should originate from for [field]: the parent's own id, or
     * its oneof pseudo-class's id when in SEPARATE mode and [field] is a real-oneof member.
     */
    private fun arrowSource(
        msg: DescriptorProtoWrapper,
        field: FieldDescriptorProtoWrapper,
    ): String? {
        val parentId = index.refOf(msg).id.takeIf { it.isNotEmpty() } ?: return null
        if (options.oneofRenderingType != OneofRenderingType.SEPARATE) return parentId
        val idx = field.oneofIndex?.value ?: return parentId
        if (isSyntheticOneof(msg, idx)) return parentId
        val oneofName = msg.oneofDecls[idx].name?.value ?: return parentId
        return oneofClassId(parentId, oneofName)
    }

    /**
     * Whether [field] was explicitly marked `optional` in the source proto.  In proto2 every
     * non-repeated field carries an explicit label and `LABEL_OPTIONAL` is the signal.  In proto3
     * every non-repeated field arrives as `LABEL_OPTIONAL` regardless of the source keyword, so
     * the `proto3_optional` flag — which the parser sets only when the user wrote `optional` —
     * is the trustworthy signal there.
     */
    private fun isExplicitOptional(
        field: FieldDescriptorProtoWrapper,
        syntax: String?,
    ): Boolean =
        when {
            field.proto3Optional?.value == true -> true
            syntax == "proto3" -> false
            else -> field.label?.value == Label.LABEL_OPTIONAL
        }

    // ===== Deprecated styling ====================================================================

    private fun deprecatedSuffix(flag: SyntaxElement<Boolean>?): String = if (flag?.value == true) DEPRECATED_MEMBER_LABEL else ""

    /** Identifiers of every deprecated message/enum/service in the diagram — bound by [appendFooter]. */
    private fun deprecatedClassIds(d: Diagram): List<String> =
        buildList {
            fun maybeAdd(
                deprecated: Boolean,
                id: String,
            ) {
                if (deprecated && id.isNotEmpty()) add(id)
            }
            d.enums.forEach { maybeAdd(it.options?.deprecated?.value == true, index.refOf(it).id) }
            d.messages.forEach { maybeAdd(it.options?.deprecated?.value == true, index.refOf(it).id) }
            d.services.forEach { maybeAdd(it.options?.deprecated?.value == true, it.name?.value.orEmpty()) }
        }

    private companion object {
        const val DEPRECATED_CSS_CLASS = "deprecated"
        const val DEPRECATED_CLASS_DEF = "classDef deprecated color:#888,text-decoration:line-through"
        const val DEPRECATED_MEMBER_LABEL = " «deprecated»"
    }
}
