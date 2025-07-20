package otoroshi.api.schema

import org.json4s.JValue
import scala.reflect.runtime.universe._

// Field metadata
case class FieldMetadata(
                            name: String,
                            fieldType: Type,
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
                            extensions: Map[String, JValue] = Map.empty
                        )
