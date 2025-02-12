package otoroshi.next.workflow

import io.otoroshi.wasm4s.scaladsl.{WasmFunctionParameters, WasmSource, WasmSourceKind}
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.events.{AnalyticEvent, AuditEvent}
import otoroshi.next.models.NgTlsConfig
import otoroshi.next.plugins.BodyHelper
import otoroshi.next.workflow.WorkflowFunction.registerFunction
import otoroshi.utils.syntax.implicits._
import otoroshi.wasm.WasmConfig
import play.api.Logger
import play.api.libs.json._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object WorkflowFunctionsInitializer {
  def initDefaults(): Unit = {
    registerFunction("core.log", new LogFunction())
    registerFunction("core.hello", new HelloFunction())
    registerFunction("core.http_client", new HttpClientFunction())
    registerFunction("core.wasm_call", new WasmCallFunction())
    registerFunction("core.workflow_call", new WorkflowCallFunction())
    registerFunction("core.store_mget", new StoreMgetFunction())
    registerFunction("core.store_match", new StoreMatchFunction())
    registerFunction("core.store_get", new StoreGetFunction())
    registerFunction("core.store_set", new StoreSetFunction())
    registerFunction("core.store_del", new StoreDelFunction())
    registerFunction("core.emit_event", new EmitEventFunction())
    // access otoroshi resources (apikeys, etc)
  }
}

class EmitEventFunction extends WorkflowFunction {
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val event = args.select("event").asOpt[JsObject].getOrElse(Json.obj())
    WorkflowEmitEvent(event, env).toAnalytics()
    JsNull.rightf
  }
}

object LogFunction {
  val logger = Logger("otoroshi-workflow-log")
}

class LogFunction extends WorkflowFunction {
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val message = args.select("message").asString
    val params  = args.select("params").asOpt[Seq[JsValue]].getOrElse(Seq.empty).map(_.stringify).mkString(" ")
    LogFunction.logger.info(message + " " + params)
    JsNull.rightf
  }
}

class HelloFunction extends WorkflowFunction {
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val name    = args.select("name").asOptString.getOrElse("Stranger")
    val message = s"Hello ${name} !"
    println(message)
    message.json.rightf
  }
}

class HttpClientFunction extends WorkflowFunction {
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val url       = args.select("url").asString
    val method    = args.select("method").asOptString.getOrElse("GET")
    val headers   = args.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty)
    val timeout   = args.select("timeout").asOpt[Long].map(_.millis).getOrElse(30.seconds)
    val body      = BodyHelper.extractBodyFromOpt(args)
    val tlsConfig =
      args.select("tls_config").asOpt[JsObject].flatMap(v => NgTlsConfig.format.reads(v).asOpt).getOrElse(NgTlsConfig())
    env.MtlsWs
      .url(url, tlsConfig.legacy)
      .withRequestTimeout(timeout)
      .withMethod(method)
      .withHttpHeaders(headers.toSeq: _*)
      .applyOnWithOpt(body) { case (builder, body) =>
        builder.withBody(body)
      }
      .execute()
      .map { resp =>
        val body_str: String   = resp.body
        val body_json: JsValue = if (resp.contentType.contains("application/json")) body_str.parseJson else JsNull
        Json
          .obj(
            "status"    -> resp.status,
            "headers"   -> resp.headers,
            "cookies"   -> JsArray(resp.cookies.map(_.json)),
            "body_str"  -> body_str,
            "body_json" -> body_json
          )
          .right
      }
      .recover { case t: Throwable =>
        WorkflowError(s"caught exception on http call", None, Some(t)).left
      }
  }
}

class WorkflowCallFunction extends WorkflowFunction {

  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val workflowId = args.select("workflow_id").asString
    val input       = args.select("input").asObject
    val extension = env.adminExtensions.extension[WorkflowAdminExtension].get
    extension.states.workflow(workflowId) match {
      case None => Left(WorkflowError("workflow not found", Some(Json.obj("workflow_id" -> workflowId)), None)).vfuture
      case Some(workflow) => {
        val node = Node.from(workflow.config)
        extension.engine.run(node, input).map {
          case res if res.hasError => Left(res.error.get)
          case res => Right(res.returned.get)
        }
      }
    }
  }
}

class WasmCallFunction extends WorkflowFunction {
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val wasmSource   = args.select("wasm_plugin").asString
    val functionName = args.select("function").asOptString.getOrElse("call")
    val params       = args.select("params").asValue.stringify
    env.wasmIntegration
      .wasmVmFor(
        WasmConfig(
          WasmSource(WasmSourceKind.Local, wasmSource, Json.obj())
        )
      )
      .flatMap {
        case None                    => WorkflowError(s"wasm plugin not found", Some(Json.obj("wasm_plugin" -> wasmSource)), None).leftf
        case Some((vm, localConfig)) =>
          vm.call(
            WasmFunctionParameters.ExtismFuntionCall(
              functionName,
              params
            ),
            None
          ).map {
            case Right(res)  => Right(Json.parse(res._1))
            case Left(value) =>
              WorkflowError(
                s"error while calling wasm function",
                Some(Json.obj("wasm_plugin" -> wasmSource, "function" -> functionName, "error" -> value)),
                None
              ).left
          }.andThen { case _ =>
            vm.release()
          }
      }
  }
}

class StoreDelFunction extends WorkflowFunction {
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val keys = args.select("keys").asOpt[Seq[String]].getOrElse(Seq.empty)
    env.datastores.rawDataStore.del(keys).map { r =>
      Right(r.json)
    }
  }
}

class StoreGetFunction extends WorkflowFunction {
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    args.select("key").asOptString match {
      case None      => Right(JsNull).vfuture
      case Some(key) =>
        env.datastores.rawDataStore.get(key).map {
          case None        => Right(JsNull)
          case Some(value) => Right(value.utf8String.json)
        }
    }
  }
}

class StoreSetFunction extends WorkflowFunction {
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val key   = args.select("key").asString
    val value = args.select("value").asValue
    val ttl   = args.select("ttl").asOptLong
    env.datastores.rawDataStore.set(key, value.stringify.byteString, ttl).map { _ =>
      Right(JsNull)
    }
  }
}

class StoreMgetFunction extends WorkflowFunction {
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val keys = args.select("keys").asOpt[Seq[String]].getOrElse(Seq.empty)
    env.datastores.rawDataStore.mget(keys).map { seq =>
      Right(JsArray(seq.collect { case Some(bs) => bs.utf8String.json }))
    }
  }
}

class StoreMatchFunction extends WorkflowFunction {
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val pattern = args.select("pattern").asString
    env.datastores.rawDataStore.allMatching(pattern).map { seq =>
      Right(JsArray(seq.map(_.utf8String.json)))
    }
  }
}

case class WorkflowEmitEvent(
    payload: JsObject,
    env: Env
) extends AnalyticEvent {

  val `@id`: String                 = env.snowflakeGenerator.nextIdStr()
  val `@timestamp`: DateTime        = DateTime.now()
  val fromOrigin: Option[String]    = None
  val fromUserAgent: Option[String] = None
  val `@type`: String               = "WorkflowEmitEvent"
  val `@service`: String            = "Otoroshi"
  val `@serviceId`: String          = ""

  override def toJson(implicit _env: Env): JsValue = {
    Json.obj(
      "@id"        -> `@id`,
      "@timestamp" -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
      "@type"      -> "WorkflowEmitEvent",
      "@product"   -> _env.eventsName,
      "@serviceId" -> "",
      "@service"   -> "Otoroshi",
      "@env"       -> env.env
    ) ++ payload
  }
}
