package com.engine.protoc.mermaid

import kotlin.system.exitProcess

public fun main() {
    ProtocGenMermaid
        .from(System.`in`)
        .compile()
        .apply {
            writeTo(System.out)
            System.out.flush()
            if (this.hasError()) {
                exitProcess(1)
            }
        }
}
