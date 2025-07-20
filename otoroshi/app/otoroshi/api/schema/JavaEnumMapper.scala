package otoroshi.api.schema

import org.json4s.JsonDSL._
import org.json4s._

import scala.reflect.runtime.universe._

object JavaEnumMapper extends TypeMapper {
    def canMap(tpe: Type): Boolean = {
        tpe.typeSymbol.isClass &&
            tpe.typeSymbol.asClass.baseClasses.exists(_.fullName == "java.lang.Enum")
    }

    def mapType(tpe: Type, context: SchemaContext): JValue = {
        try {
            val clazz = Class.forName(tpe.typeSymbol.fullName)
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