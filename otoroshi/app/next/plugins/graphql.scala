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
import sangria.ast.{DirectiveDefinition, DirectiveLocation, Document, EnumTypeDefinition, FieldDefinition, InputObjectTypeDefinition, InputValueDefinition, InterfaceTypeDefinition, ListType, NamedType, NotNullType, ObjectTypeDefinition, ObjectValue, ScalarTypeDefinition, SchemaDefinition, StringValue, TypeDefinition, TypeSystemDefinition, UnionTypeDefinition}
import sangria.execution.{ExceptionHandler, Executor, HandledException}
import sangria.macros.LiteralGraphQLStringContext
import sangria.parser.QueryParser
import sangria.schema.{Action, AnyFieldResolver, Argument, AstDirectiveContext, AstSchemaBuilder, BooleanType, Directive, DirectiveResolver, FieldResolver, InstanceCheck, IntType, ListInputType, OptionInputType, OptionType, ResolverBasedAstSchemaBuilder, ScalarType, Schema, StringType}
import sangria.marshalling.playJson._
import sangria.renderer.SchemaRenderer
import otoroshi.utils.{JsonPathUtils, JsonPathValidator, TypedMap}

import scala.concurrent.duration.{Duration, DurationLong, FiniteDuration, MILLISECONDS}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util._
import sangria.validation.ValueCoercionViolation
import akka.http.scaladsl.util.FastFuture
import org.graalvm.compiler.nodeinfo.InputType
import sangria.execution.QueryReducer

// [TODO] 
// ajouter sur chaque directive un parameter paginate=true (pour que ça marche, il faut les arguments optionnels limit et offset)
// ++ plus tard les mutations
// ajouter un endpoint dans le backoffice controller pour recuperer l'exposition d'un service depuis son id
// gérer la secu sur les champs et query (à réfléchir)

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
        initialData = json.select("initialData").asOpt[JsObject],
        maxDepth = json.select("maxDepth").as[Int]
      )
    }  match {
      case Failure(ex)    => JsError(ex.getMessage())
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: GraphQLBackendConfig): JsValue = Json.obj(
      "schema" -> o.schema,
      "permissions" -> o.permissions,
      "initialData" -> o.initialData.getOrElse(JsNull).as[JsValue],
      "maxDepth" -> o.maxDepth
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

  case object MapCoercionViolation extends ValueCoercionViolation("Exception : map value can't be parsed")
  case object JsonCoercionViolation extends ValueCoercionViolation("Not valid JSON")

  val exceptionHandler = ExceptionHandler(
    onException = {
      case (marshaller, throwable) => HandledException(throwable.getMessage)
    }
  )

  case object TooComplexQueryError extends Exception("Query is too expensive.")

  private def execute(astDocument: Document, query: String, builder: ResolverBasedAstSchemaBuilder[Unit], initialData: JsObject, maxDepth: Int, variables: JsObject)
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
          variables = variables,
          exceptionHandler = exceptionHandler,
          queryReducers = List(
            QueryReducer.rejectMaxDepth[Unit](maxDepth),
            QueryReducer.rejectComplexQueries[Unit](
                4000,
                (_, _) => TooComplexQueryError)
            )
        )
          .map(res => {
            val response = Json.toJson(res)

            bodyResponse(200,
              Map("Content-Type" -> "application/json"),
              Source.single(response.stringify.byteString)
            )
          })
            .recoverWith {
              case e: Exception => bodyResponse(200, 
                Map("Content-Type" -> "application/json"), 
                Source.single(
                  Json.obj("data" -> Json.obj("data" -> Json.arr(), "errors" -> Json.arr(Json.obj(
                    "message" -> e.getMessage()
                    )))).stringify.byteString
              )).future
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
  val valueArg = Argument("value", StringType)
  val valuesArg = Argument("values", ListInputType(StringType))
  val pathArg = Argument("path", StringType)
  val unauthorizedValueArg = Argument("unauthorized_value", OptionInputType(StringType))

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
    // println(limit, offset, arr.slice(offset, limit))
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
      c.argOpt(unauthorizedValueArg).getOrElse(throw AuthorisationException("You're not authorized"))
    else
      ResolverBasedAstSchemaBuilder.extractFieldValue(c.ctx)
  }

  def permissionDirectiveResolver(c: AstDirectiveContext[Unit], config: GraphQLBackendConfig, ctx: NgbBackendCallContext)
                                 (implicit env: Env, ec: ExecutionContext): Action[Unit, Any] = {
    val context = buildContext(ctx)
    val authorized = config.permissions.exists(path => JsonPathValidator(path, JsString(c.arg(valueArg))).validate(context))
    permissionResponse(authorized, c)
  }

  def permissionsDirectiveResolver(c: AstDirectiveContext[Unit], config: GraphQLBackendConfig, ctx: NgbBackendCallContext)
                                  (implicit env: Env, ec: ExecutionContext): Action[Unit, Any] = {
    val context = buildContext(ctx)
    val values: Seq[String] = c.arg(valuesArg)
    val authorized = values.forall(value => config.permissions.exists(path => JsonPathValidator(path, JsString(value)).validate(context)))
    permissionResponse(authorized, c)
  }

  def onePermissionOfDirectiveResolver(c: AstDirectiveContext[Unit], config: GraphQLBackendConfig, ctx: NgbBackendCallContext)
                                      (implicit env: Env, ec: ExecutionContext): Action[Unit, Any] = {
    val context = buildContext(ctx)
    val values: Seq[String] = c.arg(valuesArg)
    val authorized = values.exists(value => config.permissions.exists(path => JsonPathValidator(path, JsString(value)).validate(context)))
    permissionResponse(authorized, c)
  }

  def authorizeDirectiveResolver(c: AstDirectiveContext[Unit], config: GraphQLBackendConfig, ctx: NgbBackendCallContext)
                                (implicit env: Env, ec: ExecutionContext): Action[Unit, Any] = {
    val context = buildContext(ctx)
    val value: String = c.arg(valueArg)
    val path: String = c.arg(pathArg)

    val authorized = JsonPathValidator(path, JsString(value)).validate(context)
    permissionResponse(authorized, c)
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
      // (c.ctx.value.asInstanceOf[JsObject].value.foldLeft(Map.empty[String, String]) {
      (ResolverBasedAstSchemaBuilder.extractFieldValue(c.ctx) match {
        case Some(v: JsObject)  => v.value.foldLeft(Map.empty[String, String]) {
          case (acc, (key, value)) => acc + (s"item.$key" -> (value match {
            case JsObject(_) => ""
            case v => v.toString()
          }))
        }
        case a => Map.empty // TODO - throw exception or whatever
      }) ++ paramsArgs

    val url = GlobalExpressionLanguage.apply(
      c.arg(urlArg),
      None, None, None, None,
      ctx,
      TypedMap.empty,
      env
    )

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
  case class AuthorisationException(message: String) extends Exception(message)

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

  def manageAutoPagination(document: Document): Document = {
    val generatedSchema = Schema.buildFromAst(document).toAst

    generatedSchema.copy(
      definitions = generatedSchema.definitions.map {
        case e: TypeSystemDefinition => e match {
          case definition: TypeDefinition =>
            definition match {
              case o: ObjectTypeDefinition =>
                o.copy(
                  fields = o.fields.map(field => {
                    val limitAndOffsetValues: Vector[InputValueDefinition] = field.directives.flatMap(directive => {
                      if (directive.arguments.exists(a => a.name == "paginate")) {
                        val limit = field.arguments.exists(a => a.name == "limit")
                        val offset = field.arguments.exists(a => a.name == "limit")
                        Vector(
                          if(limit) None else Some(InputValueDefinition(
                          name = "limit",
                          valueType = NamedType("Int"),
                          defaultValue = None)),
                          if(offset) None else Some(InputValueDefinition(
                            name = "offset",
                            valueType = NamedType("Int"),
                            defaultValue = None
                          ))
                        ).flatten
                      } else
                        Vector()
                    })
                    field.copy(arguments = field.arguments ++ limitAndOffsetValues)
                  })
                    .groupBy(_.name)
                    .map(_._2.head)
                    .toVector
                )
              case e => e
            }
          case a => a
        }
        case t => t
    })
  }

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
                val permissionDirective = Directive("permission", arguments = valueArg :: unauthorizedValueArg:: Nil, locations = directivesLocations)
                val permissionsDirective = Directive("allpermissions", arguments = valuesArg :: unauthorizedValueArg:: Nil, locations = directivesLocations)
                val onePermissionOfDirective = Directive("onePermissionsOf", arguments = valuesArg :: unauthorizedValueArg:: Nil, locations = directivesLocations)
                val authorizeDirective = Directive("authorize", arguments = pathArg :: valueArg :: unauthorizedValueArg:: Nil, locations = directivesLocations)
                val HttpRestDirective = Directive("rest", arguments = arguments, locations = directivesLocations)
                val GraphQLDirective = Directive("graphql", arguments = arguments, locations = directivesLocations)
                val OtoroshiRouteDirective = Directive("otoroshi", arguments = arguments, locations = directivesLocations)
                val JsonDirective = Directive("json", arguments = jsonDataArg :: jsonPathArg :: paginateArg :: Nil, locations = directivesLocations)

                val builder: ResolverBasedAstSchemaBuilder[Unit] = AstSchemaBuilder.resolverBased[Unit](
                  InstanceCheck.field[Unit, JsValue],

                  DirectiveResolver(permissionDirective, resolve = c => permissionDirectiveResolver(c, config, ctx)),
                  DirectiveResolver(permissionsDirective, resolve = c => permissionsDirectiveResolver(c, config, ctx)),
                  DirectiveResolver(onePermissionOfDirective, resolve = c => onePermissionOfDirectiveResolver(c, config, ctx)),
                  DirectiveResolver(authorizeDirective, resolve = c => authorizeDirectiveResolver(c, config, ctx)),
                  DirectiveResolver(HttpRestDirective, resolve = httpRestDirectiveResolver),
                  DirectiveResolver(GraphQLDirective, resolve = c => graphQLDirectiveResolver(c, c.arg(queryArg).getOrElse("{}"), ctx, delegates)),
                  /*DirectiveResolver(OtoroshiRouteDirective, resolve = OtoroshiRouteDirectiveResolver),*/
                  DirectiveResolver(JsonDirective, resolve = c => jsonDirectiveResolver(c, config)),
                  FieldResolver.defaultInput[Unit, JsValue]
               )

                val document = manageAutoPagination(astDocument)

                val variables = (jsonBody \ "variables").asOpt[JsValue]

               (jsonBody \ "query").asOpt[String] match {
                 case Some(value) => execute(
                   document,
                   query = value,
                   builder,
                   config.initialData.map(_.as[JsObject]).getOrElse(JsObject.empty),
                   config.maxDepth,
                   variables.getOrElse(Json.obj()).as[JsObject]
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





