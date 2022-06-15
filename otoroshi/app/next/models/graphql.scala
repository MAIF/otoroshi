package otoroshi.next.models

import otoroshi.utils.syntax.implicits.BetterJsValue
import play.api.libs.json.{Format, JsArray, JsBoolean, JsError, JsNull, JsNumber, JsObject, JsString, JsSuccess, JsValue, Json}
import sangria.ast.{Argument, BigDecimalValue, BigIntValue, BooleanValue, Directive, FieldDefinition, FloatValue, InputValueDefinition, IntValue, ListType, ListValue, NamedType, NotNullType, NullValue, ObjectTypeDefinition, StringValue, TypeDefinition, TypeSystemDefinition, Value}
import sangria.schema.Schema

import scala.util.Try

object GraphQLFormats {
  def jsonToArgumentValue(value: JsValue, isJsonDirectiveArgument: Boolean): Value = value match {
    case JsNull => NullValue()
    case value: JsBoolean => BooleanValue(value.value)
    case JsNumber(value) => BigDecimalValue(value)
    case JsString(value) => StringValue(value)
    case o: JsArray => if (isJsonDirectiveArgument) StringValue(Json.stringify(o)) else ListValue(values = o.value.map(arg => jsonToArgumentValue(arg, isJsonDirectiveArgument)).toVector) // TODO - manage ListValue recursively
    case o: JsObject => StringValue(Json.stringify(o))
    case _ => StringValue("")
  }

  def argumentValueToJson(value: Value): JsValue = value match {
    case IntValue(value, _, _) => JsNumber(value)
    case BigIntValue(value, _, _) => JsNumber(value.intValue())
    case FloatValue(value, _, _) => JsNumber(value)
    case BigDecimalValue(value, _, _) => JsNumber(value)
    case StringValue(value, _, _, _, _) => JsString(value)
    case BooleanValue(value, _, _) => JsBoolean(value)
    case ListValue(values, _, _) =>  JsArray(values.map(argumentValueToJson))
    case _ => JsString("")
  }

  def fieldDefinitionFmt =
    new Format[FieldDefinition] {
      override def writes(field: FieldDefinition) =
        Json.obj(
          "name" -> field.name,
          "fieldType" -> Json.obj(
            "type" -> field.fieldType.namedType.name,
            "isList" -> field.fieldType.isInstanceOf[ListType],
            "required" -> field.fieldType.isInstanceOf[NotNullType]
          ),
          "arguments" -> field.arguments.map(f => {
            Json.obj(
              "name" -> f.name,
              "valueType" -> Json.obj(
                "type" -> f.valueType.namedType.name,
                "isList" -> f.valueType.isInstanceOf[ListType],
                "required" -> f.valueType.isInstanceOf[NotNullType]
              ),
              // TODO - manage defaultValue
            )
          }),
          "directives" -> field.directives.map(directive => Json.obj(
            "name" -> directive.name,
            "arguments" -> directive.arguments.map(argument => Json.obj(
              "name" -> argument.name,
              "value" -> argumentValueToJson(argument.value)
            ))
          ))
        )
      override def reads(json: JsValue)     = {
        val field = json.asOpt[JsObject].getOrElse(Json.obj())

        val fieldType = field.select("fieldType").as[JsObject]
        val `type` = fieldType.select("type").as[String]
        val isList = fieldType.select("isList").asOpt[Boolean].getOrElse(false)
        val requiredField = fieldType.select("required").asOpt[Boolean].getOrElse(false)
        val fullFieldType = if (requiredField) NotNullType(NamedType(`type`)) else NamedType(`type`)

        val directives = field.select("directives").as[JsArray].value.map(_.as[JsObject])

        Try {
          JsSuccess(
            FieldDefinition(
              name = (json \ "name").as[String],
              fieldType = if(isList) ListType(fullFieldType) else fullFieldType,
              arguments = field.select("arguments").asOpt[JsArray]
                .getOrElse(Json.arr())
                .value
                .map(_.as[JsObject])
                .map(argument => {
                  val valueType = argument.select("valueType").as[JsObject]
                  val argumentType = valueType.select("type").as[String]
                  val argumentIsList = valueType.select("isList").as[Boolean]
                  val required = valueType.select("required").as[Boolean]
                  val `type` = if(required) NotNullType(NamedType(argumentType)) else NamedType(argumentType)
                  InputValueDefinition(
                    name = argument.select("name").as[String],
                    valueType = if(argumentIsList) ListType(`type`) else `type`,
                    defaultValue = None // TODO - manage defautlValue
                  )
                }).toVector,
              directives = directives.map(directive => {
                val directiveName = directive.select("name").as[String]
                Directive(
                  name = directiveName,
                  arguments = directive.select("arguments")
                    .as[JsArray]
                    .value
                    .map(_.as[JsObject])
                    .map(argument => {
                      val name = argument.keys.head
                      Argument(
                        name = name,
                        value = jsonToArgumentValue(argument.values.head, isJsonDirectiveArgument = name == "data" && directiveName == "json")
                      )
                    }).toVector
                )}
              ).toVector
            )
          )
        } recover { case e =>
          JsError(e.getMessage)
        } get
      }
    }

  def objectTypeDefinitionFmt =
    new Format[ObjectTypeDefinition] {
      override def writes(obj: ObjectTypeDefinition) =
        Json.obj(
          "name" -> JsString(obj.name),
          "fields" -> obj.fields.map(fieldDefinitionFmt.writes),
          "directives" -> obj.directives.map(directive => Json.obj(
            "name" -> directive.name,
            "arguments" -> directive.arguments.map(argument => Json.obj(
              "name" -> argument.name,
              "value" -> argument.value.toString()
            ))
          )))
      override def reads(json: JsValue)     =
        Try {
          JsSuccess(
            ObjectTypeDefinition(
              name = json.select("name").as[String],
              interfaces = Vector.empty,
              fields = json.select("fields")
                .asOpt[JsArray]
                .getOrElse(Json.arr())
                .value
                .map(_.as[JsObject])
                .map(fieldDefinitionFmt.reads)
                .flatMap {
                  case JsSuccess(v, _) => Some(v)
                  case JsError(_) => None
                }
                .toVector
            )
          )
        } recover { case e =>
          JsError(e.getMessage)
        } get
    }

  def astDocumentToJson(schema: Schema[Any, Any]) = schema.toAst.definitions.map {
    case definition: TypeSystemDefinition =>
      definition match {
        case definition: TypeDefinition =>
          definition match {
            case obj: ObjectTypeDefinition => objectTypeDefinitionFmt.writes(obj)
            case _ => Json.obj()
          }
        case _ => Json.obj()
      }
    case _ => Json.obj()
  }
}
