package otoroshi.api.schema

import org.json4s.JValue

import scala.reflect.runtime.universe._

// Type mapper trait
trait TypeMapper {
    def canMap(tpe: Type): Boolean

    def mapType(tpe: Type, context: SchemaContext): JValue
}
