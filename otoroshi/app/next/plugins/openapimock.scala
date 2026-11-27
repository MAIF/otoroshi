package otoroshi.next.plugins

import com.github.blemale.scaffeine.{Cache, Scaffeine}
import org.apache.pekko.stream.Materializer
import org.apache.pekko.util.ByteString
import otoroshi.env.Env
import otoroshi.next.plugins.api.*
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits.*
import otoroshi.utils.yaml.Yaml
import play.api.libs.json.*

import java.util.UUID
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class OpenApiMockConfig(
    url: Option[String] = None,
    content: Option[String] = None,
    specTtl: Long = 600,
    passThrough: Boolean = false,
    stateful: Boolean = true,
    maxSizeMb: Long = 50,
    ttlHours: Long = 24,
    seed: JsObject = Json.obj(),
    readOnly: Boolean = false,
    resetOnChange: Boolean = true,
    adminPath: String = "/_mock"
) extends NgPluginConfig {
  def json: JsValue = OpenApiMockConfig.format.writes(this)
}

object OpenApiMockConfig {

  val configFlow: Seq[String] = Seq(
    "url",
    "content",
    "spec_ttl",
    "pass_through",
    "stateful",
    "seed",
    "read_only",
    "reset_on_change",
    "admin_path",
    "max_size_mb",
    "ttl_hours"
  )

  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "url"          -> Json.obj(
        "type"        -> "string",
        "label"       -> "OpenAPI spec URL",
        "placeholder" -> "https://example.com/openapi.json"
      ),
      "content"      -> Json.obj(
        "type"  -> "code",
        "label" -> "OpenAPI spec content (JSON or YAML, used instead of the URL)",
        "props" -> Json.obj("editorOnly" -> true)
      ),
      "spec_ttl"     -> Json.obj(
        "type"  -> "number",
        "label" -> "Spec cache TTL (seconds)"
      ),
      "pass_through" -> Json.obj(
        "type"  -> "bool",
        "label" -> "Forward requests unknown to the spec to the backend"
      ),
      "stateful"     -> Json.obj(
        "type"  -> "bool",
        "label" -> "Keep the data created by POST, PUT, PATCH and DELETE"
      ),
      "seed"         -> Json.obj(
        "type"    -> "json",
        "label"   -> "Initial state, by collection path (ex: {\"/pet\": [{\"id\": 1}]})",
        "default" -> "{}"
      ),
      "read_only"    -> Json.obj(
        "type"  -> "bool",
        "label" -> "Read only: writes are answered but never change the state"
      ),
      "reset_on_change" -> Json.obj(
        "type"  -> "bool",
        "label" -> "Reset the written data when the spec or the seed changes"
      ),
      "admin_path"   -> Json.obj(
        "type"  -> "string",
        "label" -> "Path prefix of the state management routes"
      ),
      "max_size_mb"  -> Json.obj(
        "type"  -> "number",
        "label" -> "Max stored data (MB)"
      ),
      "ttl_hours"    -> Json.obj(
        "type"  -> "number",
        "label" -> "Stored data TTL (hours)"
      )
    )
  )

  val format: Format[OpenApiMockConfig] = new Format[OpenApiMockConfig] {

    override def writes(o: OpenApiMockConfig): JsValue = Json.obj(
      "url"          -> o.url.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "content"      -> o.content.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "spec_ttl"     -> o.specTtl,
      "pass_through" -> o.passThrough,
      "stateful"     -> o.stateful,
      "max_size_mb"  -> o.maxSizeMb,
      "ttl_hours"    -> o.ttlHours,
      "seed"         -> o.seed,
      "read_only"    -> o.readOnly,
      "reset_on_change" -> o.resetOnChange,
      "admin_path"   -> o.adminPath
    )

    override def reads(json: JsValue): JsResult[OpenApiMockConfig] = Try {
      OpenApiMockConfig(
        url = json.select("url").asOpt[String].map(_.trim).filter(_.nonEmpty),
        content = json.select("content").asOpt[String].filter(_.trim.nonEmpty),
        specTtl = json.select("spec_ttl").asOpt[Long].getOrElse(600L),
        passThrough = json.select("pass_through").asOpt[Boolean].getOrElse(false),
        stateful = json.select("stateful").asOpt[Boolean].getOrElse(true),
        maxSizeMb = json.select("max_size_mb").asOpt[Long].getOrElse(50L),
        ttlHours = json.select("ttl_hours").asOpt[Long].getOrElse(24L),
        seed = json.select("seed").asOpt[JsObject].getOrElse(Json.obj()),
        readOnly = json.select("read_only").asOpt[Boolean].getOrElse(false),
        resetOnChange = json.select("reset_on_change").asOpt[Boolean].getOrElse(true),
        adminPath = json
          .select("admin_path")
          .asOpt[String]
          .map(_.trim.stripSuffix("/"))
          .filter(_.startsWith("/"))
          .getOrElse("/_mock")
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

case class OpenApiMockOperation(method: String, template: String, segments: Seq[String], definition: JsObject)

case class OpenApiMockMatch(operation: OpenApiMockOperation, segments: Seq[String], params: Map[String, String])

case class OpenApiMockResource(
    collection: String,
    item: String,
    idParam: String,
    idField: String,
    integerId: Boolean,
    itemSchema: JsValue
)

class OpenApiMockSchemas(root: JsValue) {

  private def lookup(ref: String): Option[JsValue] = {
    if (!ref.startsWith("#/"))
      None
    else
      ref
        .drop(2)
        .split("/")
        .toSeq
        .map(_.replace("~1", "/").replace("~0", "~"))
        .foldLeft(Option(root))((current, key) => current.flatMap(_.select(key).asOpt[JsValue]))
  }

  def resolve(value: JsValue): JsValue = {

    @tailrec
    def loop(current: JsValue, hops: Int): JsValue = current.select("$ref").asOpt[String] match {
      case Some(ref) if hops < 10 =>
        lookup(ref) match {
          case Some(target) => loop(target, hops + 1)
          case None         => Json.obj()
        }
      case _                      => current
    }

    loop(value, 0)
  }

  def properties(schema: JsValue, depth: Int = 0): Map[String, JsValue] = {
    if (depth > 10)
      Map.empty
    else {
      val resolved  = resolve(schema)
      val own       = resolved.select("properties").asOpt[JsObject].map(_.value.toMap).getOrElse(Map.empty)
      val inherited = resolved
        .select("allOf")
        .asOpt[Seq[JsValue]]
        .getOrElse(Seq.empty)
        .flatMap(part => properties(part, depth + 1))

      inherited.toMap ++ own
    }
  }

  def typeOf(schema: JsValue): Option[String] = {
    val resolved = resolve(schema)

    resolved
      .select("type")
      .asOpt[String]
      .orElse(resolved.select("type").asOpt[Seq[String]].flatMap(_.find(_ != "null")))
  }

  // a $ref already met on the current branch stops the generation, so recursive schemas end with empty arrays
  def generate(schema: JsValue, seen: Set[String] = Set.empty): Option[JsValue] = {
    schema.select("$ref").asOpt[String] match {
      case Some(ref) if seen.contains(ref) => None
      case Some(ref)                       => lookup(ref).flatMap(target => generate(target, seen + ref))
      case None                            => explicitValue(schema).orElse(generateInline(schema, seen))
    }
  }

  private def explicitValue(schema: JsValue): Option[JsValue] = {
    schema
      .select("example")
      .asOpt[JsValue]
      .orElse(schema.select("examples").asOpt[Seq[JsValue]].flatMap(_.headOption))
      .orElse(schema.select("default").asOpt[JsValue])
      .orElse(schema.select("const").asOpt[JsValue])
      .orElse(schema.select("enum").asOpt[Seq[JsValue]].flatMap(_.headOption))
  }

  private def generateInline(schema: JsValue, seen: Set[String]): Option[JsValue] = {
    val composed     = schema.select("allOf").asOpt[Seq[JsValue]]
    val alternatives = schema
      .select("oneOf")
      .asOpt[Seq[JsValue]]
      .orElse(schema.select("anyOf").asOpt[Seq[JsValue]])

    (composed, alternatives) match {
      case (Some(parts), _)   =>
        val own = schema.select("properties").asOpt[JsObject].map(_ => generateObject(schema, seen))

        Some((parts.flatMap(part => generate(part, seen)) ++ own.toSeq).foldLeft(Json.obj(): JsValue)(merge))
      case (_, Some(choices)) =>
        choices.find(choice => !typeOf(choice).contains("null")).flatMap(choice => generate(choice, seen))
      case _                  =>
        generateFromType(schema, seen)
    }
  }

  private def merge(left: JsValue, right: JsValue): JsValue = (left, right) match {
    case (l: JsObject, r: JsObject) => l.deepMerge(r)
    case (_, r)                     => r
  }

  private def generateFromType(schema: JsValue, seen: Set[String]): Option[JsValue] = {
    val inferred = (schema.select("properties").isDefined, schema.select("items").isDefined) match {
      case (true, _) => "object"
      case (_, true) => "array"
      case _         => "unknown"
    }

    typeOf(schema).getOrElse(inferred) match {
      case "object"  => Some(generateObject(schema, seen))
      case "array"   => Some(JsArray(schema.select("items").asOpt[JsValue].flatMap(i => generate(i, seen)).toSeq))
      case "string"  => Some(JsString(generateString(schema)))
      case "integer" => Some(JsNumber(schema.select("minimum").asOpt[BigDecimal].getOrElse(BigDecimal(0))))
      case "number"  => Some(JsNumber(schema.select("minimum").asOpt[BigDecimal].getOrElse(BigDecimal(0))))
      case "boolean" => Some(JsBoolean(true))
      case "null"    => Some(JsNull)
      case _         => Some(Json.obj())
    }
  }

  private def generateObject(schema: JsValue, seen: Set[String]): JsObject = {
    val properties = schema.select("properties").asOpt[JsObject].getOrElse(Json.obj())

    JsObject(properties.value.toSeq.flatMap { case (name, property) =>
      generate(property, seen).map(value => name -> value)
    })
  }

  private def generateString(schema: JsValue): String = schema.select("format").asOpt[String] match {
    case Some("uuid")              => "3fa85f64-5717-4562-b3fc-2c963f66afa6"
    case Some("date")              => "2024-01-01"
    case Some("date-time")         => "2024-01-01T00:00:00Z"
    case Some("time")              => "00:00:00"
    case Some("email")             => "user@example.com"
    case Some("uri") | Some("url") => "https://example.com"
    case Some("hostname")          => "example.com"
    case Some("ipv4")              => "127.0.0.1"
    case Some("ipv6")              => "::1"
    case Some("byte")              => "c3RyaW5n"
    case _                         => "string"
  }
}

case class OpenApiMockSpec(
    schemas: OpenApiMockSchemas,
    basePaths: Seq[String],
    operations: Seq[OpenApiMockOperation],
    resources: Seq[OpenApiMockResource]
) {

  private def candidates(path: String): Seq[String] = {
    val stripped = basePaths
      .filter(base => path == base || path.startsWith(s"$base/"))
      .map(base => path.drop(base.length))

    stripped :+ path
  }

  def matchOperation(method: String, path: String): Option[OpenApiMockMatch] = {
    candidates(path).view.flatMap(candidate => matchPath(method.toLowerCase, candidate)).headOption
  }

  def collectionOf(path: String): Option[(OpenApiMockResource, String)] = {
    candidates(path).view.flatMap { candidate =>
      val segments = candidate.split("/").toSeq.filter(_.nonEmpty)

      resources
        .find { resource =>
          val template = resource.collection.split("/").toSeq.filter(_.nonEmpty)

          template.size == segments.size && template.zip(segments).forall { case (t, s) =>
            OpenApiMockSpec.isParam(t) || t == s
          }
        }
        .map(resource => resource -> segments.mkString("/", "/", ""))
    }.headOption
  }

  def parseSeed(json: JsValue): Either[Seq[String], OpenApiMockSeed] = json match {
    case obj: JsObject =>
      val parsed = obj.fields.toSeq.map { case (path, items) => parseCollection(path, items) }
      val errors = parsed.collect { case Left(e) => e }.flatten

      if (errors.nonEmpty)
        Left(errors)
      else
        Right(OpenApiMockSeed(parsed.collect { case Right(collection) => collection }.toMap))
    case _             =>
      Left(Seq("the state must be a JSON object whose keys are collection paths"))
  }

  private def parseCollection(path: String, items: JsValue): Either[Seq[String], (String, Seq[(String, JsValue)])] = {
    (collectionOf(path), items) match {
      case (None, _)                                       =>
        Left(Seq(s"$path: no stateful resource of the spec matches this collection"))
      case (Some((resource, collection)), JsArray(values)) =>
        val invalid = values.zipWithIndex.collect {
          case (value, index) if !value.isInstanceOf[JsObject] => s"$path[$index]: an item must be a JSON object"
        }

        if (invalid.nonEmpty)
          Left(invalid.toSeq)
        else
          Right(collection -> withIds(resource, values.collect { case obj: JsObject => obj }.toSeq))
      case _                                               =>
        Left(Seq(s"$path: the items must be a JSON array"))
    }
  }

  private def withIds(resource: OpenApiMockResource, items: Seq[JsObject]): Seq[(String, JsValue)] = {
    val maxId   = items.flatMap(_.select(resource.idField).asOpt[BigDecimal]).maxOption.getOrElse(BigDecimal(0))
    val nextIds = Iterator.iterate(maxId + 1)(_ + 1)

    items.map { item =>
      val explicit = item.select(resource.idField).asOpt[JsValue].filter(OpenApiMockSpec.isScalar)
      val id       = (explicit, resource.integerId) match {
        case (Some(value), _) => value
        case (None, true)     => JsNumber(nextIds.next())
        case (None, false)    => JsString(UUID.randomUUID().toString)
      }

      OpenApiMockSpec.keyOf(id) -> (item + (resource.idField -> id))
    }
  }

  private def matchPath(method: String, path: String): Option[OpenApiMockMatch] = {
    val segments = path.split("/").toSeq.filter(_.nonEmpty)

    operations
      .filter(op => op.method == method && op.segments.size == segments.size)
      .flatMap(op => bind(op, segments))
      .sortBy(m => -m.operation.segments.count(s => !OpenApiMockSpec.isParam(s)))
      .headOption
  }

  private def bind(operation: OpenApiMockOperation, segments: Seq[String]): Option[OpenApiMockMatch] = {
    val pairs   = operation.segments.zip(segments)
    val matches = pairs.forall { case (template, segment) => OpenApiMockSpec.isParam(template) || template == segment }

    if (!matches)
      None
    else
      Some(
        OpenApiMockMatch(
          operation,
          segments,
          pairs.collect {
            case (template, segment) if OpenApiMockSpec.isParam(template) =>
              OpenApiMockSpec.paramName(template) -> segment
          }.toMap
        )
      )
  }

  def selectResponse(operation: OpenApiMockOperation, preferred: Option[Int]): (Int, JsValue) = {
    val responses = operation.definition.select("responses").asOpt[JsObject].getOrElse(Json.obj())
    val codes     = responses.fields.map(_._1)
    val chosen    = preferred
      .map(_.toString)
      .filter(codes.contains)
      .orElse(codes.find(_.startsWith("2")))
      .orElse(codes.find(_ == "default"))
      .orElse(codes.headOption)

    chosen match {
      case None       => (200, Json.obj())
      case Some(code) => (OpenApiMockSpec.statusOf(code), schemas.resolve(responses.value(code)))
    }
  }

  def jsonMediaType(response: JsValue): Option[(String, JsValue)] = {
    response
      .select("content")
      .asOpt[JsObject]
      .flatMap(_.fields.find(_._1.toLowerCase.contains("json")))
  }

  def exampleOf(mediaType: JsValue): Option[JsValue] = {
    val namedExample = mediaType
      .select("examples")
      .asOpt[JsObject]
      .flatMap(_.fields.headOption)
      .flatMap { case (_, example) => schemas.resolve(example).select("value").asOpt[JsValue] }

    mediaType
      .select("example")
      .asOpt[JsValue]
      .orElse(namedExample)
      .orElse(mediaType.select("schema").asOpt[JsValue].flatMap(schema => schemas.generate(schema)))
  }
}

object OpenApiMockSpec {

  private val methods = Seq("get", "put", "post", "delete", "options", "head", "patch", "trace")

  def isParam(segment: String): Boolean = segment.startsWith("{") && segment.endsWith("}")

  def paramName(segment: String): String = segment.drop(1).dropRight(1)

  def isScalar(value: JsValue): Boolean = value match {
    case _: JsString | _: JsNumber => true
    case _                         => false
  }

  def keyOf(id: JsValue): String = id match {
    case JsString(value)                  => value
    case JsNumber(value) if value.isWhole => value.toBigInt.toString
    case other                            => other.stringify
  }

  def statusOf(code: String): Int = {
    Try(code.toInt).toOption
      .orElse(Try(code.take(1).toInt * 100).toOption)
      .getOrElse(200)
  }

  def parse(raw: String): Either[String, OpenApiMockSpec] = {
    val parsed  = Try(Json.parse(raw)).toOption.orElse(Yaml.parseSafe(raw).toOption)
    val version = parsed.flatMap(_.select("openapi").asOpt[JsValue]).map {
      case JsString(value) => value
      case JsNumber(value) => value.toString
      case _               => ""
    }

    (parsed, version) match {
      case (None, _)                                         => Left("the OpenAPI spec is neither valid JSON nor valid YAML")
      case (Some(_), v) if !v.exists(_.startsWith("3."))     => Left("only OpenAPI 3.x specs are supported")
      case (Some(root), _)                                   =>
        root.select("paths").asOpt[JsObject] match {
          case None        => Left("the OpenAPI spec has no paths")
          case Some(paths) => Right(build(root, paths))
        }
    }
  }

  private def build(root: JsValue, paths: JsObject): OpenApiMockSpec = {
    val schemas    = new OpenApiMockSchemas(root)
    val operations = for {
      (template, pathItem) <- paths.fields
      resolvedItem          = schemas.resolve(pathItem)
      method               <- methods
      definition           <- resolvedItem.select(method).asOpt[JsObject]
    } yield OpenApiMockOperation(method, template, template.split("/").toSeq.filter(_.nonEmpty), definition)

    val basePaths = root
      .select("servers")
      .asOpt[Seq[JsObject]]
      .getOrElse(Seq.empty)
      .flatMap(_.select("url").asOpt[String])
      .map(_.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]*", "").stripSuffix("/"))
      .filter(path => path.startsWith("/") && !path.contains("{"))
      .distinct

    val spec = OpenApiMockSpec(schemas, basePaths, operations.toSeq, Seq.empty)

    spec.copy(resources = detectResources(spec))
  }

  private def shape(segments: Seq[String]): Seq[String] = segments.map(s => if (isParam(s)) "{}" else s)

  private def successSchema(spec: OpenApiMockSpec, operation: OpenApiMockOperation): Option[JsValue] = {
    val (status, response) = spec.selectResponse(operation, None)

    if (status < 200 || status >= 300)
      None
    else
      spec.jsonMediaType(response).flatMap { case (_, media) => media.select("schema").asOpt[JsValue] }
  }

  // strict on purpose: an operation only gets state when the REST pattern is unambiguous, otherwise it stays stateless
  private def detectResources(spec: OpenApiMockSpec): Seq[OpenApiMockResource] = {
    spec.operations.filter(_.method == "post").flatMap { post =>
      val items = spec.operations.filter { op =>
        op.method == "get" &&
        op.segments.size == post.segments.size + 1 &&
        isParam(op.segments.last) &&
        shape(op.segments.init) == shape(post.segments)
      }

      items match {
        case Seq(get) => resourceFrom(spec, post, get)
        case _        => None
      }
    }
  }

  private def resourceFrom(
      spec: OpenApiMockSpec,
      post: OpenApiMockOperation,
      get: OpenApiMockOperation
  ): Option[OpenApiMockResource] = {
    val schemas = spec.schemas
    val idParam = paramName(get.segments.last)

    for {
      item       <- successSchema(spec, get)
      created    <- successSchema(spec, post)
      if item == created || schemas.resolve(item) == schemas.resolve(created)
      properties  = schemas.properties(item)
      idField    <- Seq(idParam, "id").find(properties.contains)
      idType      = schemas.typeOf(properties(idField))
    } yield OpenApiMockResource(
      collection = post.template,
      item = get.template,
      idParam = idParam,
      idField = idField,
      integerId = idType.contains("integer") || idType.contains("number"),
      itemSchema = item
    )
  }
}

case class OpenApiMockSeed(collections: Map[String, Seq[(String, JsValue)]])

object OpenApiMockSeed {
  val empty: OpenApiMockSeed = OpenApiMockSeed(Map.empty)
}

// a None value is a tombstone: it hides an item of the seed that was deleted
case class OpenApiMockItem(seq: Long, value: Option[JsValue])

class OpenApiMockStore(
    val fingerprint: String,
    val maxSizeMb: Long,
    val ttlHours: Long,
    val configSeed: Either[Seq[String], OpenApiMockSeed]
) {

  private val counter = new AtomicLong(0L)

  private val pushed = new AtomicReference[Option[OpenApiMockSeed]](None)

  private val writes: Cache[String, OpenApiMockItem] = Scaffeine()
    .maximumWeight(maxSizeMb * 1024L * 1024L)
    .weigher[String, OpenApiMockItem]((key, item) => key.length + item.value.map(_.stringify.length).getOrElse(0))
    .expireAfterWrite(ttlHours.hours)
    .build[String, OpenApiMockItem]()

  private def key(collection: String, id: String): String = s"$collection::$id"

  private def seed: OpenApiMockSeed = pushed.get().orElse(configSeed.toOption).getOrElse(OpenApiMockSeed.empty)

  private def seeded(collection: String): Seq[(String, JsValue)] = seed.collections.getOrElse(collection, Seq.empty)

  private def written(collection: String): Map[String, OpenApiMockItem] = {
    val prefix = s"$collection::"

    writes.asMap().toMap.collect { case (k, item) if k.startsWith(prefix) => k.drop(prefix.length) -> item }
  }

  private def write(collection: String, id: String, value: Option[JsValue]): Unit = {
    val k   = key(collection, id)
    val seq = writes.getIfPresent(k).map(_.seq).getOrElse(counter.incrementAndGet())

    writes.put(k, OpenApiMockItem(seq, value))
  }

  def get(collection: String, id: String): Option[JsValue] = {
    writes.getIfPresent(key(collection, id)) match {
      case Some(item) => item.value
      case None       => seeded(collection).find(_._1 == id).map(_._2)
    }
  }

  def list(collection: String): Seq[JsValue] = {
    val overrides = written(collection)
    val seedItems = seeded(collection)
    val seedIds   = seedItems.map(_._1).toSet
    val fromSeed  = seedItems.flatMap { case (id, value) => overrides.get(id).map(_.value).getOrElse(Some(value)) }
    val added     = overrides.toSeq.filterNot(entry => seedIds.contains(entry._1)).map(_._2).sortBy(_.seq)

    fromSeed ++ added.flatMap(_.value)
  }

  def snapshot: JsObject = {
    val fromWrites  = writes.asMap().keys.toSeq.map(k => k.take(k.indexOf("::")))
    val collections = (seed.collections.keys.toSeq ++ fromWrites).distinct.sorted

    JsObject(collections.map(c => c -> JsArray(list(c))).filter(_._2.value.nonEmpty))
  }

  def put(collection: String, id: String, value: JsValue): Unit = write(collection, id, Some(value))

  def remove(collection: String, id: String): Unit = write(collection, id, None)

  def replaceSeed(newSeed: OpenApiMockSeed): Unit = {
    pushed.set(Some(newSeed))
    writes.invalidateAll()
  }

  def reset(): Unit = {
    pushed.set(None)
    writes.invalidateAll()
  }

  def copyFrom(other: OpenApiMockStore): Unit = {
    writes.putAll(other.writes.asMap().toMap)
    pushed.set(other.pushed.get())
    counter.set(other.counter.get())
  }
}

object OpenApiMock {

  private val specs: Cache[String, (Long, OpenApiMockSpec)] = Scaffeine()
    .expireAfterAccess(1.day)
    .maximumSize(200)
    .build[String, (Long, OpenApiMockSpec)]()

  private val stores: Cache[String, OpenApiMockStore] = Scaffeine()
    .expireAfterAccess(7.days)
    .maximumSize(1000)
    .build[String, OpenApiMockStore]()

  def preferredCode(header: String): Option[Int] = "code=(\\d{3})".r.findFirstMatchIn(header).map(_.group(1).toInt)

  // the fingerprint covers what defines the data (spec and seed): when it changes, the written data is dropped
  // unless reset_on_change is off, and a new size or ttl only rebuilds the cache
  def store(id: String, config: OpenApiMockConfig, spec: OpenApiMockSpec): OpenApiMockStore = synchronized {
    val fingerprint = Seq(
      config.content.map(_.sha256).getOrElse(""),
      config.url.getOrElse(""),
      config.seed.stringify.sha256
    ).mkString("::")
    val existing    = stores.getIfPresent(id)
    val upToDate    = existing.filter { s =>
      s.fingerprint == fingerprint && s.maxSizeMb == config.maxSizeMb && s.ttlHours == config.ttlHours
    }

    upToDate.getOrElse {
      val created  = new OpenApiMockStore(fingerprint, config.maxSizeMb, config.ttlHours, spec.parseSeed(config.seed))
      val previous = existing.filter(s => s.fingerprint == fingerprint || !config.resetOnChange)

      previous.foreach(created.copyFrom)
      stores.put(id, created)
      created
    }
  }

  def load(config: OpenApiMockConfig)(using env: Env, ec: ExecutionContext): Future[Either[String, OpenApiMockSpec]] = {
    (config.content, config.url) match {
      case (Some(content), _) => loadInline(content).future
      case (None, Some(url))  => loadUrl(url, config.specTtl)
      case _                  => Left("no OpenAPI spec configured: set url or content").future
    }
  }

  private def loadInline(content: String): Either[String, OpenApiMockSpec] = {
    val key = s"inline::${content.sha256}"

    specs.getIfPresent(key) match {
      case Some((_, spec)) => Right(spec)
      case None            =>
        val parsed = OpenApiMockSpec.parse(content)

        parsed.foreach(spec => specs.put(key, (System.currentTimeMillis(), spec)))
        parsed
    }
  }

  private def loadUrl(url: String, ttlSeconds: Long)(using
      env: Env,
      ec: ExecutionContext
  ): Future[Either[String, OpenApiMockSpec]] = {
    val key = s"url::$url"
    val now = System.currentTimeMillis()

    specs.getIfPresent(key) match {
      case Some((loadedAt, spec)) if now - loadedAt < ttlSeconds * 1000L => Right(spec).future
      case _                                                             =>
        env.Ws
          .url(url)
          .withRequestTimeout(10.seconds)
          .get()
          .map { response =>
            if (response.status != 200)
              Left(s"unable to fetch the OpenAPI spec: status ${response.status}")
            else
              OpenApiMockSpec.parse(response.body)
          }
          .recover { case e => Left(s"unable to fetch the OpenAPI spec: ${e.getMessage}") }
          .andThen { case Success(Right(spec)) => specs.put(key, (now, spec)) }
    }
  }
}

class OpenApiMockResponses extends NgBackendCall {

  override def useDelegates: Boolean                       = true
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "OpenAPI mock"
  override def description: Option[String]                 =
    "This plugin mocks every operation of an OpenAPI 3 spec, and can keep the data created through it".some
  override def defaultConfigObject: Option[NgPluginConfig] = OpenApiMockConfig().some
  override def noJsForm: Boolean                           = true
  override def configFlow: Seq[String]                     = OpenApiMockConfig.configFlow
  override def configSchema: Option[JsObject]              = OpenApiMockConfig.configSchema

  override def documentation: Option[String] = Some(
    s"""Responses come from the examples of the spec when there are some, otherwise from values generated from the schemas.
       |The first 2xx response is returned, a client can ask for another declared status with the `Prefer: code=404` header.
       |
       |When `stateful` is enabled, operations that follow the REST convention (`POST /pets` and `GET /pets/{id}` returning the same schema)
       |keep their data: a `POST` creates an item that the next `GET` returns, `PUT`, `PATCH` and `DELETE` update it.
       |Other operations stay stateless.
       |
       |`seed` gives the initial data, by collection path: `{"/pets": [{"id": 1, "name": "Rex"}]}` (a missing id is generated).
       |Reads look into the written data, then into the seed, then fall back to generated values.
       |With `read_only`, writes are answered but never change the state. With `reset_on_change`, a new spec or a new seed drops the written data.
       |
       |`GET {admin_path}/state` returns the current data, `PUT {admin_path}/state` replaces the seed in memory and drops the written data,
       |`DELETE {admin_path}/state` goes back to the seed of the config. These routes are not protected: put an authentication plugin on the route if needed.
       |
       |Stored data lives in the memory of each Otoroshi instance: it is not shared between the nodes of a cluster and it is lost on restart.
       |The `max_size_mb` limit applies to each route using the plugin.
       |""".stripMargin
  )

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.TrafficControl)
  override def steps: Seq[NgStep]                = Seq(NgStep.CallBackend)

  override def callBackend(
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(using
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(OpenApiMockConfig.format).getOrElse(OpenApiMockConfig())

    val isAdminRoute = ctx.request.path.stripSuffix("/") == s"${config.adminPath}/state"

    OpenApiMock.load(config).flatMap {
      case Left(error)                    =>
        jsonResponse(502, Some(Json.obj("error" -> error)), "application/json").future
      case Right(spec) if config.stateful =>
        val store = OpenApiMock.store(s"${ctx.route.cacheableId}::${ctx.idx}", config, spec)

        store.configSeed match {
          case Left(errors)          =>
            jsonResponse(502, Some(Json.obj("error" -> "invalid seed", "errors" -> errors)), "application/json").future
          case Right(_) if isAdminRoute => manageState(ctx, spec, store)
          case Right(_)              => route(ctx, config, spec, Some(store), delegates)
        }
      case Right(spec)                    =>
        route(ctx, config, spec, None, delegates)
    }
  }

  private def route(
      ctx: NgbBackendCallContext,
      config: OpenApiMockConfig,
      spec: OpenApiMockSpec,
      store: Option[OpenApiMockStore],
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    spec.matchOperation(ctx.request.method, ctx.request.path) match {
      case None if config.passThrough => delegates()
      case None                       =>
        jsonResponse(
          404,
          Some(Json.obj("error" -> "no operation of the OpenAPI spec matches this request")),
          "application/json"
        ).future
      case Some(matched)              => respond(ctx, config, spec, store, matched)
    }
  }

  private def manageState(
      ctx: NgbBackendCallContext,
      spec: OpenApiMockSpec,
      store: OpenApiMockStore
  )(using ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    ctx.request.method.toUpperCase match {
      case "GET"    =>
        jsonResponse(200, Some(store.snapshot), "application/json").future
      case "DELETE" =>
        store.reset()
        jsonResponse(200, Some(store.snapshot), "application/json").future
      case "PUT"    =>
        readRaw(ctx).map { bytes =>
          val parsed = Try(Json.parse(bytes.utf8String)).toOption.map(spec.parseSeed)

          parsed match {
            case Some(Right(seed))  =>
              store.replaceSeed(seed)
              jsonResponse(200, Some(store.snapshot), "application/json")
            case Some(Left(errors)) =>
              jsonResponse(400, Some(Json.obj("errors" -> errors)), "application/json")
            case None               =>
              jsonResponse(400, Some(Json.obj("errors" -> Json.arr("the body is not valid JSON"))), "application/json")
          }
        }
      case _        =>
        jsonResponse(405, Some(Json.obj("error" -> "use GET, PUT or DELETE")), "application/json").future
    }
  }

  private def jsonResponse(
      status: Int,
      body: Option[JsValue],
      contentType: String
  ): Either[NgProxyEngineError, BackendCallResponse] = body match {
    case None        => emptyBodyResponse(status, Map.empty)
    case Some(value) => inMemoryBodyResponse(status, Map("Content-Type" -> contentType), value.stringify.byteString)
  }

  private def respond(
      ctx: NgbBackendCallContext,
      config: OpenApiMockConfig,
      spec: OpenApiMockSpec,
      store: Option[OpenApiMockStore],
      matched: OpenApiMockMatch
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val preferred          = ctx.request.headers.getIgnoreCase("Prefer").flatMap(OpenApiMock.preferredCode)
    val (status, response) = spec.selectResponse(matched.operation, preferred)
    val media              = spec.jsonMediaType(response)
    val generated          = media.flatMap { case (_, mediaType) => spec.exampleOf(mediaType) }
    val contentType        = media.map(_._1).getOrElse("application/json")
    val resource           = spec.resources.find { r =>
      r.collection == matched.operation.template || r.item == matched.operation.template
    }
    val success            = status >= 200 && status < 300

    (resource, store) match {
      case (Some(res), Some(s)) if success =>
        val state = new OpenApiMockState(spec, res, matched, s, config.readOnly, generated, media)

        readBody(ctx).map(body => jsonResponse(status, state.handle(body), contentType))
      case _                               =>
        jsonResponse(status, generated, contentType).future
    }
  }

  private def readRaw(ctx: NgbBackendCallContext)(using mat: Materializer): Future[ByteString] = {
    ctx.request.body.runFold(ByteString.empty)(_ ++ _)
  }

  private def readBody(ctx: NgbBackendCallContext)(using mat: Materializer, ec: ExecutionContext): Future[JsObject] = {
    readRaw(ctx).map { bytes =>
      Try(Json.parse(bytes.utf8String)).toOption
        .collect { case obj: JsObject => obj }
        .getOrElse(Json.obj())
    }
  }
}

class OpenApiMockState(
    spec: OpenApiMockSpec,
    resource: OpenApiMockResource,
    matched: OpenApiMockMatch,
    store: OpenApiMockStore,
    readOnly: Boolean,
    generated: Option[JsValue],
    media: Option[(String, JsValue)]
) {

  private val isCollection = matched.operation.template == resource.collection

  private val collectionPath = {
    val segments = if (isCollection) matched.segments else matched.segments.init

    segments.mkString("/", "/", "")
  }

  private val pathId: Option[String] = matched.params.get(resource.idParam)

  def handle(body: JsObject): Option[JsValue] = (matched.operation.method, isCollection) match {
    case ("post", true)    => create(body)
    case ("get", true)     => list()
    case ("get", false)    => read()
    case ("put", false)    => replace(body)
    case ("patch", false)  => update(body)
    case ("delete", false) => delete()
    case _                 => generated
  }

  private def create(body: JsObject): Option[JsValue] = {
    val base    = generated.collect { case obj: JsObject => obj }.getOrElse(generatedItem)
    val id      = body.select(resource.idField).asOpt[JsValue].filter(OpenApiMockSpec.isScalar).getOrElse(newId())
    val created = base.deepMerge(body) + (resource.idField -> id)

    if (!readOnly)
      store.put(collectionPath, OpenApiMockSpec.keyOf(id), created)

    reply(created)
  }

  private def list(): Option[JsValue] = {
    val items      = store.list(collectionPath)
    val listSchema = media.flatMap { case (_, mediaType) => mediaType.select("schema").asOpt[JsValue] }

    if (items.isEmpty)
      generated
    else
      listSchema.flatMap(schema => wrapList(schema, items)).orElse(generated)
  }

  private def wrapList(schema: JsValue, items: Seq[JsValue]): Option[JsValue] = {
    val arrays = spec.schemas.properties(schema).filter { case (_, property) =>
      spec.schemas.typeOf(property).contains("array")
    }

    (spec.schemas.typeOf(schema), arrays.keys.toSeq, generated) match {
      case (Some("array"), _, _)                => Some(JsArray(items))
      case (_, Seq(field), Some(obj: JsObject)) => Some(obj + (field -> JsArray(items)))
      case _                                    => None
    }
  }

  private def read(): Option[JsValue] = {
    pathId.flatMap(id => store.get(collectionPath, id)).orElse(generated.map(withPathId))
  }

  private def replace(body: JsObject): Option[JsValue] = {
    val replaced = withPathId(generatedItem.deepMerge(body))

    save(replaced)
    reply(replaced)
  }

  private def update(body: JsObject): Option[JsValue] = {
    val base    = pathId.flatMap(id => store.get(collectionPath, id)).collect { case obj: JsObject => obj }
    val updated = withPathId(base.getOrElse(generatedItem).deepMerge(body))

    save(updated)
    reply(updated)
  }

  private def delete(): Option[JsValue] = {
    if (!readOnly)
      pathId.foreach(id => store.remove(collectionPath, id))

    generated
  }

  private def save(value: JsValue): Unit = {
    if (!readOnly)
      pathId.foreach(id => store.put(collectionPath, id, value))
  }

  private def reply(value: JsValue): Option[JsValue] = media.map(_ => value)

  private def generatedItem: JsObject = {
    spec.schemas.generate(resource.itemSchema).collect { case obj: JsObject => obj }.getOrElse(Json.obj())
  }

  private def withPathId(value: JsValue): JsValue = (value, pathId) match {
    case (obj: JsObject, Some(id)) => obj + (resource.idField -> typedId(id))
    case _                         => value
  }

  private def typedId(raw: String): JsValue = {
    if (resource.integerId)
      Try(BigDecimal(raw)).map(JsNumber.apply).getOrElse(JsString(raw))
    else
      JsString(raw)
  }

  private def newId(): JsValue = {
    if (resource.integerId) {
      val max = store
        .list(collectionPath)
        .flatMap(_.select(resource.idField).asOpt[BigDecimal])
        .maxOption
        .getOrElse(BigDecimal(0))

      JsNumber(max + 1)
    } else
      JsString(UUID.randomUUID().toString)
  }
}
