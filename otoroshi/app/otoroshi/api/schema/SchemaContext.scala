package otoroshi.api.schema

import org.json4s.JValue

// Context for schema generation
case class SchemaContext(
    visitedTypes: Set[String],
    depth: Int,
    definitions: scala.collection.concurrent.TrieMap[String, JValue],
    config: SchemaConfig
)
