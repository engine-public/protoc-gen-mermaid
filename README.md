# protoc-gen-mermaid

A `protoc` compiler plugin that turns protobuf message, enum, and service definitions into [Mermaid](https://mermaid.js.org/) [class diagrams](https://mermaid.js.org/syntax/classDiagram.html).
Each compile invocation can emit per-proto-file overview diagrams, per-message / per-service / per-enum focus diagrams, per-package diagrams, and a single aggregate diagram across the whole compile scope — selected by the [`diagramTypes`](#compiler-options) option.
The plugin compiles to a native binary via GraalVM so it can be used directly in a `protoc` invocation without a JVM on `PATH`.

## Subprojects

| path | description |
|---|---|
| **root** (`src/`) | The plugin executable. Reads `CodeGeneratorRequest` from stdin and writes `CodeGeneratorResponse` to stdout, per the protoc plugin protocol. |
| [`examples/`](examples/) | Acceptance test suite. Each example fixes one compiler option to a non-default value and dumps the resulting diagrams as checked-in reference fixtures. |

## What it renders

Every diagram is a Mermaid `classDiagram` body containing:

- **Messages** as `class` blocks, one `<type> <name>` line per field.
  Repeated fields keep a `Type[]` suffix on the field line so multiplicity is visible at a glance even without following the association arrow.
- **Enums** as `<<enumeration>>` classes, one value per line.
- **Services** as `<<service>>` classes, one `RpcName(Request) Response` method line per RPC.
- **Association arrows** from each message field to its target type, carrying a target-side cardinality of `*` for `repeated`, `0..1` for non-repeated message/group fields and for non-repeated enum/scalar fields that are explicitly `optional`, and `1` otherwise.
- **Dependency arrows** from each RPC to its request/response messages (always `1`).
- **Composition arrows** (`*--`) from a parent message to each of its nested messages or enums when both endpoints appear in the same diagram, labelled `nested`.

Nested messages and enums are flattened to siblings (depth-first, parent before children) and rendered with a disambiguated identifier built from the containing-message chain joined with `_` plus a dotted display label, e.g. `class User_Address["User.Address"]`.
Top-level types keep their bare leaf name.

Synthetic `map_entry` messages are filtered out; the map fields they back render as Mermaid generics `map~K, V~` with the association arrow re-targeted at the value type (or omitted when the value is scalar).

Oneofs are rendered per the [`oneofRenderingType`](#compiler-options) option: `EMBEDDED` (default) lists oneof members inline under an `«oneof <name>»` header within the parent's class block; `SEPARATE` extracts each oneof into its own `<<oneof>>` pseudo-class composed into the parent.
Synthetic single-field oneofs (the wrappers protobuf injects for proto3 `optional`) are filtered out and those fields render as regular fields.

Protobuf well-known types (anything under `google.protobuf.*` — `Timestamp`, `Duration`, `Any`, `Empty`, the `*Value` wrappers, etc.) are special-cased per the [`suppressWellKnownTypes`](#compiler-options) option (default `true`): the in-class field line still reads `Timestamp created_at` and the RPC method line still reads `Foo(Empty) Empty`, but no arrow is drawn and the WKT is never pulled into a `MESSAGE`-mode focus diagram as an orbital class.
Suppression triggers on *reference* — a WKT proto explicitly placed in `filesToGenerate` would still render as a class.

The `[deprecated = true]` option is honored at every surface: deprecated messages, enums, and services render with strikethrough and muted-gray text; deprecated fields, RPCs, and enum values render with a trailing inline `«deprecated»` suffix.

Every diagram is prefixed with a YAML frontmatter block carrying both Mermaid's own `title`/`config` keys and three generation-provenance keys mirroring [protoc-gen-markdown](https://github.com/HotelEngine/protoc-gen-markdown)'s: `generated-by` (the GitHub release-tag URL encoding plugin identity and version), `protoc-gen-mermaid-generated-on` (the ISO-8601 generation instant), and `protoc-gen-mermaid-options` (a round-trippable `key=value` snapshot of the compile options).
Mermaid ignores frontmatter keys it doesn't recognize, so the provenance lives in the frontmatter rather than as a comment inside the rendered diagram body.

## Usage

The plugin executable must be named `protoc-gen-mermaid` on `PATH` (or pointed at explicitly — see the Gradle example below).
Options are passed as `--mermaid_out=<comma-separated-options>:<outdir>`.

### Gradle

Configure the [`protobuf-gradle-plugin`](https://github.com/google/protobuf-gradle-plugin) to invoke `protoc-gen-mermaid` as a code-generation plugin.

```kotlin
plugins {
    id("com.google.protobuf") version "0.9.6"
}

protobuf {
    plugins {
        create("mermaid") {
            artifact = "com.engine:protoc-gen-mermaid:<version>:${osdetector.classifier}@exe"
        }
    }
    generateProtoTasks {
        all().all {
            plugins {
                create("mermaid") {
                    option("diagramTypes=FILE_OVERVIEW,COMPLETE")
                    option("direction=LR")
                    // other options as desired
                }
            }
        }
    }
}
```

The published artifact is POM-only with one classified `.exe` per platform (`linux-x86_64`, `linux-aarch_64`, `osx-aarch_64`, `windows-x86_64`), mirroring the `io.grpc:protoc-gen-grpc-java` convention.
Use the `com.google.osdetector` Gradle plugin to pick the right classifier at resolve time.

### Bash

```bash
# one-time: download the native binary for your platform from the GitHub release
curl -L -o protoc-gen-mermaid \
  https://github.com/hotelengine/protoc-gen-mermaid/releases/download/<version>/protoc-gen-mermaid-<os>-<arch>.exe
chmod +x protoc-gen-mermaid
mv protoc-gen-mermaid /usr/local/bin/

protoc \
  --proto_path=path/to/your/protos \
  --mermaid_out=diagramTypes=FILE_OVERVIEW,direction=LR:./build/mermaid \
  src/main/proto/example/v1/service.proto
```

## Compiler Options

All options are defined on [`ProtocGenMermaid.Options`](src/main/kotlin/com/engine/protoc/mermaid/ProtocGenMermaid.kt).
Click the option name to jump to its KDoc for full semantics.

| name | type | default | summary |
|---|---|---|---|
| [`completeInsertionPoint`](src/main/kotlin/com/engine/protoc/mermaid/ProtocGenMermaid.kt#L134) | string | `file_header` | Name of the protoc insertion point the `COMPLETE` diagram targets under `outputType=EMBEDDED_MARKDOWN`, emitted verbatim with no scope suffix. Matches the bare `file_header` marker protoc-gen-markdown emits in the root `overview.md`. |
| [`diagramTypes`](src/main/kotlin/com/engine/protoc/mermaid/ProtocGenMermaid.kt#L73) | enum list | every value | Which output files the compiler emits per invocation: `FILE_OVERVIEW` (one `.mermaid` per input proto), `MESSAGE` (one `.mermaid` per message, top-level or nested), `SERVICE` (one `.mermaid` per service), `ENUMERATION` (one `.mermaid` per enum, top-level or nested), `COMPLETE` (one aggregate `.mermaid` across the whole compile scope), `PACKAGE` (one `.package.mermaid` per proto package). |
| [`direction`](src/main/kotlin/com/engine/protoc/mermaid/ProtocGenMermaid.kt#L160) | enum | `TB` | Layout direction forwarded as a body-level `direction <value>` statement: `LR`, `RL`, `TB`, `BT`. `TB` matches Mermaid's default and is omitted from output. |
| [`enumerationInsertionPoint`](src/main/kotlin/com/engine/protoc/mermaid/ProtocGenMermaid.kt#L125) | string | `enum_header_scope` | Name (prefix only) of the protoc insertion point each `ENUMERATION` diagram targets under `outputType=EMBEDDED_MARKDOWN`. The compiler appends the runtime `:<fqn>` scope, matching protoc-gen-markdown's `enum_header_scope:<fqn>` marker. |
| [`fileOverviewInsertionPoint`](src/main/kotlin/com/engine/protoc/mermaid/ProtocGenMermaid.kt#L98) | string | `file_header_scope` | Name (prefix only) of the protoc insertion point each `FILE_OVERVIEW` diagram targets under `outputType=EMBEDDED_MARKDOWN`. The compiler appends the runtime `:<proto>` scope, matching protoc-gen-markdown's `file_header_scope:<proto>` marker. |
| [`hideEmptyMembersBox`](src/main/kotlin/com/engine/protoc/mermaid/ProtocGenMermaid.kt#L34) | boolean | `true` | Emits `config.class.hideEmptyMembersBox: true` in the YAML frontmatter so classes with no fields render as a single titled box rather than empty compartments. |
| [`hierarchicalNamespaces`](src/main/kotlin/com/engine/protoc/mermaid/ProtocGenMermaid.kt#L50) | boolean | `false` | When `false` (the default), emits `config.class.hierarchicalNamespaces: false` so the whole proto package renders as a single flat box. Pass `true` to fall back to Mermaid's default behavior — one nested cluster per dot-segment of the package. |
| [`logFile`](src/main/kotlin/com/engine/protoc/mermaid/ProtocGenMermaid.kt#L180) | string | — | File path the SLF4J binding writes records to. When unset, records go to standard error. |
| [`logLevel`](src/main/kotlin/com/engine/protoc/mermaid/ProtocGenMermaid.kt#L167) | enum | `ERROR` | SLF4J threshold (`TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`) applied to every logger the plugin and its dependencies create. |
| [`messageInsertionPoint`](src/main/kotlin/com/engine/protoc/mermaid/ProtocGenMermaid.kt#L107) | string | `message_header_scope` | Name (prefix only) of the protoc insertion point each `MESSAGE` diagram targets under `outputType=EMBEDDED_MARKDOWN`. The compiler appends the runtime `:<fqn>` scope, matching protoc-gen-markdown's `message_header_scope:<fqn>` marker. |
| [`oneofRenderingType`](src/main/kotlin/com/engine/protoc/mermaid/ProtocGenMermaid.kt#L79) | enum | `EMBEDDED` | How `oneof` groups are rendered: `EMBEDDED` lists members inline under an `«oneof <name>»` header in the parent; `SEPARATE` extracts each oneof into its own `<<oneof>>` pseudo-class composed into the parent. |
| [`outputType`](src/main/kotlin/com/engine/protoc/mermaid/ProtocGenMermaid.kt#L86) | enum | `STANDALONE_MERMAID` | Shape of each emitted artifact: `STANDALONE_MERMAID` writes the bare `classDiagram` to a `.mermaid` file; `STANDALONE_MARKDOWN` writes the same diagram wrapped in a fenced ` ```mermaid ` code block — prefixed by a Markdown `# <title>` heading — to a `.md` file ready to drop into documentation; `EMBEDDED_MARKDOWN` emits protoc insertion-point entries targeting the `.md` files produced by [protoc-gen-markdown](https://github.com/HotelEngine/protoc-gen-markdown) (its `PER_FILE` mode), splicing fenced diagrams into per-file `file_header_scope:<proto>` / `message_header_scope:<fqn>` / `service_header_scope:<fqn>` / `enum_header_scope:<fqn>` markers, the root `overview.md` `file_header` marker (COMPLETE), and each package-index `file_header` marker (PACKAGE) — the latter two require the markdown plugin's `includeIndices=true`. |
| [`packageInsertionPoint`](src/main/kotlin/com/engine/protoc/mermaid/ProtocGenMermaid.kt#L141) | string | `file_header` | Name of the protoc insertion point each `PACKAGE` diagram targets under `outputType=EMBEDDED_MARKDOWN`, emitted verbatim with no scope suffix. Matches the bare `file_header` marker protoc-gen-markdown emits in each package-index file. |
| [`serviceInsertionPoint`](src/main/kotlin/com/engine/protoc/mermaid/ProtocGenMermaid.kt#L116) | string | `service_header_scope` | Name (prefix only) of the protoc insertion point each `SERVICE` diagram targets under `outputType=EMBEDDED_MARKDOWN`. The compiler appends the runtime `:<fqn>` scope, matching protoc-gen-markdown's `service_header_scope:<fqn>` marker. |
| [`suppressNamespaces`](src/main/kotlin/com/engine/protoc/mermaid/ProtocGenMermaid.kt#L58) | boolean | `false` | When `true`, classes are emitted at the top of the `classDiagram` body with no surrounding `namespace { ... }` wrapper. Useful when the rendered diagram is already inside package-scoped documentation. |
| [`suppressVisibility`](src/main/kotlin/com/engine/protoc/mermaid/ProtocGenMermaid.kt#L65) | boolean | `true` | Drops the UML `+` (public) visibility glyph from field lines, since protobuf has no field-level visibility. Set `false` to restore `+type name`. |
| [`suppressWellKnownTypes`](src/main/kotlin/com/engine/protoc/mermaid/ProtocGenMermaid.kt#L148) | boolean | `true` | When `true`, references to types under `google.protobuf.*` keep their leaf name in field/method lines but draw no arrow and pull no orbital class into `MESSAGE`-mode diagrams. |

Enum-valued options accept their values case-insensitively.
List-valued options accept comma-separated values inside the protoc parameter string.

## Related Projects

- [hotelengine/protoc-utils](https://github.com/HotelEngine/protoc-utils) — shared protoc plugin utilities (descriptor wrappers, comment parsing, parameter handling) and the `recorder` plugin used by this project's example suite.
- [hotelengine/protoc-gen-openapi](https://github.com/hotelengine/protoc-gen-openapi) — sibling plugin generating OpenAPI 3.1 documents from the same descriptors; the layout and conventions of this repo follow it closely.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for build commands, the native-image / reflection metadata workflow, the example-suite mechanics, and the PR process.
