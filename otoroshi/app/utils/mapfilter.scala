package otoroshi.utils

import otoroshi.plugins.JsonPathUtils
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.workflow.{WorkFlowOperator, WorkFlowTaskContext}
import play.api.libs.json._

sealed trait Operator[T] {
  def apply(source: JsValue, key: String): T
}
trait MatchOperator     extends Operator[Boolean]
trait TransformOperator extends Operator[JsValue]
object Operator {
  def isOperator(obj: JsObject): Boolean = {
    obj.value.size == 1 && obj.keys.forall(_.startsWith("$"))
  }
}

/*object Operators {
  def apply(operator: JsValue): Operator = {

  }
}*/

object Match {

  private def singleMatches(dest: JsValue, literalMatch: Boolean = false): JsValue => Boolean = {
    case JsBoolean(v)                     => dest.asOpt[Boolean].contains(v)
    case n @ JsNumber(_)                  => dest.asOpt[JsNumber].contains(n)
    case JsString(v)                      => dest.asOpt[String].contains(v)
    case o @ JsObject(_) if !literalMatch => matches(dest, o)
    case o @ JsObject(_) if literalMatch  => dest.asOpt[JsObject].contains(o)
    case _                                => false
  }

  private def matchesOperator(operator: JsObject, key: String, source: JsValue): Boolean = {
    operator.value.head match {
      case ("$wildcard", JsString(wildcard)) =>
        source.select(key).asOpt[String].exists(str => RegexPool(wildcard).matches(str))
      case ("$regex", JsString(regex))       =>
        source.select(key).asOpt[String].exists(str => RegexPool.regex(regex).matches(str))
      case ("$between", o @ JsObject(_))     =>
        source
          .select(key)
          .asOpt[JsNumber]
          .exists(nbr =>
            nbr.value > o.select("min").as[JsNumber].value && nbr.value < o.select("max").as[JsNumber].value
          )
      case ("$betweene", o @ JsObject(_))    =>
        source
          .select(key)
          .asOpt[JsNumber]
          .exists(nbr =>
            nbr.value >= o.select("min").as[JsNumber].value && nbr.value <= o.select("max").as[JsNumber].value
          )
      case ("$gt", JsNumber(num))            => source.select(key).asOpt[JsNumber].exists(nbr => nbr.value > num)
      case ("$gte", JsNumber(num))           => source.select(key).asOpt[JsNumber].exists(nbr => nbr.value >= num)
      case ("$lt", JsNumber(num))            => source.select(key).asOpt[JsNumber].exists(nbr => nbr.value < num)
      case ("$lte", JsNumber(num))           => source.select(key).asOpt[JsNumber].exists(nbr => nbr.value <= num)
      case ("$and", JsArray(value))          => value.forall(singleMatches(source.select(key).as[JsValue]))
      case ("$or", JsArray(value))           => value.exists(singleMatches(source.select(key).as[JsValue]))
      case ("$nor", JsArray(value))          => !value.exists(singleMatches(source.select(key).as[JsValue]))
      case ("$in", JsArray(value))           => value.exists(singleMatches(source.select(key).as[JsValue], true))
      case ("$nin", JsArray(value))          => !value.exists(singleMatches(source.select(key).as[JsValue], true))
      case ("$size", JsNumber(number))       => source.select(key).asOpt[JsArray].exists(_.value.size == number.intValue())
      case ("$contains", value: JsValue)     => source.select(key).asOpt[JsArray].exists(arr => arr.value.contains(value))
      case ("$all", JsArray(value))          =>
        source.select(key).asOpt[JsArray].exists(arr => arr.value.intersect(value).toSet.size == value.size)
      case ("$not", o @ JsObject(_))         => !matchesOperator(o, key, source)
      case ("$eq", value: JsValue)           => singleMatches(source.select(key).as[JsValue])(value)
      case ("$ne", value: JsValue)           => !singleMatches(source.select(key).as[JsValue])(value)
      case ("$exists", JsString(value))      =>
        source.select(key).asOpt[JsObject].exists(o => o.select(value).asOpt[JsValue].nonEmpty)
      case _                                 => false
    }
  }

  def matches(source: JsValue, predicate: JsObject): Boolean = {
    predicate.value.forall {
      case (key, JsBoolean(value))                          => source.select(key).asOpt[Boolean].contains(value)
      case (key, num @ JsNumber(_))                         => source.select(key).asOpt[JsNumber].contains(num)
      case (key, JsString(value))                           => source.select(key).asOpt[String].contains(value)
      case (key, JsArray(value))                            => source.select(key).asOpt[JsArray].map(_.value).contains(value)
      case (key, o @ JsObject(_)) if Operator.isOperator(o) => matchesOperator(o, key, source)
      case (key, o @ JsObject(_))                           => source.select(key).asOpt[JsObject].exists(obj => matches(obj, o))
      case _                                                => false
    }
  }
}

object Projection {

  // TODO: apply el on resulting strings
  def project(source: JsValue, blueprint: JsObject, applyEl: String => String): JsObject = {
    var dest = Json.obj()
    if (Operator.isOperator(blueprint) && blueprint.value.head._1 == "$compose") {
      dest = Composition.compose(source, blueprint, applyEl).asOpt[JsObject].getOrElse(Json.obj())
    } else {
      blueprint.value.foreach {
        // direct inclusion
        case (key, JsBoolean(true))                           =>
          dest = dest ++ Json.obj(key -> source.select(key).asOpt[JsValue].getOrElse(JsNull).as[JsValue])
        // direct inclusion with rename
        case (key, JsString(newKey))                          =>
          dest = dest ++ Json.obj(newKey -> source.select(key).asOpt[JsValue].getOrElse(JsNull).as[JsValue])
        // with search
        case (key, o @ JsObject(_)) if Operator.isOperator(o) => {
          o.value.head match {
            case ("$compose", value)                => {
              dest = dest ++ Json.obj(key -> Composition.compose(source, value, applyEl))
            }
            case ("$value", value)                  => {
              dest = dest ++ Json.obj(key -> value)
            }
            case ("$at", JsString(searchPath))      => {
              dest = dest ++ Json.obj(key -> source.at(searchPath).asOpt[JsValue].getOrElse(JsNull).as[JsValue])
            }
            case ("$atIf", spec: JsObject)          => {
              val path       = (spec \ "path").as[String]
              val predPath   = (spec \ "predicate" \ "at").as[String]
              val predValue  = (spec \ "predicate" \ "value").as[JsValue]
              val atPredPath = source.at(predPath)
              if (atPredPath.isDefined && atPredPath.as[JsValue] == predValue) {
                dest = dest ++ Json.obj(key -> source.at(path).as[JsValue])
              } else {
                dest = dest ++ Json.obj(key -> JsNull)
              }
            }
            case ("$pointer", JsString(searchPath)) => {
              dest = dest ++ Json.obj(key -> source.atPointer(searchPath).asOpt[JsValue].getOrElse(JsNull).as[JsValue])
            }
            case ("$pointerIf", spec: JsObject)     => {
              val path       = (spec \ "path").as[String]
              val predPath   = (spec \ "predicate" \ "pointer").as[String]
              val predValue  = (spec \ "predicate" \ "value").as[JsValue]
              val atPredPath = source.atPointer(predPath)
              if (atPredPath.isDefined && atPredPath.as[JsValue] == predValue) {
                dest = dest ++ Json.obj(key -> source.atPointer(path).as[JsValue])
              } else {
                dest = dest ++ Json.obj(key -> JsNull)
              }
            }
            case ("$path", JsString(searchPath))    => {
              dest = dest ++ Json.obj(key -> source.atPath(searchPath).asOpt[JsValue].getOrElse(JsNull).as[JsValue])
            }
            case ("$pathIf", spec: JsObject)        => {
              val path       = (spec \ "path").as[String]
              val predPath   = (spec \ "predicate" \ "path").as[String]
              val predValue  = (spec \ "predicate" \ "value").as[JsValue]
              val atPredPath = source.atPath(predPath)
              if (atPredPath.isDefined && atPredPath.as[JsValue] == predValue) {
                dest = dest ++ Json.obj(key -> source.atPath(path).as[JsValue])
              } else {
                dest = dest ++ Json.obj(key -> JsNull)
              }
            }
            case ("$header", spec: JsObject)        => {
              val path       = (spec \ "path").as[String]
              val headerName = (spec \ "name").as[String].toLowerCase()
              val headers    = source.at(path).as[JsArray]
              val header     = headers.value
                .find { header =>
                  val name = (header \ "key").as[String].toLowerCase()
                  name == headerName
                }
                .map(_.select("value").as[JsString])
                .getOrElse(JsNull)
              dest = dest ++ Json.obj(key -> header)
            }
            case _                                  => ()
          }
        }
        case (key, o @ JsObject(_))                           => {
          if (source.select(key).isDefined) {
            dest = dest ++ Json.obj(key -> project(source.select(key).as[JsValue], o, applyEl))
          } else {
            dest = dest ++ Json.obj(key -> project(source, o, applyEl))
          }
        }
        case _                                                => ()
      }
    }
    dest
  }
}

object CompositionOperator {
  def apply(spec: JsValue, source: JsValue): JsValue = {
    val obj = spec.asOpt[JsObject].getOrElse(Json.obj())
    obj.value.head match {
      case ("$path", JsString(path))          =>
        JsonPathUtils.getAtPolyJson(source, path).getOrElse(JsNull)
      case ("$path", v @ JsObject(_))         =>
        JsonPathUtils.getAtPolyJson(source, v.select("at").as[String]).getOrElse(JsNull)
      case ("$at", JsString(searchPath))      =>
        source.at(searchPath).asOpt[JsValue].getOrElse(JsNull).as[JsValue]
      case ("$at", v @ JsObject(_))           =>
        source.at(v.select("at").as[String]).asOpt[JsValue].getOrElse(JsNull).as[JsValue]
      case ("$pointer", JsString(searchPath)) =>
        source.atPointer(searchPath).asOpt[JsValue].getOrElse(JsNull).as[JsValue]
      case ("$pointer", v @ JsObject(_))      =>
        source.atPointer(v.select("at").as[String]).asOpt[JsValue].getOrElse(JsNull).as[JsValue]
      case _                                  =>
        spec
    }
  }
}

object Composition {

  def compose(source: JsValue, blueprint: JsValue, applyEl: String => String): JsValue = {

    def isOperator(jsObject: JsObject) = WorkFlowOperator.isOperator(jsObject)

    def transform(what: JsValue): JsValue =
      what match {
        case JsString(value)                        => JsString(applyEl(value))
        case v @ JsNumber(_)                        => v
        case v @ JsBoolean(_)                       => v
        case JsNull                                 => JsNull
        case spec @ JsObject(_) if isOperator(spec) => transform(CompositionOperator(spec, source))
        case JsObject(values)                       =>
          JsObject(values.map {
            case (key, value) => (key, transform(value))
          })
        case JsArray(values)                        => JsArray(values.map(transform))
      }

    transform(blueprint)
  }
}
