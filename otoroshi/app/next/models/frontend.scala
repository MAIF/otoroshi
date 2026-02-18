package otoroshi.next.models

import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

case class NgDomainAndPath(raw: String) {
  private lazy val parts   = raw.split("\\/", 2)
  lazy val domain          = if (parts.head.isEmpty) "" else parts.head
  lazy val domainLowerCase = domain.toLowerCase()
  lazy val path            = if (parts.size == 1 || parts(1).isEmpty) "/" else "/" + parts(1)
  def json: JsValue        = JsString(raw)
}

case class NgFrontend(
    domains: Seq[NgDomainAndPath],
    headers: Map[String, String],
    query: Map[String, String],
    cookies: Map[String, String],
    methods: Seq[String],
    stripPath: Boolean,
    exact: Boolean
) {
  def json: JsValue = Json.obj(
    "domains"    -> JsArray(domains.map(_.json)),
    "strip_path" -> stripPath,
    "exact"      -> exact,
    "headers"    -> headers,
    "cookies"    -> cookies,
    "query"      -> query,
    "methods"    -> methods
  )

  def tunnelUrl: String = domains.head.raw
}

object NgFrontend {
  def empty: NgFrontend = NgFrontend(
    domains = Seq.empty,
    headers = Map.empty,
    cookies = Map.empty,
    query = Map.empty,
    methods = Seq.empty,
    stripPath = true,
    exact = false
  )
  def readFrom(lookup: JsLookupResult): NgFrontend = {
    lookup.asOpt[JsObject] match {
      case None      => empty
      case Some(obj) =>
        val optDomain = obj.select("domain").asOpt[String].map(NgDomainAndPath.apply)
        NgFrontend(
          domains = optDomain
            .map(d => Seq(d))
            .orElse(obj.select("domains").asOpt[Seq[String]].map(_.map(NgDomainAndPath.apply)))
            .getOrElse(Seq.empty),
          stripPath = obj.select("strip_path").asOpt[Boolean].getOrElse(true),
          exact = obj.select("exact").asOpt[Boolean].getOrElse(false),
          headers = obj.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty),
          cookies = obj.select("cookies").asOpt[Map[String, String]].getOrElse(Map.empty),
          query = obj.select("query").asOpt[Map[String, String]].getOrElse(Map.empty),
          methods = obj.select("methods").asOpt[Seq[String]].getOrElse(Seq.empty)
        )
    }
  }
}
