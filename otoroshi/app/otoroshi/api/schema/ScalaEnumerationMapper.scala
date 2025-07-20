package otoroshi.api.schema

import org.json4s.JValue
import org.json4s.JsonDSL._

import scala.reflect.runtime.universe._

// Built-in type mappers
object ScalaEnumerationMapper extends TypeMapper {
    def canMap(tpe: Type): Boolean = {
        tpe <:< typeOf[Enumeration#Value]
    }

    def mapType(tpe: Type, context: SchemaContext): JValue = {
        try {
            val mirror = runtimeMirror(getClass.getClassLoader)

            // Handle both direct Enumeration#Value types and type aliases like Status.Value
            val moduleSymbol = if (tpe.typeSymbol.owner.isModule) {
                tpe.typeSymbol.owner
            } else {
                // For type aliases like Status.Value, we need to get the actual enumeration
                tpe match {
                    case TypeRef(pre, _, _) if pre.termSymbol.isModule => pre.termSymbol
                    case _ =>
                        // Try to find the enumeration from the type's prefix
                        tpe.asInstanceOf[TypeRef].pre.typeSymbol.companion match {
                            case NoSymbol => tpe.typeSymbol.owner
                            case companion => companion
                        }
                }
            }

            val enumInstance = mirror.reflectModule(moduleSymbol.asModule).instance.asInstanceOf[Enumeration]
            val values = enumInstance.values.map(_.toString).toList.sorted

            ("type" -> "string") ~ ("enum" -> values)
        } catch {
            case e: Exception =>
                // Fallback: try to extract enum name from the type
                val enumName = tpe.toString.split('.').last.split('#').head
                ("type" -> "string") ~ ("description" -> s"Scala Enumeration: $enumName")
        }
    }
}