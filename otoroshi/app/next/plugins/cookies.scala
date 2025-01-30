package otoroshi.next.plugins

import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.plugins.api._
import otoroshi.utils.http.WSCookieWithSameSite
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class AdditionalCookieOutConfig(
    name: String,
    value: String,
    domain: Option[String] = None,
    path: Option[String] = None,
    maxAge: Option[Long] = None,
    secure: Boolean = false,
    httpOnly: Boolean = false,
    sameSite: Option[play.api.mvc.Cookie.SameSite] = None
) extends NgPluginConfig {
  override def json: JsValue                            = AdditionalCookieOutConfig.format.writes(this)
  def toCookie(implicit env: Env): WSCookieWithSameSite = WSCookieWithSameSite(
    name = name,
    value = value,
    domain = domain,
    path = path,
    maxAge = maxAge,
    secure = secure,
    httpOnly = httpOnly,
    sameSite = sameSite
  )
}

object AdditionalCookieOutConfig {
  val default = AdditionalCookieOutConfig("cookie", "value")
  val format  = new Format[AdditionalCookieOutConfig] {

    override def reads(json: JsValue): JsResult[AdditionalCookieOutConfig] = Try {
      AdditionalCookieOutConfig(
        name = json.select("name").as[String],
        value = json.select("value").as[String],
        domain = json.select("domain").asOpt[String],
        path = json.select("path").asOpt[String],
        maxAge = json.select("maxAge").asOpt[Long],
        secure = json.select("secure").asOpt[Boolean].getOrElse(false),
        httpOnly = json.select("httpOnly").asOpt[Boolean].getOrElse(false),
        sameSite = json.select("sameSite").asOpt[String].flatMap(v => play.api.mvc.Cookie.SameSite.parse(v))
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }

    override def writes(o: AdditionalCookieOutConfig): JsValue = Json.obj(
      "name"     -> o.name,
      "value"    -> o.value,
      "domain"   -> o.domain.map(_.json).getOrElse(JsNull).asValue,
      "path"     -> o.path.map(_.json).getOrElse(JsNull).asValue,
      "maxAge"   -> o.maxAge.map(_.json).getOrElse(JsNull).asValue,
      "secure"   -> o.secure,
      "httpOnly" -> o.httpOnly,
      "sameSite" -> o.sameSite.map(_.value.json).getOrElse(JsNull).asValue
    )
  }
  def configFlow: Seq[String]        = Seq(
    "name",
    "value",
    "domain",
    "path",
    "maxAge",
    "secure",
    "httpOnly",
    "sameSite"
  )
  def configSchema: Option[JsObject] = Some(
    Json.obj(
      "name"     -> Json.obj("label" -> "name", "type" -> "string"),
      "value"    -> Json.obj("label" -> "value", "type" -> "string"),
      "domain"   -> Json.obj("label" -> "domain", "type" -> "string"),
      "path"     -> Json.obj("label" -> "path", "type" -> "string"),
      "maxAge"   -> Json.obj("label" -> "maxAge", "type" -> "number"),
      "secure"   -> Json.obj("label" -> "secure", "type" -> "bool"),
      "httpOnly" -> Json.obj("label" -> "httpOnly", "type" -> "bool"),
      "sameSite" -> Json.obj(
        "label" -> "Same Site",
        "type"  -> "select",
        "props" -> Json.obj(
          "isClearable" -> true,
          "options"     -> Json.arr(
            Json.obj("value" -> "Lax", "label"    -> "Lax"),
            Json.obj("value" -> "Strict", "label" -> "Strict"),
            Json.obj("value" -> "None", "label"   -> "None")
          )
        )
      )
    )
  )
}

case class AdditionalCookieInConfig(
    name: String,
    value: String
) extends NgPluginConfig {
  override def json: JsValue                            = AdditionalCookieInConfig.format.writes(this)
  def toCookie(implicit env: Env): WSCookieWithSameSite = WSCookieWithSameSite(
    name = name,
    value = value
  )
}

object AdditionalCookieInConfig {
  val default = AdditionalCookieInConfig("cookie", "value")
  val format  = new Format[AdditionalCookieInConfig] {

    override def reads(json: JsValue): JsResult[AdditionalCookieInConfig] = Try {
      AdditionalCookieInConfig(
        name = json.select("name").as[String],
        value = json.select("value").as[String]
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }

    override def writes(o: AdditionalCookieInConfig): JsValue = Json.obj(
      "name"  -> o.name,
      "value" -> o.value
    )
  }
  def configFlow: Seq[String]        = Seq(
    "name",
    "value"
  )
  def configSchema: Option[JsObject] = Some(
    Json.obj(
      "name"  -> Json.obj("label" -> "name", "type" -> "string"),
      "value" -> Json.obj("label" -> "value", "type" -> "string")
    )
  )
}

class AdditionalCookieIn extends NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cookies"))
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = true
  override def transformsResponse: Boolean                 = false
  override def transformsError: Boolean                    = false
  override def name: String                                = "Additional cookies in"
  override def description: Option[String]                 = "This plugin adds cookies in the otoroshi request".some
  override def defaultConfigObject: Option[NgPluginConfig] = AdditionalCookieInConfig.default.some
  override def noJsForm: Boolean                           = true
  override def configFlow: Seq[String]                     = AdditionalCookieInConfig.configFlow
  override def configSchema: Option[JsObject]              = AdditionalCookieInConfig.configSchema

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config =
      ctx.cachedConfig(internalName)(AdditionalCookieInConfig.format.reads).getOrElse(AdditionalCookieInConfig.default)
    Right(
      ctx.otoroshiRequest.copy(
        cookies = ctx.otoroshiRequest.cookies :+ config.toCookie
      )
    ).vfuture
  }
}

class AdditionalCookieOut extends NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cookies"))
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = false
  override def transformsResponse: Boolean                 = true
  override def transformsError: Boolean                    = false
  override def name: String                                = "Additional cookies out"
  override def description: Option[String]                 = "This plugin adds cookies in the otoroshi response".some
  override def defaultConfigObject: Option[NgPluginConfig] = AdditionalCookieOutConfig.default.some
  override def noJsForm: Boolean                           = true
  override def configFlow: Seq[String]                     = AdditionalCookieOutConfig.configFlow
  override def configSchema: Option[JsObject]              = AdditionalCookieOutConfig.configSchema

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val config = ctx
      .cachedConfig(internalName)(AdditionalCookieOutConfig.format.reads)
      .getOrElse(AdditionalCookieOutConfig.default)
    Right(
      ctx.otoroshiResponse.copy(
        cookies = ctx.otoroshiResponse.cookies :+ config.toCookie
      )
    ).vfuture
  }
}

case class RemoveCookiesInConfig(names: Seq[String]) extends NgPluginConfig {
  override def json: JsValue = RemoveCookiesInConfig.format.writes(this)
}

object RemoveCookiesInConfig {
  val default                        = RemoveCookiesInConfig(Seq.empty)
  val format                         = new Format[RemoveCookiesInConfig] {
    override def reads(json: JsValue): JsResult[RemoveCookiesInConfig] = Try {
      RemoveCookiesInConfig(
        names = json.select("names").asOpt[Seq[String]].getOrElse(Seq.empty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
    override def writes(o: RemoveCookiesInConfig): JsValue             = Json.obj(
      "names" -> o.names
    )
  }
  def configFlow: Seq[String]        = Seq(
    "names"
  )
  def configSchema: Option[JsObject] = Some(
    Json.obj(
      "names" -> Json.obj("label" -> "name", "type" -> "array", "array" -> true)
    )
  )
}

class RemoveCookiesIn extends NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cookies"))
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = true
  override def transformsResponse: Boolean                 = false
  override def transformsError: Boolean                    = false
  override def name: String                                = "Remove cookies in"
  override def description: Option[String]                 = "This plugin remove cookies from the otoroshi request".some
  override def defaultConfigObject: Option[NgPluginConfig] = RemoveCookiesInConfig.default.some
  override def noJsForm: Boolean                           = true
  override def configFlow: Seq[String]                     = RemoveCookiesInConfig.configFlow
  override def configSchema: Option[JsObject]              = RemoveCookiesInConfig.configSchema

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config: RemoveCookiesInConfig =
      ctx.cachedConfig(internalName)(RemoveCookiesInConfig.format.reads).getOrElse(RemoveCookiesInConfig.default)
    Right(
      ctx.otoroshiRequest.copy(
        cookies = ctx.otoroshiRequest.cookies.filterNot(v => config.names.contains(v.name))
      )
    ).vfuture
  }
}

class RemoveCookiesOut extends NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cookies"))
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = false
  override def transformsResponse: Boolean                 = true
  override def transformsError: Boolean                    = false
  override def name: String                                = "Remove cookies out"
  override def description: Option[String]                 = "This plugin remove cookies from the otoroshi response".some
  override def defaultConfigObject: Option[NgPluginConfig] = RemoveCookiesInConfig.default.some
  override def noJsForm: Boolean                           = true
  override def configFlow: Seq[String]                     = RemoveCookiesInConfig.configFlow
  override def configSchema: Option[JsObject]              = RemoveCookiesInConfig.configSchema

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val config: RemoveCookiesInConfig =
      ctx.cachedConfig(internalName)(RemoveCookiesInConfig.format.reads).getOrElse(RemoveCookiesInConfig.default)
    Right(
      ctx.otoroshiResponse.copy(
        cookies = ctx.otoroshiResponse.cookies.filterNot(v => config.names.contains(v.name))
      )
    ).vfuture
  }
}

class MissingCookieIn extends NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cookies"))
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = true
  override def transformsResponse: Boolean                 = false
  override def transformsError: Boolean                    = false
  override def name: String                                = "Missing cookies in"
  override def description: Option[String]                 = "This plugin adds cookies in the otoroshi request if not present".some
  override def defaultConfigObject: Option[NgPluginConfig] = AdditionalCookieOutConfig.default.some
  override def noJsForm: Boolean                           = true
  override def configFlow: Seq[String]                     = AdditionalCookieInConfig.configFlow
  override def configSchema: Option[JsObject]              = AdditionalCookieInConfig.configSchema

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config =
      ctx.cachedConfig(internalName)(AdditionalCookieInConfig.format.reads).getOrElse(AdditionalCookieInConfig.default)
    if (!ctx.otoroshiRequest.cookies.exists(_.name == config.name)) {
      Right(
        ctx.otoroshiRequest.copy(
          cookies = ctx.otoroshiRequest.cookies :+ config.toCookie
        )
      ).vfuture
    } else {
      ctx.otoroshiRequest.rightf
    }
  }
}

class MissingCookieOut extends NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cookies"))
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = false
  override def transformsResponse: Boolean                 = true
  override def transformsError: Boolean                    = false
  override def name: String                                = "Missing cookies out"
  override def description: Option[String]                 = "This plugin adds cookies in the otoroshi response if not present".some
  override def defaultConfigObject: Option[NgPluginConfig] = AdditionalCookieOutConfig.default.some
  override def noJsForm: Boolean                           = true
  override def configFlow: Seq[String]                     = AdditionalCookieOutConfig.configFlow
  override def configSchema: Option[JsObject]              = AdditionalCookieOutConfig.configSchema

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val config = ctx
      .cachedConfig(internalName)(AdditionalCookieOutConfig.format.reads)
      .getOrElse(AdditionalCookieOutConfig.default)
    if (!ctx.otoroshiResponse.cookies.exists(_.name == config.name)) {
      Right(
        ctx.otoroshiResponse.copy(
          cookies = ctx.otoroshiResponse.cookies :+ config.toCookie
        )
      ).vfuture
    } else {
      ctx.otoroshiResponse.rightf
    }
  }
}

case class CookiesValidationConfig(cookies: Map[String, String] = Map.empty) extends NgPluginConfig {
  override def json: JsValue = CookiesValidationConfig.format.writes(this)
}

object CookiesValidationConfig {
  val format                         = new Format[CookiesValidationConfig] {
    override def reads(json: JsValue): JsResult[CookiesValidationConfig] = Try {
      CookiesValidationConfig(
        cookies = json.select("cookies").asOpt[Map[String, String]].getOrElse(Map.empty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
    override def writes(o: CookiesValidationConfig): JsValue             = Json.obj(
      "cookies" -> o.cookies
    )
  }
  def configFlow: Seq[String]        = Seq(
    "cookies"
  )
  def configSchema: Option[JsObject] = Some(
    Json.obj(
      "cookies" -> Json.obj("label" -> "Cookies", "type" -> "object")
    )
  )
}

class CookiesValidation extends NgAccessValidator {

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cookies"))
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Cookies validation"
  override def description: Option[String]                 = "This plugin validates cookies content".some
  override def defaultConfigObject: Option[NgPluginConfig] = CookiesValidationConfig().some
  override def noJsForm: Boolean                           = true
  override def configFlow: Seq[String]                     = CookiesValidationConfig.configFlow
  override def configSchema: Option[JsObject]              = CookiesValidationConfig.configSchema

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config            =
      ctx.cachedConfig(internalName)(CookiesValidationConfig.format.reads).getOrElse(CookiesValidationConfig())
    val validationCookies = config.cookies
    val cookies           = ctx.request.cookies.map { cookie =>
      (cookie.name, cookie.name)
    }.toMap
    if (
      validationCookies.forall { case (key, value) =>
        cookies.get(key).contains(value)
      }
    ) {
      NgAccess.NgAllowed.vfuture
    } else {
      Errors
        .craftResponseResult(
          "bad request",
          Results.BadRequest,
          ctx.request,
          None,
          None,
          attrs = ctx.attrs,
          maybeRoute = ctx.route.some
        )
        .map(NgAccess.NgDenied.apply)
    }
  }
}
