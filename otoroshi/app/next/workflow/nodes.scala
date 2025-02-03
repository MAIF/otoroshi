package otoroshi.next.workflow

import otoroshi.env.Env
import otoroshi.next.workflow.Node.registerNode
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}

object NodesInitializer {
  def initDefaults(): Unit = {
    registerNode("workflow", json =>  WorkflowNode(json))
    registerNode("call", json =>  CallNode(json))
    registerNode("assign", json =>  AssignNode(json))
    registerNode("parallel", json =>  ParallelFlowsNode(json))
    registerNode("switch", json =>  SwitchNode(json))
  }
}

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

case class AssignOperation(json: JsObject) {
  def execute(wfr: WorkflowRun)(implicit env: Env, ec: ExecutionContext): Unit = {
    val name = json.select("name").asString
    val value = WorkflowOperator.processOperators(json.select("value").asValue, wfr, env)
    wfr.memory.set(name, value)
  }
}

case class AssignNode(json: JsObject) extends Node {
  lazy val values: Seq[AssignOperation] = json.select("values").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map(o => AssignOperation(o))
  override def run(wfr: WorkflowRun)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    values.foreach(_.execute(wfr: WorkflowRun))
    Right(JsNull).vfuture
  }
}

case class ParallelFlowsNode(json: JsObject) extends Node {
  lazy val paths: Seq[JsObject] = json.select("paths").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
  override def run(wfr: WorkflowRun)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    Future.sequence(paths.filter { o =>
      if (o.select("predicate").isDefined) {
        o.select("predicate").asOptBoolean.getOrElse(false)
      } else {
        true
      }
    }.map(v => Node.from(v)).map { path =>
      path.internalRun(wfr).recover {
        case t: Throwable => WorkflowError(s"caught exception on task '${id}' at step: '${path.id}'", None, Some(t)).left
      }
    }).map { seq =>
      val hasError = seq.exists(_.isLeft)
      if (hasError) {
        Left(WorkflowError("at least on path has failed", Json.obj(
          "errors" -> JsArray(seq.collect {
            case Left(err) => err.json
          })
        ).some, None))
      } else {
        Right(JsArray(seq.map(_.right.get)))
      }
    }
  }
}

case class SwitchNode(json: JsObject) extends Node {
  override def run(wfr: WorkflowRun)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val paths: Seq[JsObject] = json.select("paths").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
    paths.find(o => o.select("predicate").asOptBoolean.getOrElse(false)) match {
      case None => JsNull.rightf
      case Some(path) => {
        val node =Node.from(path.select("node").asObject)
        node.internalRun(wfr).recover {
          case t: Throwable => WorkflowError(s"caught exception on task '${id}' at path: '${node.id}'", None, Some(t)).left
        }
      }
    }
  }
}
