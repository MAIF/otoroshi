package otoroshi.next.workflow

import otoroshi.next.plugins.BodyHelper
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsObject, JsValue}

import scala.concurrent.Future
import scala.concurrent.duration._


class PrintFunction extends WorkflowFunction {

  override def call(args: JsObject): Future[Either[WorkflowError, JsValue]] = {
    val message = args.select("message").asString
    println(s"print: ${message}")
    message.json.rightf
  }
}

class HttpClientFunction extends WorkflowFunction {

  override def call(args: JsObject): Future[Either[WorkflowError, JsValue]] = {
    val method = args.select("method").asOptString.getOrElse("GET")
    val headers = args.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty)
    val timeout = args.select("timeout").asOpt[Long].map(_.millis).getOrElse(30.seconds)
    val body = BodyHelper.extractBodyFromOpt(args)
    ???
  }
}

class WasmCallFunction extends WorkflowFunction {

  override def call(args: JsObject): Future[Either[WorkflowError, JsValue]] = {
    val function = args.select("function").asString
    val params = args.select("params").asValue.stringify
    ???
  }
}