# Message: engine.protoc.mermaid.example.outputType.Child

```mermaid
---
  title: "Message: engine.protoc.mermaid.example.outputType.Child"
  config:
    class:
      hideEmptyMembersBox: true
      hierarchicalNamespaces: false
---
classDiagram
    namespace engine.protoc.mermaid.example.outputType {
        class Child {
            string id
        }

        class Parent {
            Child child
        }
    }

    Parent --> "0..1" Child : child
```
