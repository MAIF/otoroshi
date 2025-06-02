package otoroshi.next.plugins

import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.plugins.api.{
  NgAccess,
  NgAccessContext,
  NgAccessValidator,
  NgPluginCategory,
  NgPluginConfig,
  NgPluginVisibility,
  NgStep
}
import otoroshi.utils.{JsonPathValidator, RegexPool}
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class ContextValidationConfig(validators: Seq[JsonPathValidator] = Seq.empty) extends NgPluginConfig {
  def json: JsValue = ContextValidationConfig.format.writes(this)
}

object ContextValidationConfig {
  val format = new Format[ContextValidationConfig] {
    override def writes(o: ContextValidationConfig): JsValue             = Json.obj(
      "validators" -> JsArray(o.validators.map(_.json))
    )
    override def reads(json: JsValue): JsResult[ContextValidationConfig] = Try {
      ContextValidationConfig(
        validators = (json \ "validators")
          .asOpt[Seq[JsValue]]
          .map(_.flatMap(v => JsonPathValidator.format.reads(v).asOpt))
          .getOrElse(Seq.empty)
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value)     => JsSuccess(value)
    }
  }
}

class ContextValidation extends NgAccessValidator {

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Context validator"
  //override def description: Option[String]                 = "This plugin validates the current context using JSONPath validators".some
  //override def documentation: Option[String]                 =
  override def description: Option[String]                 =
    """This plugin validates the current context using JSONPath validators.
      |
      |This plugin let you configure a list of validators that will check if the current call can pass.
      |A validator is composed of a [JSONPath](https://goessner.net/articles/JsonPath/) that will tell what to check and a value that is the expected value.
      |The JSONPath will be applied on a document that will look like
      |
      |```js
      |{
      |  "snowflake" : "1516772930422308903",
      |  "apikey" : { // current apikey
      |    "clientId" : "vrmElDerycXrofar",
      |    "clientName" : "default-apikey",
      |    "metadata" : {
      |      "foo" : "bar"
      |    },
      |    "tags" : [ ]
      |  },
      |  "user" : null, //  current user
      |  "request" : {
      |    "id" : 1,
      |    "method" : "GET",
      |    "headers" : {
      |      "Host" : "ctx-validation-next-gen.oto.tools:9999",
      |      "Accept" : "*/*",
      |      "User-Agent" : "curl/7.64.1",
      |      "Authorization" : "Basic dnJtRWxEZXJ5Y1hyb2ZhcjpvdDdOSTkyVGI2Q2J4bWVMYU9UNzJxamdCU2JlRHNLbkxtY1FBcXBjVjZTejh0Z3I1b2RUOHAzYjB5SEVNRzhZ",
      |      "Remote-Address" : "127.0.0.1:58929",
      |      "Timeout-Access" : "<function1>",
      |      "Raw-Request-URI" : "/foo",
      |      "Tls-Session-Info" : "Session(1650461821330|SSL_NULL_WITH_NULL_NULL)"
      |    },
      |    "cookies" : [ ],
      |    "tls" : false,
      |    "uri" : "/foo",
      |    "path" : "/foo",
      |    "version" : "HTTP/1.1",
      |    "has_body" : false,
      |    "remote" : "127.0.0.1",
      |    "client_cert_chain" : null
      |  },
      |  "config" : {
      |    "validators" : [ {
      |      "path" : "$.apikey.metadata.foo",
      |      "value" : "bar"
      |    } ]
      |  },
      |  "global_config" : { ... }, // global config
      |  "attrs" : {
      |    "otoroshi.core.SnowFlake" : "1516772930422308903",
      |    "otoroshi.core.ElCtx" : {
      |      "requestId" : "1516772930422308903",
      |      "requestSnowflake" : "1516772930422308903",
      |      "requestTimestamp" : "2022-04-20T15:37:01.548+02:00"
      |    },
      |    "otoroshi.next.core.Report" : "otoroshi.next.proxy.NgExecutionReport@277b44e2",
      |    "otoroshi.core.RequestStart" : 1650461821545,
      |    "otoroshi.core.RequestWebsocket" : false,
      |    "otoroshi.core.RequestCounterOut" : 0,
      |    "otoroshi.core.RemainingQuotas" : {
      |      "authorizedCallsPerWindow" : 10000000,
      |      "throttlingCallsPerWindow" : 0,
      |      "remainingCallsPerWindow" : 10000000,
      |      "authorizedCallsPerDay" : 10000000,
      |      "currentCallsPerDay" : 2,
      |      "remainingCallsPerDay" : 9999998,
      |      "authorizedCallsPerMonth" : 10000000,
      |      "currentCallsPerMonth" : 269,
      |      "remainingCallsPerMonth" : 9999731
      |    },
      |    "otoroshi.next.core.MatchedRoutes" : "MutableList(route_022825450-e97d-42ed-8e22-b23342c1c7c8)",
      |    "otoroshi.core.RequestNumber" : 1,
      |    "otoroshi.next.core.Route" : { ... }, // current route as json
      |    "otoroshi.core.RequestTimestamp" : "2022-04-20T15:37:01.548+02:00",
      |    "otoroshi.core.ApiKey" : { ... }, // current apikey as json
      |    "otoroshi.core.User" : { ... }, // current user as json
      |    "otoroshi.core.RequestCounterIn" : 0
      |  },
      |  "route" : { ... },
      |  "token" : null // current valid jwt token if one
      |}
      |```
      |
      |the expected value support some syntax tricks like
      |
      |* `Not(value)` on a string to check if the current value does not equals another value
      |* `Regex(regex)` on a string to check if the current value matches the regex
      |* `RegexNot(regex)` on a string to check if the current value does not matches the regex
      |* `Wildcard(*value*)` on a string to check if the current value matches the value with wildcards
      |* `WildcardNot(*value*)` on a string to check if the current value does not matches the value with wildcards
      |* `Contains(value)` on a string to check if the current value contains a value
      |* `ContainsNot(value)` on a string to check if the current value does not contains a value
      |* `Contains(Regex(regex))` on an array to check if one of the item of the array matches the regex
      |* `ContainsNot(Regex(regex))` on an array to check if one of the item of the array does not matches the regex
      |* `Contains(Wildcard(*value*))` on an array to check if one of the item of the array matches the wildcard value
      |* `ContainsNot(Wildcard(*value*))` on an array to check if one of the item of the array does not matches the wildcard value
      |* `Contains(value)` on an array to check if the array contains a value
      |* `ContainsNot(value)` on an array to check if the array does not contains a value
      |
      |for instance to check if the current apikey has a metadata name `foo` with a value containing `bar`, you can write the following validator
      |
      |```js
      |{
      |  "path": "$.apikey.metadata.foo",
      |  "value": "Contains(bar)"
      |}
      |```
      |""".stripMargin.some
  override def defaultConfigObject: Option[NgPluginConfig] = ContextValidationConfig().some

  private def validate(ctx: NgAccessContext)(implicit env: Env): Boolean = {
    val config         = ctx.cachedConfig(internalName)(ContextValidationConfig.format).getOrElse(ContextValidationConfig())
    val token: JsValue = ctx.attrs
      .get(otoroshi.next.plugins.Keys.JwtInjectionKey)
      .flatMap(_.decodedToken)
      .map { token =>
        Json.obj(
          "header"  -> token.getHeader.fromBase64.parseJson,
          "payload" -> token.getPayload.fromBase64.parseJson
        )
      }
      .getOrElse(JsNull)
    val json           = ctx.json.asObject ++ Json.obj(
      "route" -> ctx.route.json,
      "token" -> token
    )
    // java.nio.file.Files.writeString(new java.io.File("./ctx.json").toPath, json.prettify.debugPrintln)
    config.validators
      .map(v => v.copy(path = v.path.evaluateEl(ctx.attrs), value = v.value.asOptString.map(_.evaluateEl(ctx.attrs).json).getOrElse(JsNull)))
      .forall(validator => validator.validate(json))
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    if (validate(ctx)) {
      NgAccess.NgAllowed.vfuture
    } else {
      Errors
        .craftResponseResult(
          "forbidden",
          Results.Forbidden,
          ctx.request,
          None,
          None,
          duration = ctx.report.getDurationNow(),
          overhead = ctx.report.getOverheadInNow(),
          attrs = ctx.attrs,
          maybeRoute = ctx.route.some
        )
        .map(r => NgAccess.NgDenied(r))
    }
  }
}
