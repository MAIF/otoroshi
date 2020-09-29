package otoroshi.utils

import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import utils.RegexPool

object Match {

  private def isOperator(obj: JsObject): Boolean = {
    obj.value.size == 1 && obj.keys.forall(_.startsWith("$"))
  }

  private def singleMatches(dest: JsValue, literalMatch: Boolean = false): JsValue => Boolean = {
    case JsBoolean(v) => dest.asOpt[Boolean].contains(v)
    case n @ JsNumber(_) => dest.asOpt[JsNumber].contains(n)
    case JsString(v) => dest.asOpt[String].contains(v)
    case o @ JsObject(_) if !literalMatch => matches(dest, o)
    case o @ JsObject(_) if literalMatch => dest.asOpt[JsObject].contains(o)
    case _ => false
  }

  private def matchesOperator(operator: JsObject, key: String, source: JsValue): Boolean = {
    operator.value.head match {
      case ("$wildcard", JsString(wildcard)) => source.select(key).asOpt[String].exists(str => RegexPool(wildcard).matches(str))
      case ("$regex", JsString(regex))       => source.select(key).asOpt[String].exists(str => RegexPool.regex(regex).matches(str))
      case ("$between", o @ JsObject(_))     => source.select(key).asOpt[JsNumber].exists(nbr => nbr.value > o.select("min").as[JsNumber].value && nbr.value < o.select("max").as[JsNumber].value)
      case ("$betweeni", o @ JsObject(_))    => source.select(key).asOpt[JsNumber].exists(nbr => nbr.value >= o.select("min").as[JsNumber].value && nbr.value <= o.select("max").as[JsNumber].value)
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
      case ("$all", JsArray(value))          => source.select(key).asOpt[JsArray].exists(arr => arr.value.intersect(value).toSet.size == value.size)
      case ("$not", o @ JsObject(_))         => !matchesOperator(o, key, source)
      case ("$eq", value: JsValue)           => singleMatches(source.select(key).as[JsValue])(value)
      case ("$ne", value: JsValue)           => !singleMatches(source.select(key).as[JsValue])(value)
      case ("$exists", JsString(value))      => source.select(key).asOpt[JsObject].exists(o => o.select(value).asOpt[JsValue].nonEmpty)
      case _ => false
    }
  }

  def matches(source: JsValue, predicate: JsObject): Boolean = {
    predicate.value.forall {
      case (key, JsBoolean(value))                 => source.select(key).asOpt[Boolean].contains(value)
      case (key, num @ JsNumber(_))                => source.select(key).asOpt[JsNumber].contains(num)
      case (key, JsString(value))                  => source.select(key).asOpt[String].contains(value)
      case (key, JsArray(value))                   => source.select(key).asOpt[JsArray].map(_.value).contains(value)
      case (key, o @ JsObject(_)) if isOperator(o) => matchesOperator(o, key, source)
      case (key, o @ JsObject(_))                  => source.select(key).asOpt[JsObject].exists(obj => matches(obj, o))
      case _                                       => false
    }
  }
}

object Project {

  def project(source: JsValue, blueprint: JsObject): JsObject = {
    var dest = Json.obj()
    blueprint.value.foreach {
      case (key, JsBoolean(true)) => dest = dest ++ Json.obj(key -> source.select(key).asOpt[JsValue].getOrElse(JsNull).as[JsValue])
      case (key, o @ JsObject(_)) => dest = dest ++ Json.obj(key -> project(source.select(key).as[JsValue], o))
      case _                      => ()
    }
    dest
  }
}

