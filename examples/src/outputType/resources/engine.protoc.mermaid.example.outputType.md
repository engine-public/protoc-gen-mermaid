# Complete schema: engine.protoc.mermaid.example.outputType

```mermaid
---
  title: "Complete schema: engine.protoc.mermaid.example.outputType"
  config:
    class:
      hideEmptyMembersBox: true
      hierarchicalNamespaces: false
---
classDiagram
    namespace engine.protoc.mermaid.example.outputType {
        class Parent {
            Child child
        }

        class Child {
            string id
        }
    }

    Parent --> "0..1" Child : child
```
