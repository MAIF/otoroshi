package otoroshi.next.plugins

import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.arakelian.jq.{ImmutableJqLibrary, ImmutableJqRequest}
import com.github.blemale.scaffeine.Scaffeine
import com.jayway.jsonpath.PathNotFoundException
import otoroshi.el.GlobalExpressionLanguage
import otoroshi.env.Env
import otoroshi.next.models.NgTreeRouter
import otoroshi.next.models.NgTreeRouter_Test.NgFakeRoute
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.{JsonPathUtils, JsonPathValidator, TypedMap}
import play.api.libs.json._
import play.api.libs.ws.WSResponse
import otoroshi.utils.JsonMarshaller._
import otoroshi.wasm._
import sangria.ast
import sangria.ast._
import sangria.execution.deferred.DeferredResolver
import sangria.execution.{ExceptionHandler, Executor, HandledException, QueryReducer}
import sangria.parser.QueryParser
import sangria.schema.{
  Action,
  AdditionalTypes,
  AnyFieldResolver,
  Argument,
  AstDirectiveContext,
  AstSchemaBuilder,
  AstSchemaMaterializer,
  BooleanType,
  DefaultAstSchemaBuilder,
  Directive,
  DirectiveResolver,
  FieldResolver,
  InstanceCheck,
  IntType,
  IntrospectionSchemaBuilder,
  ListInputType,
  OptionInputType,
  ResolverBasedAstSchemaBuilder,
  ScalarType,
  Schema,
  StringType
}
import sangria.util.tag.@@
import sangria.validation.{QueryValidator, ValueCoercionViolation, Violation}

import scala.concurrent.duration.{DurationLong, FiniteDuration, MILLISECONDS}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util._
import scala.util.control.NoStackTrace

case object TooComplexQueryError                          extends Exception("Query is too expensive.") with NoStackTrace
case class ViolationsException(errors: Seq[String])       extends Exception with NoStackTrace
case class GraphlCallException(message: String)           extends Exception(message)
case class AuthorisationException(message: String)        extends Exception(message)
case class MissingMockResponsesException(message: String) extends Exception(message)
case class MockResponseNotFoundException(message: String) extends Exception(message)
case class WasmException(message: String)                 extends Exception(message)

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
      route = ctx.route.some,
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
                  bodyResponse(
                    200,
                    Map("Content-Type" -> "application/json"),
                    resp.stringify.byteString.chunks(16 * 1024)
                  )
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
    permissions: Seq[String] = Seq.empty,
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
        permissions = json.select("permissions").asOpt[Seq[String]].getOrElse(Seq.empty),
        initialData = json.select("initial_data").asOpt[JsObject],
        maxDepth = json.select("max_depth").asOpt[Int].getOrElse(15)
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage())
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: GraphQLBackendConfig): JsValue = Json.obj(
      "schema"       -> o.schema,
      "permissions"  -> o.permissions,
      "initial_data" -> o.initialData.getOrElse(JsNull).as[JsValue],
      "max_depth"    -> o.maxDepth
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

   type Query {
    users: [User] @json(data: "[{ \"firstname\": \"Foo\", \"name\": \"Bar\" }, { \"firstname\": \"Bar\", \"name\": \"Foo\" }]")
   }
  """.stripMargin

  override def useDelegates: Boolean                       = false
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = false
  override def name: String                                = "GraphQL Composer"
  override def description: Option[String]                 =
    "This plugin exposes a GraphQL API that you can compose with whatever you want".some
  override def defaultConfigObject: Option[NgPluginConfig] = GraphQLBackendConfig(
    schema = DEFAULT_GRAPHQL_SCHEMA
  ).some

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Integrations)
  override def steps: Seq[NgStep]                = Seq(NgStep.CallBackend)

  case object MapCoercionViolation extends ValueCoercionViolation("Exception : map value can't be parsed")

  val exceptionHandler = ExceptionHandler(
    onException = { case (_, throwable) =>
      HandledException(throwable.getMessage)
    }
  )

  case object TooComplexQueryError extends Exception("Query is too expensive.")

  private def execute(
      schema: Schema[Unit, Any],
      query: String,
      builder: DefaultAstSchemaBuilder[Unit],
      initialData: JsObject,
      maxDepth: Int,
      variables: JsObject
  )(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    QueryParser.parse(query) match {
      case Failure(error)    =>
        bodyResponse(
          200,
          Map("Content-Type" -> "application/json"),
          Json.obj("error" -> error.getMessage).stringify.byteString.chunks(16 * 1024)
        ).future
      case Success(queryAst) =>
        Executor
          .execute(
            schema = schema,
            queryAst = queryAst,
            root = initialData,
            variables = variables,
            exceptionHandler = exceptionHandler,
            queryReducers = List(
              QueryReducer.rejectMaxDepth[Unit](maxDepth),
              QueryReducer.rejectComplexQueries[Unit](4000, (_, _) => TooComplexQueryError)
            )
          )
          .map { res =>
            bodyResponse(
              200,
              Map("Content-Type" -> "application/json"),
              res.stringify.byteString.chunks(16 * 1024)
            )
          }
          .recoverWith { case e: Exception =>
            bodyResponse(
              200,
              Map("Content-Type" -> "application/json"),
              Json
                .obj(
                  "data" -> Json.obj(
                    "data"   -> Json.arr(),
                    "errors" -> Json.arr(
                      Json.obj(
                        "message" -> e.getMessage()
                      )
                    )
                  )
                )
                .stringify
                .byteString
                .chunks(16 * 1024)
            ).future
          }
    }
  }

  val directivesLocations = Set(
    sangria.schema.DirectiveLocation.FieldDefinition,
    sangria.schema.DirectiveLocation.Query,
    sangria.schema.DirectiveLocation.Object
  )

  val urlArg               = Argument("url", StringType)
  val methodArg            = Argument("method", OptionInputType(StringType))
  val headersArg           = Argument("headers", OptionInputType(StringType))
  val timeoutArg           = Argument("timeout", IntType, defaultValue = 5000)
  val jsonDataArg          = Argument("data", OptionInputType(StringType))
  val jsonPathArg          = Argument("path", OptionInputType(StringType))
  val queryArg             = Argument("query", OptionInputType(StringType))
  val responsePathArg      = Argument("response_path", OptionInputType(StringType))
  val responseFilterArg    = Argument("response_filter", OptionInputType(StringType))
  val limitArg             = Argument("limit", OptionInputType(IntType))
  val offsetArg            = Argument("offset", OptionInputType(IntType))
  val paginateArg          = Argument("paginate", OptionInputType(BooleanType), defaultValue = false)
  val valueArg             = Argument("value", StringType)
  val valuesArg            = Argument("values", ListInputType(StringType))
  val pathArg              = Argument("path", StringType)
  val unauthorizedValueArg = Argument("unauthorized_value", OptionInputType(StringType))

  val soapEnvelopeArg                = Argument("envelope", StringType)
  val soapUrlArg                     = Argument("url", OptionInputType(StringType))
  val soapActionArg                  = Argument("action", OptionInputType(StringType))
  val soapPreservereQueryArg         = Argument("preserve_query", BooleanType, defaultValue = true)
  val soapCharsetArg                 = Argument("charset", OptionInputType(StringType))
  val soapConvertRequestBodyToXmlArg = Argument("convert_request_body_to_xml", BooleanType, defaultValue = true)
  val soapJqRequestFilterArg         = Argument("jq_request_filter", OptionInputType(StringType))
  val soapJqResponseFilterArg        = Argument("jq_response_filter", OptionInputType(StringType))

  val wasmSourceKindArg                 = Argument("wasm_source_kind", OptionInputType(StringType))
  val wasmSourcePathArg                 = Argument("wasm_source_path", OptionInputType(StringType))
  val wasmFunctionNameArg               = Argument("wasm_function_name", StringType)
  val wasmMemoryPagesArg                = Argument("wasm_memory_pages", OptionInputType(IntType))
  // val wasmConfigArg = Argument("wasm_config", StringType)
  val wasmAllowedHostsArg               = Argument("wasm_allowed_hosts", OptionInputType(ListInputType(StringType)))
  val wasmWasiArg                       = Argument("wasm_wasi", BooleanType, defaultValue = true)
  val wasmProxyHttpCallTimeoutArg       = Argument("proxy_http_call_timeout", OptionInputType(IntType))
  val wasmHttpAccessArg                 = Argument("http_access", BooleanType, defaultValue = false)
  val wasmGlobalDataStoreAccessReadArg  = Argument("global_datastore_access_read", BooleanType, defaultValue = false)
  val wasmGlobalDataStoreAccessWriteArg = Argument("global_datastore_access_write", BooleanType, defaultValue = false)
  val wasmPluginDataStoreAccessReadArg  = Argument("plugin_datastore_access_read", BooleanType, defaultValue = false)
  val wasmPluginDataStoreAccessWriteArg = Argument("plugin_datastore_access_write", BooleanType, defaultValue = false)
  val wasmGlobalMapAccessReadArg        = Argument("global_map_access_read", BooleanType, defaultValue = false)
  val wasmGlobalMapAccessWriteArg       = Argument("global_map_access_write", BooleanType, defaultValue = false)
  val wasmPluginMapAccessReadArg        = Argument("plugin_map_access_read", BooleanType, defaultValue = false)
  val wasmPluginMapAccessWriteArg       = Argument("plugin_map_access_write", BooleanType, defaultValue = false)

  val wasmProxyStateAccessArg    = Argument("proxy_state_sccess", BooleanType, defaultValue = false)
  val wasmConfigurationAccessArg = Argument("configuration_access", BooleanType, defaultValue = false)

  val arguments =
    urlArg :: methodArg :: timeoutArg :: headersArg :: queryArg :: responsePathArg :: responseFilterArg :: limitArg :: offsetArg :: paginateArg :: Nil

  val permissionDirective      = Directive(
    "permission",
    arguments = valueArg :: unauthorizedValueArg :: Nil,
    locations = directivesLocations
  )
  val permissionsDirective     = Directive(
    "allpermissions",
    arguments = valuesArg :: unauthorizedValueArg :: Nil,
    locations = directivesLocations
  )
  val onePermissionOfDirective = Directive(
    "onePermissionsOf",
    arguments = valuesArg :: unauthorizedValueArg :: Nil,
    locations = directivesLocations
  )
  val authorizeDirective       = Directive(
    "authorize",
    arguments = pathArg :: valueArg :: unauthorizedValueArg :: Nil,
    locations = directivesLocations
  )
  val httpRestDirective        = Directive("rest", arguments = arguments, locations = directivesLocations)
  val wasmDirective            = Directive(
    "wasm",
    arguments = wasmSourceKindArg ::
      wasmSourcePathArg ::
      wasmFunctionNameArg ::
      wasmMemoryPagesArg ::
      wasmAllowedHostsArg ::
      wasmWasiArg ::
      wasmProxyHttpCallTimeoutArg ::
      wasmHttpAccessArg ::
      wasmGlobalDataStoreAccessReadArg ::
      wasmGlobalDataStoreAccessWriteArg ::
      wasmPluginDataStoreAccessReadArg ::
      wasmPluginDataStoreAccessWriteArg ::
      wasmGlobalMapAccessReadArg ::
      wasmGlobalMapAccessWriteArg ::
      wasmPluginMapAccessReadArg ::
      wasmPluginMapAccessWriteArg ::
      wasmProxyStateAccessArg ::
      wasmConfigurationAccessArg :: Nil,
    locations = directivesLocations
  )
  val graphQLDirective         = Directive("graphql", arguments = arguments, locations = directivesLocations)
  // val OtoroshiRouteDirective = Directive("otoroshi", arguments = arguments, locations = directivesLocations)
  val soapDirective            = Directive(
    "soap",
    arguments = soapEnvelopeArg :: soapUrlArg :: soapActionArg ::
      soapPreservereQueryArg ::
      soapCharsetArg ::
      soapConvertRequestBodyToXmlArg ::
      soapJqRequestFilterArg ::
      soapJqResponseFilterArg :: Nil,
    locations = directivesLocations
  )
  val jsonDirective            = Directive(
    "json",
    arguments = jsonDataArg :: jsonPathArg :: paginateArg :: Nil,
    locations = directivesLocations
  )
  val mockDirective            = Directive(
    "mock",
    arguments = urlArg :: Nil,
    locations = directivesLocations
  )

  case object JsonCoercionViolation   extends ValueCoercionViolation("Not valid JSON")
  case object StringCoercionViolation extends ValueCoercionViolation("Not valid String")

  val JsonType: ScalarType[JsValue] = ScalarType[JsValue](
    "Json",
    description = Some("Raw JSON value"),
    coerceOutput = (value, _) => value,
    coerceUserInput = {
      case v: String     => Right(JsString(v))
      case v: Boolean    => Right(JsBoolean(v))
      case v: Int        => Right(JsNumber(v))
      case v: Long       => Right(JsNumber(v))
      case v: Float      => Right(JsNumber(v))
      case v: Double     => Right(JsNumber(v))
      case v: BigInt     => Right(JsNumber(v.intValue()))
      case v: BigDecimal => Right(JsNumber(v))
      case v: JsValue    => Right(v)
    },
    coerceInput = {
      case ast.StringValue(jsonStr, _, _, _, _) =>
        Right(jsonStr.parseJson)
      case _                                    =>
        Left(JsonCoercionViolation)
    }
  )

  def builder(
      config: GraphQLBackendConfig,
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]],
      body: JsObject
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer) = AstSchemaBuilder.resolverBased[Unit](
    AdditionalTypes(JsonType),
    InstanceCheck.field[Unit, JsValue],
    DirectiveResolver(permissionDirective, resolve = c => permissionDirectiveResolver(c, config, ctx)),
    DirectiveResolver(permissionsDirective, resolve = c => permissionsDirectiveResolver(c, config, ctx)),
    DirectiveResolver(
      onePermissionOfDirective,
      resolve = c => onePermissionOfDirectiveResolver(c, config, ctx)
    ),
    DirectiveResolver(authorizeDirective, resolve = c => authorizeDirectiveResolver(c, ctx)),
    DirectiveResolver(httpRestDirective, resolve = httpRestDirectiveResolver),
    DirectiveResolver(wasmDirective, resolve = c => wasmDirectiveResolver(c, ctx)),
    DirectiveResolver(
      graphQLDirective,
      resolve = c => graphQLDirectiveResolver(c, c.arg(queryArg).getOrElse("{}"), ctx, delegates)
    ),
    DirectiveResolver(soapDirective, resolve = c => soapDirectiveResolver(c, ctx, delegates, body)),
    /*DirectiveResolver(OtoroshiRouteDirective, resolve = OtoroshiRouteDirectiveResolver),*/
    DirectiveResolver(jsonDirective, resolve = c => jsonDirectiveResolver(c, config)),
    DirectiveResolver(
      mockDirective,
      resolve = c => mockDirectiveResolver(c, ctx.route.plugins.getPluginByClass[MockResponses].map(_.config.raw))
    ),
    AnyFieldResolver.defaultInput[Unit, JsValue]
  )

  def extractLimit(c: AstDirectiveContext[Unit], itemsLength: Option[Int]) = {
    val queryParameter              = c.ctx.argOpt(limitArg)
    val limitParameterFromDirective = c.argOpt(limitArg)
    val autoPaginateParameter       = c.argOpt(paginateArg).getOrElse(false)
    val defaultLimit                = 1

    queryParameter
      .orElse(limitParameterFromDirective)
      .orElse(if (autoPaginateParameter) defaultLimit.some else None)
      .orElse(itemsLength)
      .orElse(defaultLimit.some)
      .get
      .asInstanceOf[Int]
  }

  def extractOffset(c: AstDirectiveContext[Unit]) = {
    val offsetParameter              = c.ctx.argOpt(offsetArg)
    val offsetParameterFromDirective = c.argOpt(offsetArg)
    val autoPaginateParameter        = c.argOpt(paginateArg).getOrElse(false)

    offsetParameter
      .orElse(offsetParameterFromDirective)
      .orElse(if (autoPaginateParameter) 0.some else None)
      .orElse(0.some)
      .get
      .asInstanceOf[Int]
  }

  def sliceArrayWithArgs(arr: IndexedSeq[JsValue], c: AstDirectiveContext[Unit]) = {
    val limit  = extractLimit(c, arr.length.some)
    val offset = extractOffset(c)
    arr.slice(offset, limit)
  }

  def buildContext(ctx: NgbBackendCallContext) = {
    val token: JsValue = ctx.attrs
      .get(otoroshi.next.plugins.Keys.JwtInjectionKey)
      .flatMap(_.decodedToken)
      .map { token =>
        Json.obj(
          "header"  -> token.getHeader.fromBase64.parseJson,
          "payload" -> token.getPayload.fromBase64.parseJson
        )
      }
      .getOrElse(JsNull)
    ctx.json.asObject ++ Json.obj(
      "route" -> ctx.route.json,
      "token" -> token
    )
  }

  def permissionResponse(authorized: Boolean, c: AstDirectiveContext[Unit]) = {
    if (!authorized)
      c.arg(unauthorizedValueArg)
        .map(value =>
          if (value.isEmpty) {
            throw AuthorisationException("You're not authorized")
          } else {
            value
          }
        )
        .getOrElse(throw AuthorisationException("You're not authorized"))
    else {
      ResolverBasedAstSchemaBuilder.extractFieldValue(c.ctx)
    }
  }

  def permissionDirectiveResolver(
      c: AstDirectiveContext[Unit],
      config: GraphQLBackendConfig,
      ctx: NgbBackendCallContext
  )(implicit env: Env, ec: ExecutionContext): Action[Unit, Any] = {
    val context    = buildContext(ctx)
    val authorized =
      config.permissions.exists(path => {
        try {
          JsonPathValidator(path, JsString(c.arg(valueArg))).validate(context)
        } catch {
          case _: PathNotFoundException => false
        }
      })
    permissionResponse(authorized, c)
  }

  def permissionsDirectiveResolver(
      c: AstDirectiveContext[Unit],
      config: GraphQLBackendConfig,
      ctx: NgbBackendCallContext
  )(implicit env: Env, ec: ExecutionContext): Action[Unit, Any] = {
    val context             = buildContext(ctx)
    val values: Seq[String] = c.arg(valuesArg)
    val authorized          = values.forall(value =>
      config.permissions.exists(path => {
        try {
          JsonPathValidator(path, JsString(value)).validate(context)
        } catch {
          case _: PathNotFoundException => false
        }
      })
    )
    permissionResponse(authorized, c)
  }

  def onePermissionOfDirectiveResolver(
      c: AstDirectiveContext[Unit],
      config: GraphQLBackendConfig,
      ctx: NgbBackendCallContext
  )(implicit env: Env, ec: ExecutionContext): Action[Unit, Any] = {
    val context             = buildContext(ctx)
    val values: Seq[String] = c.arg(valuesArg)
    val authorized          = values.exists(value =>
      config.permissions.exists(path => {
        try {
          JsonPathValidator(path, JsString(value)).validate(context)
        } catch {
          case _: PathNotFoundException => false
        }
      })
    )
    permissionResponse(authorized, c)
  }

  def authorizeDirectiveResolver(c: AstDirectiveContext[Unit], ctx: NgbBackendCallContext)(implicit
      env: Env,
      ec: ExecutionContext
  ): Action[Unit, Any] = {
    val context       = buildContext(ctx)
    val value: String = c.arg(valueArg)
    val path: String  = c.arg(pathArg)

    try {
      val authorized = JsonPathValidator(path, JsString(value)).validate(context)
      permissionResponse(authorized, c)
    } catch {
      case _: PathNotFoundException => permissionResponse(authorized = false, c)
    }

  }

  def httpRestDirectiveResolver(
      c: AstDirectiveContext[Unit]
  )(implicit env: Env, ec: ExecutionContext): Action[Unit, Any] = {
    val url = replaceTermsInUrl(c)

    var request = env.Ws
      .url(url)
      .withRequestTimeout(FiniteDuration(c.arg(timeoutArg), MILLISECONDS))
      .withMethod(c.arg(methodArg).getOrElse("GET"))
      .withHttpHeaders(Json.parse(c.arg(headersArg).getOrElse("{}")).as[Map[String, String]].toSeq: _*)

    if (c.arg(methodArg).contains("POST")) {
      request = request
        .withHttpHeaders(
          (Map("Content-Type" -> "application/json") ++ Json
            .parse(c.arg(headersArg).getOrElse("{}"))
            .as[Map[String, String]]).toSeq: _*
        )
        .withBody(c.ctx.args.raw.foldLeft(Json.obj()) { case (acc, curr) =>
          acc + (curr._1 -> (curr._2 match {
            case s: String        => JsString(s)
            case i: Int           => JsNumber(i)
            case f: Float         => JsNumber(f)
            case d: Boolean       => JsBoolean(d)
            case Some(s: String)  => JsString(s)
            case Some(i: Int)     => JsNumber(i)
            case Some(f: Float)   => JsNumber(f)
            case Some(d: Boolean) => JsBoolean(d)
            case a                => JsString(String.valueOf(a))
          }))
        })
    }

    request
      .execute()
      .map { resp =>
        if (resp.status == 200) {
          resp.json.atPath(c.arg(responsePathArg).getOrElse("$")).asOpt[JsValue].getOrElse(JsNull) match {
            case JsArray(value) =>
              val res = sliceArrayWithArgs(value, c)
              res.map {
                case v: JsObject  => v
                case JsString(v)  => v
                case JsNumber(v)  => v
                case JsBoolean(v) => v
                case v            => v.toString()
              }
            case v              => v
          }
        } else {
          c.ctx.field.toAst.fieldType match {
            case ListType(_, _) => Seq.empty
            case _              => JsObject.empty
          }
        }
      }
  }

  def wasmDirectiveResolver(c: AstDirectiveContext[Unit], ctx: NgbBackendCallContext)(implicit
      env: Env,
      ec: ExecutionContext
  ): Action[Unit, Any] = {

    val wasmSourceKind   = c.arg(wasmSourceKindArg)
    val wasmSourcePath   = c.arg(wasmSourcePathArg)
    val wasmFunctionName = c.argOpt(wasmFunctionNameArg)

    val wasmMemoryPages                = c.arg(wasmMemoryPagesArg)
    val wasmAllowedHosts               = c.arg(wasmAllowedHostsArg)
    val wasmWasi                       = c.arg(wasmWasiArg)
    val wasmProxyHttpCallTimeout       = c.arg(wasmProxyHttpCallTimeoutArg)
    val wasmHttpAccess                 = c.argOpt(wasmHttpAccessArg)
    val wasmGlobalDataStoreAccessRead  = c.argOpt(wasmGlobalDataStoreAccessReadArg)
    val wasmGlobalDataStoreAccessWrite = c.argOpt(wasmGlobalDataStoreAccessWriteArg)
    val wasmPluginDataStoreAccessRead  = c.argOpt(wasmPluginDataStoreAccessReadArg)
    val wasmPluginDataStoreAccessWrite = c.argOpt(wasmPluginDataStoreAccessWriteArg)
    val wasmGlobalMapAccessRead        = c.argOpt(wasmGlobalMapAccessReadArg)
    val wasmGlobalMapAccessWrite       = c.argOpt(wasmGlobalMapAccessWriteArg)
    val wasmPluginMapAccessRead        = c.argOpt(wasmPluginMapAccessReadArg)
    val wasmPluginMapAccessWrite       = c.argOpt(wasmPluginMapAccessWriteArg)
    val wasmProxyStateAccess           = c.argOpt(wasmProxyStateAccessArg)
    val wasmConfigurationAccess        = c.argOpt(wasmConfigurationAccessArg)

    if (wasmSourceKind.isEmpty && wasmSourcePath.isEmpty) {
      throw WasmException("Missing sources")
    } else if (wasmFunctionName.isEmpty) {
      throw WasmException("Missing function")
    } else {
      val input  = ctx.json.asObject ++ Json.obj(
        "route"   -> ctx.route.json,
        "request" -> ctx.request.json
      )
      val wsmCfg = WasmConfig(
        source = WasmSource(WasmSourceKind(wasmSourceKind.getOrElse("")), wasmSourcePath.getOrElse("")),
        memoryPages = wasmMemoryPages.getOrElse(30),
        functionName = wasmFunctionName,
        config = Map.empty,
        allowedHosts = wasmAllowedHosts.getOrElse(Seq.empty),
        wasi = wasmWasi,
        authorizations = WasmAuthorizations(
          proxyHttpCallTimeout = wasmProxyHttpCallTimeout.getOrElse(5000),
          httpAccess = wasmHttpAccess.getOrElse(false),
          globalDataStoreAccess = WasmDataRights(
            read = wasmGlobalDataStoreAccessRead.getOrElse(false),
            write = wasmGlobalDataStoreAccessWrite.getOrElse(false)
          ),
          pluginDataStoreAccess = WasmDataRights(
            read = wasmPluginDataStoreAccessRead.getOrElse(false),
            write = wasmPluginDataStoreAccessWrite.getOrElse(false)
          ),
          globalMapAccess = WasmDataRights(
            read = wasmGlobalMapAccessRead.getOrElse(false),
            write = wasmGlobalMapAccessWrite.getOrElse(false)
          ),
          pluginMapAccess = WasmDataRights(
            read = wasmPluginMapAccessRead.getOrElse(false),
            write = wasmPluginMapAccessWrite.getOrElse(false)
          ),
          proxyStateAccess = wasmProxyStateAccess.getOrElse(false),
          configurationAccess = wasmConfigurationAccess.getOrElse(false)
        )
      )
      WasmVm.fromConfig(wsmCfg).flatMap {
        case None          => Future.failed(WasmException("plugin not found !"))
        case Some((vm, _)) =>
          vm.call(WasmFunctionParameters.ExtismFuntionCall("execute", input.stringify), None)
            .map {
              case Right(output) =>
                try {
                  Json.parse(output._1)
                } catch {
                  case _: Exception => output
                }
              case Left(error)   => error
            }
            .andThen { case _ =>
              vm.release()
            }
      }
    }
  }

  def soapDirectiveResolver(
      c: AstDirectiveContext[Unit],
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]],
      body: JsObject
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Object] = {
    new SOAPAction()
      .process(
        ctx.copy(
          request = ctx.request.copy(
            body = Source.single(body.stringify.byteString)
          )
        ),
        delegates,
        SOAPActionConfig(
          url = c.arg(soapUrlArg),
          envelope = c.arg(soapEnvelopeArg),
          action = c.arg(soapActionArg),
          preserveQuery = c.arg(soapPreservereQueryArg),
          charset = c.arg(soapCharsetArg),
          jqRequestFilter = c.arg(soapJqRequestFilterArg),
          jqResponseFilter = c.arg(soapJqResponseFilterArg)
        )
      )
      .flatMap {
        case Right(value) =>
          value.response.body
            .runFold(ByteString.empty)(_ ++ _)
            .map(_.utf8String)
        case Left(error)  => FastFuture.successful(error)
      }
  }

  def jsonDirectiveResolver(c: AstDirectiveContext[Unit], config: GraphQLBackendConfig): Action[Unit, Any] = {
    val data: JsValue = config.initialData.getOrElse(Json.obj())

    c.arg(jsonPathArg) match {
      case Some(path) =>
        c.ctx.field.toAst.fieldType match {
          case ListType(_, __) =>
            sliceArrayWithArgs(
              JsonPathUtils.getAtPolyJson(data, path).getOrElse(Json.arr()).asInstanceOf[JsArray].value,
              c
            )
          case _               => JsonPathUtils.getAtPolyJson(data, path).getOrElse(Json.obj()).asInstanceOf[JsObject]
        }
      case _          =>
        c.ctx.field.toAst.fieldType match {
          case ListType(_, __) =>
            sliceArrayWithArgs(
              Json.parse(c.arg(jsonDataArg).getOrElse("[]")).as[JsArray].value,
              c
            )
          case _               => c.arg(jsonDataArg)
        }
    }
  }

  def mockDirectiveResolver(c: AstDirectiveContext[Unit], rawConfig: Option[JsObject])(implicit env: Env) = {
    rawConfig match {
      case None         => throw MissingMockResponsesException("Missing mock response plugin")
      case Some(config) =>
        MockResponsesConfig.format.reads(config) match {
          case JsSuccess(value, _) =>
            val url = replaceTermsInUrl(c)

            NgTreeRouter
              .build(
                value.responses.map(resp => {
                  val r = NgFakeRoute.routeFromPath(s"oto.tools${resp.path}")
                  r.copy(
                    metadata = Map("mock" -> resp.json.stringify),
                    frontend = r.frontend.copy(exact = true)
                  )
                })
              )
              .find("oto.tools", url)
              .filter(_.noMoreSegments)
              .flatMap { c =>
                if (c.routes.headOption.nonEmpty)
                  Some(c)
                else
                  None
              }
              .map(r => {
                val route    = r.routes.headOption.get
                val response = Json.parse(route.metadata("mock")).as[MockResponse](MockResponse.format)

                Json.parse(response.body) match {
                  case JsArray(value) =>
                    val res = sliceArrayWithArgs(value, c)
                    res.map {
                      case v: JsObject  => v
                      case JsString(v)  => v
                      case JsNumber(v)  => v
                      case JsBoolean(v) => v
                      case v            => v.toString()
                    }
                  case v              => v
                }
              })
              .getOrElse {
                throw MockResponseNotFoundException("Mock response not found for this uri")
              }
          case JsError(err)        =>
            println(err)
            throw MissingMockResponsesException("Missing mock response plugin")
        }
    }
  }

  def replaceQueryParams(c: AstDirectiveContext[Unit]) = {
    val queryArgs = c.ctx.args.raw.map {
      case (str, Some(v)) => (str, String.valueOf(v))
      case (k, v)         => (k, String.valueOf(v))
    }

    queryArgs.foldLeft(c.arg(urlArg))((u, value) =>
      GlobalExpressionLanguage.expressionReplacer.replaceOn(u) {
        case value._1 => value._2
        case v        => v
      }
    )
  }

  def replaceTermsInUrl(c: AstDirectiveContext[Unit])(implicit env: Env) = {
    val queryArgs = c.ctx.args.raw.map {
      case (str, Some(v)) => (str, String.valueOf(v))
      case (k, v)         => (k, String.valueOf(v))
    }

    val paramsArgs: Map[String, String] = queryArgs.foldLeft(Map.empty[String, String]) { case (acc, (key, value)) =>
      acc + (s"params.$key" -> value)
    }

    val extractedField = ResolverBasedAstSchemaBuilder.extractFieldValue(c.ctx) match {
      case Some(v: JsObject) =>
        v.value.foldLeft(Map.empty[String, String]) { case (acc, (key, value)) =>
          acc + (s"item.$key" -> (value match {
            case JsObject(_) => ""
            case JsString(v) => v
            case v           => v.toString()
          }))
        }
      case _                 => Map.empty // TODO - throw exception or whatever
    }

    val fieldsFromValue = c.ctx.value match {
      case JsObject(fields) =>
        fields.foldLeft(Map.empty[String, String]) { case (acc, (key, value)) =>
          acc + (s"item.$key" -> (value match {
            case JsObject(_) => ""
            case JsString(v) => v
            case v           => v.toString()
          }))
        }
      case _                => Map.empty
    }

    val ctx = extractedField ++ paramsArgs ++ fieldsFromValue

    GlobalExpressionLanguage.apply(
      c.arg(urlArg),
      None,
      None,
      None,
      None,
      None,
      ctx,
      TypedMap.empty,
      env
    )
  }

  def graphQLDirectiveResolver(
      c: AstDirectiveContext[Unit],
      query: String,
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Action[Unit, Any] = {
    val url = replaceQueryParams(c)

    val graphqlQuery = env.scriptManager.getAnyScript[GraphQLQuery](s"cp:${classOf[GraphQLQuery].getName}").right.get
    graphqlQuery
      .callBackend(
        ctx.copy(
          config = Json.obj(
            "url"            -> url,
            "headers"        -> Json.parse(c.arg(headersArg).getOrElse("{}")).as[Map[String, String]],
            "method"         -> JsString(c.arg(methodArg).getOrElse("POST")),
            "timeout"        -> JsNumber(c.argOpt(timeoutArg).getOrElse(60000).toInt),
            "query"          -> query,
            "responsePath"   -> c.argOpt(responsePathArg),
            "responseFilter" -> c.argOpt(responseFilterArg)
          )
        ),
        delegates
      )
      .flatMap {
        case Left(_)      => GraphlCallException("Something happens when calling GraphQL directive").future
        case Right(value) =>
          bodyToJson(value.response.body).map(body => {
            if (value.status > 299) {
              GraphlCallException((body \ "stringified").as[String])
            } else {
              ((body \ "json").as[JsObject] \ "data").select(c.ctx.field.toAst.name).asOpt[JsArray] match {
                case Some(value) => sliceArrayWithArgs(value.value, c)
                case None        => JsArray.empty
              }
            }
          })
      }
  }

  def bodyToJson(source: Source[ByteString, _])(implicit mat: Materializer, ec: ExecutionContext) = source
    .runFold(ByteString.empty)(_ ++ _)
    .map { rawBody =>
      {
        val body = rawBody.utf8String
        val json = Json
          .parse(if (body.isEmpty) {
            "{}"
          } else {
            body
          })
          .as[JsObject]

        Json.obj(
          "stringified" -> body,
          "json"        -> json
        )
      }
    }

  def manageAutoPagination(document: Document, builder: ResolverBasedAstSchemaBuilder[Unit]): Schema[Unit, Any] = {
    val patchDoc = document.copy(definitions = document.definitions.map {
      case e: TypeSystemDefinition =>
        e match {
          case definition: TypeDefinition =>
            definition match {
              case o: ObjectTypeDefinition =>
                val containsMockDirective = o.directives.exists(d => d.name == "mock")
                o.copy(
                  directives = o.directives
                    .filter(d => (containsMockDirective && d.name != "rest") || !containsMockDirective)
                    .sortWith { case (a, b) =>
                      val containsA =
                        List("permission", "allpermissions", "onePermissionsOf", "authorize", "mock").contains(a.name)
                      val containsB =
                        List("permission", "allpermissions", "onePermissionsOf", "authorize", "mock").contains(b.name)
                      if (containsA && containsB)
                        true
                      else if (containsA && !containsB)
                        true
                      else
                        false
                    },
                  fields = o.fields
                    .map(field => {
                      val fieldContainsMockDirective                         = field.directives.exists(d => d.name == "mock")
                      val limitAndOffsetValues: Vector[InputValueDefinition] = field.directives.flatMap(directive => {
                        if (directive.arguments.exists(a => a.name == "paginate")) {
                          val limit  = field.arguments.exists(a => a.name == "limit")
                          val offset = field.arguments.exists(a => a.name == "limit")
                          Vector(
                            if (limit) None
                            else
                              Some(
                                InputValueDefinition(name = "limit", valueType = NamedType("Int"), defaultValue = None)
                              ),
                            if (offset) None
                            else
                              Some(
                                InputValueDefinition(
                                  name = "offset",
                                  valueType = NamedType("Int"),
                                  defaultValue = None
                                )
                              )
                          ).flatten
                        } else
                          Vector()
                      })
                      field.copy(
                        arguments = field.arguments ++ limitAndOffsetValues,
                        directives = field.directives
                          .filter(d => (fieldContainsMockDirective && d.name != "rest") || !fieldContainsMockDirective)
                          .sortWith { case (a, b) =>
                            val containsA =
                              List("permission", "allpermissions", "onePermissionsOf", "authorize", "mock")
                                .contains(a.name)
                            val containsB =
                              List("permission", "allpermissions", "onePermissionsOf", "authorize", "mock")
                                .contains(b.name)
                            if (containsA && containsB)
                              true
                            else if (containsA && !containsB)
                              true
                            else
                              false
                          }
                      )
                    })
                    .groupBy(_.name)
                    .map(_._2.head)
                    .toVector
                )
              case e                       => e
            }
          case a                          => a
        }
      case t                       => t
    })

    AstSchemaMaterializer.buildSchema(patchDoc, builder)
  }

  def jsonResponse(status: Int, body: JsValue) =
    bodyResponse(
      status,
      Map("Content-Type" -> "application/json"),
      Source.single(body.stringify.byteString)
    )

  def introspectionResponse(config: GraphQLBackendConfig, builder: ResolverBasedAstSchemaBuilder[Unit])(implicit
      ec: ExecutionContext
  ) = {
    QueryParser.parse(config.schema) match {
      case Failure(exception)   => jsonResponse(400, Json.obj("error" -> exception.getMessage)).future
      case Success(astDocument) =>
        Executor
          .execute(Schema.buildFromAst(astDocument, builder), sangria.introspection.introspectionQuery)
          .map(res => {
            val response = Json.toJson(res)
            jsonResponse(200, response)
          })
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
      .cachedConfig(internalName)(GraphQLBackendConfig.format)
      .getOrElse(GraphQLBackendConfig(schema = DEFAULT_GRAPHQL_SCHEMA))

    bodyToJson(ctx.request.body).flatMap(body => {
      val jsonBody                                           = (body \ "json").as[JsObject]
      val customBuilder: ResolverBasedAstSchemaBuilder[Unit] = builder(config, ctx, delegates, body)
      if ((jsonBody \ "operationName").asOpt[String].contains("IntrospectionQuery"))
        introspectionResponse(config, customBuilder)
      else {
        QueryParser.parse(config.schema) match {
          case Failure(exception)             => jsonResponse(400, Json.obj("error" -> exception.getMessage)).future
          case Success(astDocument: Document) =>
            (jsonBody \ "query").asOpt[String] match {
              case Some(value) =>
                execute(
                  schema = manageAutoPagination(astDocument, customBuilder),
                  query = value,
                  builder = customBuilder,
                  initialData = config.initialData.map(_.as[JsObject]).getOrElse(JsObject.empty),
                  maxDepth = config.maxDepth,
                  variables = (jsonBody \ "variables").asOpt[JsValue].getOrElse(Json.obj()).as[JsObject]
                )
              case None        => jsonResponse(400, Json.obj("error" -> "query field missing")).future
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

case class GraphQLProxyConfig(
    endpoint: String,
    schema: Option[String],
    maxDepth: Int,
    maxComplexity: Double,
    path: String,
    headers: Map[String, String]
) extends NgPluginConfig {
  def json: JsValue = Json.obj(
    "endpoint"       -> endpoint,
    "schema"         -> schema.map(JsString.apply).getOrElse(JsNull).asValue,
    "max_depth"      -> maxDepth,
    "max_complexity" -> maxComplexity,
    "path"           -> path,
    "headers"        -> headers
  )
}

object GraphQLProxyConfig {
  val format  = new Format[GraphQLProxyConfig] {
    override def writes(o: GraphQLProxyConfig): JsValue             = o.json
    override def reads(json: JsValue): JsResult[GraphQLProxyConfig] = Try {
      GraphQLProxyConfig(
        endpoint = json.select("endpoint").asString,
        schema = json.select("schema").asOpt[String],
        maxDepth = json.select("max_depth").asOpt[Int].getOrElse(50),
        maxComplexity = json.select("max_complexity").asOpt[Double].getOrElse(50000.0),
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
  override def description: Option[String]                 =
    "This plugin can apply validations (query, schema, max depth, max complexity) on graphql endpoints".some
  override def defaultConfigObject: Option[NgPluginConfig] = GraphQLProxyConfig.default.some

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Integrations)
  override def steps: Seq[NgStep]                = Seq(NgStep.CallBackend)

  private val cache       = Scaffeine().maximumSize(1000).expireAfterWrite(5.minutes).build[String, Schema[Unit, Any]]()
  private val inlinecache =
    Scaffeine().maximumSize(1000).expireAfterWrite(10.seconds).build[String, Schema[Unit, Any]]()

  private val exceptionHandler = ExceptionHandler(
    onException = { case (marshaller, throwable) =>
      HandledException(throwable.getMessage)
    }
  )

  private def executeGraphQLCall(
      schema: Schema[Unit, Any],
      query: String,
      initialData: JsValue,
      maxDepth: Int,
      complexityThreshold: Double
  )(implicit env: Env, ec: ExecutionContext): Future[Either[Seq[String], JsValue]] = {
    QueryParser.parse(query) match {
      case Failure(error)    => Seq(s"Bad query format: ${error.getMessage}").leftf[JsValue]
      case Success(queryAst) =>
        Executor
          .execute(
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
          .map((res: JsValue) => Right(res))
          .recover {
            case ViolationsException(errors) => errors.left[JsValue]
            case e: Throwable                => Seq(e.getMessage).left[JsValue]
          }
    }
  }

  private def getSchema(builder: ResolverBasedAstSchemaBuilder[Unit], config: GraphQLProxyConfig)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[Seq[String], Schema[Unit, Any]]] = {
    config.schema
      .map { s =>
        inlinecache.getIfPresent(s) match {
          case Some(schema) => schema.rightf
          case None         => {
            (if (s.trim.startsWith("{")) {
               Try(Schema.buildFromIntrospection(Json.parse(s), IntrospectionSchemaBuilder.default[Unit])) match {
                 case Failure(exception) => Seq(exception.getMessage).left
                 case Success(value)     =>
                   inlinecache.put(s, value)
                   value.right
               }
             } else {
               Try {
                 val astDocument = QueryParser.parse(s).get
                 Schema.buildFromAst(astDocument, builder.validateSchemaWithException(astDocument))
               } match {
                 case Failure(exception) => Seq(exception.getMessage).left
                 case Success(value)     =>
                   inlinecache.put(s, value)
                   value.right
               }
             }).vfuture
          }
        }
      }
      .getOrElse {
        val headers: Seq[(String, String)] = config.headers.toSeq :+ ("Content-Type" -> "application/json")
        cache.getIfPresent(config.endpoint) match {
          case Some(schema) => schema.rightf
          case None         => {
            env.Ws
              .url(config.endpoint)
              .withMethod("POST")
              .withHttpHeaders(headers: _*)
              .withBody(
                s"""{"operationName":"IntrospectionQuery","variables":{},"query":"${sangria.introspection
                  .introspectionQueryString(true)}"}""".replace("\n", "\\n")
              )
              .execute()
              .map { res =>
                if (res.status == 200) {
                  Try(Schema.buildFromIntrospection(res.json, IntrospectionSchemaBuilder.default[Unit])) match {
                    case Failure(exception) => Seq(exception.getMessage).left
                    case Success(value)     =>
                      cache.put(config.endpoint, value)
                      value.right
                  }
                } else {
                  Seq(s"bad server response: ${res.status} - ${res.headers} - ${res.body}").left
                }
              }
              .recover { case e: Throwable =>
                Seq(e.getMessage).left
              }
          }
        }
      }
  }

  def callBackendApi(body: ByteString, config: GraphQLProxyConfig)(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[WSResponse] = {
    val headers: Seq[(String, String)] = config.headers.toSeq :+ ("Content-Type" -> "application/json")
    env.Ws
      .url(config.endpoint)
      .withMethod("POST")
      .withHttpHeaders(headers: _*)
      .withBody(body)
      .execute()
    // .map { res =>
    //   bodyResponse(res.status, res.headers.mapValues(_.last), res.bodyAsSource)
    // }
  }

  override def callBackend(
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    // TODO: fields permissions
    // TODO: WS timeout
    val path   = ctx.request.path
    val method = ctx.request.method
    val config = ctx.cachedConfig(internalName)(GraphQLProxyConfig.format).getOrElse(GraphQLProxyConfig.default)
    if (method != "POST") {
      bodyResponse(
        404,
        Map("Content-Type" -> "application/json"),
        Json.obj("error" -> "resource not found").stringify.byteString.chunks(16 * 1024)
      ).vfuture
    } else if (path != config.path) {
      bodyResponse(
        404,
        Map("Content-Type" -> "application/json"),
        Json.obj("error" -> "resource not found").stringify.byteString.chunks(16 * 1024)
      ).vfuture
    } else {
      val builder: ResolverBasedAstSchemaBuilder[Unit] = AstSchemaBuilder.resolverBased[Unit](
        InstanceCheck.field[Unit, JsValue],
        FieldResolver.defaultInput[Unit, JsValue]
      )
      ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
        val body          = bodyRaw.utf8String.parseJson.asObject
        val operationName = body.select("operationName").asOpt[String]
        if (operationName.contains("IntrospectionQuery")) {
          callBackendApi(bodyRaw, config).map { res =>
            bodyResponse(res.status, res.headers.mapValues(_.last), res.bodyAsSource)
          }
        } else {
          val query = body.select("query").asString
          getSchema(builder, config).flatMap {
            case Left(errors)  => {
              bodyResponse(
                200,
                Map("Content-Type" -> "application/json"),
                Json
                  .obj(
                    "data"   -> JsNull,
                    "errors" -> JsArray(
                      Seq(Json.obj("message" -> s"unable to fetch schema at '${config.endpoint}'")) ++ errors.map(e =>
                        Json.obj("message" -> e)
                      )
                    )
                  )
                  .stringify
                  .byteString
                  .chunks(16 * 1024)
              ).vfuture
            }
            case Right(schema) => {
              executeGraphQLCall(schema, query, Json.obj(), config.maxDepth, config.maxComplexity).flatMap {
                case Left(errors) => {
                  bodyResponse(
                    200,
                    Map("Content-Type" -> "application/json"),
                    Json
                      .obj(
                        "data"   -> JsNull,
                        "errors" -> JsArray(errors.map(e => Json.obj("message" -> e)))
                      )
                      .stringify
                      .byteString
                      .chunks(16 * 1024)
                  ).future
                }
                case Right(_)     => {
                  callBackendApi(bodyRaw, config).flatMap { res =>
                    if (res.status == 200) {
                      val sa = schema.toAst
                      val s2 = Schema
                        .buildFromAst(sa, builder.validateSchemaWithException(sa)) // don't know how to avoid that !
                      executeGraphQLCall(
                        s2,
                        query,
                        res.json.select("data").asValue,
                        config.maxDepth,
                        config.maxComplexity
                      ).map {
                        case Left(errors)    => {
                          bodyResponse(
                            200,
                            Map("Content-Type" -> "application/json"),
                            Json
                              .obj(
                                "data"   -> JsNull,
                                "errors" -> JsArray(errors.map(e => Json.obj("message" -> e)))
                              )
                              .stringify
                              .byteString
                              .chunks(16 * 1024)
                          )
                        }
                        case Right(response) => {
                          bodyResponse(
                            200,
                            Map("Content-Type" -> "application/json"),
                            response.stringify.byteString.singleSource
                          )
                        }
                      }
                    } else {
                      bodyResponse(
                        res.status,
                        res.headers.mapValues(_.last),
                        res.bodyAsSource
                      ).vfuture
                    }
                  }
                  //callBackendApi(bodyRaw, config).map { res =>
                  //  bodyResponse(res.status, res.headers.mapValues(_.last), res.bodyAsSource)
                  //}
                }
              }
            }
          }
        }
      }
    }
  }
}
