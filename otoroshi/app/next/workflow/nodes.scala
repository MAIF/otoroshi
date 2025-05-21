package otoroshi.next.workflow

import akka.stream.scaladsl.{Sink, Source}
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}

object NodesInitializer {
  def initDefaults(): Unit = {
    Node.registerNode("workflow", json => WorkflowNode(json))
    Node.registerNode("call", json => CallNode(json))
    Node.registerNode("assign", json => AssignNode(json))
    Node.registerNode("parallel", json => ParallelFlowsNode(json))
    Node.registerNode("switch", json => SwitchNode(json))
    Node.registerNode("if", json => IfThenElseNode(json))
    Node.registerNode("foreach", json => ForEachNode(json))
    Node.registerNode("map", json => MapNode(json))
    Node.registerNode("filter", json => FilterNode(json))
    Node.registerNode("flatmap", json => FlatMapNode(json))
  }
}

case class NoopNode(json: JsObject) extends Node {
  override def run(
      wfr: WorkflowRun
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    println("noop, doing nothing")
    JsNull.rightf
  }
}

case class WorkflowNode(json: JsObject) extends Node {

  lazy val steps: Seq[Node] = json.select("steps").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map(o => Node.from(o))

  def next(nodes: Seq[Node], wfr: WorkflowRun)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[WorkflowError, JsValue]] = {
    if (nodes.nonEmpty) {
      val head = nodes.head
      head
        .internalRun(wfr)
        .flatMap {
          case Left(err) => Left(err).vfuture
          case Right(_)  => next(nodes.tail, wfr)
        }
        .recover { case t: Throwable =>
          WorkflowError(s"caught exception on task '${id}' at step: '${head.id}'", None, Some(t)).left
        }
    } else {
      JsNull.rightf
    }
  }

  override def run(
      wfr: WorkflowRun
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    next(steps, wfr)
  }
}

case class CallNode(json: JsObject) extends Node {

  lazy val functionName: String = json.select("function").asString
  lazy val args: JsObject       = json.select("args").asObject

  override def run(
      wfr: WorkflowRun
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    WorkflowFunction.get(functionName) match {
      case None           => WorkflowError(s"function '${functionName}' not supported in task '${id}'", None, None).leftf
      case Some(function) => function.call(WorkflowOperator.processOperators(args, wfr, env).asObject)
    }
  }
}

case class AssignOperation(json: JsObject) {
  def execute(wfr: WorkflowRun)(implicit env: Env, ec: ExecutionContext): Unit = {
    val name  = json.select("name").asString
    val value = WorkflowOperator.processOperators(json.select("value").asValue, wfr, env)
    wfr.memory.set(name, value)
  }
}

case class AssignNode(json: JsObject) extends Node {
  lazy val values: Seq[AssignOperation] =
    json.select("values").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map(o => AssignOperation(o))
  override def run(
      wfr: WorkflowRun
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    values.foreach(_.execute(wfr: WorkflowRun))
    Right(JsNull).vfuture
  }
}

case class ParallelFlowsNode(json: JsObject) extends Node {
  lazy val paths: Seq[JsObject] = json.select("paths").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
  override def run(
      wfr: WorkflowRun
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    Future
      .sequence(
        paths
          .filter { o =>
            if (o.select("predicate").isDefined) {
              WorkflowOperator.processOperators(o.select("predicate").asValue, wfr, env).asOptBoolean.getOrElse(false)
            } else {
              true
            }
          }
          .map(v => Node.from(v))
          .map { path =>
            path.internalRun(wfr).recover { case t: Throwable =>
              WorkflowError(s"caught exception on task '${id}' at step: '${path.id}'", None, Some(t)).left
            }
          }
      )
      .map { seq =>
        val hasError = seq.exists(_.isLeft)
        if (hasError) {
          Left(
            WorkflowError(
              "at least on path has failed",
              Json
                .obj(
                  "errors" -> JsArray(seq.collect { case Left(err) =>
                    err.json
                  })
                )
                .some,
              None
            )
          )
        } else {
          Right(JsArray(seq.map(_.right.get)))
        }
      }
  }
}

case class SwitchNode(json: JsObject) extends Node {
  override def run(
      wfr: WorkflowRun
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val paths: Seq[JsObject] = json.select("paths").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
    paths.find(o =>
      WorkflowOperator.processOperators(o.select("predicate").asValue, wfr, env).asOptBoolean.getOrElse(false)
    ) match {
      case None       => JsNull.rightf
      case Some(path) => {
        val node = Node.from(path.select("node").asObject)
        node.internalRun(wfr).recover { case t: Throwable =>
          WorkflowError(s"caught exception on task '${id}' at path: '${node.id}'", None, Some(t)).left
        }
      }
    }
  }
}

case class IfThenElseNode(json: JsObject) extends Node {
  override def run(
      wfr: WorkflowRun
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val pass = WorkflowOperator.processOperators(json.select("predicate").asValue, wfr, env).asOptBoolean.getOrElse(false)
    if (pass) {
      val node = Node.from(json.select("then").asObject)
      node.internalRun(wfr).recover { case t: Throwable =>
        WorkflowError(s"caught exception on task '${id}' at path: '${node.id}'", None, Some(t)).left
      }
    } else {
      json.select("else").asOpt[JsObject] match {
        case None           => JsNull.rightf
        case Some(nodeJson) => {
          val node = Node.from(nodeJson)
          node.internalRun(wfr).recover { case t: Throwable =>
            WorkflowError(s"caught exception on task '${id}' at path: '${node.id}'", None, Some(t)).left
          }
        }
      }
    }
  }
}

case class ForEachNode(json: JsObject) extends Node {
  override def run(
      wfr: WorkflowRun
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val values         = WorkflowOperator.processOperators(json.select("values").asValue, wfr, env)
    val node           = Node.from(json.select("node").asObject)
    val iterableObject = values.asOpt[JsObject]
    val iterableArray  = values.asOpt[JsArray]
    if (iterableObject.isDefined) {
      Source(iterableObject.get.value.toList)
        .mapAsync(1) { item =>
          val (key, value) = item
          wfr.memory.set("foreach_key", key.json)
          wfr.memory.set("foreach_value", value)
          node
            .internalRun(wfr)
            .recover { case t: Throwable =>
              WorkflowError(s"caught exception on task '${id}' at path: '${node.id}'", None, Some(t)).left
            }
            .andThen { case _ =>
              wfr.memory.remove("foreach_key")
              wfr.memory.remove("foreach_value")
            }
        }
        .takeWhile(_.isLeft, inclusive = true)
        .runWith(Sink.seq)(env.otoroshiMaterializer)
        .map { seq =>
          val last = seq.last
          if (last.isLeft) {
            last
          } else {
            JsArray(seq.collect { case Right(v) =>
              v
            }).right
          }
        }
    } else if (iterableArray.isDefined) {
      Source(iterableArray.get.value.toList)
        .mapAsync(1) { item =>
          wfr.memory.set("foreach_value", item)
          node
            .internalRun(wfr)
            .recover { case t: Throwable =>
              WorkflowError(s"caught exception on task '${id}' at path: '${node.id}'", None, Some(t)).left
            }
            .andThen { case _ =>
              wfr.memory.remove("foreach_value")
            }
        }
        .takeWhile(_.isLeft, inclusive = true)
        .runWith(Sink.seq)(env.otoroshiMaterializer)
        .map { seq =>
          val last = seq.last
          if (last.isLeft) {
            last
          } else {
            JsArray(seq.collect { case Right(v) =>
              v
            }).right
          }
        }
    } else {
      JsNull.rightf
    }
  }
}

case class MapNode(json: JsObject) extends Node {
  override def run(
      wfr: WorkflowRun
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val values = WorkflowOperator.processOperators(json.select("values").asValue, wfr, env)
    val node   = Node.from(json.select("node").asObject)
    values match {
      case arr: JsArray => {
        Source(arr.value.toList)
          .mapAsync(1) { item =>
            wfr.memory.set("foreach_value", item)
            node
              .internalRun(wfr)
              .recover { case t: Throwable =>
                WorkflowError(s"caught exception on task '${id}' at path: '${node.id}'", None, Some(t)).left
              }
              .andThen { case _ =>
                wfr.memory.remove("foreach_value")
              }
          }
          .takeWhile(_.isLeft, inclusive = true)
          .runWith(Sink.seq)(env.otoroshiMaterializer)
          .map { seq =>
            val last = seq.last
            if (last.isLeft) {
              last
            } else {
              val result = JsArray(seq.collect { case Right(v) =>
                v
              })
              json.select("destination").asOptString.foreach { destination =>
                wfr.memory.set(destination, result)
              }
              result.right
            }
          }
      }
      case _            => JsNull.rightf
    }
  }
}

case class FlatMapNode(json: JsObject) extends Node {
  override def run(
      wfr: WorkflowRun
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val values = WorkflowOperator.processOperators(json.select("values").asValue, wfr, env)
    val node   = Node.from(json.select("node").asObject)
    values match {
      case arr: JsArray => {
        Source(arr.value.toList)
          .mapAsync(1) { item =>
            wfr.memory.set("foreach_value", item)
            node
              .internalRun(wfr)
              .recover { case t: Throwable =>
                WorkflowError(s"caught exception on task '${id}' at path: '${node.id}'", None, Some(t)).left
              }
              .andThen { case _ =>
                wfr.memory.remove("foreach_value")
              }
          }
          .takeWhile(_.isLeft, inclusive = true)
          .runWith(Sink.seq)(env.otoroshiMaterializer)
          .map { seq =>
            val last = seq.last
            if (last.isLeft) {
              last
            } else {
              val result = JsArray(seq.collect { case Right(JsArray(v)) =>
                v
              }.flatten)
              json.select("destination").asOptString.foreach { destination =>
                wfr.memory.set(destination, result)
              }
              result.right
            }
          }
      }
      case _            => JsNull.rightf
    }
  }
}

case class FilterNode(json: JsObject) extends Node {
  override def run(
      wfr: WorkflowRun
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val values = WorkflowOperator.processOperators(json.select("values").asValue, wfr, env)
    val node   = Node.from(json.select("predicate").asObject)
    val not    = json.select("not").asOptBoolean.getOrElse(false)
    values match {
      case arr: JsArray => {
        Source(arr.value.toList)
          .mapAsync(1) { item =>
            wfr.memory.set("foreach_value", item)
            node
              .internalRun(wfr)
              .recover { case t: Throwable =>
                WorkflowError(s"caught exception on task '${id}' at path: '${node.id}'", None, Some(t)).left
              }
              .andThen { case _ =>
                wfr.memory.remove("foreach_value")
              }
              .map { eith =>
                (item, eith)
              }
          }
          .takeWhile(_._2.isLeft, inclusive = true)
          .runWith(Sink.seq)(env.otoroshiMaterializer)
          .map { seq =>
            val last = seq.last
            if (last._2.isLeft) {
              last._2
            } else {
              val result = JsArray(
                seq
                  .collect { case (JsBoolean(v), Right(e)) =>
                    (v, e)
                  }
                  .filter { case (bool, _) =>
                    if (not) {
                      !bool
                    } else {
                      bool
                    }
                  }
                  .map(_._2)
              )
              json.select("destination").asOptString.foreach { destination =>
                wfr.memory.set(destination, result)
              }
              result.right
            }
          }
      }
      case _            => JsNull.rightf
    }
  }
}
