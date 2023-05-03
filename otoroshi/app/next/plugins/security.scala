package otoroshi.next.plugins

import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future}
import scala.util._

case class NgSecurityTxtConfig(
    contact: String = "contact@foo.bar",
    encryption: Option[String] = None,
    acknowledgments: Option[String] = None,
    preferredLanguages: Option[String] = None,
    policy: Option[String] = None,
    hiring: Option[String] = None
) extends NgPluginConfig {
  override def json: JsValue = Json
    .obj(
      "contact" -> contact
    )
    .applyOnWithOpt(encryption) { case (obj, v) => obj ++ Json.obj("encryption" -> v) }
    .applyOnWithOpt(acknowledgments) { case (obj, v) => obj ++ Json.obj("acknowledgments" -> v) }
    .applyOnWithOpt(preferredLanguages) { case (obj, v) => obj ++ Json.obj("preferred_languages" -> v) }
    .applyOnWithOpt(policy) { case (obj, v) => obj ++ Json.obj("policy" -> v) }
    .applyOnWithOpt(hiring) { case (obj, v) => obj ++ Json.obj("hiring" -> v) }
}

object NgSecurityTxtConfig {
  val format = new Format[NgSecurityTxtConfig] {
    override def writes(o: NgSecurityTxtConfig): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgSecurityTxtConfig] = Try {
      NgSecurityTxtConfig(
        contact = json.select("contact").asOpt[String].getOrElse("contact@foo.bar"),
        encryption = json.select("encryption").asOpt[String],
        acknowledgments = json.select("acknowledgments").asOpt[String],
        preferredLanguages = json.select("preferred_languages").asOpt[String],
        policy = json.select("policy").asOpt[String],
        hiring = json.select("hiring").asOpt[String]
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

class NgSecurityTxt extends NgRequestTransformer {

  override def name: String                                = "Security Txt"
  override def description: Option[String]                 =
    "This plugin exposes a special route `/.well-known/security.txt` as proposed at [https://securitytxt.org/](https://securitytxt.org/)".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgSecurityTxtConfig().some
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Security)
  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformRequest)

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    (ctx.rawRequest.method, ctx.rawRequest.path) match {
      case ("GET", "/.well-known/security.txt") => {
        val config  = ctx.cachedConfig(internalName)(NgSecurityTxtConfig.format).getOrElse(NgSecurityTxtConfig())
        val host    = s"https://${ctx.route.legacy.toHost}"
        val content = Seq(
          s"Contact: ${config.contact}".some,
          config.encryption.map(v => s"Encryption: $v"),
          config.encryption.map(v => s"Acknowledgments: $v"),
          config.encryption.map(v => s"Preferred-Languages: $v"),
          config.encryption.map(v => s"Policy: $v"),
          config.encryption.map(v => s"Hiring: $v"),
          s"Canonical: ${host}/.well-known/security.txt\n".some
        ).collect { case Some(v) =>
          v
        }.mkString("\n")
        Results.Ok(content).leftf
      }
      case (_, _)                               => Right(ctx.otoroshiRequest).future
    }
  }
}
