package otoroshi.next.plugins

import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import java.time.{Instant, ZoneId, ZonedDateTime}
import java.time.format.DateTimeFormatter
import scala.concurrent.{ExecutionContext, Future}
import scala.util._

case class NgSecurityTxtConfig(
    contact: Seq[String] = Seq("contact@foo.bar"),
    expires: Option[String] = None,
    acknowledgments: Option[String] = None,
    preferredLanguages: Option[String] = None,
    policy: Option[String] = None,
    hiring: Option[String] = None,
    encryption: Option[String] = None,
    csaf: Option[String] = None,
    autoExpires: Boolean = false,
    expiresYears: Int = 1
) extends NgPluginConfig {
  override def json: JsValue = Json
    .obj(
      "contact"       -> JsArray(contact.map(JsString)),
      "auto_expires"  -> autoExpires,
      "expires_years" -> expiresYears
    )
    .applyOnWithOpt(expires) { case (obj, v) => obj ++ Json.obj("expires" -> v) }
    .applyOnWithOpt(acknowledgments) { case (obj, v) => obj ++ Json.obj("acknowledgments" -> v) }
    .applyOnWithOpt(preferredLanguages) { case (obj, v) => obj ++ Json.obj("preferred_languages" -> v) }
    .applyOnWithOpt(policy) { case (obj, v) => obj ++ Json.obj("policy" -> v) }
    .applyOnWithOpt(hiring) { case (obj, v) => obj ++ Json.obj("hiring" -> v) }
    .applyOnWithOpt(encryption) { case (obj, v) => obj ++ Json.obj("encryption" -> v) }
    .applyOnWithOpt(csaf) { case (obj, v) => obj ++ Json.obj("csaf" -> v) }
}

object NgSecurityTxtConfig {
  val format = new Format[NgSecurityTxtConfig] {
    override def writes(o: NgSecurityTxtConfig): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgSecurityTxtConfig] = Try {
      NgSecurityTxtConfig(
        contact = json.select("contact").asOpt[Seq[String]].getOrElse(Seq("contact@foo.bar")),
        expires = json.select("expires").asOpt[String],
        acknowledgments = json.select("acknowledgments").asOpt[String],
        preferredLanguages = json.select("preferred_languages").asOpt[String],
        policy = json.select("policy").asOpt[String],
        hiring = json.select("hiring").asOpt[String],
        encryption = json.select("encryption").asOpt[String],
        csaf = json.select("csaf").asOpt[String],
        autoExpires = json.select("auto_expires").asOpt[Boolean].getOrElse(false),
        expiresYears = json.select("expires_years").asOpt[Int].getOrElse(1)
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
    "This plugin exposes a special route `/.well-known/security.txt` as defined in RFC 9116 (https://www.rfc-editor.org/rfc/rfc9116.html)".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgSecurityTxtConfig().some
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Security)
  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformRequest)

  private def generateExpiresDate(years: Int): String = {
    val expiryDate = ZonedDateTime.now(ZoneId.of("UTC")).plusYears(years)
    expiryDate.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
  }

  private def buildSecurityTxt(config: NgSecurityTxtConfig, host: String): String = {
    val lines = scala.collection.mutable.ArrayBuffer.empty[String]

    config.contact.foreach { contact =>
      lines += s"Contact: $contact"
    }

    val expiresValue = if (config.autoExpires && config.expires.isEmpty) {
      generateExpiresDate(config.expiresYears)
    } else {
      config.expires.getOrElse(generateExpiresDate(1))
    }
    lines += s"Expires: $expiresValue"

    lines += s"Canonical: $host/.well-known/security.txt"
    config.acknowledgments.foreach { url =>
      lines += s"Acknowledgments: $url"
    }

    config.preferredLanguages.foreach { langs =>
      lines += s"Preferred-Languages: $langs"
    }
    config.policy.foreach { url =>
      lines += s"Policy: $url"
    }

    config.hiring.foreach { url =>
      lines += s"Hiring: $url"
    }

    config.encryption.foreach { url =>
      lines += s"Encryption: $url"
    }

    config.csaf.foreach { url =>
      lines += s"CSAF: $url"
    }

    lines.mkString("\n")
  }

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    (ctx.rawRequest.method, ctx.rawRequest.path) match {
      case ("GET", "/.well-known/security.txt") => {
        val config  = ctx.cachedConfig(internalName)(NgSecurityTxtConfig.format).getOrElse(NgSecurityTxtConfig())
        val host    = s"https://${ctx.route.frontend.domains.head.domainLowerCase}"
        val content = buildSecurityTxt(config, host)

        // RFC 9116 specifies text/plain with charset=utf-8
        Results
          .Ok(content)
          .withHeaders("Content-Type" -> "text/plain; charset=utf-8")
          .leftf
      }
      case (_, _)                               => Right(ctx.otoroshiRequest).future
    }
  }
}
