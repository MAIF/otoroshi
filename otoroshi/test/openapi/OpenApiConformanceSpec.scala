package openapi

import com.typesafe.config.ConfigFactory
import functional.OtoroshiSpec
import org.scalatest.BeforeAndAfterAll
import otoroshi.api.{Resource, ResourceAccessApi}
import otoroshi.models.EntityLocationSupport
import otoroshi.utils.yaml.Yaml
import play.api.Configuration
import play.api.libs.json.*
import play.api.libs.ws.{writeableOf_String, WSAuthScheme}

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.concurrent.TrieMap
import scala.util.{Failure, Success, Try}

// the openapi document of the admin api, checked against what a running otoroshi really does. it is the reference
// of the admin api: everything the document says has to be true, and everything the api does has to be in the
// document. the tests are built from the document the instance serves, so the instance is started while they are
// registered:
// - the document itself: a valid openapi 3.0 document, with nothing dangling
// - every schema: it says what it describes, and what each of its properties is
// - every operation: routed by otoroshi, with its parameters, bodies and responses described
// - every route of the admin api: documented
// - every entity: its schema is what the api reads and writes, field by field, in process through the format of
//   the entity, then through the api, each documented operation being called the way the document describes it
// - every other documented operation: called the way the document describes it
class OpenApiConformanceSpec(configurationSpec: => Configuration) extends OtoroshiSpec with BeforeAndAfterAll {

  // for the scalatest runner, which runs a single spec with a filter on the name of its tests:
  // org.scalatest.tools.Runner -s openapi.OpenApiConformanceSpec -z "proxy.otoroshi.io"
  def this() = this(Configuration(ConfigFactory.parseString("app.storage = \"inmemory\"").resolve()))

  override def getTestConfiguration(configuration: Configuration): Configuration = {
    Configuration(
      ConfigFactory
        .parseString(s"""
          |otoroshi.next.state-sync-interval = 5
          |# the cluster api the document describes only answers on a leader
          |otoroshi.cluster.mode = "leader"
          |# every admin extension shipped with otoroshi is described
          |otoroshi.admin-extensions.configurations.otoroshi_extensions_greenscore.enabled = true
          |""".stripMargin)
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)
  }

  private val adminHost    = "otoroshi-api.oto.tools"
  private val clientId     = "admin-api-apikey-id"
  private val clientSecret = "admin-api-apikey-secret"
  private val counter      = new AtomicInteger(0)

  startOtoroshi()

  override def afterAll(): Unit = stopAll()

  private val env = otoroshiComponents.env

  // the suite calls the admin api faster than the per ip quota of a new instance allows
  locally {
    given scala.concurrent.ExecutionContext = env.otoroshiExecutionContext
    val config                              = env.datastores.globalConfigDataStore.latest()(using env.otoroshiExecutionContext, env)
    config.copy(perIpThrottlingQuota = 100000000L, throttlingQuota = 100000000L).save()(using env.otoroshiExecutionContext, env).futureValue
    val deadline                            = System.currentTimeMillis() + 30000L
    while (!env.datastores.globalConfigDataStore.latestSafe.exists(_.perIpThrottlingQuota == 100000000L) && System.currentTimeMillis() < deadline) {
      Thread.sleep(200)
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // calls
  // ---------------------------------------------------------------------------------------------------------------

  case class Call(method: String, path: String, status: Int, contentType: Option[String], body: String) {
    def baseContentType: Option[String] = contentType.map(_.split(";").head.trim.toLowerCase)
    def excerpt: String                 = if (body.length > 300) body.take(297) + "..." else body
    def json: Option[JsValue]           = Try(Json.parse(body)).toOption
    override def toString: String       = s"$method $path answered $status ${contentType.getOrElse("")}: $excerpt"
  }

  private def call(
      method: String,
      path: String,
      body: Option[(String, String)] = None,
      accept: Option[String] = Some("application/json"),
      authenticated: Boolean = true,
      host: String = adminHost,
      streamed: Boolean = false,
      extraHeaders: Seq[(String, String)] = Seq.empty
  ): Call = {
    val headers  = Seq("Host" -> host) ++ accept.map(a => "Accept" -> a) ++ body.map(b => "Content-Type" -> b._1) ++ extraHeaders
    val prepared = ws
      .url(s"http://127.0.0.1:$port$path")
      .withHttpHeaders(headers*)
      .withFollowRedirects(false)
      .withMethod(method.toUpperCase)
    val request  = if (authenticated) prepared.withAuth(clientId, clientSecret, WSAuthScheme.BASIC) else prepared
    // a stream that never ends is read up to its status and its headers
    if (streamed) {
      Try(request.stream().futureValue) match {
        case Failure(e) => Call(method.toUpperCase, path, 0, None, s"no answer: ${Option(e.getCause).getOrElse(e)}")
        case Success(r) =>
          r.bodyAsSource.runWith(org.apache.pekko.stream.scaladsl.Sink.cancelled)(using otoroshiComponents.env.otoroshiMaterializer)
          return Call(method.toUpperCase, path, r.status, r.header("Content-Type"), "")
      }
    }
    val response = Try(body match {
      case Some((_, content)) => request.withBody(content).execute().futureValue
      case None               => request.execute().futureValue
    })
    response match {
      // no answer at all is an answer the document does not describe either
      case Failure(e) => Call(method.toUpperCase, path, 0, None, s"no answer: ${Option(e.getCause).getOrElse(e)}")
      case Success(r) => Call(method.toUpperCase, path, r.status, r.header("Content-Type"), r.body)
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // the document
  // ---------------------------------------------------------------------------------------------------------------

  // the published document describes otoroshi and the admin extensions shipped with it
  private val documentCall: Call = call("GET", "/apis/openapi.json?core=true", accept = None, host = env.backOfficeHost)
  private val documentJson: JsObject = documentCall.json.collect { case o: JsObject => o }.getOrElse(Json.obj())
  private val document               = new OpenApiDocument(documentJson)
  private val validator              = new SchemaValidator(document)
  private val samples                = new SampleBuilder(document)

  private val schemaKeywords = Set(
    "title", "multipleOf", "maximum", "exclusiveMaximum", "minimum", "exclusiveMinimum", "maxLength", "minLength",
    "pattern", "maxItems", "minItems", "uniqueItems", "maxProperties", "minProperties", "required", "enum", "type",
    "allOf", "oneOf", "anyOf", "not", "items", "properties", "additionalProperties", "description", "format",
    "default", "nullable", "discriminator", "readOnly", "writeOnly", "xml", "externalDocs", "example", "deprecated",
    "$ref"
  )
  private val typeFormats: Map[String, Set[String]] = Map(
    "integer" -> Set("int32", "int64"),
    "number"  -> Set("float", "double"),
    "string"  -> Set("byte", "binary", "date", "date-time", "password", "uuid", "uri", "email", "hostname", "ipv4", "ipv6", "regex"),
    "boolean" -> Set.empty,
    "array"   -> Set.empty,
    "object"  -> Set.empty
  )

  // every schema object of the document, nested ones included, with the place it was found at
  private def schemaObjects(schema: JsValue, path: String): Seq[(String, JsObject)] = schema match {
    case obj: JsObject =>
      val nested: Seq[(String, JsValue)] =
        (obj \ "properties").asOpt[JsObject].toSeq.flatMap(_.fields.map { case (k, v) => (JsonPath.field(s"$path.properties", k), v) }) ++
        (obj \ "items").toOption.map(v => (s"$path.items", v)).toSeq ++
        (obj \ "additionalProperties").asOpt[JsObject].map(v => (s"$path.additionalProperties", v)).toSeq ++
        (obj \ "not").asOpt[JsObject].map(v => (s"$path.not", v)).toSeq ++
        Seq("allOf", "oneOf", "anyOf").flatMap { k =>
          (obj \ k).asOpt[Seq[JsValue]].toSeq.flatMap(_.zipWithIndex.map { case (v, i) => (s"$path.$k[$i]", v) })
        }
      (path, obj) +: nested.flatMap { case (p, v) => schemaObjects(v, p) }
    case _             => Seq.empty
  }

  private def operationSchemas(op: OpenApiOperation): Seq[(String, JsValue)] = {
    val at = s"${op.name}"
    op.parameters.flatMap(p => (p \ "schema").toOption.map(s => (s"$at parameter ${(p \ "name").asOpt[String].getOrElse("?")}", s))) ++
    op.requestContent.toSeq.flatMap { case (ct, c) => (c \ "schema").toOption.map(s => (s"$at request $ct", s)) } ++
    op.responses.toSeq.flatMap { case (status, r) =>
      (r \ "content").asOpt[Map[String, JsObject]].getOrElse(Map.empty).toSeq.flatMap { case (ct, c) =>
        (c \ "schema").toOption.map(s => (s"$at response $status $ct", s))
      }
    }
  }

  private lazy val allSchemaObjects: Seq[(String, JsObject)] =
    document.schemas.fields.toSeq.flatMap { case (name, s) => schemaObjects(s, s"#/components/schemas/$name") } ++
    document.operations.flatMap(op => operationSchemas(op).flatMap { case (p, s) => schemaObjects(s, p) })

  private def assertNoViolation(title: String, violations: Seq[Violation]): Unit =
    if (violations.nonEmpty) fail(Violations.report(title, violations))

  "The openapi document" should {
    "be served as json" in {
      withClue(documentCall.toString) {
        documentCall.status mustBe 200
        documentCall.baseContentType mustBe Some("application/json")
        documentJson.keys must not be empty
      }
    }
    "be an openapi 3.0.3 document" in {
      (documentJson \ "openapi").asOpt[String] mustBe Some("3.0.3")
    }
    "be the same document in yaml" in {
      val yaml = call("GET", "/apis/openapi.yaml?core=true", accept = None, host = env.backOfficeHost)
      withClue(yaml.toString.take(300)) {
        yaml.status mustBe 200
        Yaml.parse(yaml.body) mustBe Some(documentJson)
      }
    }
    "describe the running version" in {
      (documentJson \ "info" \ "version").asOpt[String] mustBe Some(env.otoroshiVersion)
    }
    "have a title, a description, a contact and a license" in {
      val info = (documentJson \ "info").asOpt[JsObject].getOrElse(Json.obj())
      Seq("title", "description").foreach(k => withClue(k)((info \ k).asOpt[String].exists(_.trim.nonEmpty) mustBe true))
      (info \ "contact" \ "name").asOpt[String] must not be empty
      (info \ "license" \ "name").asOpt[String] must not be empty
    }
    "declare the admin api as its server" in {
      val urls = (documentJson \ "servers").asOpt[Seq[JsObject]].getOrElse(Seq.empty).flatMap(s => (s \ "url").asOpt[String])
      urls must not be empty
      urls.foreach { url =>
        withClue(url)(Try(new java.net.URI(url).getHost).toOption mustBe Some(env.adminApiExposedHost))
      }
    }
    "declare how to authenticate" in {
      val schemes = (documentJson \ "components" \ "securitySchemes").asOpt[JsObject].getOrElse(Json.obj())
      schemes.keys must not be empty
      val used    = document.operations.flatMap(op => (op.json \ "security").asOpt[Seq[JsObject]].toSeq.flatten.flatMap(_.keys)).toSet
      withClue("security schemes used but not declared")(used.diff(schemes.keys.toSet) mustBe empty)
    }
    "only reference what it holds" in {
      val violations = document.refs(documentJson).collect {
        case (path, ref) if document.resolve(ref).isEmpty => Violation(path, s"$ref does not exist")
      }
      assertNoViolation("dangling $ref", violations)
    }
    "only hold schemas something references" in {
      val referenced = {
        // the schemas reachable from the paths, through the schemas they reference
        var found    = Set.empty[String]
        var frontier = document.refs(document.paths).map(_._2).toSet
        while (frontier.nonEmpty) {
          val names = frontier.collect { case r if r.startsWith("#/components/schemas/") => r.stripPrefix("#/components/schemas/") }
          found = found ++ names
          frontier = names.flatMap(n => (document.schemas \ n).toOption.toSeq.flatMap(s => document.refs(s).map(_._2)))
            .filterNot(r => found.contains(r.stripPrefix("#/components/schemas/")))
        }
        found
      }
      val orphans    = document.schemas.keys.toSeq.sorted.filterNot(referenced.contains)
      assertNoViolation("schemas no operation leads to", orphans.map(n => Violation(s"#/components/schemas/$n", "unused")))
    }
    "give every operation its own operation id" in {
      val ids        = document.operations.flatMap(op => op.operationId.map(_ -> op.name))
      val duplicated = ids.groupBy(_._1).filter(_._2.size > 1).toSeq.sortBy(_._1)
      assertNoViolation(
        "operation ids used several times",
        duplicated.map { case (id, ops) => Violation(id, ops.map(_._2).mkString(", ")) }
      )
    }
    "only use the tags it declares" in {
      val declared = (documentJson \ "tags").asOpt[Seq[JsObject]].getOrElse(Seq.empty).flatMap(t => (t \ "name").asOpt[String]).toSet
      val used     = document.operations.flatMap(op => op.tags.map(_ -> op.name))
      assertNoViolation("undeclared tags", used.filterNot(t => declared.contains(t._1)).map { case (t, op) => Violation(op, t) })
    }
    "only declare the tags its operations use, with a description" in {
      val tags = (documentJson \ "tags").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
      val used = document.operations.flatMap(_.tags).toSet
      assertNoViolation(
        "tags",
        tags.flatMap { t =>
          val name = (t \ "name").asOpt[String].getOrElse("?")
          (if (used.contains(name)) Seq.empty else Seq(Violation(name, "no operation uses it"))) ++
          (if ((t \ "description").asOpt[String].exists(_.trim.nonEmpty)) Seq.empty else Seq(Violation(name, "no description")))
        }
      )
    }
    "only use the keywords of an openapi 3.0 schema" in {
      assertNoViolation(
        "unknown schema keywords",
        allSchemaObjects.flatMap { case (path, s) =>
          s.keys.toSeq.filterNot(k => schemaKeywords.contains(k) || k.startsWith("x-")).map(k => Violation(path, s"unknown keyword '$k'"))
        }
      )
    }
    "only use the types and formats of openapi 3.0" in {
      assertNoViolation(
        "types and formats",
        allSchemaObjects.flatMap { case (path, s) =>
          (s \ "type").toOption match {
            case None                                              => Seq.empty
            case Some(JsString(t)) if !typeFormats.contains(t)     => Seq(Violation(path, s"unknown type '$t'"))
            case Some(JsString(t))                                 =>
              (s \ "format").asOpt[String].filterNot(f => typeFormats(t).contains(f)).map(f => Violation(path, s"format '$f' on a $t")).toSeq
            case Some(other)                                       => Seq(Violation(path, s"type ${Json.stringify(other)} is not a string"))
          }
        }
      )
    }
    "give every array its items" in {
      assertNoViolation(
        "arrays without items",
        allSchemaObjects.collect {
          case (path, s) if (s \ "type").asOpt[String].contains("array") && (s \ "items").isEmpty => Violation(path, "no items")
        }
      )
    }
    "only require the properties it declares" in {
      assertNoViolation(
        "required properties",
        allSchemaObjects.flatMap { case (path, s) =>
          val declared = (s \ "properties").asOpt[JsObject].map(_.keys).getOrElse(Set.empty)
          (s \ "required").asOpt[Seq[String]].getOrElse(Seq.empty).filterNot(declared.contains).map(r => Violation(path, s"requires '$r'"))
        }
      )
    }
    "only give enums of the type of their schema" in {
      assertNoViolation(
        "enums",
        allSchemaObjects.flatMap { case (path, s) =>
          (s \ "enum").asOpt[JsArray].toSeq.flatMap { values =>
            val withoutEnum = s - "enum"
            values.value.toSeq.flatMap(v => validator.validate(v, withoutEnum, s"$path.enum"))
          }
        }
      )
    }
    "only give defaults and examples valid for their schema" in {
      assertNoViolation(
        "defaults and examples",
        allSchemaObjects.flatMap { case (path, s) =>
          Seq("default", "example").flatMap { k =>
            (s \ k).toOption.toSeq.flatMap(v => validator.validate(v, s - k - "example" - "default", s"$path.$k"))
          }
        }
      )
    }
    "describe each line of an ndjson content, not an array of lines" in {
      assertNoViolation(
        "ndjson contents",
        document.operations.flatMap { op =>
          val contents = op.requestContent.toSeq.map { case (ct, c) => (s"${op.name} request", ct, c) } ++
            op.responses.toSeq.flatMap { case (status, r) =>
              (r \ "content").asOpt[Map[String, JsObject]].getOrElse(Map.empty).toSeq.map { case (ct, c) => (s"${op.name} response $status", ct, c) }
            }
          contents.collect {
            case (at, "application/x-ndjson", c) if (c \ "schema").toOption.flatMap(document.deref(_)).exists(s => (s \ "type").asOpt[String].contains("array")) =>
              Violation(at, "the schema of an ndjson content is an array")
          }
        }
      )
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // the schemas
  // ---------------------------------------------------------------------------------------------------------------

  private def described(s: JsValue): Boolean = (s \ "description").asOpt[String].exists(_.trim.nonEmpty)

  document.schemas.fields.toSeq.sortBy(_._1).foreach { case (name, schema) =>
    s"The schema $name" should {
      "say what it describes" in {
        if (!described(schema)) fail(s"$name has no description")
      }
      "say what each of its properties is" in {
        val properties = (schema \ "properties").asOpt[JsObject].getOrElse(Json.obj())
        assertNoViolation(
          s"properties of $name without a description",
          properties.fields.toSeq.collect {
            // a property that is a $ref takes the description of its schema, unless it has one of its own
            case (key, p) if !described(p) && !document.deref(p).exists(described) =>
              Violation(JsonPath.field("$", key), "no description")
          }
        )
      }
      "say what each of its values is" in {
        // a value that can be anything is fine as long as its description says what it holds. an object says what it
        // holds through its properties, or through additionalProperties (true when it holds anything)
        val violations = schemaObjects(schema, s"#/components/schemas/$name").flatMap { case (path, s) =>
          val tpe = (s \ "type").asOpt[String]
          val free = tpe.contains("object") && (s \ "properties").asOpt[JsObject].forall(_.keys.isEmpty) && (s \ "additionalProperties").isEmpty &&
            Seq("allOf", "oneOf", "anyOf").forall(k => (s \ k).isEmpty)
          val any  = !described(s) && tpe.isEmpty && Seq("$ref", "properties", "items", "enum", "allOf", "oneOf", "anyOf", "additionalProperties").forall(k => (s \ k).isEmpty)
          if (free) Seq(Violation(path, "an object without properties nor additionalProperties (true when it can hold anything)"))
          else if (any) Seq(Violation(path, "no type"))
          else Seq.empty
        }
        assertNoViolation(s"values of $name described as anything", violations)
      }
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // the operations
  // ---------------------------------------------------------------------------------------------------------------

  // a route of conf/routes, its path written with openapi parameters
  case class PlayRoute(method: String, path: String, regex: scala.util.matching.Regex, line: Int, controller: String)

  private val playRoutes: Seq[PlayRoute] = {
    val source = scala.io.Source.fromInputStream(env.environment.resourceAsStream("/routes").get)
    try {
      source.getLines().zipWithIndex.toSeq.flatMap { case (raw, idx) =>
        raw.trim.split("\\s+").toList match {
          case method :: path :: rest if Set("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(method) =>
            val segments = path.split("/").toSeq.map {
              case s if s.startsWith(":")  => ("{" + s.drop(1) + "}", "[^/]+")
              case s if s.startsWith("*")  => ("{" + s.drop(1) + "}", ".+")
              case s if s.startsWith("$")  =>
                val name = s.drop(1).takeWhile(_ != '<')
                ("{" + name + "}", s.dropWhile(_ != '<').drop(1).dropRight(1))
              case s                       => (s, java.util.regex.Pattern.quote(s))
            }
            val controller = rest.mkString(" ").takeWhile(_ != '(').trim
            Seq(PlayRoute(method.toLowerCase, segments.map(_._1).mkString("/"), ("^" + segments.map(_._2).mkString("/") + "$").r, idx + 1, controller))
          case _ => Seq.empty
        }
      }
    } finally {
      source.close()
    }
  }

  // the route that serves a documented path: the parameters of the path are replaced by a value no literal segment
  // uses, and play picks the first route that matches
  private def routeOf(op: OpenApiOperation): Option[PlayRoute] = {
    val concrete = "\\{[^}]+\\}".r.replaceAllIn(op.path, "openapi-param")
    playRoutes.find(r => r.method == op.method && r.regex.matches(concrete))
  }

  document.operations.foreach { op =>
    s"The operation ${op.name}" should {
      "be routed by otoroshi" in {
        routeOf(op) match {
          case None    => fail(s"no route of conf/routes serves ${op.name}")
          // the generic routes take the entity as a parameter, a literal segment of the document must not be taken as one
          case Some(r) =>
            val literal = op.path.split("/").zipAll(r.path.split("/"), "", "").collect {
              case (doc, route) if !doc.startsWith("{") && route.startsWith("{") && Set("{id}").contains(route) && doc.startsWith("_") =>
                s"'$doc' is read as the id of an entity by the route ${r.method.toUpperCase} ${r.path} (line ${r.line})"
            }
            if (literal.nonEmpty) fail(literal.mkString("\n"))
            // the operations of the generic api have to reach it, not a route of another controller declared before
            val generic = resources.exists(res => genericOperations(res).exists(_._2 == op.path))
            if (generic && !r.controller.startsWith("otoroshi.api.GenericApiController."))
              fail(s"served by ${r.controller} (route ${r.method.toUpperCase} ${r.path}, line ${r.line}) instead of the generic api")
        }
      }
      "have an operation id, a summary and a description" in {
        val missing = Seq("operationId", "summary", "description").filterNot(k => (op.json \ k).asOpt[String].exists(_.trim.nonEmpty))
        if (missing.nonEmpty) fail(s"missing: ${missing.mkString(", ")}")
      }
      "declare the parameters of its path" in {
        val declared = op.parameters.filter(p => (p \ "in").asOpt[String].contains("path"))
        val names    = declared.flatMap(p => (p \ "name").asOpt[String])
        assertNoViolation(
          "path parameters",
          op.pathParameterNames.filterNot(names.contains).map(n => Violation(n, "in the path but not declared")) ++
          names.filterNot(op.pathParameterNames.contains).map(n => Violation(n, "declared but not in the path")) ++
          declared.filterNot(p => (p \ "required").asOpt[Boolean].contains(true)).map(p => Violation((p \ "name").asOpt[String].getOrElse("?"), "a path parameter must be required"))
        )
      }
      "describe each of its parameters" in {
        assertNoViolation(
          "parameters",
          op.parameters.flatMap { p =>
            val name = (p \ "name").asOpt[String].getOrElse("?")
            Seq(
              (p \ "name").asOpt[String].filter(_.nonEmpty).fold(Seq(Violation(name, "no name")))(_ => Seq.empty),
              (p \ "in").asOpt[String].filter(Set("path", "query", "header", "cookie").contains).fold(Seq(Violation(name, "no valid location")))(_ => Seq.empty),
              if ((p \ "schema").isDefined || (p \ "content").isDefined) Seq.empty else Seq(Violation(name, "no schema")),
              if (described(p)) Seq.empty else Seq(Violation(name, "no description"))
            ).flatten
          }
        )
      }
      "describe its request body" in {
        op.requestBody match {
          case None       => succeed
          case Some(body) =>
            assertNoViolation(
              "request body",
              (if (described(body)) Seq.empty else Seq(Violation("requestBody", "no description"))) ++
              (if (op.requestContent.isEmpty) Seq(Violation("requestBody", "no content")) else Seq.empty) ++
              op.requestContent.toSeq.collect { case (ct, c) if (c \ "schema").isEmpty => Violation(ct, "no schema") }
            )
        }
      }
      "describe its responses" in {
        // a websocket endpoint succeeds with the upgrade
        val successes = op.responses.keys.filter(s => s.startsWith("2") || s == "101")
        assertNoViolation(
          "responses",
          (if (successes.isEmpty) Seq(Violation("responses", "no success response")) else Seq.empty) ++
          op.responses.toSeq.flatMap { case (status, r) =>
            (if (described(r)) Seq.empty else Seq(Violation(status, "no description"))) ++
            (r \ "content").asOpt[Map[String, JsObject]].getOrElse(Map.empty).toSeq.collect {
              case (ct, c) if (c \ "schema").isEmpty => Violation(s"$status $ct", "no schema")
            }
          }
        )
      }
      "be flagged deprecated when its entity is" in {
        resources.find(res => genericOperations(res).exists(_ == (op.method, op.path))) match {
          case None      => succeed
          case Some(res) =>
            withClue(s"${res.group}/${res.pluralName} deprecated: ${res.version.deprecated}") {
              (op.json \ "deprecated").asOpt[Boolean].getOrElse(false) mustBe res.version.deprecated
            }
        }
      }
      "document the error of a call without credentials" in {
        val secured = (op.json \ "security").asOpt[Seq[JsObject]].exists(_.nonEmpty)
        if (secured && !op.documentedStatuses.contains("401")) fail("secured but no 401 response")
      }
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // the routes
  // ---------------------------------------------------------------------------------------------------------------

  private val resources: Seq[Resource] =
    env.allResources.coreResources.filter(_.version.served).sortBy(r => (r.group, r.pluralName))

  private def basePath(r: Resource): String = s"/apis/${r.group}/${r.version.name}/${r.pluralName}"

  private def hasTemplate(r: Resource): Boolean =
    Try(r.access.template(r.version.name, Map.empty, None)).toOption.exists(_ != Json.obj())

  // the operations the generic api serves for an entity, given what its access allows
  private def genericOperations(r: Resource): Seq[(String, String)] = {
    val base = basePath(r)
    val a    = r.access
    Seq(
      (a.canRead, "get", base),
      (a.canCreate, "post", base),
      (a.canDelete, "delete", base),
      (a.canRead, "get", s"$base/_count"),
      (a.canRead, "get", s"$base/_schema"),
      (a.canRead && hasTemplate(r), "get", s"$base/_template"),
      (a.canRead, "get", s"$base/{id}"),
      (a.canCreate, "post", s"$base/{id}"),
      (a.canUpdate, "put", s"$base/{id}"),
      (a.canUpdate, "patch", s"$base/{id}"),
      (a.canDelete, "delete", s"$base/{id}"),
      (a.canBulk && a.canCreate, "post", s"$base/_bulk"),
      (a.canBulk && a.canUpdate, "put", s"$base/_bulk"),
      (a.canBulk && a.canUpdate, "patch", s"$base/_bulk"),
      (a.canBulk && a.canDelete, "delete", s"$base/_bulk")
    ).collect { case (true, m, p) => (m, p) }
  }

  // the /api routes of the entities that the generic api serves on /apis, and the html pages. every other route of
  // the admin api has to be documented
  private val undocumentedApiFamilies: Seq[String] = Seq(
    "apikeys", "routes", "groups", "certificates", "auths", "admins", "scripts", "route-compositions", "tcp", "backends",
    "verifiers", "tenants", "teams", "data-exporter-configs", "error-templates", "client-validators", "services",
    "globalconfig", "new", "{entity}", "admin-sessions", "apps-sessions", "apps-sessions-module", "openapi.json", "openapi"
  )

  // the routes of these families that the generic api has no equivalent of: they have to be documented
  private val requiredInUndocumentedFamilies: Set[(String, String)] = Set(
    ("get", "/api/apikeys/{id}/quotas"),
    ("delete", "/api/apikeys/{id}/quotas"),
    ("post", "/api/certificates/{certId}/_renew"),
    ("post", "/api/certificates/_bundle"),
    ("post", "/api/certificates/_dedupLetsEncrypt"),
    ("delete", "/api/apps-sessions-module/{id}"),
    ("post", "/api/new/resources")
  )

  private val expectedOperations: Seq[(String, String)] = {
    val generic = resources.flatMap(genericOperations)
    val others  = playRoutes.filter { r =>
      val segments = r.path.split("/").toSeq.drop(1)
      segments match {
        case "apis" +: "{group}" +: _                    => false
        case "apis" +: rest                              => !Set(Seq("openapi", "ui"), Seq("openapi"), Seq("workflows", "doc")).contains(rest)
        case "api" +: family +: _                        =>
          (!undocumentedApiFamilies.contains(family) || requiredInUndocumentedFamilies.contains((r.method, r.path))) &&
          r.path != "/api/analytics/_migrate"
        case _                                           => false
      }
    }.map(r => (r.method, r.path))
    (generic ++ others).distinct
  }

  "The admin api" should {
    expectedOperations.foreach { case (method, path) =>
      s"document ${method.toUpperCase} $path" in {
        document.operation(method, path) match {
          case Some(_) => succeed
          case None    =>
            // the name of a parameter is not part of the path
            val shape = "\\{[^}]+\\}".r.replaceAllIn(path, "{}")
            document.operations.find(op => op.method == method && "\\{[^}]+\\}".r.replaceAllIn(op.path, "{}") == shape) match {
              case Some(op) => fail(s"documented as ${op.path}, the route names its parameters ${path}")
              case None     => fail(s"${method.toUpperCase} $path is served but not documented")
            }
        }
      }
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // the entities
  // ---------------------------------------------------------------------------------------------------------------

  private def roundTrip(resource: Resource, json: JsValue): Either[String, JsValue] = {
    val access = resource.access.asInstanceOf[ResourceAccessApi[EntityLocationSupport]]
    Try(access.format.reads(json)) match {
      case Failure(e)                => Left(s"the format of the entity throws $e")
      case Success(JsError(errors))  => Left(s"the format of the entity refuses it: ${Json.stringify(JsError.toJson(errors))}")
      case Success(JsSuccess(v, _))  =>
        Try(access.format.writes(v)) match {
          case Failure(e)      => Left(s"the format of the entity cannot write it: $e")
          case Success(output) => Right(output)
        }
    }
  }

  case class Variant(label: String, schema: JsValue, template: JsValue, tpe: Option[String])

  private def template(resource: Resource, params: Map[String, String]): JsValue =
    Try(resource.access.template(resource.version.name, params, None)).getOrElse(Json.obj())

  private def under(path: String)(v: Violation): Boolean =
    v.path == path || v.path.startsWith(path + ".") || v.path.startsWith(path + "[")

  private def lostLeaves(sent: JsValue, schema: JsValue, written: JsValue, at: String): Seq[Violation] =
    samples.leaves(sent, schema, at).flatMap { leaf =>
      JsonPathReader.read(written, leaf.path) match {
        case Some(back) if back == leaf.value => None
        case Some(back)                       => Some(Violation(leaf.path, s"sent ${Json.stringify(leaf.value)}, read back ${Json.stringify(back)}"))
        case None                             => Some(Violation(leaf.path, s"sent ${Json.stringify(leaf.value)}, not written back"))
      }
    }

  // the properties of an object schema, the ones of its allOf parts included
  private def properties(schema: JsValue): Seq[(String, JsValue)] = document.deref(schema).toSeq.flatMap { s =>
    (s \ "properties").asOpt[JsObject].toSeq.flatMap(_.fields) ++
    (s \ "allOf").asOpt[Seq[JsValue]].toSeq.flatten.flatMap(properties)
  }

  // what the generic api writes on top of an entity
  private def withoutKind(js: JsValue): JsValue = js match {
    case o: JsObject => o - "kind"
    case other       => other
  }

  // the schema documented for a content, and the json values of a body in that content
  private def bodyValues(contentType: String, body: String): Either[String, Seq[JsValue]] = contentType match {
    case "application/json"                           => Try(Json.parse(body)).toEither.left.map(e => s"not json: ${e.getMessage}").map(Seq(_))
    case "application/x-ndjson"                       =>
      Try(body.split("\n").toSeq.map(_.trim).filter(_.nonEmpty).map(Json.parse)).toEither.left.map(e => s"not ndjson: ${e.getMessage}")
    case "application/yaml" | "application/yml"       => Yaml.parse(body).map(Seq(_)).toRight("not yaml")
    case _                                            => Right(Seq.empty)
  }

  // a request built from the document is a valid one: the api has to accept it
  private def accepted(op: OpenApiOperation, c: Call): Seq[Violation] =
    if (c.status >= 200 && c.status < 300) {
      // a bulk operation answers 200 and tells the outcome of each entity on its own line
      if (c.baseContentType.contains("application/x-ndjson")) {
        bodyValues("application/x-ndjson", c.body).getOrElse(Seq.empty).zipWithIndex.collect {
          case (line, idx) if (line \ "status").asOpt[Int].orElse((line \ "http_status_code").asOpt[Int]).exists(_ >= 300) =>
            Violation(s"${op.name} line ${idx + 1}", s"refused the entity built from the document: ${Json.stringify(line).take(300)}")
        }
      } else Seq.empty
    } else Seq(Violation(op.name, s"refused the request built from the document: ${c.status} ${c.excerpt}"))

  // what a client reading the document expects from a call
  private def conformance(op: OpenApiOperation, c: Call): Seq[Violation] = {
    val status = c.status.toString
    op.response(c.status) match {
      case None           =>
        Seq(Violation(s"${op.name}", s"answered $status ${c.contentType.getOrElse("without content type")}, documented ${op.documentedStatuses.toSeq.sorted.mkString(", ")}: ${c.excerpt}"))
      case Some(response) =>
        val contents = (response \ "content").asOpt[Map[String, JsObject]].getOrElse(Map.empty)
        if (contents.isEmpty) {
          if (c.body.trim.nonEmpty) Seq(Violation(s"${op.name} $status", s"answered a body the document does not describe: ${c.excerpt}"))
          else Seq.empty
        } else {
          c.baseContentType match {
            case None                                  => Seq(Violation(s"${op.name} $status", s"answered no content type, documented ${contents.keys.mkString(", ")}"))
            case Some(ct) if !contents.contains(ct)    =>
              Seq(Violation(s"${op.name} $status", s"answered $ct, documented ${contents.keys.mkString(", ")}: ${c.excerpt}"))
            case Some(ct)                              =>
              (contents(ct) \ "schema").toOption match {
                case None         => Seq.empty
                case Some(schema) =>
                  bodyValues(ct, c.body) match {
                    case Left(err)    => Seq(Violation(s"${op.name} $status", s"$err: ${c.excerpt}"))
                    case Right(values) =>
                      values.zipWithIndex.flatMap { case (v, idx) =>
                        validator.validate(v, schema, if (values.size > 1) s"$$[line ${idx + 1}]" else "$")
                      }
                  }
              }
          }
        }
    }
  }

  // a call the way the document describes it: the first documented content of the request, json when documented
  private def documentedRequest(op: OpenApiOperation, values: Seq[JsValue]): Option[(String, String)] = {
    val contents = op.requestContent.keys.toSeq
    if (contents.isEmpty) None
    else encode(if (contents.contains("application/json")) "application/json" else contents.head, values)
  }

  private def encode(_ct: String, values: Seq[JsValue]): Option[(String, String)] = {
    // a body of any type is sent as raw bytes
    val ct = if (_ct.contains("*")) "application/octet-stream" else _ct
    Some(ct -> (ct match {
        case "application/x-ndjson"                     => values.map(Json.stringify).mkString("\n")
        case "application/yaml" | "application/yml"     => values.headOption.map(Yaml.write).getOrElse("")
        case "application/x-www-form-urlencoded"        =>
          values.headOption.collect { case o: JsObject => o }.getOrElse(Json.obj()).fields.map { case (k, v) =>
            s"${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v match { case JsString(s) => s; case o => Json.stringify(o) }, "UTF-8")}"
          }.mkString("&")
        // a body that is not json (a pem file, raw bytes) is sent as the string it is
        case other if !other.contains("json")           =>
          values match {
            case Seq(JsString(raw)) => raw
            case Seq(single)        => Json.stringify(single)
            case many               => Json.stringify(JsArray(many))
          }
        case _                                          =>
          values match {
            case Seq(single) => Json.stringify(single)
            case many        => Json.stringify(JsArray(many))
          }
      }))
  }

  // every content type documented for the success of an operation, json first
  private def successContents(op: OpenApiOperation): Seq[String] = {
    val success = op.responses.toSeq.filter(_._1.startsWith("2")).sortBy(_._1).headOption
    val types   = success.flatMap(s => (s._2 \ "content").asOpt[Map[String, JsObject]].map(_.keys.toSeq)).getOrElse(Seq.empty)
    types.sortBy(t => if (t == "application/json") "" else t)
  }

  // the content type a client asks for: the documented content of the success response
  private def documentedAccept(op: OpenApiOperation): Option[String] = {
    val success = op.responses.toSeq.filter(_._1.startsWith("2")).sortBy(_._1).headOption
    success.flatMap(s => (s._2 \ "content").asOpt[Map[String, JsObject]].flatMap(_.keys.headOption)).orElse(Some("application/json"))
  }

  private def perform(op: OpenApiOperation, path: String, values: Seq[JsValue]): Call = {
    val accept = documentedAccept(op)
    call(op.method, path, documentedRequest(op, values), accept = accept, streamed = accept.contains("text/event-stream"))
  }

  // some templates always hold the same id, every entity created by the spec gets its own
  // an entity without a template is created from the example of its schema
  private def freshTemplate(resource: Resource): Option[JsObject] = {
    val c        = call("GET", s"${basePath(resource)}/_template")
    val template = if (c.status == 200) c.json.collect { case o: JsObject => withoutKind(o).as[JsObject] }
    else (document.schemas \ s"${resource.group}.${resource.kind}" \ "example").asOpt[JsObject]
    template.map { t =>
      prepared(resource, t ++ Json.obj(resource.access.idFieldName() -> s"openapi-${resource.singularName}-${counter.incrementAndGet()}"))
    }
  }

  // an api subscription can only be created for a plan of a staging or published api: one is created, with the
  // plan of the example of the document
  private lazy val parentApi: Either[String, (String, String)] = {
    val base = "/apis/apis.otoroshi.io/v1/apis"
    call("GET", s"$base/_template").json.collect { case o: JsObject => withoutKind(o).as[JsObject] } match {
      case None    => Left("no template of api")
      case Some(t) =>
        val id   = s"openapi-parent-api-${counter.incrementAndGet()}"
        val plan = (document.schemas \ "ApiPlan" \ "example").asOpt[JsObject].getOrElse(Json.obj()) ++
          Json.obj("id" -> "openapi-plan", "status" -> "published", "access_mode_configuration_type" -> "apikey")
        // a new api has to be created in staging, which subscriptions accept too
        val body = t ++ Json.obj("id" -> id, "state" -> "staging", "plans" -> Json.arr(plan))
        val c    = call("POST", base, Some("application/json" -> Json.stringify(body)))
        if (c.status < 300) Right((id, "openapi-plan")) else Left(s"the api the subscriptions need is refused: $c")
    }
  }

  // what an entity needs to be created
  private def prepared(resource: Resource, body: JsObject): JsObject =
    if (resource.group == "apis.otoroshi.io" && resource.pluralName == "apisubscriptions")
      parentApi match {
        case Left(err)          => fail(err)
        case Right((api, plan)) => body ++ Json.obj("api_ref" -> api, "plan_ref" -> plan, "subscription_kind" -> "apikey")
      }
    else body

  private def idOf(resource: Resource, js: JsValue): Option[String] = Try(resource.access.extractIdJson(js)).toOption.filter(_.nonEmpty)

  resources.foreach { resource =>
    val base        = basePath(resource)
    val schemaName  = s"${resource.group}.${resource.kind}"
    val schema      = document.schemaRef(schemaName)
    val hasSchema   = (document.schemas \ schemaName).isDefined
    // an entity of several types (auth modules, data exporters) is a oneOf of its types, each one with its own
    // template: the field by field checks are run for each type. the type of a variant is the single value of the
    // enum of its `type` property
    val variants: Seq[Variant] = {
      val alternatives = document.deref(schema).flatMap(s => (s \ "oneOf").asOpt[Seq[JsValue]]).getOrElse(Seq.empty)
      if (alternatives.isEmpty) Seq(Variant("", schema, template(resource, Map.empty), None))
      else
        alternatives.zipWithIndex.map { case (alt, idx) =>
          val tpe = document.deref(alt).flatMap(a => (a \ "properties" \ "type" \ "enum").asOpt[Seq[String]]).flatMap(_.headOption)
          Variant(s" of type ${tpe.getOrElse(s"#$idx")}", alt, template(resource, tpe.map(t => Map("type" -> t)).getOrElse(Map.empty)), tpe)
        }
    }
    val documented  = variants.flatMap(v => properties(v.schema)).distinctBy(_._1).sortBy(_._1)
    // the entities this spec created through the api, deleted once their checks are done
    val created     = TrieMap.empty[String, Unit]

    def op(method: String, suffix: String): Option[OpenApiOperation] = document.operation(method, s"$base$suffix")
    def documentedOp(method: String, suffix: String): OpenApiOperation =
      op(method, suffix).getOrElse(cancel(s"the document does not describe ${method.toUpperCase} $base$suffix"))
    def target: String = created.keys.headOption.getOrElse {
      call("GET", base).json.flatMap(_.asOpt[Seq[JsValue]]).flatMap(_.headOption).flatMap(e => idOf(resource, e))
        .getOrElse(cancel(s"no ${resource.singularName} to call the api on"))
    }
    def cleanup(): Unit = created.keys.foreach { id =>
      call("DELETE", s"$base/$id")
      created.remove(id)
    }

    s"The entity $base" should {

      // in process: the schema against the format of the entity

      "be described by its own schema" in {
        if (!hasSchema) fail(s"no schema $schemaName in the components")
        if (documented.isEmpty) fail(s"the schema $schemaName describes no property")
      }
      variants.foreach { variant =>
        val v       = variant.label
        // the entities without a template (the api answers none, or one of another type) are checked from the
        // example of their schema
        val served  = variant.template match {
          case o: JsObject if o.keys.isEmpty                                                     => false
          case t if variant.tpe.exists(tpe => !(t \ "type").asOpt[String].contains(tpe)) => false
          case _                                                                                  => true
        }
        val tmpl    = if (served) variant.template
        else document.deref(variant.schema).flatMap(s => (s \ "example").toOption).getOrElse(variant.template)
        variant.tpe.foreach { tpe =>
          s"build a template$v" in {
            if (!served) fail(s"the template asked for the type $tpe is of type ${(variant.template \ "type").asOpt[String].getOrElse("none")}")
          }
        }
        val vschema = variant.schema
        val vdocumented = properties(vschema).distinctBy(_._1).sortBy(_._1)
        val written = roundTrip(resource, tmpl)
        s"describe its id$v" in {
          val idField = resource.access.idFieldName()
          vdocumented.map(_._1) must contain(idField)
        }
        s"describe the template the api serves$v" in {
          // against the schema of the entity: a template of a type has to match the variant of its type only
          assertNoViolation(s"the template of $base$v against $schemaName", validator.validate(tmpl, schema))
        }
        s"read back an entity filling every documented field$v" in {
          samples.sample(vschema) match {
            case None         => fail(s"no value can be built from $schemaName$v")
            case Some(sample) =>
              roundTrip(resource, sample) match {
                case Left(err)     => fail(err)
                case Right(output) => assertNoViolation("documented fields lost", lostLeaves(sample, vschema, output, "$"))
              }
          }
        }
        s"provide an example the api reads back unchanged$v" in {
          document.deref(vschema).flatMap(s => (s \ "example").toOption) match {
            case None          => fail(s"$schemaName$v has no example")
            case Some(example) =>
              assertNoViolation("the example against its schema", validator.validate(example, schema))
              roundTrip(resource, example) match {
                case Left(err)     => fail(err)
                case Right(output) => assertNoViolation("example fields lost", lostLeaves(example, vschema, output, "$"))
              }
          }
        }
        vdocumented.filter(p => samples.readOnly(p._2)).foreach { case (name, _) =>
          s"answer the read only field $name$v" in {
            val listed = call("GET", base).json.flatMap(_.asOpt[Seq[JsValue]]).flatMap(_.headOption)
            listed match {
              case None    => cancel(s"no ${resource.singularName} to read")
              case Some(e) => if ((e \ name).isEmpty) fail(s"$name is documented as written by the api, it is not in ${Json.stringify(e).take(300)}")
            }
          }
        }
        vdocumented.filterNot(p => samples.readOnly(p._2)).foreach { case (name, propertySchema) =>
          s"keep the documented field $name$v" in {
            samples.sample(propertySchema) match {
              case None        => fail(s"no value can be built from the schema of $name")
              case Some(value) =>
                val input = tmpl.asOpt[JsObject].getOrElse(Json.obj()) ++ Json.obj(name -> value)
                roundTrip(resource, input) match {
                  case Left(err)     => fail(s"once $name holds ${Json.stringify(value).take(200)}, $err")
                  case Right(output) =>
                    val at     = JsonPath.field("$", name)
                    val lost   = lostLeaves(value, propertySchema, output, at)
                    val absent = if ((output \ name).isEmpty) Seq(Violation(at, "documented but never written")) else Seq.empty
                    assertNoViolation(s"documented field $name", (absent ++ lost).distinct)
                }
            }
          }
        }
        written match {
          case Left(err)     =>
            s"write its template back$v" in fail(err)
          case Right(output) =>
            output.asOpt[JsObject].getOrElse(Json.obj()).keys.toSeq.sorted.foreach { key =>
              s"document the written field $key$v" in {
                val at = JsonPath.field("$", key)
                assertNoViolation(s"written field $key", validator.validate(output, vschema).filter(under(at)))
              }
            }
        }
      }

      // through the api: each documented operation, called the way the document describes it

      "serve its template as documented" in {
        val o = documentedOp("get", "/_template")
        val call0 = perform(o, o.path, Seq.empty)
        assertNoViolation(o.name, accepted(o, call0) ++ conformance(o, call0))
      }
      "create an entity as documented" in {
        val o = documentedOp("post", "")
        // one entity per documented content type of the body
        assertNoViolation(o.name, o.requestContent.keys.toSeq.sorted.flatMap { ct =>
          val body = freshTemplate(resource).getOrElse(cancel("no template to create an entity from"))
          val c    = call("POST", base, encode(ct, Seq(body)), accept = documentedAccept(o))
          idOf(resource, body).filter(_ => c.status < 300).foreach(id => created.put(id, ()))
          (accepted(o, c) ++ conformance(o, c)).map(v => v.copy(message = s"[$ct] ${v.message}"))
        })
      }
      "create the documented sample" in {
        val o      = documentedOp("post", "")
        val sample = samples.sample(schema).collect { case s: JsObject => s }.getOrElse(fail(s"no value can be built from $schemaName"))
        val id     = s"openapi-sample-${counter.incrementAndGet()}"
        val body   = prepared(resource, sample ++ Json.obj(resource.access.idFieldName() -> id))
        val c      = perform(o, base, Seq(body))
        if (c.status < 300) call("DELETE", s"$base/$id")
        assertNoViolation(o.name, accepted(o, c) ++ conformance(o, c))
      }
      "read an entity as documented" in {
        val o  = documentedOp("get", "/{id}")
        val id = target
        assertNoViolation(o.name, successContents(o).flatMap { ct =>
          val c = call("GET", s"$base/$id", accept = Some(ct))
          accepted(o, c) ++ conformance(o, c)
        })
      }
      "list the entities as documented" in {
        val o = documentedOp("get", "")
        assertNoViolation(o.name, successContents(o).flatMap { ct =>
          val c = call("GET", base, accept = Some(ct))
          accepted(o, c) ++ conformance(o, c)
        })
      }
      "serve the schema the document holds" in {
        val c = call("GET", s"$base/_schema")
        c.json match {
          case Some(answered: JsObject) =>
            (document.schemas \ schemaName).toOption match {
              case None             => fail(s"no schema $schemaName in the document")
              case Some(documented) =>
                (answered - "components") mustBe documented
                val referenced = document.refs(documented).map(_._2.stripPrefix("#/components/schemas/")).toSet
                referenced.diff((answered \ "components" \ "schemas").asOpt[JsObject].map(_.keys.toSet).getOrElse(Set.empty)) mustBe empty
            }
          case _                         => fail(c.toString)
        }
      }
      "count the entities as documented" in {
        val o = documentedOp("get", "/_count")
        val c = perform(o, s"$base/_count", Seq.empty)
        assertNoViolation(o.name, accepted(o, c) ++ conformance(o, c))
        val listed = call("GET", base).json.flatMap(_.asOpt[Seq[JsValue]]).map(_.size)
        (c.json.flatMap(j => (j \ "count").asOpt[Int]), listed) match {
          case (Some(count), Some(size)) => count mustBe size
          case _                         => fail(s"no count to compare with the list: $c")
        }
      }
      "serve its schema as documented" in {
        val o = documentedOp("get", "/_schema")
        val call0 = perform(o, s"$base/_schema", Seq.empty)
        assertNoViolation(o.name, accepted(o, call0) ++ conformance(o, call0))
      }
      "update an entity as documented" in {
        val o       = documentedOp("put", "/{id}")
        val id      = target
        val current = call("GET", s"$base/$id").json.map(withoutKind).getOrElse(cancel(s"$base/$id cannot be read"))
        val call0 = perform(o, s"$base/$id", Seq(current))
        assertNoViolation(o.name, accepted(o, call0) ++ conformance(o, call0))
      }
      "upsert an entity as documented" in {
        val o       = documentedOp("post", "/{id}")
        val id      = target
        val current = call("GET", s"$base/$id").json.map(withoutKind).getOrElse(cancel(s"$base/$id cannot be read"))
        val call0 = perform(o, s"$base/$id", Seq(current))
        assertNoViolation(o.name, accepted(o, call0) ++ conformance(o, call0))
      }
      "patch an entity as documented" in {
        val o  = documentedOp("patch", "/{id}")
        val id = target
        // an empty patch in each documented content type, the entity may not have been created by this spec
        assertNoViolation(o.name, o.requestContent.keys.toSeq.sorted.flatMap { ct =>
          val body = ct match {
            case "application/x-www-form-urlencoded" => Some(ct -> "")
            case _                                   => Some(ct -> "[]")
          }
          val c    = call("PATCH", s"$base/$id", body, accept = documentedAccept(o))
          (accepted(o, c) ++ conformance(o, c)).map(v => v.copy(message = s"[$ct] ${v.message}"))
        })
      }
      "create entities in bulk as documented" in {
        val o      = documentedOp("post", "/_bulk")
        val bodies = Seq(freshTemplate(resource), freshTemplate(resource)).flatten
        val c      = perform(o, s"$base/_bulk", bodies)
        bodies.flatMap(b => idOf(resource, b)).foreach(id => created.put(id, ()))
        assertNoViolation(o.name, accepted(o, c) ++ conformance(o, c))
      }
      "update entities in bulk as documented" in {
        val o      = documentedOp("put", "/_bulk")
        val bodies = created.keys.toSeq.flatMap(id => call("GET", s"$base/$id").json.map(withoutKind))
        if (bodies.isEmpty) cancel("no entity created by this spec to update")
        val call0 = perform(o, s"$base/_bulk", bodies)
        assertNoViolation(o.name, accepted(o, call0) ++ conformance(o, call0))
      }
      "patch entities in bulk as documented" in {
        val o          = documentedOp("patch", "/_bulk")
        val bodySchema = o.requestContent.values.headOption.flatMap(c => (c \ "schema").toOption).getOrElse(fail("no documented body"))
        // an empty patch: the example of the document patches fields not every entity has
        val bodies     = created.keys.toSeq.flatMap { id =>
          samples.sample(bodySchema).map {
            case obj: JsObject => obj ++ Json.obj(resource.access.idFieldName() -> id, "patch" -> Json.arr())
            case other         => other
          }
        }
        if (bodies.isEmpty) cancel("no entity created by this spec to patch")
        val call0 = perform(o, s"$base/_bulk", bodies)
        assertNoViolation(o.name, accepted(o, call0) ++ conformance(o, call0))
      }
      "delete entities in bulk as documented" in {
        val o   = documentedOp("delete", "/_bulk")
        val ids = created.keys.toSeq.drop(1)
        if (ids.isEmpty) cancel("no entity created by this spec to delete")
        val c   = perform(o, s"$base/_bulk", ids.map(id => Json.obj(resource.access.idFieldName() -> id)))
        if (c.status < 300) ids.foreach(created.remove)
        assertNoViolation(o.name, accepted(o, c) ++ conformance(o, c))
      }
      "delete an entity as documented" in {
        val o  = documentedOp("delete", "/{id}")
        val id = created.keys.headOption.getOrElse(cancel("no entity created by this spec to delete"))
        val c  = perform(o, s"$base/$id", Seq.empty)
        if (c.status < 300) created.remove(id)
        assertNoViolation(o.name, accepted(o, c) ++ conformance(o, c))
      }
      "answer an unknown entity as documented" in {
        val o = documentedOp("get", "/{id}")
        val c = perform(o, s"$base/openapi-unknown-${counter.incrementAndGet()}", Seq.empty)
        c.status mustBe 404
        assertNoViolation(o.name, conformance(o, c))
      }
      "answer a call without credentials as documented" in {
        val o = documentedOp("get", "")
        val c = call("GET", base, accept = documentedAccept(o), authenticated = false)
        withClue(c.toString)(c.status / 100 mustBe 4)
        assertNoViolation(o.name, conformance(o, c))
      }
      "answer a call with wrong credentials as documented" in {
        val o = documentedOp("get", "")
        val c = ws
          .url(s"http://127.0.0.1:$port$base")
          .withHttpHeaders("Host" -> adminHost, "Accept" -> "application/json")
          .withAuth(clientId, "not-the-secret", WSAuthScheme.BASIC)
          .get()
          .futureValue
        val answered = Call("GET", base, c.status, c.header("Content-Type"), c.body)
        withClue(answered.toString)(answered.status / 100 mustBe 4)
        assertNoViolation(o.name, conformance(o, answered))
      }
      "answer an entity that is not an object as documented" in {
        val o = documentedOp("post", "")
        val c = call("POST", base, Some("application/json" -> "[]"), accept = documentedAccept(o))
        c.status mustBe 400
        assertNoViolation(o.name, conformance(o, c))
      }
      "say whether it is deprecated as documented" in {
        val c = ws
          .url(s"http://127.0.0.1:$port$base")
          .withHttpHeaders("Host" -> adminHost, "Accept" -> "application/json")
          .withAuth(clientId, clientSecret, WSAuthScheme.BASIC)
          .get()
          .futureValue
        c.header("Otoroshi-Api-Deprecated") mustBe (if (resource.version.deprecated) Some("yes") else None)
      }
      "leave no entity behind" in {
        cleanup()
        created mustBe empty
      }
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // the other operations
  // ---------------------------------------------------------------------------------------------------------------

  // the values of the path parameters of the documented operations: the ids of entities a new instance holds, or
  // ids nothing holds, that the operation has to answer as documented
  private def pathParameterValue(name: String): String = name match {
    case "ca" => "otoroshi-intermediate-ca"
    case _    => s"openapi-unknown-${counter.incrementAndGet()}"
  }

  // the operations that change the whole instance run last, the import replacing everything it holds
  private val lastOperations: Seq[(String, String)] = Seq(
    ("post", "/api/snowmonkey/_start"),
    ("post", "/api/snowmonkey/_stop"),
    ("delete", "/api/cluster/members"),
    ("post", "/api/otoroshi.json"),
    ("post", "/api/import")
  )

  // every operation that is not one of the generic api, the specific operations of an entity included
  // the legacy analytics read the events from an elasticsearch the test instance has none of: they answer the
  // documented 404 "no elastic read config found"
  private val needsElastic: Set[(String, String)] = Set(
    ("get", "/api/events"),
    ("get", "/api/stats"),
    ("get", "/api/stats/global"),
    ("get", "/api/status/global")
  )

  private val otherOperations: Seq[OpenApiOperation] = {
    val generic = resources.flatMap(genericOperations).toSet
    val others  = document.operations.filterNot(op => generic.contains((op.method, op.path)))
    val (last, first) = others.partition(op => lastOperations.contains((op.method, op.path)))
    first ++ lastOperations.flatMap(l => last.find(op => (op.method, op.path) == l))
  }

  // the operations that call a system outside of the test instance, the suite does not call them
  private val callsOutside: Map[(String, String), String] = Map(
    ("post", "/api/pki/certs/_letencrypt") -> "it orders a certificate from let's encrypt"
  )

  // an import replaces the whole state of the instance: it gets the export of the instance, which also checks that
  // an export can be imported back
  private val imports: Set[(String, String)] = Set(("post", "/api/otoroshi.json"), ("post", "/api/import"))

  "The other operations" should {
    otherOperations.foreach { op =>
      s"answer ${op.name} as documented" in {
        // the suite has no websocket client: an upgrade is only checked by the description of the operation
        if (op.documentedStatuses.contains("101")) cancel(s"${op.name} is a websocket endpoint")
        callsOutside.get((op.method, op.path)).foreach(reason => cancel(s"${op.name} is not called: $reason"))
        val path   = op.pathParameterNames.foldLeft(op.path)((p, n) => p.replace(s"{$n}", pathParameterValue(n)))
        val query  = op.parameters.filter(p => (p \ "in").asOpt[String].contains("query") && (p \ "required").asOpt[Boolean].contains(true))
          .flatMap(p => (p \ "name").asOpt[String].map(n => s"$n=${samples.sample((p \ "schema").getOrElse(Json.obj())).map { case JsString(v) => v; case v => Json.stringify(v) }.getOrElse("")}"))
        val target = if (query.isEmpty) path else s"$path?${query.mkString("&")}"
        val body   =
          if (imports.contains((op.method, op.path))) call("GET", "/api/otoroshi.json").json.toSeq
          else op.requestContent.values.headOption.flatMap(c => (c \ "schema").toOption).flatMap(samples.sample).toSeq
        val headers = op.parameters
          .filter(p => (p \ "in").asOpt[String].contains("header") && (p \ "required").asOpt[Boolean].contains(true))
          .flatMap { p =>
            (p \ "name").asOpt[String].map { n =>
              n -> samples.sample((p \ "schema").getOrElse(Json.obj())).map { case JsString(v) => v; case v => Json.stringify(v) }.getOrElse("")
            }
          }
        val accept = documentedAccept(op)
        val c      = call(op.method, target, documentedRequest(op, body), accept = accept, streamed = accept.contains("text/event-stream"), extraHeaders = headers)
        val outcome = if (c.status == 0 || c.status >= 500) Seq(Violation(op.name, s"failed: ${c.status} ${c.excerpt}")) else Seq.empty
        // without parameters to guess, a request built from the document has to succeed, unless it needs a system the
        // test instance does not have
        val success =
          if (op.pathParameterNames.isEmpty && op.requestBody.isEmpty && !needsElastic.contains((op.method, op.path))) accepted(op, c)
          else Seq.empty
        assertNoViolation(op.name, outcome ++ success ++ conformance(op, c))
      }
    }
  }
}
