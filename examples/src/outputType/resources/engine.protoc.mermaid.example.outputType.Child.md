# Message: engine.protoc.mermaid.example.outputType.Child

```mermaid
---
  generated-by: https://github.com/hotelengine/protoc-gen-mermaid/releases/tag/0.0.0-pre.0
  protoc-gen-mermaid-generated-on: 2026-01-01T00:00:00Z
  protoc-gen-mermaid-options: hideEmptyMembersBox=true,hierarchicalNamespaces=false,suppressNamespaces=false,suppressVisibility=true,diagramTypes=FILE_OVERVIEW,diagramTypes=MESSAGE,diagramTypes=COMPLETE,diagramTypes=PACKAGE,diagramTypes=SERVICE,diagramTypes=ENUMERATION,oneofRenderingType=EMBEDDED,outputType=STANDALONE_MARKDOWN,fileOverviewInsertionPoint=file_header_scope,messageInsertionPoint=message_header_scope,serviceInsertionPoint=service_header_scope,enumerationInsertionPoint=enum_header_scope,completeInsertionPoint=file_header,packageInsertionPoint=file_header,suppressWellKnownTypes=true,direction=TB
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
