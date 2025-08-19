package otoroshi.next.workflow

import akka.stream.scaladsl.{Sink, Source}
import next.workflow.CallNodes
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.concurrent.duration.DurationLong
import scala.concurrent.{ExecutionContext, Future, Promise}

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
    Node.registerNode("wait", json => WaitNode(json))
    Node.registerNode("error", json => ErrorNode(json))
    Node.registerNode("value", json => ValueNode(json))
    Node.registerNode("wasm", json => CallNodes.WasmNode(json))
    Node.registerNode("log", json => CallNodes.LogNode(json))
  }
}

case class ValueNode(json: JsObject) extends Node {
  override def documentationName: String                  = "value"
  override def documentationDescription: String           = "This node executes a sequence of nodes sequentially"
  override def documentationInputSchema: Option[JsObject] = Node.baseInputSchema
    .deepMerge(
      Json.obj(
        "properties" -> Json.obj(
          "value" -> Json.obj("description" -> "the returned value")
        )
      )
    )
    .some
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"        -> "value",
      "description" -> "This node returns 'foo'",
      "value"       -> "foo"
    )
  )
  override def run(
      wfr: WorkflowRun
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val value = WorkflowOperator.processOperators(json.select("value").asValue, wfr, env)
    println("return value", value)
    value.rightf
  }
}

case class ErrorNode(json: JsObject) extends Node {
  override def documentationName: String                  = "error"
  override def documentationDescription: String           = "This node returns an error"
  override def documentationInputSchema: Option[JsObject] = Node.baseInputSchema
    .deepMerge(
      Json.obj(
        "properties" -> Json.obj(
          "message" -> Json.obj("type" -> "string", "description" -> "the error message"),
          "details" -> Json.obj("type" -> "object", "description" -> "the optional details of the error")
        )
      )
    )
    .some
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"        -> "error",
      "description" -> "This node fails the workflow with an error",
      "message"     -> "an error occurred",
      "details"     -> Json.obj("foo" -> "bar")
    )
  )
  override def run(
      wfr: WorkflowRun
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val message = json.select("message").asOpt[String].getOrElse("")
    val details = json.select("details").asOpt[JsObject]
    WorkflowError(
      message = message,
      details = details,
      exception = None
    ).leftf
  }
}

case class WaitNode(json: JsObject) extends Node {
  override def documentationName: String                  = "wait"
  override def documentationDescription: String           = "This node waits a certain amount of time"
  override def documentationInputSchema: Option[JsObject] = Node.baseInputSchema
    .deepMerge(
      Json.obj(
        "properties" -> Json.obj(
          "duration" -> Json.obj("type" -> "number", "description" -> "the number of milliseconds to wait")
        )
      )
    )
    .some
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"        -> "wait",
      "description" -> "This node waits 20 seconds",
      "duration"    -> 20000
    )
  )
  override def run(
      wfr: WorkflowRun
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val duration = json.select("duration").asOpt[Long].getOrElse(0L).millis
    val promise  = Promise[Either[WorkflowError, JsValue]]()
    env.otoroshiScheduler.scheduleOnce(duration) {
      promise.trySuccess(JsNull.right)
    }
    promise.future
  }
}

case class NoopNode(json: JsObject) extends Node {
  override def documentationName: String                  = "noop"
  override def documentationDescription: String           = "This node does nothing"
  override def documentationInputSchema: Option[JsObject] = Node.baseInputSchema.some
  override def run(
      wfr: WorkflowRun
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    println("noop, doing nothing")
    JsNull.rightf
  }
}

case class WorkflowNode(json: JsObject) extends Node {

  override def documentationName: String                  = "workflow"
  override def documentationDescription: String           = "This node executes a sequence of nodes sequentially"
  override def documentationInputSchema: Option[JsObject] = Node.baseInputSchema
    .deepMerge(
      Json.obj(
        "properties" -> Json.obj(
          "steps" -> Json.obj("type" -> "array", "description" -> "the nodes to be executed")
        )
      )
    )
    .some

  override def documentationExample: Option[JsObject] = Some(
    Json.obj(
      "kind"        -> "workflow",
      "description" -> "This node executes says hello, waits and says hello again",
      "steps"       -> Json.arr(
        Json.obj(
          "kind"     -> "call",
          "function" -> "core.hello"
        ),
        Json.obj(
          "kind"     -> "wait",
          "duration" -> 10000
        ),
        Json.obj(
          "kind"     -> "call",
          "function" -> "core.hello"
        )
      )
    )
  )

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

  override def documentationName: String                  = "call"
  override def documentationDescription: String           = "This node calls a function an returns its result"
  override def documentationInputSchema: Option[JsObject] = Node.baseInputSchema
    .deepMerge(
      Json.obj(
        "properties" -> Json.obj(
          "function" -> Json.obj("type" -> "string", "description" -> "the function name"),
          "args"     -> Json.obj("type" -> "object", "description" -> "the arguments of the call")
        )
      )
    )
    .some

  override def documentationExample: Option[JsObject] = Some(
    Json.obj(
      "kind"        -> "call",
      "description" -> "This node call the core.hello function",
      "function"    -> "core.hello",
      "args"        -> Json.obj(
        "name" -> "Otoroshi"
      )
    )
  )

  lazy val functionName: String = json.select("function").asString
  lazy val args: JsObject       = json.select("args").asObject

  override def run(
      wfr: WorkflowRun
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    WorkflowFunction.get(functionName) match {
      case None           => WorkflowError(s"function '${functionName}' not supported in task '${id}'", None, None).leftf
      case Some(function) =>
        function.callWithRun(WorkflowOperator.processOperators(args, wfr, env).asObject)(env, ec, wfr)
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
  override def documentationName: String                  = "assign"
  override def documentationDescription: String           =
    "This node with executes a sequence of memory assignation operations sequentially"
  override def documentationInputSchema: Option[JsObject] = Node.baseInputSchema
    .deepMerge(
      Json.obj(
        "properties" -> Json.obj(
          "values" -> Json.obj(
            "type"        -> "array",
            "description" -> "the assignation operations sequence",
            "items"       -> Json.obj(
              "type"       -> "object",
              "required"   -> Seq("name", "value"),
              "properties" -> Json.obj(
                "name"  -> Json.obj("type" -> "string", "description" -> "the name of the assignment in memory"),
                "value" -> Json.obj("description" -> "the value of the assignment")
              )
            )
          )
        )
      )
    )
    .some
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"        -> "assign",
      "description" -> "set a counter, increment it of 2 and initialize an array in memory",
      "values"      -> Json.arr(
        Json.obj(
          "name"  -> "count",
          "value" -> 0
        ),
        Json.obj(
          "name"  -> "count",
          "value" -> Json.obj(
            "$incr" -> Json.obj(
              "value"     -> "count",
              "increment" -> 2
            )
          )
        ),
        Json.obj(
          "name"  -> "items",
          "value" -> Json.arr(1, 2, 3)
        )
      )
    )
  )
  lazy val values: Seq[AssignOperation]                   =
    json.select("values").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map(o => AssignOperation(o))
  override def run(
      wfr: WorkflowRun
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    values.foreach(_.execute(wfr: WorkflowRun))
    Right(JsNull).vfuture
  }
}

case class ParallelFlowsNode(json: JsObject) extends Node {
  override def documentationName: String                  = "parallel"
  override def documentationDescription: String           = "This node executes multiple nodes in parallel"
  override def documentationInputSchema: Option[JsObject] = Node.baseInputSchema
    .deepMerge(
      Json.obj(
        "properties" -> Json.obj(
          "paths" -> Json.obj(
            "type"        -> "array",
            "description" -> "the nodes to be executed",
            "items"       -> Json.obj(
              "type"       -> "object",
              "properties" -> Json.obj(
                "predicate" -> Json
                  .obj("type" -> "boolean", "description" -> "The predicate defining if the path is run or not")
              )
            )
          )
        )
      )
    )
    .some
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"        -> "parallel",
      "description" -> "This node call 3 core.hello function in parallel",
      "paths"       -> Json.arr(
        Json.obj(
          "kind"     -> "call",
          "function" -> "core.hello",
          "args"     -> Json.obj(
            "name" -> "Otoroshi 1"
          )
        ),
        Json.obj(
          "kind"     -> "call",
          "function" -> "core.hello",
          "args"     -> Json.obj(
            "name" -> "Otoroshi 2"
          )
        ),
        Json.obj(
          "kind"     -> "call",
          "function" -> "core.hello",
          "args"     -> Json.obj(
            "name" -> "Otoroshi 3"
          )
        )
      )
    )
  )
  lazy val paths: Seq[JsObject]                           = json.select("paths").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
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
  override def documentationName: String                  = "switch"
  override def documentationDescription: String           = "This node executes the first path matching a predicate"
  override def documentationInputSchema: Option[JsObject] = Node.baseInputSchema
    .deepMerge(
      Json.obj(
        "properties" -> Json.obj(
          "paths" -> Json.obj(
            "type"        -> "array",
            "description" -> "the nodes to be executed",
            "items"       -> Json.obj(
              "type"       -> "object",
              "properties" -> Json.obj(
                "predicate" -> Json
                  .obj("type" -> "boolean", "description" -> "The predicate defining if the path is run or not")
              )
            )
          )
        )
      )
    )
    .some

  override def documentationExample: Option[JsObject] = Some(
    Json.obj(
      "kind"        -> "switch",
      "description" -> "This node will say 'Hello Otoroshi 1'",
      "paths"       -> Json.arr(
        Json.obj(
          "predicate" -> true,
          "kind"      -> "call",
          "function"  -> "core.hello",
          "args"      -> Json.obj(
            "name" -> "Otoroshi 1"
          )
        ),
        Json.obj(
          "predicate" -> false,
          "kind"      -> "call",
          "function"  -> "core.hello",
          "args"      -> Json.obj(
            "name" -> "Otoroshi 2"
          )
        )
      )
    )
  )
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
  override def documentationName: String                  = "if"
  override def documentationDescription: String           = "This executes a node if the predicate matches or another one if not"
  override def documentationInputSchema: Option[JsObject] = Node.baseInputSchema
    .deepMerge(
      Json.obj(
        "properties" -> Json.obj(
          "properties" -> Json.obj(
            "predicate" -> Json
              .obj("type" -> "boolean", "description" -> "The predicate defining if the path is run or not"),
            "then"      -> Json.obj("type" -> "object", "description" -> "The node run if the predicate matches"),
            "else"      -> Json.obj("type" -> "object", "description" -> "The node run if the predicate does not matches")
          )
        )
      )
    )
    .some
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"        -> "if",
      "description" -> "This node will say 'Hello Otoroshi 1'",
      "predicate"   -> "${truthyValueInMemory}",
      "then"        -> Json.obj(
        "kind"     -> "call",
        "function" -> "core.hello",
        "args"     -> Json.obj(
          "name" -> "Otoroshi 1"
        )
      ),
      "else"        -> Json.obj(
        "kind"     -> "call",
        "function" -> "core.hello",
        "args"     -> Json.obj(
          "name" -> "Otoroshi 2"
        )
      )
    )
  )
  override def run(
      wfr: WorkflowRun
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    if (json.select("predicate").isEmpty) {
      WorkflowError(s"Missing predicate node").left.future
    } else {
      val pass =
        WorkflowOperator.processOperators(json.select("predicate").asValue, wfr, env).asOptBoolean.getOrElse(false)
      if (pass) {
        val node = Node.from(json.select("then").asObject)
        node.internalRun(wfr).recover { case t: Throwable =>
          WorkflowError(s"caught exception on task '${id}' at path: '${node.id}'", None, Some(t)).left
        }
      } else {
        json.select("else").asOpt[JsObject] match {
          case None           => JsNull.rightf
          case Some(nodeJson) =>
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
  override def documentationName: String                  = "foreach"
  override def documentationDescription: String           = "This node executes a node for each element in an array"
  override def documentationInputSchema: Option[JsObject] = Node.baseInputSchema
    .deepMerge(
      Json.obj(
        "properties" -> Json.obj(
          "values" -> Json.obj("type" -> "array", "description" -> "the values to iterate on"),
          "node"   -> Json.obj("type" -> "object", "description" -> "the node to execute for each element in an array")
        )
      )
    )
    .some
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"        -> "foreach",
      "description" -> "This node execute the core.hello function for each user",
      "values"      -> "${users}",
      "node"        -> Json.obj(
        "kind"     -> "call",
        "function" -> "core.hello",
        "args"     -> Json.obj(
          "name" -> "${foreach_value.name}"
        )
      )
    )
  )
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
        .takeWhile(_.isRight, inclusive = true)
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
        .takeWhile(_.isRight, inclusive = true)
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
  override def documentationName: String                  = "map"
  override def documentationDescription: String           = "This node transforms an array by applying a node on each value"
  override def documentationInputSchema: Option[JsObject] = Node.baseInputSchema
    .deepMerge(
      Json.obj(
        "properties" -> Json.obj(
          "values" -> Json.obj("type" -> "array", "description" -> "the values to iterate on"),
          "node"   -> Json.obj("type" -> "object", "description" -> "the node to execute for each element in an array")
        )
      )
    )
    .some
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"        -> "map",
      "description" -> "This node will transform user names",
      "values"      -> "${users}",
      "node"        -> Json.obj(
        "kind"  -> "value",
        "value" -> Json.obj(
          "$str_upper_case" -> "${foreach_value.name}"
        )
      )
    )
  )
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
            val value = node
              .internalRun(wfr)

            println("internal run", value)

            value
              .recover { case t: Throwable =>
                WorkflowError(s"caught exception on task '${id}' at path: '${node.id}'", None, Some(t)).left
              }
              .andThen { case _ =>
                wfr.memory.remove("foreach_value")
              }
          }
          .takeWhile(_.isRight, inclusive = true)
          .runWith(Sink.seq)(env.otoroshiMaterializer)
          .map { seq =>
            println("seq", seq)
            val last = seq.last
            if (last.isLeft) {
              println("LAST", last)
              last
            } else {
              val result = JsArray(seq.collect { case Right(v) =>
                v
              })
              json.select("destination").asOptString.foreach { destination =>
                wfr.memory.set(destination, result)
              }
              println("result", result)
              result.right
            }
          }
      }
      case _            => JsNull.rightf
    }
  }
}

case class FlatMapNode(json: JsObject) extends Node {
  override def documentationName: String                  = "flatmap"
  override def documentationDescription: String           = "This node transforms an array by applying a node on each value"
  override def documentationInputSchema: Option[JsObject] = Node.baseInputSchema
    .deepMerge(
      Json.obj(
        "properties" -> Json.obj(
          "values" -> Json.obj("type" -> "array", "description" -> "the values to iterate on"),
          "node"   -> Json.obj(
            "type"        -> "object",
            "description" -> "the node to execute for each element in an array, should return an array to flatten it"
          )
        )
      )
    )
    .some
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"        -> "map",
      "description" -> "This node extract all emails from users",
      "values"      -> "${users}",
      "node"        -> Json.obj(
        "kind"  -> "value",
        "value" -> "${foreach_value.emails}"
      )
    )
  )
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
          .takeWhile(_.isRight, inclusive = true)
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
  override def documentationName: String                  = "filter"
  override def documentationDescription: String           =
    "This node transforms an array by filtering values based on a node execution"
  override def documentationInputSchema: Option[JsObject] = Node.baseInputSchema
    .deepMerge(
      Json.obj(
        "properties" -> Json.obj(
          "values"    -> Json.obj("type" -> "array", "description" -> "the values to iterate on"),
          "predicate" -> Json
            .obj("type" -> "object", "description" -> "the node to execute for each element in an array")
        )
      )
    )
    .some
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"        -> "filter",
      "description" -> "This node will filter out users that are not admins",
      "values"      -> "${users}",
      "predicate"   -> Json.obj(
        "kind"  -> "value",
        "value" -> "${foreach_value.admin}"
      )
    )
  )
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
          .takeWhile(_._2.isRight, inclusive = true)
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
