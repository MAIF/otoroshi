package otoroshi.next.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.arakelian.jq.{ImmutableJqLibrary, ImmutableJqRequest}
import com.github.blemale.scaffeine.Scaffeine
import otoroshi.el.GlobalExpressionLanguage
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.{JsonPathUtils, TypedMap}
import play.api.libs.json._
import sangria.ast.{Document, ListType}
import sangria.execution.deferred.DeferredResolver
import sangria.execution.{ExceptionHandler, Executor, HandledException, QueryReducer}
import sangria.marshalling.playJson._
import sangria.parser.QueryParser
import sangria.schema.{Action, Argument, AstDirectiveContext, AstSchemaBuilder, BooleanType, Directive, DirectiveResolver, FieldResolver, InstanceCheck, IntType, IntrospectionSchemaBuilder, OptionInputType, ResolverBasedAstSchemaBuilder, Schema, StringType}
import sangria.validation.{QueryValidator, ValueCoercionViolation, Violation}

import scala.concurrent.duration.{DurationLong, FiniteDuration, MILLISECONDS}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util._
import scala.util.control.NoStackTrace

// [TODO] 
// ajouter sur chaque directive un parameter paginate=true (pour que ça marche, il faut les arguments optionnels limit et offset)
// ++ plus tard les mutations
// ajouter un endpoint dans le backoffice controller pour recuperer l'exposition d'un service depuis son id
// gérer la secu sur les champs et query (à réfléchir)
// @soap directive

case object TooComplexQueryError extends Exception("Query is too expensive.") with NoStackTrace
case class ViolationsException(errors: Seq[String]) extends Exception with NoStackTrace

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
                partialBody.stringify.byteString.chunks(16 * 1024)
              )
            case Some(filter) =>
              applyJq(partialBody, filter) match {
                case Left(error) =>
                  bodyResponse(
                    500,
                    Map("Content-Type" -> "application/json"),
                    error.stringify.byteString.chunks(16 * 1024)
                  )
                case Right(resp) =>
                  bodyResponse(200, Map("Content-Type" -> "application/json"), resp.stringify.byteString.chunks(16 * 1024))
              }
          }
        } else {
          bodyResponse(resp.status, Map("Content-Type" -> resp.contentType), resp.bodyAsSource)
        }
      }
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

case class GraphQLBackendConfig(
                               schema: String,
                               initialData: Option[JsValue] = None,
                               maxDepth: Int = 15
                             ) extends NgPluginConfig {
  def json: JsValue = GraphQLBackendConfig.format.writes(this)
}

object GraphQLBackendConfig {
  val format = new Format[GraphQLBackendConfig] {
    override def reads(json: JsValue): JsResult[GraphQLBackendConfig] = Try {
      GraphQLBackendConfig(
        schema = json.select("schema").as[String],
        initialData = json.select("initialData").asOpt[JsObject],
        maxDepth = json.select("maxDepth").as[Int]
      )
    }  match {
      case Failure(ex)    => JsError(ex.getMessage())
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: GraphQLBackendConfig): JsValue = Json.obj(
      "schema" -> o.schema,
      "initialData" -> o.initialData.getOrElse(JsNull).as[JsValue],
      "maxDepth" -> o.maxDepth
    )
  }
}

// TODO: rename to GraphQLComposer or something like that
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
  override def name: String                                = "GraphQL Composer"
  override def description: Option[String]                 = "This plugin exposes a GraphQL API that you can compose with whatever you want".some
  override def defaultConfigObject: Option[NgPluginConfig] = GraphQLBackendConfig(
    schema = DEFAULT_GRAPHQL_SCHEMA
  ).some

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Integrations)
  override def steps: Seq[NgStep]                = Seq(NgStep.CallBackend)

  case object MapCoercionViolation extends ValueCoercionViolation("Exception : map value can't be parsed")
  case object JsonCoercionViolation extends ValueCoercionViolation("Not valid JSON")

  val exceptionHandler = ExceptionHandler(
    onException = {
      case (marshaller, throwable) => HandledException(throwable.getMessage)
    }
  )

  private def execute(astDocument: Document, query: String, builder: ResolverBasedAstSchemaBuilder[Unit], initialData: JsObject, maxDepth: Int)
                     (implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    QueryParser.parse(query) match {
      case Failure(error) =>
        bodyResponse(200,
          Map("Content-Type" -> "application/json"),
          Json.obj("error" -> error.getMessage).stringify.byteString.chunks(16 * 1024)
        ).future
      case Success(queryAst) =>
        val schema = Schema.buildFromAst(astDocument, builder.validateSchemaWithException(astDocument))

        Executor.execute(
          schema = Schema.buildFromAst(astDocument, builder.validateSchemaWithException(astDocument)),
          queryAst = queryAst,
          root = initialData,
          exceptionHandler = exceptionHandler,
          queryReducers = List(
            QueryReducer.rejectMaxDepth[Unit](maxDepth),
            QueryReducer.rejectComplexQueries[Unit](
                4000,
                (_, _) => TooComplexQueryError)
            )
        )
          .map(res => {
            // println(res)
            val response = Json.toJson(res)

            bodyResponse(200,
              Map("Content-Type" -> "application/json"),
              response.stringify.byteString.chunks(16 * 1024)
            )
          })
            .recoverWith {
              case e: Exception => bodyResponse(200, 
                Map("Content-Type" -> "application/json"), 
                Json.obj("data" -> Json.obj("data" -> Json.arr(), "errors" -> Json.arr(Json.obj(
                "message" -> e.getMessage()
                )))).stringify.byteString.chunks(16 * 1024)
              ).future
            }
    }
  }

  val directivesLocations = Set(
    sangria.schema.DirectiveLocation.FieldDefinition,
    sangria.schema.DirectiveLocation.Query,
    sangria.schema.DirectiveLocation.Object
  )

  val urlArg: Argument[String] = Argument("url", StringType)
  val methodArg: Argument[Option[String]] = Argument("method", OptionInputType(StringType))
  val headersArg = Argument("headers", OptionInputType(StringType))
  val timeoutArg: Argument[Int] = Argument("timeout", IntType, defaultValue = 5000)
  val jsonDataArg: Argument[Option[String]] = Argument("data", OptionInputType(StringType))
  val jsonPathArg: Argument[Option[String]] = Argument("path", OptionInputType(StringType))
  val queryArg: Argument[Option[String]] = Argument("query", OptionInputType(StringType))
  val responsePathArg: Argument[Option[String]] = Argument("response_path", OptionInputType(StringType))
  val responseFilterArg   = Argument("response_filter", OptionInputType(StringType))
  val limitArg = Argument("limit", OptionInputType(IntType))
  val offsetArg = Argument("offset", OptionInputType(IntType))
  val paginateArg = Argument("paginate", BooleanType, defaultValue = false)

  val arguments = urlArg :: methodArg :: timeoutArg :: headersArg :: queryArg :: responsePathArg :: responseFilterArg :: limitArg :: offsetArg :: paginateArg :: Nil

  def extractLimit(c: AstDirectiveContext[Unit], itemsLength: Option[Int]) = {
    val queryParameter = c.ctx.argOpt(limitArg)
    val limitParameterFromDirective = c.argOpt(limitArg)
    val autoPaginateParameter = c.arg(paginateArg)
    val defaultLimit = 1

    queryParameter
      .orElse(limitParameterFromDirective)
      .orElse(if(autoPaginateParameter) defaultLimit.some else None)
      .orElse(itemsLength)
      .orElse(defaultLimit.some)
      .get
      .asInstanceOf[Int]
  }

  def extractOffset(c: AstDirectiveContext[Unit]) = {
    val offsetParameter = c.ctx.argOpt(offsetArg)
    val offsetParameterFromDirective = c.argOpt(offsetArg)
    val autoPaginateParameter = c.arg(paginateArg)
    
    offsetParameter
      .orElse(offsetParameterFromDirective)
      .orElse(if(autoPaginateParameter) 0.some else None)
      .orElse(0.some)
      .get
      .asInstanceOf[Int]
  }

  def sliceArrayWithArgs(arr: IndexedSeq[JsValue], c: AstDirectiveContext[Unit]) = {
    val limit = extractLimit(c, arr.length.some)
    val offset = extractOffset(c)
    println(limit, offset, arr.slice(offset, limit))
    arr.slice(offset, limit)
  }

  def httpRestDirectiveResolver(c: AstDirectiveContext[Unit])(implicit env: Env, ec: ExecutionContext): Action[Unit, Any] = {
    val queryArgs = c.ctx.args.raw.map {
      case (str, Some(v)) =>(str, String.valueOf(v))
      case (k, v) => (k, String.valueOf(v))
    }

    val paramsArgs: Map[String, String] = queryArgs.foldLeft(Map.empty[String, String]) {
      case (acc, (key, value)) => acc + (s"params.$key" -> value)
    }


    val ctx = 
      (c.ctx.value.asInstanceOf[JsObject].value.foldLeft(Map.empty[String, String]) {
      case (acc, (key, value)) => acc + (s"item.$key" -> (value match {
        case JsObject(_) => ""
        case v => v.toString()
      })) 
      }) ++ paramsArgs

    val url = GlobalExpressionLanguage.apply(
      c.arg(urlArg),
      None, None, None, None,
      ctx,
      TypedMap.empty,
      env
    )

    println(url, ctx)

    env.Ws.url(url)
      .withRequestTimeout(FiniteDuration(c.arg(timeoutArg), MILLISECONDS))
      .withMethod(c.arg(methodArg).getOrElse("GET"))
      .withHttpHeaders(Json.parse(c.arg(headersArg).getOrElse("{}")).as[Map[String, String]].toSeq:_*)
      .execute()
      .map { resp =>
        if (resp.status == 200) {
            resp.json.atPath(c.arg(responsePathArg).getOrElse("$")).asOpt[JsValue].getOrElse(JsNull) match {
              case JsArray(value) => 
                val res = sliceArrayWithArgs(value, c)
                res.map {
                  case v: JsObject => v
                  case JsString(v) => v
                  case JsNumber(v) => v
                  case JsBoolean(v) => v
                  case v => v.toString()
                }

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

  def jsonDirectiveResolver(c: AstDirectiveContext[Unit], config: GraphQLBackendConfig): Action[Unit, Any] = {
    val data: JsValue = config.initialData.getOrElse(Json.obj())

    c.arg(jsonPathArg) match {
      case Some(path) =>
        c.ctx.field.toAst.fieldType match {
          case ListType(_, __) => sliceArrayWithArgs(JsonPathUtils.getAtPolyJson(data, path).getOrElse(Json.arr()).asInstanceOf[JsArray].value, c)
          case _ => JsonPathUtils.getAtPolyJson(data, path).getOrElse(Json.obj()).asInstanceOf[JsObject]
        }
      case _ => 
        c.ctx.field.toAst.fieldType match {
          case ListType(_, __) => sliceArrayWithArgs(
            Json.parse(c.arg(jsonDataArg).getOrElse("[]")).as[JsArray].value, 
            c
          ).asInstanceOf[JsArray].value
          case _ => c.arg(jsonDataArg)
        }
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
            "headers" -> Json.parse(c.arg(headersArg).getOrElse("{}")).as[Map[String, String]],
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
                    case Some(value) => sliceArrayWithArgs(value.value, c)
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
          val config = ctx.cachedConfig(internalName)(GraphQLBackendConfig.format).getOrElse(GraphQLBackendConfig(schema = DEFAULT_GRAPHQL_SCHEMA))

          bodyToJson(ctx.request.body).flatMap(body => {

            val jsonBody = (body \ "json").as[JsObject]
            if ((jsonBody \ "operationName").asOpt[String].contains("IntrospectionQuery")) {
              QueryParser.parse(config.schema) match {
                case Failure(exception) =>
                  bodyResponse(400,
                    Map("Content-Type" -> "application/json"),
                    Json.obj("error" -> exception.getMessage).stringify.byteString.chunks(16 * 1024)
                  ).future
                case Success(astDocument) =>
                  Executor.execute(Schema.buildFromAst(astDocument), sangria.introspection.introspectionQuery)
                    .map { res =>
                      val response = Json.toJson(res)
                      bodyResponse(200,
                        Map("Content-Type" -> "application/json"),
                        response.stringify.byteString.chunks(16 * 1024)
                      )
                    }
              }

              } else {
                QueryParser.parse(config.schema) match {
                  case Failure(exception) => bodyResponse(400,
                    Map("Content-Type" -> "application/json"),
                    Json.obj("error" -> exception.getMessage).stringify.byteString.chunks(16 * 1024)
                  ).future
                  case Success(astDocument: Document) =>
                    val HttpRestDirective = Directive("rest", arguments = arguments, locations = directivesLocations)
                    val GraphQLDirective = Directive("graphql", arguments = arguments, locations = directivesLocations)
                    val OtoroshiRouteDirective = Directive("otoroshi", arguments = arguments, locations = directivesLocations)
                    val JsonDirective = Directive("json", arguments = jsonDataArg :: jsonPathArg :: Nil, locations = directivesLocations)

                    val builder: ResolverBasedAstSchemaBuilder[Unit] = AstSchemaBuilder.resolverBased[Unit](
                      InstanceCheck.field[Unit, JsValue],

                      DirectiveResolver(HttpRestDirective, resolve = httpRestDirectiveResolver),
                      DirectiveResolver(GraphQLDirective, resolve = c => graphQLDirectiveResolver(c, c.arg(queryArg).getOrElse("{}"), ctx, delegates)),
                      /*DirectiveResolver(OtoroshiRouteDirective, resolve = OtoroshiRouteDirectiveResolver),*/
                     DirectiveResolver(JsonDirective, resolve = c => jsonDirectiveResolver(c, config)),
                     FieldResolver.defaultInput[Unit, JsValue]
                   )


                 (jsonBody \ "query").asOpt[String] match {
                   case Some(value) => execute(
                     astDocument,
                     query = value,
                     builder,
                     config.initialData.map(_.as[JsObject]).getOrElse(JsObject.empty),
                     config.maxDepth
                   )
                 case None => bodyResponse(400,
                   Map("Content-Type" -> "application/json"),
                   Json.obj("error" -> "query field missing").stringify.byteString.chunks(16 * 1024)).future
                 }
                }
              }
          })
        }
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

// TODO: GraphQLFederation

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

case class GraphQLProxyConfig(endpoint: String, schema: Option[String], maxDepth: Int, maxComplexity: Double, path: String, headers: Map[String, String]) extends NgPluginConfig {
  def json: JsValue = Json.obj(
    "endpoint" -> endpoint,
    "schema" -> schema.map(JsString.apply).getOrElse(JsNull).asValue,
    "max_depth" -> maxDepth,
    "max_complexity" -> maxComplexity,
    "path" -> path,
    "headers" -> headers
  )
}

object GraphQLProxyConfig {
  val format = new Format[GraphQLProxyConfig] {
    override def writes(o: GraphQLProxyConfig): JsValue = o.json
    override def reads(json: JsValue): JsResult[GraphQLProxyConfig] = Try {
      GraphQLProxyConfig(
        endpoint = json.select("endpoint").asString,
        schema = json.select("schema").asOpt[String],
        maxDepth = json.select("max_depth").asOpt[Int].getOrElse(50),
        maxComplexity  = json.select("max_complexity").asOpt[Double].getOrElse(50000.0),
        path = json.select("path").asOpt[String].getOrElse("/graphql"),
        headers = json.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty)
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage())
      case Success(value) => JsSuccess(value)
    }
  }
  val default = GraphQLProxyConfig(
    "https://countries.trevorblades.com/graphql",
    None,
    50,
    50000,
    "/graphql",
    Map.empty
  )
}

class GraphQLProxy extends NgBackendCall {

  override def useDelegates: Boolean                       = true
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = false
  override def name: String                                = "GraphQL Proxy"
  override def description: Option[String]                 = "This plugin can apply validations (query, schema, max depth, max complexity) on graphql endpoints".some
  override def defaultConfigObject: Option[NgPluginConfig] = GraphQLProxyConfig.default.some

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Integrations)
  override def steps: Seq[NgStep]                = Seq(NgStep.CallBackend)

  private val cache = Scaffeine().maximumSize(1000).expireAfterWrite(5.minutes).build[String, Schema[Unit, Any]]()
  private val inlinecache = Scaffeine().maximumSize(1000).expireAfterWrite(10.seconds).build[String, Schema[Unit, Any]]()

  private val exceptionHandler = ExceptionHandler(
    onException = {
      case (marshaller, throwable) => HandledException(throwable.getMessage)
    }
  )

  private def executeGraphQLCall(
    schema: Schema[Unit, Any],
    query: String,
    initialData: JsObject,
    maxDepth: Int,
    complexityThreshold: Double
  )(implicit env: Env, ec: ExecutionContext): Future[Either[Seq[String], Unit]] = {
    QueryParser.parse(query) match {
      case Failure(error) => Seq(s"Bad query format: ${error.getMessage}").leftf[Unit]
      case Success(queryAst) => {
        Executor.execute(
          schema = schema,
          queryAst = queryAst,
          root = initialData,
          exceptionHandler = exceptionHandler,
          queryValidator = new QueryValidator() {
            override def validateQuery(schema: Schema[_, _], queryAst: Document): Vector[Violation] = {
              val violations = QueryValidator.default.validateQuery(schema, queryAst)
              if (violations.nonEmpty) {
                throw ViolationsException(violations.map(_.errorMessage))
              }
              violations
            }
          }, // QueryValidator.default,
          deferredResolver = DeferredResolver.empty,
          queryReducers = List(
            QueryReducer.rejectMaxDepth[Unit](maxDepth),
            QueryReducer.rejectComplexQueries[Unit](
              complexityThreshold = complexityThreshold,
              (_, _) => TooComplexQueryError
            )
          )
        )
        .map { _ =>
          ().right[Seq[String]]
        }
        .recoverWith {
          case ViolationsException(errors) => errors.leftf[Unit]
          case e: Exception => Seq(e.getMessage).leftf[Unit]
        }
      }
    }
  }

  private def getSchema(builder: ResolverBasedAstSchemaBuilder[Unit], config: GraphQLProxyConfig)(implicit env: Env, ec: ExecutionContext): Future[Either[Seq[String], Schema[Unit, Any]]] = {
    config.schema.map { s =>
      inlinecache.getIfPresent(s) match {
        case Some(schema) => schema.rightf
        case None => {
          (if (s.trim.startsWith("{")) {
            Try(Schema.buildFromIntrospection(Json.parse(s), IntrospectionSchemaBuilder.default[Unit])) match {
              case Failure(exception) => Seq(exception.getMessage).left
              case Success(value) =>
                inlinecache.put(s, value)
                value.right
            }
          } else {
            Try {
              val astDocument = QueryParser.parse(s).get
              Schema.buildFromAst(astDocument, builder.validateSchemaWithException(astDocument))
            } match {
              case Failure(exception) => Seq(exception.getMessage).left
              case Success(value) =>
                inlinecache.put(s, value)
                value.right
            }
          }).vfuture
        }
      }
    }.getOrElse {
      val headers: Seq[(String, String)] = config.headers.toSeq :+ ("Content-Type" -> "application/json")
      cache.getIfPresent(config.endpoint) match {
        case Some(schema) => schema.rightf
        case None => {
          env.Ws.url(config.endpoint)
            .withMethod("POST")
            .withHttpHeaders(headers: _*)
            .withBody(
              """{"operationName":"IntrospectionQuery","variables":{},"query":"query IntrospectionQuery {\n  __schema {\n    queryType {\n      name\n    }\n    mutationType {\n      name\n    }\n    subscriptionType {\n      name\n    }\n    types {\n      ...FullType\n    }\n    directives {\n      name\n      description\n      locations\n      args {\n        ...InputValue\n      }\n    }\n  }\n}\n\nfragment FullType on __Type {\n  kind\n  name\n  description\n  fields(includeDeprecated: true) {\n    name\n    description\n    args {\n      ...InputValue\n    }\n    type {\n      ...TypeRef\n    }\n    isDeprecated\n    deprecationReason\n  }\n  inputFields {\n    ...InputValue\n  }\n  interfaces {\n    ...TypeRef\n  }\n  enumValues(includeDeprecated: true) {\n    name\n    description\n    isDeprecated\n    deprecationReason\n  }\n  possibleTypes {\n    ...TypeRef\n  }\n}\n\nfragment InputValue on __InputValue {\n  name\n  description\n  type {\n    ...TypeRef\n  }\n  defaultValue\n}\n\nfragment TypeRef on __Type {\n  kind\n  name\n  ofType {\n    kind\n    name\n    ofType {\n      kind\n      name\n      ofType {\n        kind\n        name\n        ofType {\n          kind\n          name\n          ofType {\n            kind\n            name\n            ofType {\n              kind\n              name\n              ofType {\n                kind\n                name\n              }\n            }\n          }\n        }\n      }\n    }\n  }\n}\n"}""".stripMargin)
            .execute()
            .map { res =>
              if (res.status == 200) {
                Try(Schema.buildFromIntrospection(res.json, IntrospectionSchemaBuilder.default[Unit])) match {
                  case Failure(exception) => Seq(exception.getMessage).left
                  case Success(value) =>
                    cache.put(config.endpoint, value)
                    value.right
                }
              } else {
                Seq(s"bad server response: ${res.status} - ${res.headers} - ${res.body}").left
              }
            }.recover {
            case e: Throwable => Seq(e.getMessage).left
          }
        }
      }
    }
  }

  def callBackendApi(body: ByteString, config: GraphQLProxyConfig)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val headers: Seq[(String, String)] = config.headers.toSeq :+ ("Content-Type" -> "application/json")
    env.Ws.url(config.endpoint)
      .withMethod("POST")
      .withHttpHeaders(headers: _*)
      .withBody(body)
      .execute()
      .map { res =>
        bodyResponse(res.status, res.headers.mapValues(_.last), res.bodyAsSource)
      }
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    // TODO: fields permissions
    val path = ctx.request.path
    val method = ctx.request.method
    val config = ctx.cachedConfig(internalName)(GraphQLProxyConfig.format).getOrElse(GraphQLProxyConfig.default)
    if (method != "POST") {
      bodyResponse(404, Map("Content-Type" -> "application/json"), Json.obj("error" -> "resource not found").stringify.byteString.chunks(16 * 1024)).vfuture
    } else if (path != config.path) {
      bodyResponse(404, Map("Content-Type" -> "application/json"), Json.obj("error" -> "resource not found").stringify.byteString.chunks(16 * 1024)).vfuture
    } else {
      val builder: ResolverBasedAstSchemaBuilder[Unit] = AstSchemaBuilder.resolverBased[Unit](
        InstanceCheck.field[Unit, JsValue],
        FieldResolver.defaultInput[Unit, JsValue]
      )
      ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
        val body = bodyRaw.utf8String.parseJson.asObject
        val operationName = body.select("operationName").asOpt[String]
        if (operationName.contains("IntrospectionQuery")) {
          callBackendApi(bodyRaw, config)
        } else {
          val query = body.select("query").asString
          getSchema(builder, config).flatMap {
            case Left(errors) => {
              bodyResponse(200,
                Map("Content-Type" -> "application/json"),
                Json.obj(
                  "data" -> Json.arr(),
                  "errors" -> JsArray(Seq(Json.obj("message" -> s"unable to fetch schema at '${config.endpoint}'")) ++ errors.map(e => Json.obj("message" -> e)))
                ).stringify.byteString.chunks(16 * 1024)
              ).vfuture
            }
            case Right(schema) => {
              executeGraphQLCall(schema, query, Json.obj(), config.maxDepth, config.maxComplexity).flatMap {
                case Left(errors) => {
                  bodyResponse(200,
                    Map("Content-Type" -> "application/json"),
                    Json.obj(
                      "data" -> Json.arr(),
                      "errors" -> JsArray(errors.map(e => Json.obj("message" -> e)))
                    ).stringify.byteString.chunks(16 * 1024)
                  ).future
                }
                case Right(_) => callBackendApi(bodyRaw, config)
              }
            }
          }
        }
      }
    }
  }
}
