package models
import java.util.concurrent.TimeUnit

import play.api.libs.json._
import play.api.mvc.RequestHeader

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

case class CorsSettings(
  enabled: Boolean = false,
  allowOrigin: String = "*",
  exposeHeaders: Seq[String] = Seq.empty[String],
  allowHeaders: Seq[String] = Seq.empty[String],
  allowMethods: Seq[String] = Seq.empty[String],
  maxAge: Option[FiniteDuration] = None,
  allowCredentials: Boolean = true
) extends AsJson {
  def asHeaders: Seq[(String, String)] = {
    if (enabled) {
      var headers = Map(
        "Access-Control-Allow-Origin" -> allowOrigin,
        "Access-Control-Allow-Credentials" -> allowCredentials.toString
      )
      if (exposeHeaders.nonEmpty) {
        headers = headers + ("Access-Control-Expose-Headers" -> exposeHeaders.mkString(", "))
      }
      if (allowHeaders.nonEmpty) {
        headers = headers + ("Access-Control-Allow-Headers" -> allowHeaders.mkString(", "))
      }
      if (allowMethods.nonEmpty) {
        headers = headers + ("Access-Control-Allow-Methods" -> allowMethods.mkString(", "))
      }
      maxAge.foreach { age =>
        headers = headers + ("Access-Control-Max-Age" -> age.toSeconds.toString)
      }
      headers.toSeq
    } else {
      Seq.empty[(String, String)]
    }
  }

  def shouldNotPass(req: RequestHeader): Boolean = {
    val passOrigin: Boolean = req.headers.get("Origin").map(_.toLowerCase()).map(o => allowOrigin == "*" || o == allowOrigin).getOrElse(allowOrigin == "*")
    val passAllowedRequestHeaders: Boolean = req.headers.get("Access-Control-Request-Headers").map(h => h.split(",").map(_.trim.toLowerCase())).map(headers => headers.map(h => allowHeaders.contains(h)).foldLeft(true)(_ && _)).getOrElse(!req.headers.get("Access-Control-Request-Headers").isDefined)
    val passAllowedRequestMethod: Boolean = req.headers.get("Access-Control-Request-Method").map(h => h.split(",").map(_.trim.toLowerCase())).map(_.contains(req.method.toLowerCase())).getOrElse(!req.headers.get("Access-Control-Request-Method").isDefined)
    !(passOrigin && passAllowedRequestHeaders && passAllowedRequestMethod)
  }

  override def asJson: JsValue = Json.obj(
    "enabled" -> enabled,
    "allowOrigin" -> allowOrigin,
    "exposeHeaders" -> JsArray(exposeHeaders.map(_.toLowerCase().trim).map(JsString.apply)),
    "allowHeaders" -> JsArray(allowHeaders.map(_.toLowerCase().trim).map(JsString.apply)),
    "allowMethods" -> JsArray(allowMethods.map(JsString.apply)),
    "maxAge" -> maxAge.map(a => JsNumber(BigDecimal(a.toSeconds))).getOrElse(JsNull).as[JsValue],
    "allowCredentials" -> allowCredentials
  )
}

object CorsSettings extends FromJson[CorsSettings] {
  override def fromJson(json: JsValue): Either[Throwable, CorsSettings] = Try {
    Right(CorsSettings(
      enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
      allowOrigin = (json \ "allowOrigin").asOpt[String].getOrElse("*"),
      exposeHeaders = (json \ "exposeHeaders").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
      allowHeaders = (json \ "allowHeaders").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
      allowMethods = (json \ "allowMethods").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
      maxAge = (json \ "maxAge").asOpt[Long].map(a => FiniteDuration(a, TimeUnit.SECONDS)),
      allowCredentials = (json \ "allowCredentials").asOpt[Boolean].getOrElse(true)
    ))
  } recover {
    case e => Left(e)
  } get
}
