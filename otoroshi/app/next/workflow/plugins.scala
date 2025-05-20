package otoroshi.next.workflow

import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.plugins.BodyHelper
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import otoroshi.wasm.{WasmConfig, WasmUtils}
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future}

case class WorkflowBackendConfig(json: JsValue = Json.obj()) extends NgPluginConfig {
  lazy val ref: String    = json.select("ref").asString
  lazy val async: Boolean = json.select("async").asOptBoolean.getOrElse(false)
}

object WorkflowBackendConfig {
  val configFlow: Seq[String]        = Seq("ref", "async")
  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "async" -> Json.obj("type" -> "bool", "label" -> "Async"),
      "ref"   -> Json.obj(
        "type"  -> "select",
        "label" -> s"Workflow",
        "props" -> Json.obj(
          "optionsFrom"        -> s"/bo/api/proxy/apis/plugins.otoroshi.io/v1/workflows",
          "optionsTransformer" -> Json.obj(
            "label" -> "name",
            "value" -> "id"
          )
        )
      )
    )
  )
  val format                         = new Format[WorkflowBackendConfig] {
    override def reads(json: JsValue): JsResult[WorkflowBackendConfig] = JsSuccess(WorkflowBackendConfig(json))
    override def writes(o: WorkflowBackendConfig): JsValue             = o.json
  }
}

class WorkflowBackend extends NgBackendCall {

  override def useDelegates: Boolean                       = false
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Workflow Backend"
  override def description: Option[String]                 = "This plugin uses a workflow as a backend".some
  override def defaultConfigObject: Option[NgPluginConfig] = WorkflowBackendConfig().some

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Workflow"))
  override def steps: Seq[NgStep]                = Seq(NgStep.CallBackend)
  override def noJsForm: Boolean                 = true
  override def configFlow: Seq[String]           = WorkflowBackendConfig.configFlow
  override def configSchema: Option[JsObject]    = WorkflowBackendConfig.configSchema

  override def callBackend(
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx
      .cachedConfig(internalName)(WorkflowBackendConfig.format)
      .getOrElse(WorkflowBackendConfig())
    env.adminExtensions
      .extension[WorkflowAdminExtension]
      .flatMap(ext => ext.states.workflow(config.ref).map(w => (ext, w))) match {
      case None                        =>
        Errors
          .craftResponseResult(
            "workflow not found !",
            Results.Status(500),
            ctx.rawRequest,
            None,
            None,
            attrs = ctx.attrs,
            maybeRoute = ctx.route.some
          )
          .map(r => NgProxyEngineError.NgResultProxyEngineError(r).left)
      case Some((extension, workflow)) => {
        ctx.wasmJson
          .flatMap { input =>
            val f = extension.engine.run(Node.from(workflow.config), input.asObject)
            if (config.async) {
              Right(
                BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(Json.obj("ack" -> true))), None)
              ).vfuture
            } else {
              f.map { res =>
                if (res.hasError) {
                  Right(
                    BackendCallResponse(
                      NgPluginHttpResponse.fromResult(
                        Results.InternalServerError(Json.obj("error" -> res.error.get.json))
                      ),
                      None
                    )
                  )
                } else {
                  val respBody = res.json
                  val status   = respBody.select("status").asOpt[Int]
                  val headers  = respBody.select("headers").asOpt[Map[String, String]]
                  val body     = BodyHelper.extractBodyFromOpt(respBody)
                  if (status.isDefined && headers.isDefined && body.isDefined) {
                    val heads = headers.get.getIgnoreCase("Content-Length") match {
                      case None    =>
                        headers.get - "Content-Type" - "content-type" ++ Map("Content-Length" -> s"${body.get.length}")
                      case Some(_) => headers.get - "Content-Type" - "content-type"
                    }
                    val ctype = headers.get.getIgnoreCase("Content-Type").getOrElse("application/json")
                    Right(
                      BackendCallResponse(
                        NgPluginHttpResponse.fromResult(
                          Results.Status(status.get)(body.get).withHeaders(heads.toSeq: _*).as(ctype)
                        ),
                        None
                      )
                    )
                  } else {
                    Right(
                      BackendCallResponse(
                        NgPluginHttpResponse.fromResult(Results.Ok(body.get).as("application/json")),
                        None
                      )
                    )
                  }
                }
              }
            }
          }
      }
    }
  }
}

class WorkflowRequestTransformer extends NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Workflow"))
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = true
  override def transformsResponse: Boolean                 = false
  override def transformsError: Boolean                    = false
  override def name: String                                = "Workflow Request Transformer"
  override def description: Option[String]                 =
    "Transform the content of the request with a workflow".some
  override def defaultConfigObject: Option[NgPluginConfig] = WorkflowBackendConfig().some

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx
      .cachedConfig(internalName)(WorkflowBackendConfig.format)
      .getOrElse(WorkflowBackendConfig())
    env.adminExtensions
      .extension[WorkflowAdminExtension]
      .flatMap(ext => ext.states.workflow(config.ref).map(w => (ext, w))) match {
      case None                        =>
        Errors
          .craftResponseResult(
            "workflow not found !",
            Results.Status(500),
            ctx.request,
            None,
            None,
            attrs = ctx.attrs,
            maybeRoute = ctx.route.some
          )
          .map(r => r.left)
      case Some((extension, workflow)) => {
        ctx.wasmJson
          .flatMap { input =>
            extension.engine.run(Node.from(workflow.config), input.asObject).map { res =>
              if (res.hasError) {
                Results.InternalServerError(Json.obj("error" -> res.error.get.json)).left
              } else {
                val response = res.json
                val body     = BodyHelper.extractBodyFromOpt(response)
                Right(
                  ctx.otoroshiRequest.copy(
                    method = (response \ "method").asOpt[String].getOrElse(ctx.otoroshiRequest.method),
                    url = (response \ "url").asOpt[String].getOrElse(ctx.otoroshiRequest.url),
                    headers = (response \ "headers").asOpt[Map[String, String]].getOrElse(ctx.otoroshiRequest.headers),
                    cookies = WasmUtils.convertJsonCookies(response).getOrElse(ctx.otoroshiRequest.cookies),
                    body = body.map(_.chunks(16 * 1024)).getOrElse(ctx.otoroshiRequest.body)
                  )
                )
              }
            }
          }
      }
    }
  }
}

class WorkflowResponseTransformer extends NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Workflow"))
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = false
  override def transformsResponse: Boolean                 = true
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = true
  override def name: String                                = "Workflow Response Transformer"
  override def description: Option[String]                 =
    "Transform the content of a response with a workflow".some
  override def defaultConfigObject: Option[NgPluginConfig] = WorkflowBackendConfig().some

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val config = ctx
      .cachedConfig(internalName)(WorkflowBackendConfig.format)
      .getOrElse(WorkflowBackendConfig())
    env.adminExtensions
      .extension[WorkflowAdminExtension]
      .flatMap(ext => ext.states.workflow(config.ref).map(w => (ext, w))) match {
      case None                        =>
        Errors
          .craftResponseResult(
            "workflow not found !",
            Results.Status(500),
            ctx.request,
            None,
            None,
            attrs = ctx.attrs,
            maybeRoute = ctx.route.some
          )
          .map(r => r.left)
      case Some((extension, workflow)) => {
        ctx.wasmJson
          .flatMap { input =>
            extension.engine.run(Node.from(workflow.config), input.asObject).map { res =>
              if (res.hasError) {
                Results.InternalServerError(Json.obj("error" -> res.error.get.json)).left
              } else {
                val response = res.json
                val body     = BodyHelper.extractBodyFromOpt(response)
                Right(
                  ctx.otoroshiResponse.copy(
                    status = (response \ "status").asOpt[Int].getOrElse(200),
                    headers = (response \ "headers").asOpt[Map[String, String]].getOrElse(ctx.otoroshiResponse.headers),
                    cookies = WasmUtils.convertJsonCookies(response).getOrElse(ctx.otoroshiResponse.cookies),
                    body = body.map(_.chunks(16 * 1024)).getOrElse(ctx.otoroshiResponse.body)
                  )
                )
              }
            }
          }
      }
    }
  }
}

class WorkflowAccessValidator extends NgAccessValidator {

  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Custom("Workflow"))
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Workflow Access control"
  override def description: Option[String]                 = "Delegate route access to a worflow".some
  override def isAccessAsync: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = WorkflowBackendConfig().some

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {

    val config = ctx
      .cachedConfig(internalName)(WorkflowBackendConfig.format)
      .getOrElse(WorkflowBackendConfig())

    env.adminExtensions
      .extension[WorkflowAdminExtension]
      .flatMap(ext => ext.states.workflow(config.ref).map(w => (ext, w))) match {
      case None                        =>
        Errors
          .craftResponseResult(
            "workflow not found !",
            Results.Status(500),
            ctx.request,
            None,
            None,
            attrs = ctx.attrs,
            maybeRoute = ctx.route.some
          )
          .map(r => NgAccess.NgDenied(r))
      case Some((extension, workflow)) => {
        val input = ctx.wasmJson
        extension.engine.run(Node.from(workflow.config), input.asObject).flatMap { res =>
          if (res.hasError) {
            NgAccess.NgDenied(Results.InternalServerError(Json.obj("error" -> res.error.get.json))).vfuture
          } else {
            val response = res.json
            val result   = (response \ "result").asOpt[Boolean].getOrElse(false)
            if (result) {
              NgAccess.NgAllowed.vfuture
            } else {
              val error = (response \ "error").asOpt[JsObject].getOrElse(Json.obj())
              Errors
                .craftResponseResult(
                  (error \ "message").asOpt[String].getOrElse("An error occurred"),
                  Results.Status((error \ "status").asOpt[Int].getOrElse(403)),
                  ctx.request,
                  None,
                  None,
                  attrs = ctx.attrs,
                  maybeRoute = ctx.route.some
                )
                .map(r => NgAccess.NgDenied(r))
            }
          }
        }
      }
    }
  }
}
