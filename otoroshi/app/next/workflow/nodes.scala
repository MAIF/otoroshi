package otoroshi.next.workflow

import akka.stream.scaladsl.{Sink, Source}
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationLong
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Success

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
    Node.registerNode("pause", json => PauseNode(json))
    Node.registerNode("end", json => EndNode(json))
    Node.registerNode("while", json => WhileNode(json))
    Node.registerNode("jump", json => JumpNode(json))
    Node.registerNode("try", json => TryNode(json))
    Node.registerNode("async", json => AsyncNode(json))
  }
}

case class AsyncNode(json: JsObject) extends Node {

  lazy val node = Node.from(json.select("node").asObject)

  override def subNodes: Seq[NodeLike]                    = Seq(node)
  override def documentationName: String                  = "async"
  override def documentationDisplayName: String           = "Async"
  override def documentationIcon: String                  = "fas fa-code-branch"
  override def documentationDescription: String           = "This node runs a node asynchronously and does not wait for the end."
  override def documentationInputSchema: Option[JsObject] = Node.baseInputSchema
    .deepMerge(
      Json.obj(
        "properties" -> Json.obj(
          "node" -> Json
            .obj("type" -> "object", "description" -> "The node to run"),
        )
      )
    )
    .some
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"        -> "try",
      "description" -> "Jump to the 'incr' node if count value is not 4",
      "node"   -> Json.obj(
        "kind" -> "error",
        "message"     -> "an error occurred",
        "details"     -> Json.obj("foo" -> "bar")
      )
    )
  )

  override def run(
    wfr: WorkflowRun,
    prefix: Seq[Int],
    from: Seq[Int]
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    if (from.nonEmpty && from.head > 0) {
      WorkflowError(
        s"Async Node (${prefix.mkString(".")}) cannot resume sub nodes: ${from.mkString(".")}",
        None,
        None
      ).leftf
    } else {
      node.internalRun(wfr, prefix :+ 0, from)
      JsNull.rightf
    }
  }
}

case class TryNode(json: JsObject) extends Node {

  lazy val node = Node.from(json.select("node").asObject)
  lazy val catchNode = Node.from(json.select("catch").asObject)
  lazy val finallyNode = json.select("finally").asOpt[JsObject].map(n => Node.from(n))

  override def subNodes: Seq[NodeLike]                    = Seq(node, catchNode) ++ finallyNode.toSeq
  override def documentationName: String                  = "try"
  override def documentationDisplayName: String           = "Try/catch"
  override def documentationIcon: String                  = "fas fa-hand-paper"
  override def documentationDescription: String           = "This node catch errors and can return something else"
  override def documentationInputSchema: Option[JsObject] = Node.baseInputSchema
    .deepMerge(
      Json.obj(
        "properties" -> Json.obj(
          "node" -> Json
            .obj("type" -> "object", "description" -> "The node to run"),
          "catch"   -> Json.obj("type" -> "object", "description" -> "the node executed when an error is caught"),
          "finally"   -> Json.obj("type" -> "object", "description" -> "the node executed after everything")
        )
      )
    )
    .some
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"        -> "try",
      "description" -> "Jump to the 'incr' node if count value is not 4",
      "node"   -> Json.obj(
        "kind" -> "error",
        "message"     -> "an error occurred",
        "details"     -> Json.obj("foo" -> "bar")
      ),
      "catch" -> Json.obj(
        "kind" -> "call",
        "function"   -> "core.log",
        "args" -> Json.obj(
          "message" -> "caught an error",
          "params" -> Json.obj(
            "error" -> "${caught_error}"
          )
        )
      )
    )
  )

  def runCatch(
                    wfr: WorkflowRun,
                    prefix: Seq[Int],
                    from: Seq[Int],
                    error: WorkflowError
                  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    wfr.memory.set("caught_error", error.json)
    catchNode
      .internalRun(wfr, prefix :+ 1, from)
      .recover { case t: Throwable =>
        WorkflowError(s"caught exception on task '${id}' at path: '${node.id}'", None, Some(t)).left
      }
      .andThen { case _ =>
        wfr.memory.remove("caught_error")
      }
  }

  override def run(
    wfr: WorkflowRun,
    prefix: Seq[Int],
    from: Seq[Int]
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    if (from.nonEmpty && from.head > 0) {
      WorkflowError(
        s"Try Node (${prefix.mkString(".")}) cannot resume sub nodes: ${from.mkString(".")}",
        None,
        None
      ).leftf
    } else {
      node
        .internalRun(wfr, prefix :+ 0, from)
        .recover { case t: Throwable =>
          WorkflowError(s"caught exception on task '${id}' at path: '${node.id}'", None, Some(t)).left
        }
        .flatMap {
          case Left(error) => runCatch(wfr, prefix, from, error)
          case Right(res) => Right(res).future
        }
        .andThen {
          case _ => finallyNode.foreach(_.internalRun(wfr, prefix :+ 2, from))
        }
    }
  }
}

case class JumpNode(json: JsObject) extends Node {
  override def subNodes: Seq[NodeLike]                    = Seq.empty
  override def documentationName: String                  = "jump"
  override def documentationDisplayName: String           = "Jump"
  override def documentationIcon: String                  = "fas fa-share"
  override def documentationDescription: String           = "This node jumps to another node with a specific id"
  override def documentationInputSchema: Option[JsObject] = Node.baseInputSchema
    .deepMerge(
      Json.obj(
        "properties" -> Json.obj(
          "predicate" -> Json
            .obj("type" -> "boolean", "description" -> "The predicate defining if the jump is done or not"),
          "to"   -> Json.obj("type" -> "string", "description" -> "the node id to jump to")
        )
      )
    )
    .some
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"        -> "jump",
      "description" -> "Jump to the 'incr' node if count value is not 4",
      "predicate"   -> Json.obj(
        "$neq" -> Json.obj(
          "a" -> "${count}",
          "b" -> 4
        )
      ),
      "to" -> "incr"
    )
  )
  override def run(
                    wfr: WorkflowRun,
                    prefix: Seq[Int],
                    from: Seq[Int]
                  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val predicate = WorkflowOperator.processOperators(json.select("predicate").asValue, wfr, env).asOptBoolean.getOrElse(true)
    val to = json.select("to").asOpt[String].getOrElse("--")
    val path = json.select("path").asOpt[String].getOrElse("--")
    if (predicate) {
      WorkflowError(
        message = "_____otoroshi_workflow_jump",
        details = Some(Json.obj("path" -> path, "to" -> to)),
        exception = None
      ).leftf
    } else {
      JsNull.rightf
    }
  }
}

case class WhileNode(json: JsObject) extends Node {

  lazy val node = Node.from(json.select("node").asObject)

  override def subNodes: Seq[NodeLike]                    = Seq(node)
  override def documentationName: String                  = "while"
  override def documentationDisplayName: String           = "While"
  override def documentationIcon: String                  = "fas fa-rotate-right"
  override def documentationDescription: String           = "This node executes a node while the predicate is true"
  override def documentationInputSchema: Option[JsObject] = Node.baseInputSchema
    .deepMerge(
      Json.obj(
        "properties" -> Json.obj(
          "predicate" -> Json
            .obj("type" -> "boolean", "description" -> "The predicate defining if the node is run or not"),
          "node"   -> Json.obj("type" -> "object", "description" -> "the node to execute for each element in an array")
        )
      )
    )
    .some
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"        -> "while",
      "description" -> "This loop until value is 5",
      "predicate"   -> Json.obj(
        "$neq" -> Json.obj(
          "a" -> "${count}",
          "b" -> 5
        )
      ),
      "node"        -> Json.obj(
        "kind"     -> "assign",
        "values" -> Json.arr(
          Json.obj(
            "name"  -> "count",
            "value" -> Json.obj(
              "$incr" -> Json.obj(
                "value"     -> "count",
                "increment" -> 1
              )
            )
          )
        )
      )
    )
  )
  override def run(
                    wfr: WorkflowRun,
                    prefix: Seq[Int],
                    from: Seq[Int]
                  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    if (from.nonEmpty) {
      WorkflowError(
        s"While Node (${prefix.mkString(".")}) cannot resume sub nodes: ${from.mkString(".")}",
        None,
        None
      ).leftf
    } else {

      val promise = Promise[Either[WorkflowError, JsValue]]()
      val maxBudget = json.select("max_budget").asOpt[Int].getOrElse(999)
      val count = new AtomicInteger(0)

      def next(): Unit = {
        if (count.get() == maxBudget) {
          promise.trySuccess(WorkflowError(
            s"while loop '${maxBudget}' exceeds maximum budget",
            None, None
          ).left)
        }
        count.incrementAndGet()
        val predicate = WorkflowOperator.processOperators(json.select("predicate").asValue, wfr, env).asOptBoolean.getOrElse(false)
        if (predicate) {
          node
            .internalRun(wfr, prefix :+ 0, from)
            .andThen {
              case Success(_) => next()
            }
            .recover { case t: Throwable =>
              val e: Either[WorkflowError, JsValue] = WorkflowError(s"caught exception on task '${id}' at path: '${node.id}'", None, Some(t)).left
              promise.trySuccess(e)
            }
        } else {
          promise.trySuccess(JsNull.right)
        }
      }

      next()
      promise.future
    }
  }
}

case class EndNode(json: JsObject) extends Node {
  override def subNodes: Seq[NodeLike]                    = Seq.empty
  override def documentationName: String                  = "end"
  override def documentationDisplayName: String           = "End"
  override def documentationIcon: String                  = "fas fa-door-open"
  override def documentationDescription: String           = "This node end the workflow"
  override def documentationInputSchema: Option[JsObject] = Node.baseInputSchema
    .deepMerge(
      Json.obj()
    )
    .some
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"        -> "exit",
      "description" -> "End the workflow at this point."
    )
  )
  override def run(
    wfr: WorkflowRun,
    prefix: Seq[Int],
    from: Seq[Int]
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    WorkflowError(
      message = "_____otoroshi_workflow_ended",
      details = None,
      exception = None
    ).leftf
  }
}

case class PauseNode(json: JsObject) extends Node {
  override def subNodes: Seq[NodeLike]                    = Seq.empty
  override def documentationName: String                  = "pause"
  override def documentationDisplayName: String           = "Pause"
  override def documentationIcon: String                  = "fas fa-pause"
  override def documentationDescription: String           = "This node pauses the current workflow"
  override def documentationInputSchema: Option[JsObject] = Node.baseInputSchema
    .deepMerge(
      Json.obj()
    )
    .some
  override def documentationExample: Option[JsObject]     = Some(
    Json.obj(
      "kind"        -> "pause",
      "description" -> "Pause the workflow at this point."
    )
  )
  override def run(
      wfr: WorkflowRun,
      prefix: Seq[Int],
      from: Seq[Int]
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    if (env.isDev) println(s"running: ${prefix.mkString(".")} - ${kind} / ${id}")
    val session = PausedWorkflowSession(
      id = wfr.id,
      workflowRef = wfr.workflow_ref,
      workflow = wfr.workflow,
      functions = wfr.functions,
      wfr = wfr,
      from = if (prefix.length > 1) {
        val init = prefix.init
        val last = prefix.last
        init :+ (last + 1)
      } else {
        prefix
      },
      createdAt = DateTime.now(),
      validUntil = None
    )
    // println(s"saving session: ${session.json.prettify}")
    session.save(env).map { _ =>
      WorkflowError(
        message = "_____otoroshi_workflow_paused",
        details = Some(Json.obj("access_token" -> session.token)),
        exception = None
      ).left
    }
  }
}

case class ValueNode(json: JsObject) extends Node {
  override def subNodes: Seq[NodeLike]                    = Seq.empty
  override def documentationName: String                  = "value"
  override def documentationDisplayName: String           = "Value"
  override def documentationIcon: String                  = "fas fa-font"
  override def documentationDescription: String           = "This node returns a value"
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
      wfr: WorkflowRun,
      prefix: Seq[Int],
      from: Seq[Int]
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    if (env.isDev) println(s"running: ${prefix.mkString(".")} - ${kind} / ${id}")
    val value = WorkflowOperator.processOperators(json.select("value").asValue, wfr, env)
    // println("return value", value)
    value.rightf
  }
}

case class ErrorNode(json: JsObject) extends Node {
  override def subNodes: Seq[NodeLike]                    = Seq.empty
  override def documentationName: String                  = "error"
  override def documentationDisplayName: String           = "Stop and Error"
  override def documentationIcon: String                  = "fas fa-exclamation"
  override def documentationDescription: String           = "This node returns an error"
  override def documentationFormSchema: Option[JsObject]  = Json
    .obj(
      "message" -> Json.obj(
        "type"  -> "string",
        "label" -> "Message"
      ),
      "details" -> Json.obj(
        "type"  -> "code",
        "label" -> "Details",
        "props" -> Json.obj(
          "editorOnly" -> true
        )
      )
    )
    .some
  override def documentationInputSchema: Option[JsObject] = Node.baseInputSchema
    .deepMerge(
      Json.obj(
        "message" -> Json.obj("type" -> "string", "description" -> "the error message"),
        "details" -> Json.obj("type" -> "object", "description" -> "the optional details of the error")
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
      wfr: WorkflowRun,
      prefix: Seq[Int],
      from: Seq[Int]
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    if (env.isDev) println(s"running: ${prefix.mkString(".")} - ${kind} / ${id}")
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
  override def subNodes: Seq[NodeLike]                    = Seq.empty
  override def documentationName: String                  = "wait"
  override def documentationDisplayName: String           = "Wait"
  override def documentationIcon: String                  = "fas fa-clock"
  override def documentationDescription: String           = "This node waits a certain amount of time"
  override def documentationFormSchema: Option[JsObject]  = Json
    .obj(
      "duration" -> Json.obj(
        "type"  -> "number",
        "label" -> "Duration"
      )
    )
    .some
  override def documentationInputSchema: Option[JsObject] = Node.baseInputSchema
    .deepMerge(
      Json.obj(
        "duration" -> Json.obj("type" -> "number", "description" -> "the number of milliseconds to wait")
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
      wfr: WorkflowRun,
      prefix: Seq[Int],
      from: Seq[Int]
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    if (env.isDev) println(s"running: ${prefix.mkString(".")} - ${kind} / ${id}")
    val duration = json.select("duration").asOpt[Long].getOrElse(0L).millis
    val promise  = Promise[Either[WorkflowError, JsValue]]()
    env.otoroshiScheduler.scheduleOnce(duration) {
      promise.trySuccess(JsNull.right)
    }
    promise.future
  }
}

case class NoopNode(json: JsObject) extends Node {
  override def subNodes: Seq[NodeLike]                    = Seq.empty
  override def documentationName: String                  = "noop"
  override def documentationDisplayName: String           = "Noop"
  override def documentationIcon: String                  = "fas fa-poop"
  override def documentationDescription: String           = "This node does nothing"
  override def documentationInputSchema: Option[JsObject] = Node.baseInputSchema.some
  override def run(
      wfr: WorkflowRun,
      prefix: Seq[Int],
      from: Seq[Int]
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    if (env.isDev) println(s"running: ${prefix.mkString(".")} - ${kind} / ${id}")
    // println("noop, doing nothing")
    JsNull.rightf
  }
}

case class WorkflowNode(json: JsObject) extends Node {

  lazy val steps: Seq[Node] = json.select("steps").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map(o => Node.from(o))

  override def subNodes: Seq[NodeLike]                    = steps
  override def documentationName: String                  = "workflow"
  override def documentationDisplayName: String           = "Workflow"
  override def documentationIcon: String                  = "fas fa-code-branch"
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

  def next(nodes: Seq[Node], wfr: WorkflowRun, prefix: Seq[Int], from: Seq[Int], idx: Int)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[WorkflowError, JsValue]] = {
    if (nodes.nonEmpty) {
      val head = nodes.head
      head
        .internalRun(wfr, prefix :+ idx, from)
        .flatMap {
          // case Left(err) if err.message == "_____otoroshi_workflow_paused" =>
          //   err.details.get.select("access_token").asValue.rightf
          // case Left(err) if err.message == "_____otoroshi_workflow_ended" =>
          //   Right(wfr.memory.get("input").getOrElse(JsNull)).vfuture
          case Left(err)                                                   => Left(err).vfuture
          case Right(_)                                                    => next(nodes.tail, wfr, prefix, from, idx + 1)
        }
        .recover { case t: Throwable =>
          WorkflowError(s"caught exception on task '${id}' at step: '${head.id}'", None, Some(t)).left
        }
    } else {
      JsNull.rightf
    }
  }

  override def run(
      wfr: WorkflowRun,
      prefix: Seq[Int],
      from: Seq[Int]
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    if (from.nonEmpty) {
      val head      = from.head
      val lastSteps = steps.drop(head)
      if (env.isDev) println(s"resuming: ${prefix.mkString(".")} at ${head} - ${kind} / ${id}")
      next(lastSteps, wfr, prefix, from.tail, head)
    } else {
      if (env.isDev) println(s"running: ${prefix.mkString(".")} - ${kind} / ${id}")
      next(steps, wfr, prefix, from, 0)
    }
  }
}

case class CallNode(json: JsObject) extends Node {

  override def subNodes: Seq[NodeLike]                    = Seq.empty
  override def documentationName: String                  = "call"
  override def documentationDisplayName: String           = "Call"
  override def documentationIcon: String                  = "fas fa-code"
  override def documentationDescription: String           = "This node calls a function an returns its result"
  override def documentationInputSchema: Option[JsObject] = Node.baseInputSchema
    .deepMerge(
      Json.obj(
        "properties" -> Json.obj(
          "async" -> Json.obj("type" -> "bool", "description" -> "if true, the call do not block"),
          "function" -> Json.obj("type" -> "string", "description" -> "the function name"),
          "args"     -> Json.obj("type" -> "object", "description" -> "the arguments of the call")
        )
      )
    )
    .some
  override def documentationExample: Option[JsObject]     = Some(
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
  lazy val args: JsObject       = json.selectAsOptObject("args").getOrElse(Json.obj())
  lazy val async: Boolean       = json.select("async").asOptBoolean.getOrElse(false)

  override def run(
      wfr: WorkflowRun,
      prefix: Seq[Int],
      from: Seq[Int]
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    if (from.nonEmpty) {
      WorkflowError(
        s"Call Node (${prefix.mkString(".")}) cannot resume sub nodes: ${from.mkString(".")}",
        None,
        None
      ).leftf
    } else {
      if (env.isDev) println(s"running: ${prefix.mkString(".")} - ${kind} / ${id}")
      if (functionName.startsWith("self.")) {
        wfr.functions.get(functionName.substring(5)) match {
          case None           => WorkflowError(s"self function '${functionName}' not supported in task '${id}'", None, None).leftf
          case Some(function) =>
            wfr.memory.set("function_args", WorkflowOperator.processOperators(args, wfr, env))
            val r = Node.from(function).run(wfr, prefix, from).andThen { case _ =>
              wfr.memory.remove("function_args")
            }
            if (async) {
              JsNull.rightf
            } else {
              r
            }
        }
      } else {
        WorkflowFunction.get(functionName) match {
          case None           => WorkflowError(s"function '${functionName}' not supported in task '${id}'", None, None).leftf
          case Some(function) =>
            val r = function.callWithRun(WorkflowOperator.processOperators(args, wfr, env).asObject)(env, ec, wfr)
            if (async) {
              JsNull.rightf
            } else {
              r
            }
        }
      }
    }
  }
}

case class AssignOperation(json: JsObject) {
  def execute(wfr: WorkflowRun)(implicit env: Env, ec: ExecutionContext): Unit = {
    try {
      val name  = json.select("name").asString
      val value = WorkflowOperator.processOperators(json.select("value").asValue, wfr, env)
      wfr.memory.set(name, value)
    } catch {
      case e: Throwable => e.printStackTrace()
    }
  }
}

case class AssignNode(json: JsObject) extends Node {
  override def subNodes: Seq[NodeLike]                    = Seq.empty
  override def documentationName: String                  = "assign"
  override def documentationDisplayName: String           = "Assign in memory"
  override def documentationIcon: String                  = "fas fa-dollar-sign"
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
              "value"     -> "${count}",
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
      wfr: WorkflowRun,
      prefix: Seq[Int],
      from: Seq[Int]
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    if (from.nonEmpty) {
      WorkflowError(
        s"Assign Node (${prefix.mkString(".")}) cannot resume sub nodes: ${from.mkString(".")}",
        None,
        None
      ).leftf
    } else {
      if (env.isDev) println(s"running: ${prefix.mkString(".")} - ${kind} / ${id}")
      values.foreach(_.execute(wfr))
      Right(JsNull).vfuture
    }
  }
}

case class ParallelFlowsNode(json: JsObject) extends Node {
  override def subNodes: Seq[NodeLike]                    =
    json.select("paths").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map(v => Node.from(v))
  override def documentationName: String                  = "parallel"
  override def documentationDisplayName: String           = "Parallel paths"
  override def documentationIcon: String                  = "fas fa-code-branch"
  override def documentationDescription: String           = "This node executes multiple nodes in parallel"
  override def documentationInputSchema: Option[JsObject] = Node.baseInputSchema
    .deepMerge(
      Json.obj(
        "properties" -> Json.obj(
          "paths" -> Json.obj(
            "type"        -> "array",
            "description" -> "the nodes to be executed",
            "items"       -> Json.obj(
              "type"   -> "form",
              "flow"   -> Seq("predicate"),
              "schema" -> Json.obj(
                "predicate" -> Json.obj(
                  "type"        -> "boolean",
                  "description" -> "The predicate defining if the path is run or not"
                )
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
      wfr: WorkflowRun,
      prefix: Seq[Int],
      from: Seq[Int]
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    if (from.nonEmpty) {
      WorkflowError(
        s"Parallel Node (${prefix.mkString(".")}) does not support resume: ${from.mkString(".")}",
        None,
        None
      ).leftf
    } else {
      Future
        .sequence(
          paths.zipWithIndex
            .filter { case (o, idx) =>
              if (o.select("predicate").isDefined) {
                WorkflowOperator.processOperators(o.select("predicate").asValue, wfr, env).asOptBoolean.getOrElse(false)
              } else {
                true
              }
            }
            .map {
              case (o, idx) => {
                val node = o
                  .selectAsOptObject("node")
                  .map(Node.from)
                  .getOrElse(NoopNode(json))
                (node, idx)
              }
            }
            .map { case (path, idx) =>
              if (env.isDev) println(s"running: ${prefix.mkString(".")}.${idx} - ${kind} / ${id}")
              path.internalRun(wfr, prefix :+ idx, from).recover { case t: Throwable =>
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
}

case class SwitchNode(json: JsObject) extends Node {
  override def subNodes: Seq[NodeLike]                    =
    json.select("paths").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map(v => Node.from(v))
  override def documentationName: String                  = "switch"
  override def documentationDisplayName: String           = "Switch paths"
  override def documentationIcon: String                  = "fas fa-exchange-alt"
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
  override def documentationExample: Option[JsObject]     = Some(
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
      wfr: WorkflowRun,
      prefix: Seq[Int],
      from: Seq[Int]
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    if (from.nonEmpty) {
      WorkflowError(
        s"Switch Node (${prefix.mkString(".")}) does not support resume: ${from.mkString(".")}",
        None,
        None
      ).leftf
    } else {
      val paths: Seq[JsObject] = json.select("paths").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
      paths.zipWithIndex.find { case (o, idx) =>
        WorkflowOperator.processOperators(o.select("predicate").asValue, wfr, env).asOptBoolean.getOrElse(false)
      } match {
        case None              => JsNull.rightf
        case Some((path, idx)) => {
          if (env.isDev) println(s"running: ${prefix.mkString(".")}.${idx} - ${kind} / ${id}")
          val node = Node.from(path.select("node").asObject)
          node.internalRun(wfr, prefix :+ idx, from).recover { case t: Throwable =>
            WorkflowError(s"caught exception on task '${id}' at path: '${node.id}'", None, Some(t)).left
          }
        }
      }
    }
  }
}

case class IfThenElseNode(json: JsObject) extends Node {
  override def subNodes: Seq[NodeLike]                    = Seq(
    Seq(Node.from(json.select("then").asObject)),
    json.select("else").asOpt[JsObject].map(v => Node.from(v)).toSeq
  ).flatten
  override def documentationName: String                  = "if"
  override def documentationDisplayName: String           = "If then else"
  override def documentationIcon: String                  = "fas fa-question"
  override def documentationDescription: String           = "This executes a node if the predicate matches or another one if not"
  override def documentationInputSchema: Option[JsObject] = Node.baseInputSchema
    .deepMerge(
      Json.obj(
        "properties" -> Json.obj(
          "predicate" -> Json
            .obj("type" -> "boolean", "description" -> "The predicate defining if the path is run or not"),
          "then"      -> Json.obj("type" -> "object", "description" -> "The node run if the predicate matches"),
          "else"      -> Json.obj("type" -> "object", "description" -> "The node run if the predicate does not matches")
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
      wfr: WorkflowRun,
      prefix: Seq[Int],
      from: Seq[Int]
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val pass    = if (from.nonEmpty) {
      from.head == 0
    } else {
      WorkflowOperator.processOperators(json.select("predicate").asValue, wfr, env).asOptBoolean.getOrElse(false)
    }
    val newFrom = if (from.nonEmpty) {
      from.tail
    } else {
      from
    }
    if (pass) {
      if (env.isDev)
        println(s"${if (from.isEmpty) "running" else "resuming"}: ${prefix.mkString(".")} - ${kind} / ${id}")
      val node = Node.from(json.select("then").asObject)
      node.internalRun(wfr, prefix :+ 0, newFrom).recover { case t: Throwable =>
        WorkflowError(s"caught exception on task '${id}' at path: '${node.id}'", None, Some(t)).left
      }
    } else {
      json.select("else").asOpt[JsObject] match {
        case None           => JsNull.rightf
        case Some(nodeJson) => {
          if (env.isDev)
            println(s"${if (from.isEmpty) "running" else "resuming"}: ${prefix.mkString(".")} - ${kind} / ${id}")
          val node = Node.from(nodeJson)
          node.internalRun(wfr, prefix :+ 1, newFrom).recover { case t: Throwable =>
            WorkflowError(s"caught exception on task '${id}' at path: '${node.id}'", None, Some(t)).left
          }
        }
      }
    }
  }
}

case class ForEachNode(json: JsObject) extends Node {
  override def subNodes: Seq[NodeLike]                    = Seq(Node.from(json.select("node").asObject))
  override def documentationName: String                  = "foreach"
  override def documentationDisplayName: String           = "For each"
  override def documentationIcon: String                  = "fas fa-sync"
  override def documentationDescription: String           = "This node executes a node for each element in an array"
  override def documentationFormSchema: Option[JsObject]  = Json
    .obj(
      "values" -> Json.obj(
        "type"  -> "code",
        "label" -> "Values to iterate",
        "props" -> Json.obj(
          "editorOnly" -> true
        )
      )
    )
    .some
  override def documentationInputSchema: Option[JsObject] = Node.baseInputSchema
    .deepMerge(
      Json.obj(
        "values" -> Json.obj("type" -> "array", "description" -> "the values to iterate on"),
        "node"   -> Json.obj("type" -> "object", "description" -> "the node to execute for each element in an array")
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
      wfr: WorkflowRun,
      prefix: Seq[Int],
      from: Seq[Int]
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    if (from.nonEmpty) {
      WorkflowError(
        s"ForEach Node (${prefix.mkString(".")}) does not support resume: ${from.mkString(".")}",
        None,
        None
      ).leftf
    } else {
      val values         = WorkflowOperator.processOperators(json.select("values").asValue, wfr, env)
      val node           = Node.from(json.select("node").asObject)
      val iterableObject = values.asOpt[JsObject]
      val iterableArray  = values.asOpt[JsArray]
      if (iterableObject.isDefined) {
        if (env.isDev) println(s"running: ${prefix.mkString(".")} - ${kind} / ${id}")
        Source(iterableObject.get.value.toList.zipWithIndex)
          .mapAsync(1) { case (item, idx) =>
            val (key, value) = item
            wfr.memory.set("foreach_key", key.json)
            wfr.memory.set("foreach_value", value)
            node
              .internalRun(wfr, prefix :+ idx, from)
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
        if (env.isDev) println(s"running: ${prefix.mkString(".")} - ${kind} / ${id}")
        Source(iterableArray.get.value.toList.zipWithIndex)
          .mapAsync(1) { case (item, idx) =>
            wfr.memory.set("foreach_value", item)
            node
              .internalRun(wfr, prefix :+ idx, from)
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
}

case class MapNode(json: JsObject) extends Node {
  override def subNodes: Seq[NodeLike]                    = Seq(Node.from(json.select("node").asObject))
  override def documentationName: String                  = "map"
  override def documentationDisplayName: String           = "Map"
  override def documentationIcon: String                  = "fas fa-map"
  override def documentationDescription: String           = "This node transforms an array by applying a node on each value"
  override def documentationFormSchema: Option[JsObject]  = Json
    .obj(
      "values"      -> Json.obj(
        "type"  -> "code",
        "label" -> "Values to iterate",
        "props" -> Json.obj(
          "editorOnly" -> true
        )
      ),
      "destination" -> Json.obj(
        "type"  -> "string",
        "label" -> "Destination"
      )
    )
    .some
  override def documentationInputSchema: Option[JsObject] = Node.baseInputSchema
    .deepMerge(
      Json.obj(
        "values" -> Json.obj("type" -> "array", "description" -> "the values to iterate on"),
        "node"   -> Json.obj("type" -> "object", "description" -> "the node to execute for each element in an array")
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
      wfr: WorkflowRun,
      prefix: Seq[Int],
      from: Seq[Int]
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    if (from.nonEmpty) {
      WorkflowError(
        s"Map Node (${prefix.mkString(".")}) does not support resume: ${from.mkString(".")}",
        None,
        None
      ).leftf
    } else {
      val values = WorkflowOperator.processOperators(json.select("values").asValue, wfr, env)
      val node   = Node.from(json.select("node").asObject)
      values match {
        case arr: JsArray => {
          if (env.isDev) println(s"running: ${prefix.mkString(".")} - ${kind} / ${id}")
          Source(arr.value.toList.zipWithIndex)
            .mapAsync(1) { case (item, idx) =>
              wfr.memory.set("foreach_value", item)
              node
                .internalRun(wfr, prefix :+ idx, from)
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
}

case class FlatMapNode(json: JsObject) extends Node {
  override def subNodes: Seq[NodeLike]                    = Seq(Node.from(json.select("node").asObject))
  override def documentationName: String                  = "flatmap"
  override def documentationDisplayName: String           = "Flatmap"
  override def documentationIcon: String                  = "fas fa-layer-group"
  override def documentationDescription: String           = "This node transforms an array by applying a node on each value"
  override def documentationFormSchema: Option[JsObject]  = Json
    .obj(
      "values"      -> Json.obj(
        "type"  -> "code",
        "label" -> "Values to iterate",
        "props" -> Json.obj(
          "editorOnly" -> true
        )
      ),
      "destination" -> Json.obj(
        "type"  -> "string",
        "label" -> "Destination"
      )
    )
    .some
  override def documentationInputSchema: Option[JsObject] = Node.baseInputSchema
    .deepMerge(
      Json.obj(
        "values" -> Json.obj("type" -> "array", "description" -> "the values to iterate on"),
        "node"   -> Json.obj(
          "type"        -> "object",
          "description" -> "the node to execute for each element in an array, should return an array to flatten it"
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
      wfr: WorkflowRun,
      prefix: Seq[Int],
      from: Seq[Int]
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    if (from.nonEmpty) {
      WorkflowError(
        s"FlatMap Node (${prefix.mkString(".")}) does not support resume: ${from.mkString(".")}",
        None,
        None
      ).leftf
    } else {
      val values = WorkflowOperator.processOperators(json.select("values").asValue, wfr, env)
      val node   = Node.from(json.select("node").asObject)
      values match {
        case arr: JsArray => {
          if (env.isDev) println(s"running: ${prefix.mkString(".")} - ${kind} / ${id}")
          Source(arr.value.toList.zipWithIndex)
            .mapAsync(1) { case (item, idx) =>
              wfr.memory.set("foreach_value", item)
              node
                .internalRun(wfr, prefix :+ idx, from)
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
}

case class FilterNode(json: JsObject) extends Node {
  override def subNodes: Seq[NodeLike]                    = Seq(Node.from(json.select("predicate").asObject))
  override def documentationName: String                  = "filter"
  override def documentationDisplayName: String           = "Filter"
  override def documentationIcon: String                  = "fas fa-filter"
  override def documentationDescription: String           =
    "This node transforms an array by filtering values based on a node execution"
  override def documentationFormSchema: Option[JsObject]  = Json
    .obj(
      "values"      -> Json.obj(
        "type"  -> "code",
        "label" -> "Values to iterate",
        "props" -> Json.obj(
          "editorOnly" -> true
        )
      ),
      "not"         -> Json.obj(
        "type"  -> "bool",
        "label" -> "Not"
      ),
      "destination" -> Json.obj(
        "type"  -> "string",
        "label" -> "Destination"
      )
    )
    .some
  override def documentationInputSchema: Option[JsObject] = Node.baseInputSchema
    .deepMerge(
      Json.obj(
        "values"    -> Json.obj("type" -> "array", "description" -> "the values to iterate on"),
        "predicate" -> Json
          .obj("type" -> "object", "description" -> "the node to execute for each element in an array")
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
      wfr: WorkflowRun,
      prefix: Seq[Int],
      from: Seq[Int]
  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    if (from.nonEmpty) {
      WorkflowError(
        s"Filter Node (${prefix.mkString(".")}) does not support resume: ${from.mkString(".")}",
        None,
        None
      ).leftf
    } else {
      val values = WorkflowOperator.processOperators(json.select("values").asValue, wfr, env)
      val node   = Node.from(json.select("predicate").asObject)
      val not    = json.select("not").asOptBoolean.getOrElse(false)
      values match {
        case arr: JsArray => {
          if (env.isDev) println(s"running: ${prefix.mkString(".")} - ${kind} / ${id}")
          Source(arr.value.toList.zipWithIndex)
            .mapAsync(1) { case (item, idx) =>
              wfr.memory.set("foreach_value", item)
              node
                .internalRun(wfr, prefix :+ idx, from)
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
}
