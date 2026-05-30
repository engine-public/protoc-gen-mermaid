package com.engine.protoc.mermaid.compile

import com.engine.protoc.util.enums.EnumDescriptorProtoWrapper
import com.engine.protoc.util.file.FileDescriptorProtoWrapper
import com.engine.protoc.util.message.DescriptorProtoWrapper
import com.engine.protoc.util.service.ServiceDescriptorProtoWrapper
import java.util.IdentityHashMap

/** Disambiguated Mermaid identifier (no dots) and human-facing display label (dotted) for a type. */
internal data class TypeRef(val id: String, val label: String)

/**
 * Single-pass scope analysis: walks every in-scope proto file once, flattening nested messages and
 * enums into preorder lists and recording every fact the renderer needs about each descriptor.
 *
 * For a top-level type, the [TypeRef] is `(name, name)`; for a nested type, the containing-message
 * chain is folded in — joined with `_` for the [TypeRef.id] (Mermaid class identifiers can't
 * contain `.`) and with `.` for the [TypeRef.label].  Example: `User.Address` nested inside `User`
 * → `TypeRef("User_Address", "User.Address")`.  The fully-qualified name string protobuf hands us
 * in field/RPC references (`".pkg.User.Address"`) resolves to that same ref via [resolveFqn].
 *
 * Synthetic `map_entry` messages are filtered out everywhere — they never appear in the message
 * list, never get a ref, and never resolve from their FQN.
 */
internal class TypeIndex(val files: List<FileDescriptorProtoWrapper>) {

    private val messageRefs = IdentityHashMap<DescriptorProtoWrapper, TypeRef>()
    private val enumRefs = IdentityHashMap<EnumDescriptorProtoWrapper, TypeRef>()
    private val refsByFqn = HashMap<String, TypeRef>()
    private val packages = IdentityHashMap<Any, String>()
    private val syntaxes = IdentityHashMap<DescriptorProtoWrapper, String?>()
    private val parents = IdentityHashMap<DescriptorProtoWrapper, DescriptorProtoWrapper?>()
    private val fqns = IdentityHashMap<DescriptorProtoWrapper, String>()
    private val messagesByFile = IdentityHashMap<FileDescriptorProtoWrapper, List<DescriptorProtoWrapper>>()
    private val enumsByFile = IdentityHashMap<FileDescriptorProtoWrapper, List<EnumDescriptorProtoWrapper>>()

    init {
        for (file in files) indexFile(file)
    }

    private fun indexFile(file: FileDescriptorProtoWrapper) {
        val pkg = file.`package`?.value.orEmpty()
        val syntax = file.syntax?.value
        val fqnLeader = if (pkg.isEmpty()) "." else ".$pkg."
        val messages = mutableListOf<DescriptorProtoWrapper>()
        val enums = mutableListOf<EnumDescriptorProtoWrapper>()
        packages[file] = pkg

        for (enum in file.enumTypes) {
            val name = enum.name?.value ?: continue
            registerEnum(enum, listOf(name), pkg, fqnLeader, enums)
        }
        for (svc in file.services) packages[svc] = pkg

        fun walk(
            msgs: List<DescriptorProtoWrapper>,
            chain: List<String>,
            parent: DescriptorProtoWrapper?,
        ) {
            for (msg in msgs) {
                if (msg.options?.mapEntry?.value == true) continue
                val name = msg.name?.value ?: continue
                val newChain = chain + name
                val ref = TypeRef(newChain.joinToString("_"), newChain.joinToString("."))
                messageRefs[msg] = ref
                refsByFqn[fqnLeader + ref.label] = ref
                packages[msg] = pkg
                syntaxes[msg] = syntax
                parents[msg] = parent
                fqns[msg] = if (pkg.isEmpty()) ref.label else "$pkg.${ref.label}"
                messages.add(msg)
                for (enum in msg.enumTypes) {
                    val ename = enum.name?.value ?: continue
                    registerEnum(enum, newChain + ename, pkg, fqnLeader, enums)
                }
                walk(msg.nestedTypes, newChain, msg)
            }
        }
        walk(file.messageTypes, emptyList(), null)
        messagesByFile[file] = messages
        enumsByFile[file] = enums
    }

    private fun registerEnum(
        enum: EnumDescriptorProtoWrapper,
        chain: List<String>,
        pkg: String,
        fqnLeader: String,
        out: MutableList<EnumDescriptorProtoWrapper>,
    ) {
        val ref = TypeRef(chain.joinToString("_"), chain.joinToString("."))
        enumRefs[enum] = ref
        refsByFqn[fqnLeader + ref.label] = ref
        packages[enum] = pkg
        out.add(enum)
    }

    fun refOf(msg: DescriptorProtoWrapper): TypeRef = messageRefs[msg] ?: msg.name?.value.orEmpty().let { TypeRef(it, it) }

    fun refOf(enum: EnumDescriptorProtoWrapper): TypeRef = enumRefs[enum] ?: enum.name?.value.orEmpty().let { TypeRef(it, it) }

    /** Resolve a protobuf-emitted fully-qualified type-name (`".pkg.A.B"`) to its [TypeRef], or null if out of scope. */
    fun resolveFqn(fqn: String?): TypeRef? = fqn?.let { refsByFqn[it] }

    fun packageOf(x: Any): String = packages[x].orEmpty()

    fun syntaxOf(msg: DescriptorProtoWrapper): String? = syntaxes[msg]

    fun parentOf(msg: DescriptorProtoWrapper): DescriptorProtoWrapper? = parents[msg]

    fun fqnOf(msg: DescriptorProtoWrapper): String = fqns[msg].orEmpty()

    /** Package-prefixed dotted name for an enum (e.g. `pkg.Outer.Color`), mirroring [fqnOf] for messages. */
    fun fqnOf(enum: EnumDescriptorProtoWrapper): String = refOf(enum).label.let { if (packageOf(enum).isEmpty()) it else "${packageOf(enum)}.$it" }

    /** Package-prefixed name for a service (e.g. `pkg.FooService`); services are always top-level. */
    fun fqnOf(service: ServiceDescriptorProtoWrapper): String = service.name?.value.orEmpty().let { if (packageOf(service).isEmpty()) it else "${packageOf(service)}.$it" }

    fun messages(file: FileDescriptorProtoWrapper): List<DescriptorProtoWrapper> = messagesByFile[file].orEmpty()

    fun enums(file: FileDescriptorProtoWrapper): List<EnumDescriptorProtoWrapper> = enumsByFile[file].orEmpty()

    fun allMessages(): List<DescriptorProtoWrapper> = files.flatMap { messages(it) }

    fun allEnums(): List<EnumDescriptorProtoWrapper> = files.flatMap { enums(it) }
}

/**
 * Whether [fqn] names a protobuf well-known type — anything under `google.protobuf.*`.  Pure FQN
 * predicate independent of any suppression policy; see callers for how policy is layered on top.
 */
internal fun isWellKnownType(fqn: String?): Boolean = fqn != null && fqn.startsWith(".google.protobuf.")
