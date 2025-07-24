package otoroshi.api.schema

import org.json4s.JValue

// Type mapper trait - migrated to use Class instead of Type
trait TypeMapper {
  def canMap(clazz: Class[?]): Boolean
  def mapType(clazz: Class[?], context: SchemaContext): JValue
}
