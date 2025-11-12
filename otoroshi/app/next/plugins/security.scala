package otoroshi.next.plugins

import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import java.time.format.DateTimeFormatter
import java.time.{ZoneId, ZonedDateTime}
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

sealed trait FrameOptions {
  def json: JsValue
}
object FrameOptions {
  case object DENY extends FrameOptions { def json: JsValue = JsString("DENY") }
  case object SAMEORIGIN extends FrameOptions { def json: JsValue = JsString("SAMEORIGIN") }
  case object DISABLED extends FrameOptions { def json: JsValue = JsString("DISABLED") }
  def fromString(s: String): FrameOptions = s.toUpperCase match {
    case "DENY" => DENY
    case "SAMEORIGIN" => SAMEORIGIN
    case _ => DISABLED
  }
}

sealed trait XssProtection{
  def json: JsValue
}
object XssProtection {
  case object OFF extends XssProtection { def json: JsValue = JsString("OFF")}
  case object ON extends XssProtection { def json: JsValue = JsString("ON")}
  case object BLOCK extends XssProtection { def json: JsValue = JsString("BLOCK")}
  case object DISABLED extends XssProtection { def json: JsValue = JsString("DISABLED")}
  def fromString(s: String): XssProtection = s.toUpperCase match {
    case "OFF" => OFF
    case "ON" => ON
    case "BLOCK" => BLOCK
    case _ => DISABLED
  }
}

sealed trait CspMode {
  def json: JsValue
}
object CspMode {
  case object ENABLED extends CspMode { def json: JsValue = JsString("ENABLED")}
  case object REPORT_ONLY extends CspMode { def json: JsValue = JsString("REPORT_ONLY")}
  case object DISABLED extends CspMode { def json: JsValue = JsString("DISABLED")}
  def fromString(s: String): CspMode = s.toUpperCase match {
    case "ENABLED" => ENABLED
    case "REPORT_ONLY" => REPORT_ONLY
    case _ => DISABLED
  }
}


final case class HstsConf(
  enabled: Boolean,
  includeSubdomains: Boolean,
  maxAge: Long,
  preload: Boolean,
  onHttp: Boolean
) {
  def json: JsValue = HstsConf.format.writes(this)
}

object HstsConf {
  val format = new Format[HstsConf] {
    override def reads(json: JsValue): JsResult[HstsConf] = Try {
      HstsConf(
        enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
        includeSubdomains = (json \ "include_subdomains").asOpt[Boolean].getOrElse(false),
        maxAge = (json \ "max_age").asOpt[Long].getOrElse(3600L),
        preload = (json \ "preload").asOpt[Boolean].getOrElse(false),
        onHttp = (json \ "on_http").asOpt[Boolean].getOrElse(false),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
    
    override def writes(o: HstsConf): JsValue = Json.obj(
      "enabled" -> o.enabled,
      "include_subdomains" -> o.includeSubdomains,
      "max_age" -> o.maxAge,
      "preload" -> o.preload,
      "on_http" -> o.onHttp
    )
  }
}


final case class CspConf(mode: CspMode, csp: String) {
  def json: JsValue = CspConf.format.writes(this)
}

object CspConf {
  val format = new Format[CspConf] {
    override def reads(json: JsValue): JsResult[CspConf] = Try {
      CspConf(
        CspMode.fromString((json \ "mode").as[String]),
        (json \ "csp").as[String]
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    
    override def writes(o: CspConf): JsValue = Json.obj(
      "mode" -> o.mode.json,
      "csp" -> o.csp
    )
  }
}

object SecurityHeadersPluginConfig {
  val configFlow: Seq[String] = Seq(
    "frame_options",
    "xss_protection",
    "content_type_options",
    "hsts",
    "csp",
  )
  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "frame_options" -> Json.obj(
        "type"  -> "select",
        "label" -> s"X-Frame-Options",
        "props" -> Json.obj(
          "options" -> Json.arr(
            Json.obj("label" -> "DENY", "value" -> "DENY"),
            Json.obj("label" -> "SAMEORIGIN", "value"   -> "SAMEORIGIN"),
            Json.obj("label" -> "DISABLED", "value"   -> "DISABLED"),
          )
        )
      ),
      "xss_protection" -> Json.obj(
        "type"  -> "select",
        "label" -> s"X-XSS-Protection",
        "props" -> Json.obj(
          "options" -> Json.arr(
            Json.obj("label" -> "OFF", "value" -> "OFF"),
            Json.obj("label" -> "ON", "value"   -> "ON"),
            Json.obj("label" -> "BLOCK", "value"   -> "BLOCK"),
            Json.obj("label" -> "DISABLED", "value"   -> "DISABLED"),
          )
        )
      ),
      "content_type_options" -> Json.obj(
        "type" -> "bool",
        "label" -> s"X-Content-Type-Options",
      ),
      "csp" -> Json.obj(
        "label" -> "Content Security Policy",
        "type" -> "form",
        "collapsable" -> true,
        "collapsed" -> true,
        "schema" -> Json.obj(
          "mode" -> Json.obj(
            "type"  -> "select",
            "label" -> s"Content-Security-Policy Mode",
            "props" -> Json.obj(
              "options" -> Json.arr(
                Json.obj("label" -> "REPORT_ONLY", "value"   -> "REPORT_ONLY"),
                Json.obj("label" -> "ENABLED", "value"   -> "ENABLED"),
                Json.obj("label" -> "DISABLED", "value"   -> "DISABLED"),
              )
            )
          ),
          "csp" -> Json.obj(
            "type"  -> "string",
            "label" -> s"Content-Security-Policy",
          ),
        ),
        "flow" -> Json.arr("mode", "csp"),
      ),
      "hsts" -> Json.obj(
        "label" -> "HTTP Strict Transport Security",
        "type" -> "form",
        "collapsable" -> true,
        "collapsed" -> true,
        "schema" -> Json.obj(
          "enabled" -> Json.obj(
            "type" -> "bool",
            "label" -> "Enabled",
          ),
          "include_subdomains" -> Json.obj(
            "type" -> "bool",
            "label" -> "Include Subdomains",
          ),
          "max_age" -> Json.obj(
            "type" -> "number",
            "label" -> "Max Age",
          ),
          "preload" -> Json.obj(
            "type" -> "bool",
            "label" -> "Preload",
          ),
          "on_http" -> Json.obj(
            "type" -> "bool",
            "label" -> "On HTTP",
          ),
        ),
        "flow" -> Json.arr("enabled", "include_subdomains", "max_age", "preload", "on_http"),
      )
    )
  )
  val default = SecurityHeadersPluginConfig(
    FrameOptions.DISABLED,
    XssProtection.DISABLED,
    false,
    HstsConf(false, false, 31536000L, false, false),
    CspConf(CspMode.DISABLED, "")
  )
  val format = new Format[SecurityHeadersPluginConfig] {

    override def reads(json: JsValue): JsResult[SecurityHeadersPluginConfig] = Try {
      SecurityHeadersPluginConfig(
        frameOptions = FrameOptions.fromString((json \ "frame_options").as[String]),
        xssProtection = XssProtection.fromString((json \ "xss_protection").as[String]),
        contentTypeOptions = (json \ "content_type_options").as[Boolean],
        hsts = json.select("hsts").asOpt[JsObject].flatMap(o => HstsConf.format.reads(o).asOpt).getOrElse(SecurityHeadersPluginConfig.default.hsts),
        csp = json.select("csp").asOpt[JsObject].flatMap(o => CspConf.format.reads(o).asOpt).getOrElse(SecurityHeadersPluginConfig.default.csp),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }

    override def writes(o: SecurityHeadersPluginConfig): JsValue = Json.obj(
      "frame_options" -> o.frameOptions.json,
      "xss_protection" -> o.xssProtection.json,
      "content_type_options" -> o.contentTypeOptions,
    )
  }
}

case class SecurityHeadersPluginConfig(
  frameOptions: FrameOptions,
  xssProtection: XssProtection,
  contentTypeOptions: Boolean,
  hsts: HstsConf,
  csp: CspConf
) extends NgPluginConfig {
  def json: JsValue = SecurityHeadersPluginConfig.format.writes(this)
}

class SecurityHeadersPlugin extends NgRequestTransformer {

  override def name: String = "Security Headers"
  override def description: Option[String] = Some("Inject common HTTP security headers on responses (HSTS, CSP, XFO, X-XSS-Protection, X-Content-Type-Options)")
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Security)
  override def defaultConfigObject: Option[NgPluginConfig] = Some(SecurityHeadersPluginConfig.default)
  override def multiInstance: Boolean = true
  override def core: Boolean                  = true
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def steps: Seq[NgStep]             = Seq(NgStep.TransformResponse)
  override def transformsError: Boolean = false
  override def transformsRequest: Boolean = true
  override def transformsResponse: Boolean = false
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = SecurityHeadersPluginConfig.configFlow
  override def configSchema: Option[JsObject] = SecurityHeadersPluginConfig.configSchema

  override def transformResponse(ctx: NgTransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val conf  = ctx.cachedConfig(internalName)(SecurityHeadersPluginConfig.format).getOrElse(SecurityHeadersPluginConfig.default)
    val initial = ctx.otoroshiResponse.headers
    val withXfo = conf.frameOptions match {
      case FrameOptions.DENY => initial + ("X-Frame-Options" -> "DENY")
      case FrameOptions.SAMEORIGIN => initial + ("X-Frame-Options" -> "SAMEORIGIN")
      case FrameOptions.DISABLED => initial - "X-Frame-Options"
    }
    val withXss = conf.xssProtection match {
      case XssProtection.OFF => withXfo + ("X-XSS-Protection" -> "0")
      case XssProtection.ON => withXfo + ("X-XSS-Protection" -> "1")
      case XssProtection.BLOCK => withXfo + ("X-XSS-Protection" -> "1; mode=block")
      case XssProtection.DISABLED => withXfo - "X-XSS-Protection"
    }
    val withNosniff = if (conf.contentTypeOptions) withXss + ("X-Content-Type-Options" -> "nosniff") else withXss - "X-Content-Type-Options"
    val isHttps = ctx.request.theSecured
    val withHsts = if (conf.hsts.enabled && (isHttps || conf.hsts.onHttp)) {
      val base = s"max-age=${conf.hsts.maxAge}"
      val sub = if (conf.hsts.includeSubdomains) "; includeSubDomains" else ""
      val preload= if (conf.hsts.preload) "; preload" else ""
      withNosniff + ("Strict-Transport-Security" -> (base + sub + preload))
    } else withNosniff - "Strict-Transport-Security"
    val finalHeaders = conf.csp.mode match {
      case CspMode.DISABLED => withHsts - "Content-Security-Policy" - "Content-Security-Policy-Report-Only"
      case CspMode.ENABLED => withHsts + ("Content-Security-Policy" -> conf.csp.csp.trim)
      case CspMode.REPORT_ONLY => withHsts + ("Content-Security-Policy-Report-Only" -> conf.csp.csp.trim)
    }
    ctx.otoroshiResponse.copy(headers = finalHeaders).rightf
  }
}