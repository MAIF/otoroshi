package otoroshi.api.schema

import org.json4s.JsonDSL._
import org.json4s._

object JavaEnumMapper extends TypeMapper {
  def canMap(clazz: Class[?]): Boolean = {
    clazz.isEnum
  }

  def mapType(clazz: Class[?], context: SchemaContext): JValue = {
    try {
      if (clazz.isEnum) {
        val values = clazz.getEnumConstants.map(_.toString).toList
        ("type" -> "string") ~ ("enum" -> values)
      } else {
        ("type" -> "string")
      }
    } catch {
      case _: Exception =>
        ("type" -> "string") ~ ("description" -> "Java Enum")
    }
  }
}
