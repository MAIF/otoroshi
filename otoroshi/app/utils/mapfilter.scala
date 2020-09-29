package utils

import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

object Match {

  private def isOperator(obj: JsObject): Boolean = {
    obj.value.size == 1 && obj.keys.forall(_.startsWith("$"))
  }

  private def matchesOperator(operator: JsObject, key: String, source: JsObject): Boolean = {
    operator.value.head match {
      case ("$wildcard", JsString(wildcard)) => source.select(key).asOpt[String].exists(str => RegexPool(wildcard).matches(str))
      case ("$regex", JsString(regex))       => source.select(key).asOpt[String].exists(str => RegexPool(regex).matches(str))
      case ("$between", o @ JsObject(_))     => source.select(key).asOpt[JsNumber].exists(nbr => nbr.value > o.select("min").as[JsNumber].value && nbr.value < o.select("max").as[JsNumber].value)
      case ("$betweenIncl", o @ JsObject(_)) => source.select(key).asOpt[JsNumber].exists(nbr => nbr.value >= o.select("min").as[JsNumber].value && nbr.value <= o.select("max").as[JsNumber].value)
      case ("$gt", JsNumber(num))            => source.select(key).asOpt[JsNumber].exists(nbr => nbr.value > num)
      case ("$gte", JsNumber(num))           => source.select(key).asOpt[JsNumber].exists(nbr => nbr.value >= num)
      case ("$lt", JsNumber(num))            => source.select(key).asOpt[JsNumber].exists(nbr => nbr.value < num)
      case ("$lte", JsNumber(num))           => source.select(key).asOpt[JsNumber].exists(nbr => nbr.value <= num)
      case ("$and", JsArray(value))          => value.forall(v => matches(source.select(key).asOpt[JsObject].getOrElse(Json.obj()), v.asOpt[JsObject].getOrElse(Json.obj())))
      case ("$or", JsArray(value))           => value.exists(v => matches(source.select(key).asOpt[JsObject].getOrElse(Json.obj()), v.asOpt[JsObject].getOrElse(Json.obj())))
      case _ => false
    }
  }

  def matches(source: JsObject, predicate: JsObject): Boolean = {
    predicate.value.forall {
      case (key, JsBoolean(value))                 => source.select(key).asOpt[Boolean].contains(value)
      case (key, num @ JsNumber(_))                => source.select(key).asOpt[JsNumber].contains(num)
      case (key, JsString(value))                  => source.select(key).asOpt[String].contains(value)
      case (key, o @ JsObject(_)) if isOperator(o) => matchesOperator(o, key, source)
      case (key, o @ JsObject(_))                  => source.select(key).asOpt[JsObject].exists(obj => matches(obj, o))
      case _                                       => false
      // TODO: support JsArray ? how ?
    }
  }
}

object Project {

  def project(source: JsObject, blueprint: JsObject): JsObject = {
    var dest = Json.obj()
    blueprint.value.foreach {
      case (key, JsBoolean(true)) => dest = dest ++ Json.obj(key -> source.select(key).asOpt[JsValue].getOrElse(JsNull).as[JsValue])
      case (key, o @ JsObject(_)) => dest = dest ++ Json.obj(key -> project(source.select(key).asOpt[JsObject].getOrElse(Json.obj()), o))
      case _                      => ()
    }
    dest
  }
}

