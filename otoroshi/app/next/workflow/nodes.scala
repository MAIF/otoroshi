package otoroshi.next.workflow

import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}

case class NoopNode(json: JsObject) extends Node {
  override def run(wfr: WorkflowRun)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    println("noop, doing nothing")
    JsNull.rightf
  }
}

case class WorkflowNode(json: JsObject) extends Node {

  lazy val steps: Seq[Node] = json.select("steps").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map(o => Node.from(o))

  def next(nodes: Seq[Node], wfr: WorkflowRun)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    if (nodes.nonEmpty) {
      val head = nodes.head
      head.internalRun(wfr).flatMap {
        case Left(err) => Left(err).vfuture
        case Right(_) => next(nodes.tail, wfr)
      }.recover {
        case t: Throwable => WorkflowError(s"caught exception on task '${id}' at step: '${head.id}'", None, Some(t)).left
      }
    } else {
      JsNull.rightf
    }
  }

  override def run(wfr: WorkflowRun)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    next(steps, wfr)
  }
}

case class CallNode(json: JsObject) extends Node {

  lazy val functionName: String = json.select("function").asString
  lazy val args: JsObject = json.select("args").asObject

  override def run(wfr: WorkflowRun)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    WorkflowFunction.get(functionName) match {
      case None => WorkflowError(s"function '${functionName}' not supported in task '${id}'", None, None).leftf
      case Some(function) => function.call(WorkflowOperator.processOperators(args, wfr, env).asObject)
    }
  }
}

case class AssignNode(json: JsObject) extends Node {

  lazy val values = ???

  override def run(wfr: WorkflowRun)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    ???
  }
}

case class ParallelFlowsNode(json: JsObject) extends Node {

  override def run(wfr: WorkflowRun)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = ???
}

case class SwitchNode(json: JsObject) extends Node {

  override def run(wfr: WorkflowRun)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = ???
}
