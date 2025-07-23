package otoroshi.api.schema

import org.json4s.JValue

// Field metadata - migrated to not depend on Type
case class FieldMetadata(
                            name: String,
                            fieldType: Any, // In Scala 3, we can't use Type, so we use Any or Class[_]
                            required: Boolean,
                            description: Option[String] = None,
                            defaultValue: Option[Any] = None,
                            pattern: Option[String] = None,
                            minimum: Option[Double] = None,
                            maximum: Option[Double] = None,
                            examples: List[Any] = Nil,
                            readOnly: Boolean = false,
                            writeOnly: Boolean = false,
                            deprecated: Boolean = false,
                            customName: Option[String] = None,
                            extensions: Map[String, JValue] = Map.empty,
                            genericType: Option[java.lang.reflect.Type] = None
                        )