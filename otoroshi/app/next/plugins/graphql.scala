package otoroshi.next.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.arakelian.jq.{ImmutableJqLibrary, ImmutableJqRequest}
import otoroshi.el.GlobalExpressionLanguage
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import sangria.ast.{DirectiveLocation, Document, FieldDefinition, ListType, NamedType, NotNullType, ObjectValue, StringValue}
import sangria.execution.{ExceptionHandler, Executor, HandledException}
import sangria.macros.LiteralGraphQLStringContext
import sangria.parser.QueryParser
import sangria.schema.{Action, AnyFieldResolver, Argument, AstDirectiveContext, AstSchemaBuilder, Directive, DirectiveResolver, FieldResolver, InstanceCheck, IntType, OptionInputType, OptionType, ResolverBasedAstSchemaBuilder, ScalarType, Schema, StringType}
import sangria.marshalling.playJson._
import sangria.renderer.SchemaRenderer

import scala.concurrent.duration.{Duration, DurationLong, FiniteDuration, MILLISECONDS}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util._
import sangria.validation.ValueCoercionViolation

case class GraphQLQueryConfig(
    url: String,
    headers: Map[String, String] = Map.empty,
    method: String = "POST",
    timeout: Long = 60000L,
    query: String = "{\n\n}",
    responsePath: Option[String] = None,
    responseFilter: Option[String] = None
) extends NgPluginConfig {
  def json: JsValue = GraphQLQueryConfig.format.writes(this)
}

object GraphQLQueryConfig {
  val format = new Format[GraphQLQueryConfig] {
    override def reads(json: JsValue): JsResult[GraphQLQueryConfig] = Try {
      GraphQLQueryConfig(
        url = json.select("url").asString,
        headers = json.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty),
        method = json.select("method").asOpt[String].getOrElse("POST"),
        timeout = json.select("timeout").asOpt[Long].getOrElse(60000L),
        query = json.select("query").asOpt[String].getOrElse("{\n\n}"),
        responsePath = json.select("response_path").asOpt[String],
        responseFilter = json.select("response_filter").asOpt[String]
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage())
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: GraphQLQueryConfig): JsValue = Json.obj(
      "url"             -> o.url,
      "headers"         -> o.headers,
      "method"          -> o.method,
      "query"           -> o.query,
      "timeout"         -> o.timeout,
      "response_path"   -> o.responsePath.map(JsString.apply).getOrElse(JsNull).asValue,
      "response_filter" -> o.responsePath.map(JsString.apply).getOrElse(JsNull).asValue
    )
  }
}

class GraphQLQuery extends NgBackendCall {

  private val library = ImmutableJqLibrary.of()

  override def useDelegates: Boolean                       = false
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = false
  override def name: String                                = "GraphQL Query to REST"
  override def description: Option[String]                 =
    "This plugin can be used to call GraphQL query endpoints and expose it as a REST endpoint".some
  override def defaultConfigObject: Option[NgPluginConfig] =
    GraphQLQueryConfig(url = "https://some.graphql/endpoint").some

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Integrations)
  override def steps: Seq[NgStep]                = Seq(NgStep.CallBackend)

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

  override def callBackend(
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx
      .cachedConfig(internalName)(GraphQLQueryConfig.format)
      .getOrElse(GraphQLQueryConfig(url = "https://some.graphql/endpoint"))
    val query  = GlobalExpressionLanguage.apply(
      value = config.query,
      req = ctx.rawRequest.some,
      service = ctx.route.legacy.some,
      apiKey = ctx.apikey,
      user = ctx.user,
      context = ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
      attrs = ctx.attrs,
      env = env
    )
    env.Ws
      .url(config.url)
      .withRequestTimeout(config.timeout.millis)
      .withMethod(config.method)
      .withHttpHeaders(config.headers.toSeq: _*)
      .withBody(Json.obj("query" -> query, "variables" -> JsNull))
      .execute()
      .map { resp =>
        if (resp.status == 200) {
          val partialBody = resp.json.atPath(config.responsePath.getOrElse("$")).asOpt[JsValue].getOrElse(JsNull)
          config.responseFilter match {
            case None         =>
              bodyResponse(
                200,
                Map("Content-Type" -> "application/json"),
                Source.single(partialBody.stringify.byteString)
              )
            case Some(filter) =>
              applyJq(partialBody, filter) match {
                case Left(error) =>
                  bodyResponse(
                    500,
                    Map("Content-Type" -> "application/json"),
                    Source.single(error.stringify.byteString)
                  )
                case Right(resp) =>
                  bodyResponse(200, Map("Content-Type" -> "application/json"), Source.single(resp.stringify.byteString))
              }
          }
        } else {
          bodyResponse(resp.status, Map("Content-Type" -> resp.contentType), resp.bodyAsSource)
        }
      }
  }
}


case class GraphQLBackendConfig(
                               schema: String,
                               initialData: Option[JsValue] = None
                             ) extends NgPluginConfig {
  def json: JsValue = GraphQLBackendConfig.format.writes(this)
}

object GraphQLBackendConfig {
  val format = new Format[GraphQLBackendConfig] {
    override def reads(json: JsValue): JsResult[GraphQLBackendConfig] = Try {
      GraphQLBackendConfig(
        schema = json.select("schema").as[String],
        initialData = json.select("initialData").asOpt[JsObject]
      )
    }  match {
      case Failure(ex)    => JsError(ex.getMessage())
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: GraphQLBackendConfig): JsValue = Json.obj(
      "schema" -> o.schema,
      "initialData" -> o.initialData.getOrElse(JsNull).as[JsValue]
    )
  }
}


class GraphQLBackend extends NgBackendCall {

  private val DEFAULT_GRAPHQL_SCHEMA = """
   type User {
     name: String!
     firstname: String!
   }
   schema {
    query: Query
   }

   type Query {
    users: [User] @json(data: "[{ \"firstname\": \"Foo\", \"name\": \"Bar\" }, { \"firstname\": \"Bar\", \"name\": \"Foo\" }]")
   }
  """.stripMargin

  override def useDelegates: Boolean                       = false
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = false
  override def name: String                                = "GraphQL Backend"
  override def description: Option[String]                 = "This plugin can be used to create a GraphQL schema".some
  override def defaultConfigObject: Option[NgPluginConfig] = GraphQLBackendConfig(
    schema = DEFAULT_GRAPHQL_SCHEMA
  ).some

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Integrations)
  override def steps: Seq[NgStep]                = Seq(NgStep.CallBackend)

  case object MapCoercionViolation extends ValueCoercionViolation("Map value can't be parsed")
  case object JsonCoercionViolation extends ValueCoercionViolation("Not valid JSON")

  val MapType = ScalarType[Map[String, String]]("Map",
    coerceOutput = (data, _) =>
      //Json.stringify(
    //  JsObject(data.view.mapValues(JsString.apply).toSeq)
      Json.toJson(data.view).stringify,
    // ),
    coerceUserInput = e => e.asInstanceOf[Map[String, String]] match {
      case r: Map[String, String] => Right(r)
      case _ => Left(MapCoercionViolation)
    },
    coerceInput = {
      case ObjectValue(fields, _, _) => Right(fields.map(f => (f.name, f.value.toString)).toMap)
      case _ => Left(MapCoercionViolation)
    })

  val exceptionHandler = ExceptionHandler(
    onException = {
      case (marshaller, throwable) => HandledException(throwable.getMessage)
    }
  )

  private def execute(astDocument: Document, query: String, builder: ResolverBasedAstSchemaBuilder[Unit], initialData: JsObject)
                     (implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    QueryParser.parse(query) match {
      case Failure(error) =>
        bodyResponse(200,
          Map("Content-Type" -> "application/json"),
          Source.single(Json.obj("error" -> error.getMessage).stringify.byteString)
        ).future
      case Success(queryAst) =>
        Executor.execute(
          schema = Schema.buildFromAst(astDocument, builder.validateSchemaWithException(astDocument)),
          queryAst = queryAst,
          root = initialData,
          exceptionHandler = exceptionHandler
        )
          .map(res => {
            println(res)
            val response = Json.toJson(res)

            bodyResponse(200,
              Map("Content-Type" -> "application/json"),
              Source.single(response.stringify.byteString)
            )
          })
    }
  }

  val directivesLocations = Set(
    sangria.schema.DirectiveLocation.FieldDefinition,
    sangria.schema.DirectiveLocation.Query,
    sangria.schema.DirectiveLocation.Object
  )

  val urlArg: Argument[String] = Argument("url", StringType)
  val methodArg: Argument[Option[String]] = Argument("method", OptionInputType(StringType))
  val headersArg: Argument[Option[Map[String, String]]] = Argument("headers", OptionInputType(MapType))
  val timeoutArg: Argument[Int] = Argument("timeout", IntType, defaultValue = 5000)
  val jsonDataArg: Argument[String] = Argument("data", StringType)
  val queryArg: Argument[Option[String]] = Argument("query", OptionInputType(StringType))
  val responsePathArg: Argument[Option[String]] = Argument("response_path", OptionInputType(StringType))
  val responseFilterArg   = Argument("response_filter", OptionInputType(StringType))
  val arguments     = urlArg :: methodArg :: timeoutArg :: headersArg :: queryArg :: responsePathArg :: responseFilterArg :: Nil

  def httpRestDirectiveResolver(c: AstDirectiveContext[Unit])(implicit env: Env, ec: ExecutionContext): Action[Unit, Any] = {
    val queryArgs = c.ctx.args.raw.map {
      case (str, Some(v)) =>(str, String.valueOf(v))
      case (k, v) => (k, String.valueOf(v))
    }
    val url = queryArgs.foldLeft(c.arg(urlArg))((u, value) => GlobalExpressionLanguage.expressionReplacer.replaceOn(u) {
      case value._1 => value._2
      case v => v
    })

    env.Ws.url(url)
      .withRequestTimeout(FiniteDuration(c.arg(timeoutArg), MILLISECONDS))
      .withMethod(c.arg(methodArg).getOrElse("GET"))
      .withHttpHeaders(c.argOpt(headersArg).getOrElse(Map.empty[String, String]).asInstanceOf[Map[String, String]].toSeq:_*)
      .execute()
      .map { resp =>
        if (resp.status == 200) {
          resp.json match {
            case JsArray(value) => value
            case v => v
          }
        } else {
          c.ctx.field.toAst.fieldType match {
            case ListType(_, _) => Seq.empty
            case _ => JsObject.empty
          }
        }
      }
  }

  def jsonDirectiveResolver(c: AstDirectiveContext[Unit]): Action[Unit, Any] = {
    c.ctx.field.toAst.fieldType match {
      case ListType(_, _) => Json.parse(c.arg(jsonDataArg)).as[JsArray].value
      case _ => Json.parse(c.arg(jsonDataArg))
    }
  }

  case class GraphlCallException(message: String) extends Exception(message)

  def graphQLDirectiveResolver(c: AstDirectiveContext[Unit], query: String, ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])
                              (implicit env: Env, ec: ExecutionContext, mat: Materializer): Action[Unit, Any] = {
    val queryArgs = c.ctx.args.raw.map {
      case (str, Some(v)) => (str, String.valueOf(v))
      case (k, v) => (k, String.valueOf(v))
    }
    val url = queryArgs.foldLeft(c.arg(urlArg))((u, value) => GlobalExpressionLanguage.expressionReplacer.replaceOn(u) {
      case value._1 => value._2
      case v => v
    })

    val graphqlQuery = env.scriptManager.getAnyScript[GraphQLQuery](s"cp:${classOf[GraphQLQuery].getName}").right.get
    graphqlQuery.callBackend(ctx.copy(
      config = Json.obj(
        "url" -> url,
        "headers" -> c.argOpt(headersArg).getOrElse(Map.empty[String, String]).asInstanceOf[Map[String, String]],
        "method" -> JsString(c.arg(methodArg).getOrElse("POST")),
        "timeout" -> JsNumber(c.argOpt(timeoutArg).getOrElse(60000).toInt),
        "query" -> query,
        "responsePath" -> c.argOpt(responsePathArg),
        "responseFilter" -> c.argOpt(responseFilterArg),
      )
    ), delegates)
      .flatMap {
        case Left(_) => GraphlCallException("Something happens when calling GraphQL directive").future
        case Right(value) => bodyToJson(value.response.body).map(body => {
          if (value.status > 299) {
            GraphlCallException((body \ "stringified").as[String])
          } else {
            ((body \ "json").as[JsObject] \ "data").select(c.ctx.field.toAst.name).asOpt[JsArray] match {
              case Some(value) => value.value
              case None => JsArray.empty
            }
          }
        })
      }
  }

  def bodyToJson(source: Source[ByteString, _])(implicit mat: Materializer, ec: ExecutionContext) = source
    .runFold(ByteString.empty)(_ ++ _)
    .map { rawBody => {
      val body = rawBody.utf8String
      val json = Json.parse(if (body.isEmpty) {
        "{}"
      } else {
        body
      }).as[JsObject]

      Json.obj(
        "stringified" -> body,
        "json" -> json
      )
    }}

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])
                          (implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig("internalName")(GraphQLBackendConfig.format).getOrElse(GraphQLBackendConfig(schema = DEFAULT_GRAPHQL_SCHEMA))

    bodyToJson(ctx.request.body).flatMap(body => {

      val jsonBody = (body \ "json").as[JsObject]
      if ((jsonBody \ "operationName").asOpt[String].contains("IntrospectionQuery")) {
        QueryParser.parse(config.schema) match {
          case Failure(exception) =>
            bodyResponse(400,
              Map("Content-Type" -> "application/json"),
              Source.single(Json.obj("error" -> exception.getMessage).stringify.byteString))
              .future
          case Success(astDocument) =>
            Executor.execute(Schema.buildFromAst(astDocument), sangria.introspection.introspectionQuery)
              .map(res => {
                val response = Json.toJson(res)

                bodyResponse(200,
                  Map("Content-Type" -> "application/json"),
                  Source.single(response.stringify.byteString)
                )
              })
        }

      } else {
        QueryParser.parse(config.schema) match {
          case Failure(exception) => bodyResponse(400,
            Map("Content-Type" -> "application/json"),
            Source.single(Json.obj("error" -> exception.getMessage).stringify.byteString)).future
          case Success(astDocument: Document) =>
            val HttpRestDirective = Directive("rest", arguments = arguments, locations = directivesLocations)
            val GraphQLDirective = Directive("graphql", arguments = arguments, locations = directivesLocations)
            val OtoroshiRouteDirective = Directive("otoroshi", arguments = arguments, locations = directivesLocations)
            val JsonDirective = Directive("json", arguments = jsonDataArg :: Nil, locations = directivesLocations)

            val builder: ResolverBasedAstSchemaBuilder[Unit] = AstSchemaBuilder.resolverBased[Unit](
              InstanceCheck.field[Unit, JsValue],

              DirectiveResolver(HttpRestDirective, resolve = httpRestDirectiveResolver),
              DirectiveResolver(GraphQLDirective, resolve = c => graphQLDirectiveResolver(c, c.arg(queryArg).getOrElse("{}"), ctx, delegates)),
              /*DirectiveResolver(OtoroshiRouteDirective, resolve = OtoroshiRouteDirectiveResolver),*/
              DirectiveResolver(JsonDirective, resolve = jsonDirectiveResolver),
              FieldResolver.defaultInput[Unit, JsValue]
            )

            (jsonBody \ "query").asOpt[String] match {
              case Some(value) => execute(
                astDocument,
                query = value,
                builder,
                config.initialData.map(_.as[JsObject]).getOrElse(JsObject.empty)
              )
              case None => bodyResponse(400,
                Map("Content-Type" -> "application/json"),
                Source.single(Json.obj("error" -> "query field missing").stringify.byteString)).future
            }
        }
      }
    })
  }
}





