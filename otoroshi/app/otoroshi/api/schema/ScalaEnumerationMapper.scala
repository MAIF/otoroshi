package otoroshi.api.schema

import org.json4s.JValue
import org.json4s.JsonDSL._

// Built-in type mapper for Scala Enumerations
object ScalaEnumerationMapper extends TypeMapper {
    def canMap(clazz: Class[_]): Boolean = {
        classOf[Enumeration#Value].isAssignableFrom(clazz)
    }

    def mapType(clazz: Class[_], context: SchemaContext): JValue = {
        // Since we can't determine the specific enumeration from scala.Enumeration$Value alone,
        // we'll fall back to the generic string type without enum values
        // This is a limitation of working without runtime type information
        ("type" -> "string") ~ ("description" -> s"Scala Enumeration: ${clazz.getSimpleName}")
    }

    private def findEnumerationClass(clazz: Class[_]): Option[Class[_]] = {
        // For Scala enumerations, we need to find the specific enumeration object class
        // The field type is scala.Enumeration$Value but we need to find which specific enumeration it belongs to
        // Unfortunately, without runtime type information, we can't determine this from the class alone
        // We would need the actual enumeration instance or additional context
        // For now, return None to indicate we can't find the specific enumeration
        None
    }
}
