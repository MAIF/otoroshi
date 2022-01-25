package otoroshi.next.models

import otoroshi.models.ApiKeyRouteMatcher
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

case class NgDomainAndPath(raw: String) {
  private lazy val parts = raw.split("\\/")
  lazy val domain = parts.head
  lazy val path = if (parts.size == 1) "/" else parts.tail.mkString("/", "/", "")
  def json: JsValue = JsString(raw)
}

case class NgFrontend(domains: Seq[NgDomainAndPath], headers: Map[String, String], methods: Seq[String], stripPath: Boolean, apikey: ApiKeyRouteMatcher) {
  def json: JsValue = Json.obj(
    "domains" -> JsArray(domains.map(_.json)),
    "strip_path" -> stripPath,
    "headers" -> headers,
    "methods" -> methods,
    "apikey" -> apikey.gentleJson
  )
}

object NgFrontend {
  def readFrom(lookup: JsLookupResult): NgFrontend = {
    lookup.asOpt[JsObject] match {
      case None => NgFrontend(Seq.empty, Map.empty, Seq.empty, true, ApiKeyRouteMatcher())
      case Some(obj) => NgFrontend(
        domains = obj.select("domains").asOpt[Seq[String]].map(_.map(NgDomainAndPath.apply)).getOrElse(Seq.empty),
        stripPath = obj.select("strip_path").asOpt[Boolean].getOrElse(true),
        headers = obj.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty),
        methods = obj.select("methods").asOpt[Seq[String]].getOrElse(Seq.empty),
        apikey = obj.select("apikey").asOpt[JsValue].flatMap(v => ApiKeyRouteMatcher.format.reads(v).asOpt).getOrElse(ApiKeyRouteMatcher())
      )
    }
  }
}