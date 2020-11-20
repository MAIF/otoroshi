package otoroshi.utils.workflow

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import env.Env
import otoroshi.plugins.JsonPathUtils
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import utils.ReplaceAllWith
import utils.http.MtlsConfig

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

sealed trait WorkFlowSpec {
  def name: String
  def description: String
  def tasks: Seq[WorkFlowTask]
}
object WorkFlowSpec {
  def inline(spec: JsValue): WorkFlowSpec = InlineWorkFlowSpec(spec)
  case class InlineWorkFlowSpec(spec: JsValue) extends WorkFlowSpec {
    lazy val name: String = spec.select("name").asString
    lazy val description: String = spec.select("description").asString
    lazy val tasks: Seq[WorkFlowTask] = spec.select("tasks")
      .asOpt[JsArray]
      .map(_.value.map(WorkFlowTask.format.reads)
        .collect {
          case JsSuccess(value, _) => value
        }
      ).getOrElse(Seq.empty)
  }
}
sealed trait WorkFlowRequest {
  def input: JsValue
}
object WorkFlowRequest {
  def inline(spec: JsValue): WorkFlowRequest = InlineWorkFlowRequest(spec)
  case class InlineWorkFlowRequest(input: JsValue) extends WorkFlowRequest
}
case class WorkFlowResponse(success: Boolean, ctx: WorkFlowTaskContext, results: Seq[WorkFlowResult]) {
  def json: JsValue = Json.obj(
    "success" -> success,
    "ctx" -> ctx.json,
    "results" -> JsArray(results.map(_.json))
  )
}

sealed trait WorkFlowTaskType

object WorkFlowTaskType {
  case object HTTP extends WorkFlowTaskType
  case object ComposeResponse extends WorkFlowTaskType
}

sealed trait WorkFlowResult {
  def json: JsValue
}

object WorkFlowResult {
  case class WorkFlowSuccess(task: WorkFlowTask) extends WorkFlowResult {
    def json: JsValue = Json.obj("success" -> true, "name" -> task.name)
  }
  case class WorkFlowFailure(task: WorkFlowTask, t: Throwable) extends WorkFlowResult {
    def json: JsValue = Json.obj("success" -> false, "name" -> task.name, "error" -> t.getMessage)
  }
}

object WorkFlowOperator {
  def isOperator(value: JsValue): Boolean = value.asOpt[JsObject].exists(_.value.keySet.exists(_.startsWith("$")))
  def apply(spec: JsValue, ctx: WorkFlowTaskContext): JsValue = {
    val obj = spec.asOpt[JsObject].getOrElse(Json.obj())
    obj.value.head match {
      case ("$path", JsString(path)) =>
        JsonPathUtils.getAtPolyJson(ctx.json, path).getOrElse(JsNull)
      case ("$path", v@JsObject(_)) =>
        JsonPathUtils.getAtPolyJson(ctx.json, v.select("at").as[String]).getOrElse(JsNull)
      case _ =>
        spec
    }
  }
}

trait WorkFlowTask {
  def name: String
  def theType: WorkFlowTaskType
  def json: JsValue
  def run(ctx: WorkFlowTaskContext)(implicit ec: ExecutionContext, mat: Materializer, env: Env): Future[WorkFlowResult]
  def withPredicate(spec: JsValue, ctx: WorkFlowTaskContext)(f: => Future[WorkFlowResult])(implicit ec: ExecutionContext, mat: Materializer, env: Env): Future[WorkFlowResult] = {
    lazy val predicate = WorkFlowPredicate(spec.select("predicate").asOpt[JsObject].getOrElse(Json.obj()))
    if (predicate.check(ctx.json)) {
      f
    } else {
      ctx.responses.put(name, Json.obj("success" -> false))
      WorkFlowResult.WorkFlowFailure(this, new RuntimeException(s"initial predicate check fail")).future
    }
  }
  def applyEl(value: String, ctx: WorkFlowTaskContext, env: Env): String = {
    WorkFlowEl(value, ctx, env)
  }

  def applyTransformation(value: JsValue, ctx: WorkFlowTaskContext, env: Env): JsValue = {

    def isOperator(jsObject: JsObject) = WorkFlowOperator.isOperator(jsObject)

    def transform(what: JsValue): JsValue = what match {
      case JsString(value)                => JsString(applyEl(value, ctx, env))
      case v@JsNumber(_)                  => v
      case v@JsBoolean(_)                 => v
      case JsNull                         => JsNull
      case v@JsObject(_) if isOperator(v) => transform(WorkFlowOperator.apply(v, ctx))
      case JsObject(values)               => JsObject(values.map {
        case (key, value) => (key, transform(value))
      })
      case JsArray(values)                => JsArray(values.map(transform))
    }

    transform(value)
  }
}

object WorkFlowTask {
  val format = new Format[WorkFlowTask] {
    override def reads(json: JsValue): JsResult[WorkFlowTask] = json.select("type").asString match {
      case "http" => HttpWorkFlowTask.format.reads(json)
      case "compose-response" => ComposeResponseWorkFlowTask.format.reads(json)
      case v => JsError(s"$v is not a valid task type")
    }
    override def writes(o: WorkFlowTask): JsValue = o.json
  }
}

object WorkFlowEl {

  import kaleidoscope._

  import collection.JavaConverters._

  val logger = Logger("workflow-el")
  val expressionReplacer = ReplaceAllWith("\\$\\{([^}]*)\\}")

  def apply(value: String, ctx: WorkFlowTaskContext, env: Env): String = {
    value match {
      case v if v.contains("${") => {
        Try {
          expressionReplacer.replaceOn(value) {

            case r"input.$path@(.*)" => JsonPathUtils.getAtPolyJsonStr(ctx.input, path)
            case r"cache.$field@(.*)\[$path@(.*)\]" => ctx.cache.get(field).map(f => JsonPathUtils.getAtPolyJsonStr(f, path)).getOrElse("null")
            case r"responses.$field@(.*)\[$path@(.*)\]" => ctx.responses.get(field).map(f => JsonPathUtils.getAtPolyJsonStr(f, path)).getOrElse("null")

            case r"file://$path@(.*)" => Try(Files.readAllLines(new File(path).toPath).asScala.mkString("\n").trim()).getOrElse("null")
            case r"file:$path@(.*)" => Try(Files.readAllLines(new File(path).toPath).asScala.mkString("\n").trim()).getOrElse("null")
            case r"env.$field@(.*):$dv@(.*)" => Option(System.getenv(field)).getOrElse(dv)
            case r"env.$field@(.*)"          => Option(System.getenv(field)).getOrElse(s"no-env-var-$field")

            case r"config.$field@(.*):$dv@(.*)" =>
              env.configuration
                .getOptionalWithFileSupport[String](field)
                .orElse(
                  env.configuration.getOptionalWithFileSupport[Int](field).map(_.toString)
                )
                .orElse(
                  env.configuration.getOptionalWithFileSupport[Double](field).map(_.toString)
                )
                .orElse(
                  env.configuration.getOptionalWithFileSupport[Long](field).map(_.toString)
                )
                .orElse(
                  env.configuration.getOptionalWithFileSupport[Boolean](field).map(_.toString)
                )
                .getOrElse(dv)
            case r"config.$field@(.*)" =>
              env.configuration
                .getOptionalWithFileSupport[String](field)
                .orElse(
                  env.configuration.getOptionalWithFileSupport[Int](field).map(_.toString)
                )
                .orElse(
                  env.configuration.getOptionalWithFileSupport[Double](field).map(_.toString)
                )
                .orElse(
                  env.configuration.getOptionalWithFileSupport[Long](field).map(_.toString)
                )
                .orElse(
                  env.configuration.getOptionalWithFileSupport[Boolean](field).map(_.toString)
                )
                .getOrElse(s"no-config-$field")
          }
        }  recover {
          case e =>
            logger.error(s"Error while parsing expression, returning raw value: $value", e)
            value
        } get
      }
      case _ => value
    }
  }
}

case class WorkFlowTaskContext(input: JsValue, cache: TrieMap[String, JsValue], responses: TrieMap[String, JsValue], response: AtomicReference[JsValue]) {
  def json: JsValue = Json.obj(
    "input" -> input,
    "cache" -> JsObject(cache),
    "responses" -> JsObject(responses),
    "response" -> Option(response.get()).getOrElse(Json.obj()).as[JsValue]
  )
}

object WorkFlow {
  def apply(spec: WorkFlowSpec): WorkFlow = new WorkFlow(spec)
}

class WorkFlow(spec: WorkFlowSpec) {

  lazy val logger = Logger(s"workflow-$name")
  lazy val name: String = spec.name
  lazy val description: String = spec.description

  def run(input: WorkFlowRequest)(implicit ec: ExecutionContext, mat: Materializer, env: Env): Future[WorkFlowResponse] = {
    val ctx = WorkFlowTaskContext(
      input.input,
      new TrieMap[String, JsValue](),
      new TrieMap[String, JsValue](),
      new AtomicReference[JsValue](Json.obj(
        "status" -> 200,
        "headers" -> Json.obj(
          "Content-Type" -> "application/json"
        ),
        "body" -> Json.obj()
      ))
    )
    logger.info("running workflow")
    Source(spec.tasks.toList)
      .mapAsync(1) { task =>
        logger.info(s"running task '${task.name}'")
        task.run(ctx).recover {
          case e => WorkFlowResult.WorkFlowFailure(task, e)
        }.andThen {
          case Failure(e) => logger.info(s"task '${task.name}' completed with failure: ${e.getMessage}")
          case Success(WorkFlowResult.WorkFlowFailure(t, e)) => logger.info(s"task '${task.name}' completed with failure: ${e.getMessage}")
          case Success(WorkFlowResult.WorkFlowSuccess(t)) => logger.info(s"task '${task.name}' completed with success")
        }
      }.takeWhile({
        case WorkFlowResult.WorkFlowSuccess(_) => true
        case WorkFlowResult.WorkFlowFailure(_, _) => false
      }, true)
      .runWith(Sink.seq[WorkFlowResult])
      .map { results =>
        val success = !(results.exists {
          case WorkFlowResult.WorkFlowSuccess(_) => false
          case WorkFlowResult.WorkFlowFailure(_, _) => true
        })
        if (success) {
          logger.info("workflow finished with success")
        } else {
          logger.info("workflow finished with failure")
        }
        WorkFlowResponse(success, ctx, results)
      }
  }
}

case class WorkFlowPredicatePart(spec: JsValue) {
  def value(payload: JsValue): JsValue = {
    spec match {
      case JsObject(values) if values.keySet.contains("$path") => {
        val path = values.get("$path").get.as[String]
        val respath = values.get("$resultPath").map(_.as[String])
        val append = values.get("$append").map(_.as[String])
        val prepend = values.get("$prepend").map(_.as[String])
        val res = JsonPathUtils.getAtPolyJson(payload, path).getOrElse(JsNull)
        val res2 = respath.map(r => JsonPathUtils.getAtPolyJson(res, r).getOrElse(JsNull)).getOrElse(res)
        res2.asOpt[String].map(s => JsString(s"${prepend.getOrElse("")}${s}${append.getOrElse("")}")).getOrElse(res2)
      }
      case _ => payload
    }
  }
}

case class WorkFlowPredicateOperator(operator: String) {
  def apply(left: WorkFlowPredicatePart, right: WorkFlowPredicatePart, payload: JsValue): Boolean = operator match {
    case "equals" => left.value(payload) == right.value(payload)
    case "not-equals" => left.value(payload) != right.value(payload)
    case _ => throw new RuntimeException(s"operator $operator not supported yet")
  }
}

case class WorkFlowPredicate(spec: JsValue) {

  lazy val left = WorkFlowPredicatePart(spec.select("left").as[JsValue])
  lazy val right = WorkFlowPredicatePart(spec.select("right").as[JsValue])
  lazy val operator = WorkFlowPredicateOperator(spec.select("operator").as[String])

  def check(payload: JsValue): Boolean = {
    if (spec.asOpt[JsObject].getOrElse(Json.obj()).value.isEmpty) {
      true
    } else {
      operator.apply(left, right, payload)
    }
  }
}

object ComposeResponseWorkFlowTask {
  val format = new Format[ComposeResponseWorkFlowTask] {
    override def reads(json: JsValue): JsResult[ComposeResponseWorkFlowTask] = Try {
      ComposeResponseWorkFlowTask(json)
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(value) => JsSuccess(value)
    }
    override def writes(o: ComposeResponseWorkFlowTask): JsValue = ???
  }
}

case class ComposeResponseWorkFlowTask(spec: JsValue) extends WorkFlowTask {

  lazy val name = spec.select("name").as[String]
  override def theType: WorkFlowTaskType = WorkFlowTaskType.ComposeResponse
  override def json: JsValue = ComposeResponseWorkFlowTask.format.writes(this)

  override def run(ctx: WorkFlowTaskContext)(implicit ec: ExecutionContext, mat: Materializer, env: Env): Future[WorkFlowResult] = {
    withPredicate(spec, ctx) {
      val response = applyTransformation(spec.select("response").as[JsValue], ctx, env)
      //val responseStr = applyEl(response.stringify, ctx, env)
      //ctx.response.set(Json.parse(responseStr))
      ctx.response.set(response)
      WorkFlowResult.WorkFlowSuccess(this).future
    }
  }
}

object HttpWorkFlowTask {
  val format = new Format[HttpWorkFlowTask] {
    override def reads(json: JsValue): JsResult[HttpWorkFlowTask] = Try {
      HttpWorkFlowTask(json)
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(value) => JsSuccess(value)
    }
    override def writes(o: HttpWorkFlowTask): JsValue = ???
  }
}

case class HttpWorkFlowTask(spec: JsValue) extends WorkFlowTask {

  override def theType: WorkFlowTaskType = WorkFlowTaskType.HTTP
  override def json: JsValue = HttpWorkFlowTask.format.writes(this)

  lazy val name                         = spec.select("name").as[String]
  lazy val requestSpec                  = spec.select("request").as[JsObject]
  lazy val method: String               = requestSpec.select("method").asOpt[String].map(_.toUpperCase()).getOrElse("GET")
  def url(ctx: WorkFlowTaskContext, env: Env): String = applyEl(applyTransformation(requestSpec.select("url").as[JsValue], ctx, env).as[String], ctx, env)
  lazy val timeout: FiniteDuration      = requestSpec.select("timeout").asOpt[Long].getOrElse(10000L).millis
  def headers(ctx: WorkFlowTaskContext, env: Env): Map[String, String] = requestSpec.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty).mapValues(v => applyEl(v, ctx, env))
  lazy val tls: MtlsConfig              = requestSpec.select("tls").asOpt(MtlsConfig.format).getOrElse(MtlsConfig())
  def bodyOpt(ctx: WorkFlowTaskContext, env: Env): Option[ByteString]  = requestSpec.select("body").asOpt[JsValue].map { body =>
    val finalBody = applyTransformation(body, ctx, env)
    finalBody match {
      case JsString(value) => ByteString(applyEl(value, ctx, env))
      case JsNumber(value) => ByteString(value.toString())
      case JsBoolean(value) => ByteString(value.toString)
      case _ => ByteString(applyEl(Json.stringify(body), ctx, env))
    }
  }
  lazy val successSpec                  = spec.select("success").as[JsObject]
  lazy val successStatuses              = successSpec.select("statuses").asOpt[JsArray].map(_.value.map(_.asInt)).getOrElse(Seq(200))
  lazy val successPredicate             = WorkFlowPredicate(successSpec.select("predicate").asOpt[JsObject].getOrElse(Json.obj()))

  override def run(ctx: WorkFlowTaskContext)(implicit ec: ExecutionContext, mat: Materializer, env: Env): Future[WorkFlowResult] = {
    withPredicate(spec, ctx) {
      val finalUrl = url(ctx, env)
      val req = env.MtlsWs.url(finalUrl, tls) // TODO: handle service-id
        .withRequestTimeout(timeout) // TODO: handle apikey
        .withHttpHeaders(headers(ctx, env).toSeq: _*)
        .withMethod(method)
      val reqWithBody = bodyOpt(ctx, env).map(b => req.withBody(b)).getOrElse(req)
      reqWithBody
        .execute()
        .map { response =>
          val bodyTxt: String = response.body
          val body: JsValue = if (response.contentType.contains("application/json")) Json.parse(bodyTxt) else JsString(bodyTxt)
          ctx.responses.put(name, Json.obj(
            "status" -> response.status,
            "statusTxt" -> response.statusText,
            "headers" -> response.headers.map {
              case (key, value) if value.size == 1 => Json.obj(key -> value.headOption.map(JsString.apply))
              case (key, value) if value.size > 1 => Json.obj(key -> JsArray(value.map(JsString.apply)))
            }.foldLeft(Json.obj())(_ ++ _),
            "bodyTxt" -> bodyTxt,
            "body" -> body
          ))
          if (successPredicate.check(ctx.json)) {
            if (successStatuses.isEmpty) {
              ctx.responses.put(name, ctx.responses.get(name).get.as[JsObject] ++ Json.obj("success" -> true))
              WorkFlowResult.WorkFlowSuccess(this)
            } else if (successStatuses.contains(response.status)) {
              ctx.responses.put(name, ctx.responses.get(name).get.as[JsObject] ++ Json.obj("success" -> true))
              WorkFlowResult.WorkFlowSuccess(this)
            } else {
              ctx.responses.put(name, ctx.responses.get(name).get.as[JsObject] ++ Json.obj("success" -> false))
              WorkFlowResult.WorkFlowFailure(this, new RuntimeException(s"bad status ${response.status}"))
            }
          } else {
            ctx.responses.put(name, ctx.responses.get(name).get.as[JsObject] ++ Json.obj("success" -> false))
            WorkFlowResult.WorkFlowFailure(this, new RuntimeException(s"success predicate check fail"))
          }
        }
    }
  }
}
