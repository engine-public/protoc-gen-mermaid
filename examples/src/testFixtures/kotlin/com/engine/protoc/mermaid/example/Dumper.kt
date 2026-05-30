package com.engine.protoc.mermaid.example

import com.engine.protoc.mermaid.ProtocGenMermaid
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import java.io.File

/**
 * Shared dev-tool spec for every example suite.  Runs the compiler over the suite's recorded
 * `code-generator-request.binpb` and writes each emitted `.mermaid` file straight into the
 * suite's `src/<suite>/resources/` directory, overwriting whatever is there.  Not assertion-bearing
 * — just a one-shot regenerator.  Each example suite contributes a tiny concrete subclass under
 * its own `src/<suite>/kotlin/` so Kotest picks the spec up and runs the dump once per suite;
 * declaring this class `abstract` keeps Kotest from instantiating it directly from testFixtures.
 *
 * Out path is resolved via the `dumpDir` system property (wired by `examples/build.gradle.kts`)
 * so this works regardless of the test task's working directory.
 */
public abstract class Dumper :
    FunSpec({
        test("dump diagrams into the suite's resources directory") {
            val req = Dumper::class.java.getResourceAsStream("/code-generator-request.binpb").shouldNotBeNull()
            val response = ProtocGenMermaid.from(req).compile()
            val outDir = File(System.getProperty("dumpDir") ?: "src/test/resources").also { it.mkdirs() }
            for (file in response.fileList) {
                /*
                 * EMBEDDED_MARKDOWN emits multiple File entries with the same `name` (the host
                 * markdown path), distinguished only by `insertion_point`.  Suffix the on-disk
                 * filename with the insertion-point name so each entry lands in its own fixture
                 * file instead of clobbering siblings.  Colons and slashes — common inside
                 * insertion-point names like `message_header_scope:pkg.X.Y` — would otherwise
                 * confuse the filesystem path, so swap them for underscores.
                 */
                val target =
                    if (file.hasInsertionPoint() && file.insertionPoint.isNotEmpty()) {
                        "${file.name}__${file.insertionPoint.replace(':', '_').replace('/', '_')}"
                    } else {
                        file.name
                    }
                File(outDir, target).writeText(file.content)
            }
        }
    })
