package com.engine.protoc.mermaid

public object Version {
    private const val RESOURCE_PATH: String = "META-INF/com.engine.protoc.mermaid/version"

    public val value: String by lazy {
        Version::class.java.classLoader
            .getResourceAsStream(RESOURCE_PATH)
            ?.bufferedReader()
            ?.use { it.readText().trim() }
            ?: error("Missing version resource: $RESOURCE_PATH")
    }
}
