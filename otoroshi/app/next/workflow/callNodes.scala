package next.workflow

import otoroshi.env.Env
import otoroshi.next.workflow.{CallNode, LogFunction, Node, NodeLike, WasmCallFunction, WorkflowError, WorkflowFunction, WorkflowOperator, WorkflowRun}
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}

object CallNodes {
  case class WasmNode(json: JsObject) extends Node {
    lazy val function: WorkflowFunction = new WasmCallFunction()

    override def documentationName: String = function.documentationName
    override def documentationDescription: String = function.documentationDescription

    override def run(wfr: WorkflowRun, prefix: String)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
      function.callWithRun(Json.obj(
          "wasm_plugin" -> json.select("source").select("path").as[String],
          "function" -> json.selectAsString("functionName"),
          "params" -> WorkflowOperator.processOperators(Json.parse(json.selectAsString("params")), wfr, env)
        ))(env, ec, wfr)
    }

    override def subNodes: Seq[NodeLike] = Seq.empty
  }

  case class LogNode(json: JsObject) extends Node {
    lazy val function: WorkflowFunction = new LogFunction()

    override def documentationName: String = function.documentationName
    override def documentationDescription: String = function.documentationDescription

    override def run(wfr: WorkflowRun, prefix: String)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
      function.callWithRun(json.selectAsOptObject("args").getOrElse(Json.obj()))(env, ec, wfr)
    }

    override def subNodes: Seq[NodeLike] = Seq.empty
  }
}