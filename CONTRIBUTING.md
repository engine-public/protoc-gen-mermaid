# Contributing

## Prerequisites

- GraalVM 21 (the Gradle toolchain spec pins `JvmVendorSpec.GRAAL_VM`; install via SDKMAN, asdf, or the [GraalVM downloads page](https://www.graalvm.org/downloads/) — `.tool-versions` selects `graalvm-community-21.0.2` for asdf).
- A POSIX shell environment.
- A [GitHub personal access token](https://github.com/settings/tokens) with the `read:packages` scope.
  The [engine-public/protoc-utils](https://github.com/engine-public/protoc-utils) dependencies are published to GitHub Packages, which requires authentication even for public packages.
  Set `gpr.user` (your GitHub username) and `gpr.key` (the token) in `~/.gradle/gradle.properties`, or export `GITHUB_ACTOR` and `GITHUB_TOKEN`.

The version of the produced artifacts is read from the `ENGINE_BUILD_VERSION` environment variable and falls back to `0.0.0-pre.0` when unset.

## Build Commands

```bash
./gradlew build                                       # Full JVM build + tests + lint + license check
./gradlew nativeCompile                               # Produce the native plugin binary for the host os/arch
./gradlew test                                        # Unit tests for the root plugin
./gradlew :protoc-gen-mermaid-examples:check          # Run every example suite
./gradlew :protoc-gen-mermaid-examples:hello          # Run a single example suite (one task per JvmTestSuite name)
./gradlew ktlintCheck                                 # Lint
./gradlew ktlintFormat                                # Auto-format
```

To regenerate the example fixture `.binpb` and recorded `.mermaid` files end-to-end:

```bash
./gradlew :protoc-gen-mermaid-examples:check
```

The shared `Dumper` test base (under `examples/src/testFixtures/`) runs the compiler against each suite's recorded `CodeGeneratorRequest` and writes the resulting `.mermaid` files into the suite's `src/<suite>/resources/` directory.
Inspect the diff before committing.

## Subprojects

Subprojects are auto-discovered by [`settings.gradle.kts`](settings.gradle.kts): any subdirectory containing a `build.gradle.kts` is included as `:protoc-gen-mermaid-<relative-path>`.
Drop a `.gradle_ignore` marker file in a directory to exclude it.
The included projects are reachable from build scripts via the typesafe accessors `projects.protocGenMermaid` (root) and `projects.protocGenMermaidExamples`.

## GraalVM Native Image and Reflection Metadata

Protobuf uses runtime reflection, which must be explicitly declared for `native-image`.
Reflection metadata lives in [`src/main/resources/META-INF/native-image/com.engine/protoc-gen-mermaid/`](src/main/resources/META-INF/native-image/com.engine/protoc-gen-mermaid/).

The build sets `-H:ThrowMissingRegistrationErrors=` so missing reflection registrations fail loudly rather than silently at runtime.

The GraalVM agent is scoped to the root project's `:run` task only (configured in [`build.gradle.kts`](build.gradle.kts) under `graalvmNative.agent`).
Test tasks are excluded because the plugin's JVMTI agent conflicts with the IDE debugger's own agent and aborts test runs with `JVMTI_ERROR_NOT_AVAILABLE`.

To regenerate metadata after adding new proto types, new plugin options, or new reflective Kotlin usage:

1. Feed a representative `CodeGeneratorRequest` through `:run` with the agent attached:
   `./gradlew -Pagent :run < your.binpb`
2. Merge the captured metadata into the resources directory:
   `./gradlew :metadataCopy`
3. Verify by re-building the native image:
   `./gradlew :nativeCompile`

A good source of representative `.binpb` files is `examples/build/generated/sources/proto/<suite>/recorder/code-generator-request.binpb`, produced by every `generate<Suite>Proto` task.

Review the resulting diff under `src/main/resources/META-INF/native-image/...` by hand before committing — additions are expected, but unexpected entries (e.g. third-party classes only reachable from one specific suite's shape) signal that the recording set should be broadened or filtered.

## Publishing

Publication is handled by `maven-publish`, configured at the root [`build.gradle.kts`](build.gradle.kts), and targets [GitHub Packages](https://github.com/engine-public/protoc-gen-mermaid/packages).
The published artifact is POM-only (no main jar) with one classified `.exe` per platform: `linux-x86_64`, `linux-aarch_64`, `osx-aarch_64`, `windows-x86_64`.
A `cyclonedx`-classified JSON BOM is also attached.

Releases are cut by running the `Release` workflow ([`.github/workflows/release.yaml`](.github/workflows/release.yaml)) via `workflow_dispatch`.
It fans out to `build.yaml` (JVM build, SBOMs) and `native-build.yaml` (one native binary per platform), then publishes to GitHub Packages, tags the commit, and attaches the same artifacts to a GitHub Release.

Publishing runs `./gradlew publishAllPublicationsToGitHubPackagesRepository`, which pushes `com.engine:protoc-gen-mermaid`.
It authenticates with the `GITHUB_ACTOR` / `GITHUB_TOKEN` environment variables; the release job supplies the workflow token with `packages: write`, so no additional secrets are needed.
The publication picks up native binaries from `ENGINE_NATIVE_BIN_DIR` when set, otherwise it attaches only the host's binary from the local `nativeCompile` output.
Use `./gradlew publishToMavenLocal` to inspect the published artifact set without uploading anything.

## Code Style

- ktlint 1.8.0 with IntelliJ IDEA style.
- `explicitApi()` is enabled — every public declaration requires an explicit visibility modifier.
- ktlint excludes everything under `build/` (workaround for the plugin not honoring generated-source exclusions).

## Markdown Style

Use one sentence per line in all `*.md` files.
Markdown collapses consecutive lines into a single paragraph at render time, so this convention keeps diffs sentence-level and avoids reflow noise without affecting the rendered output.

## Example Suite Mechanics

Each example under `examples/src/<name>/` is a self-contained Gradle test suite that compiles real `.proto` files through the plugin and writes the resulting diagrams to `src/<name>/resources/` for review and commit.

The matrix is defined as a `suiteRecorderOptions` map in [`examples/build.gradle.kts`](examples/build.gradle.kts).
Each map entry isolates a single compiler option from its default; the `hello` suite passes no options so its fixtures act as a defaults baseline.

Each suite runs:

1. `protoc` with the `recorder` plugin (a native binary published to GitHub Packages as `com.engine:protoc-utils-recorder` from [engine-public/protoc-utils](https://github.com/engine-public/protoc-utils)) to capture the raw `CodeGeneratorRequest` as `code-generator-request.binpb`.
2. A `Dumper` subclass under `src/<name>/kotlin/` that loads the `.binpb`, feeds it to `ProtocGenMermaid.compile()` at the same options the recorder used, and writes each output file into `src/<name>/resources/`.

Tests use [kotest](https://kotest.io) `FunSpec` style.

## Adding a New Example

1. Add an entry to the `suiteRecorderOptions` map in [`examples/build.gradle.kts`](examples/build.gradle.kts).
   The map key doubles as the proto source directory under `src/<key>/proto/`, the Kotlin source directory under `src/<key>/kotlin/`, and the fixture sink under `src/<key>/resources/`.
2. Create `examples/src/<name>/proto/` with your `.proto` files.
3. Create a `Dumper` subclass at `examples/src/<name>/kotlin/<Name>Dumper.kt` following the pattern of `HelloDumper`.
4. Run `./gradlew :protoc-gen-mermaid-examples:<name>` to record the `.binpb` and generate initial fixtures.
5. Review the fixtures under `src/<name>/resources/` by hand, then commit.

## PR Process

1. Make your change.
   Cover behavior with unit tests in `src/test/` and, for anything that affects generated output, an example test suite under `examples/src/`.
2. Run `./gradlew build` locally — it runs ktlint, the root unit tests, and every example suite.
3. If you added new proto types or new reflection usage and `nativeCompile` (or the resulting binary) fails, regenerate the reflection metadata as described above and include the updated metadata files in your PR.
4. Open a pull request describing the change.
