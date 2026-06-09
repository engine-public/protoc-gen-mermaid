package com.engine.protoc.mermaid.nativeimage

import org.graalvm.nativeimage.hosted.Feature
import org.graalvm.nativeimage.hosted.RuntimeResourceAccess

/*
 * Plugs holes in the agent-recorded native-image metadata for runtime lookups
 * the example suites don't exercise but real consumers do.
 *
 * All entries fall into the "phantom resource" category — a class probes for
 * a `META-INF/services/<iface>` file or class-as-resource that no jar on the
 * classpath actually ships.  On the JVM the lookup silently returns nothing;
 * under native-image with `-H:ThrowMissingRegistrationErrors=` the lookup
 * throws `MissingResourceRegistrationError`.  A pattern in
 * `resource-config.json` does not help — native-image only honors patterns
 * that match a real file at image-build time, and the two-arg
 * `RuntimeResourceAccess.addResource(Module, String)` form does not record a
 * strict-mode-visible negative query in GraalVM 23.1.2.  Registering an
 * empty payload via the three-arg form lets the lookup parse zero providers,
 * matching the JVM outcome.
 *
 * `slf4j-api` 2.x is **not** in this category — `log4j-slf4j2-impl` ships a
 * real `META-INF/services/org.slf4j.spi.SLF4JServiceProvider` that binds
 * SLF4J to Log4j 2, so the entry must not be registered as empty here (it
 * would shadow the binding).
 */
public class PhantomServiceFilesFeature : Feature {
    override fun beforeAnalysis(access: Feature.BeforeAnalysisAccess) {
        PHANTOM_RESOURCES.forEach { (anchorClass, resourcePath) ->
            val module = access.findClassByName(anchorClass)?.module ?: return@forEach
            RuntimeResourceAccess.addResource(module, resourcePath, EMPTY)
        }
    }

    private companion object {
        private val EMPTY = ByteArray(0)
        private val PHANTOM_RESOURCES =
            listOf(
                // SLF4J 2.x falls back to `getResources("org/slf4j/impl/StaticLoggerBinder.class")`
                // to detect a pre-2.0 binding that does not exist in this build.
                "org.slf4j.LoggerFactory" to "org/slf4j/impl/StaticLoggerBinder.class",
                // Log4j 2's ThreadContext init calls ProviderUtil.lazyInit, which probes for the
                // Log4j 1.x bridge file at this path.  It is absent in this build, but
                // `getResources` under strict mode still requires a registration.  Empty payload
                // lets the enumeration return no providers and the Log4j 2 ServiceLoader path
                // takes over.
                "org.apache.logging.log4j.util.LoaderUtil" to
                    "META-INF/log4j-provider.properties",
                // log4j-core enumerates WatchEventService providers via ServiceLoader during
                // configuration build.  No jar on the classpath ships this service file, so the
                // lookup needs an empty registration to satisfy strict-mode checks.
                "org.apache.logging.log4j.core.util.WatchManager" to
                    "META-INF/services/org.apache.logging.log4j.core.util.WatchEventService",
                // Log4j 2's setConfiguration populates a `${hostName}` interpolation by calling
                // `InetAddress.getLocalHost`, which routes through `InetAddress.loadResolver` and
                // `ServiceLoader.findFirst(InetAddressResolverProvider)`.  The JDK ships no
                // default provider, so the lookup needs an empty registration.
                "java.net.InetAddress" to
                    "META-INF/services/java.net.spi.InetAddressResolverProvider",
            )
    }
}
