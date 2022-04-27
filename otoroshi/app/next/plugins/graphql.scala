package otoroshi.next.plugins

import akka.stream.Materializer
import com.arakelian.jq.{ImmutableJqLibrary, ImmutableJqRequest}
import otoroshi.el.GlobalExpressionLanguage
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import scala.concurrent.duration.{DurationInt, DurationLong}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util._

case class GraphQLCallerConfig(
                    url: String,
                    headers: Map[String, String] = Map.empty,
                    method: String = "POST",
                    timeout: Long = 60000L,
                    query: String = "{\n\n}",
                    responsePath: Option[String] = None,
                    responseFilter: Option[String] = None
) extends NgPluginConfig {
  def json: JsValue = GraphQLCallerConfig.format.writes(this)
}

object GraphQLCallerConfig {
  val format = new Format[GraphQLCallerConfig] {
    override def reads(json: JsValue): JsResult[GraphQLCallerConfig] = Try {
      GraphQLCallerConfig(
        url = json.select("url").asString,
        headers = json.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty),
        method = json.select("method").asOpt[String].getOrElse("POST"),
        timeout = json.select("timeout").asOpt[Long].getOrElse(60000L),
        query = json.select("query").asOpt[String].getOrElse("{\n\n}"),
        responsePath = json.select("response_path").asOpt[String],
        responseFilter = json.select("response_filter").asOpt[String],
      )
    }  match {
      case Failure(ex)    => JsError(ex.getMessage())
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: GraphQLCallerConfig): JsValue = Json.obj(
      "url" -> o.url,
      "headers" -> o.headers,
      "method" -> o.method,
      "query" -> o.query,
      "timeout" -> o.timeout,
      "response_path" -> o.responsePath.map(JsString.apply).getOrElse(JsNull).asValue,
      "response_filter" -> o.responsePath.map(JsString.apply).getOrElse(JsNull).asValue,
    )
  }
}

class GraphQLCaller extends NgRequestTransformer {

  private val library = ImmutableJqLibrary.of()

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "GraphQL Query"
  override def description: Option[String]                 = "This plugin can be used to call GraphQL query endpoints and expose it as a REST endpoint".some
  override def defaultConfigObject: Option[NgPluginConfig] = GraphQLCallerConfig(url = "https://some.graphql/endpoint").some

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Integrations)
  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)

  override def transformsResponse: Boolean = false
  override def transformsError: Boolean    = false
  override def isTransformRequestAsync: Boolean = true

  def applyJq(payload: JsValue, filter: String): Either[JsValue, JsValue] = {
    val request  = ImmutableJqRequest
      .builder()
      .lib(library)
      .input(payload.stringify)
      .filter(filter)
      .build()
    val response = request.execute()
    if (response.hasErrors) {
      val errors = JsArray(response.getErrors.asScala.map(err => JsString(err)))
      Json.obj("error" -> "error while transforming response body", "details" -> errors).left
    } else {
      val rawBody = response.getOutput.byteString
      Json.parse(rawBody.utf8String).right
    }
  }

  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx.cachedConfig(internalName)(GraphQLCallerConfig.format).getOrElse(GraphQLCallerConfig(url = "https://some.graphql/endpoint"))
    val query = GlobalExpressionLanguage.apply(
      value = config.query,
      req = ctx.request.some,
      service = ctx.route.legacy.some,
      apiKey = ctx.apikey,
      user = ctx.user,
      context = ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
      attrs = ctx.attrs,
      env = env
    )
    env.Ws.url(config.url)
      .withRequestTimeout(config.timeout.millis)
      .withMethod(config.method)
      .withHttpHeaders(config.headers.toSeq: _*)
      .withBody(Json.obj("query" -> query, "variables" -> JsNull))
      .execute()
      .map { resp =>
        if (resp.status == 200) {
          val partialBody = resp.json.atPath(config.responsePath.getOrElse("$")).asOpt[JsValue].getOrElse(JsNull)
          config.responseFilter match {
            case None => Left(Results.Status(200)(partialBody).as("application/json"))
            case Some(filter) => applyJq(partialBody, filter) match {
              case Left(error) => Left(Results.Status(500)(error).as("application/json"))
              case Right(resp) => Left(Results.Status(200)(resp).as("application/json"))
            }
          }
        } else {
          Left(Results.Status(resp.status)(resp.body).as(resp.contentType))
        }
      }
  }
}
