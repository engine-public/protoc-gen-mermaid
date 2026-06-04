import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.protobuf).apply(false)
}

description = "protoc compiler to turn gRPC services into mermaid class diagrams"

allprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    group = "com.engine"
    version = System.getenv("ENGINE_BUILD_VERSION")?.ifEmpty { null } ?: "0.0.0-pre.0"

    repositories {
        mavenCentral()
    }

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    configure<KotlinJvmProjectExtension> {
        explicitApi()
    }

    configure<KtlintExtension> {
        version.set("1.8.0")
        filter {
            /*
             * work around bug in the ktlint plugin that doesn't honor exclusions of
             * generated code (protobuf, etc.)
             */
            exclude {
                it.file.absolutePath.startsWith(layout.buildDirectory.get().asFile.absolutePath)
            }
        }
        reporters {
            reporter(ReporterType.CHECKSTYLE)
            reporter(ReporterType.HTML)
        }
    }

    afterEvaluate {
        configure<TestingExtension> {
            suites {
                withType<JvmTestSuite>().configureEach {
                    useJUnitJupiter()
                    dependencies {
                        implementation.bundle(libs.bundles.test.kotest)
                    }
                }
            }
        }
    }
}

dependencies {
    implementation(libs.engine.protoc.utils)
    implementation(libs.protobuf.java)

    // Compiler code calls SLF4J 2.x (LoggerFactory.getLogger, log.info(...))
    // directly, so slf4j-api sits on the compile classpath. Ship Log4j 2 as
    // the binding so the plugin's logLevel / logFile options can be applied
    // programmatically via the Configurator API: log4j-core is used directly
    // in applyLoggingConfiguration() (ConfigurationBuilder, Configurator,
    // ConsoleAppender.Target), and log4j-slf4j2-impl is the runtime bridge
    // from SLF4J 2.x to log4j-core. log4j-core 2.25.0+ ships its own GraalVM
    // native-image reachability metadata, so no hand-rolled reflect/resource
    // config is required for the core appender path.
    implementation(libs.slf4j.api)
    implementation(libs.log4j.api)
    implementation(libs.log4j.core)
    runtimeOnly(libs.log4j.slf4j2.impl)
}

application {
    mainClass.set("com.engine.protoc.mermaid.MainKt")
}

/*
 * Forward Gradle's stdin into the `run` task so `./gradlew -Pagent :run < cgr.binpb`
 * actually streams the recorded CodeGeneratorRequest to the plugin — the GraalVM agent
 * regen workflow documented in CONTRIBUTING.md depends on this.  JavaExec doesn't pipe
 * stdin by default.
 */
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
