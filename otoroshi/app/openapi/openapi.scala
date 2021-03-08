package otoroshi.openapi

import io.github.classgraph._
import otoroshi.models.Entity
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap

case class OpenApiGeneratorConfig(filePath: String, raw: JsValue) {

  lazy val banned: Seq[String] = raw.select("banned").asOpt[Seq[String]].getOrElse(Seq(
    "otoroshi.controllers.BackOfficeController$SearchedService",
    "otoroshi.models.EntityLocationSupport",
    "otoroshi.auth.AuthModuleConfig",
    "otoroshi.auth.OAuth2ModuleConfig",
    "otoroshi.ssl.ClientCertificateValidator"
  ))
  lazy val descriptions: Map[String, String] = raw.select("descriptions").asOpt[Map[String, String]].getOrElse(Map.empty)
  lazy val old_descriptions: Map[String, String] = raw.select("old_descriptions").asOpt[Map[String, String]].getOrElse(Map.empty)
  lazy val old_examples: Map[String, String] = raw.select("old_examples").asOpt[Map[String, String]].getOrElse(Map.empty)
  def write(): Unit = {
    val config = Json.obj(
      "banned" -> JsArray(banned.map(JsString.apply)),
      "descriptions" -> JsObject(descriptions.mapValues(JsString.apply)),
      "old_descriptions" -> JsObject(old_descriptions.mapValues(JsString.apply)),
      "old_examples" -> JsObject(old_examples.mapValues(JsString.apply)),
    )
    val f = new File(filePath)
    if (!f.exists()) {
      f.createNewFile()
    }

    val descs = descriptions.mapValues(JsString.apply).toSeq.sortWith((a, b) => a._1.compareTo(b._1) < 0).map(t => s"    ${JsString(t._1).stringify}: ${t._2.stringify}").mkString(",\n")
    val olddescs = old_descriptions.mapValues(JsString.apply).toSeq.sortWith((a, b) => a._1.compareTo(b._1) < 0).map(t => s"    ${JsString(t._1).stringify}: ${t._2.stringify}").mkString(",\n")

    val fileContent = s"""{
  "banned": ${JsArray(banned.map(JsString.apply)).prettify},
  "descriptions": {
$descs
   },
   "old_descriptions": {
$olddescs
   }
}"""
    Files.writeString(f.toPath, fileContent, StandardCharsets.UTF_8)
  }
}

class OpenApiGenerator(routerPath: String, configFilePath: String, specFile: String, oldSpecPath: String) {

  val nullType = JsString("string")
  val openApiVersion = JsString("3.0.3")
  val unknownValue = "???"

  val scanResult: ScanResult = new ClassGraph()
    .addClassLoader(this.getClass.getClassLoader)
    .enableAllInfo()
    .whitelistPackages(Seq("otoroshi", "play.api.libs.ws"): _*)
    .scan

  val world = scanResult.getAllClassesAsMap.asScala

  val entities = (
    scanResult.getClassesImplementing(classOf[Entity].getName).asScala ++
      scanResult.getSubclasses(classOf[Entity].getName).asScala ++
      world.get("otoroshi.models.GlobalConfig")
    ).toSeq.distinct

  val foundDescriptions = new TrieMap[String, String]()
  val found = new AtomicLong(0L)
  val notFound = new AtomicLong(0L)

  def getFieldDescription(clazz: ClassInfo, name: String, typ: TypeSignature, config: OpenApiGeneratorConfig): JsValue = {
    val finalPath = s"${clazz.getName}.$name"
    val simpleName = clazz.getSimpleName match {
      case "ServiceDescriptor" => "Service"
      case "ServiceGroup" => "Service"
      case v => v
    }
    config.descriptions.get(s"${clazz.getName}.$name").orElse(
      config.old_descriptions.get(s"old.$simpleName.$name")
    ).filterNot(_ == unknownValue) match {
      case None =>
        notFound.incrementAndGet()
        foundDescriptions.put(finalPath,unknownValue)
        unknownValue.json
      case Some(value) =>
        found.incrementAndGet()
        foundDescriptions.put(finalPath, value)
        value.json
    }
  }

  def getTagDescription(tagName: String, config: OpenApiGeneratorConfig): JsValue = {
    val finalPath = s"tags.$tagName"
    config.descriptions.get(finalPath).filterNot(_ == unknownValue) match {
      case None =>
        notFound.incrementAndGet()
        foundDescriptions.put(finalPath,unknownValue)
        unknownValue.json
      case Some(value) =>
        found.incrementAndGet()
        foundDescriptions.put(finalPath, value)
        value.json
    }
  }

  def getOperationDescription(verb: String, path: String, operationId: String, tag: String, controllerName: String, controllerMethod: String, config: OpenApiGeneratorConfig): JsValue = {
    val finalPath = s"operations.$controllerName.${controllerMethod}_$tag"
    config.descriptions.get(finalPath).filterNot(_ == unknownValue) match {
      case None =>
        notFound.incrementAndGet()
        foundDescriptions.put(finalPath,unknownValue)
        unknownValue.json
      case Some(value) =>
        found.incrementAndGet()
        foundDescriptions.put(finalPath, value)
        value.json
    }
  }

  // TODO: fix traits/enum issues
  // TODO: validate weird generated stuff
  def visitEntity(clazz: ClassInfo, result: TrieMap[String, JsValue], config: OpenApiGeneratorConfig): Unit = {
    val ctrInfo = clazz.getDeclaredConstructorInfo.asScala.headOption
    val params = ctrInfo.map(_.getParameterInfo.toSeq).getOrElse(Seq.empty)
    val paramNames = params.map { param =>
      param.getName
    }
    val fields = (clazz.getFieldInfo.asScala ++ clazz.getDeclaredFieldInfo.asScala).toSet.filter(_.isFinal).filter(i => paramNames.contains(i.getName))
    var properties = Json.obj()
    var required = Json.arr()

    def handleType(name: String, valueName: String, typ: TypeSignature): Option[JsObject] = {
      valueName match {
        case "java.security.KeyPair"                    => None
        case "play.api.Logger"                          => None
        case "byte"                                     => None
        case "java.security.cert.X509Certificate[]"     => None
        case _ if typ.toString == "byte"                => None
        case _ if typ.toString == "java.security.cert.X509Certificate[]" => None
        case "int"                                      => Json.obj("type" -> "integer", "format" -> "int32").some
        case "long"                                     => Json.obj("type" -> "integer", "format" -> "int64").some
        case "double"                                   => Json.obj("type" -> "number", "format" -> "double").some
        case "float"                                    => Json.obj("type" -> "number", "format" -> "float").some

        case "java.math.BigInteger"                     => Json.obj("type" -> "integer", "format" -> "int64").some
        case "java.math.BigDecimal"                     => Json.obj("type" -> "number", "format" -> "double").some
        case "java.lang.Integer"                        => Json.obj("type" -> "integer", "format" -> "int32").some
        case "java.lang.Long"                           => Json.obj("type" -> "integer", "format" -> "int64").some
        case "java.lang.Double"                         => Json.obj("type" -> "number", "format" -> "double").some
        case "java.lang.Float"                          => Json.obj("type" -> "number", "format" -> "float").some

        case "scala.math.BigInt"                        => Json.obj("type" -> "integer", "format" -> "int64").some
        case "scala.math.BigDecimal"                    => Json.obj("type" -> "number", "format" -> "double").some
        case "scala.Int"                                => Json.obj("type" -> "integer", "format" -> "int32").some
        case "scala.Long"                               => Json.obj("type" -> "integer", "format" -> "int64").some
        case "scala.Double"                             => Json.obj("type" -> "number", "format" -> "double").some
        case "scala.Float"                              => Json.obj("type" -> "number", "format" -> "float").some

        case "boolean"                                  => Json.obj("type" -> "boolean").some
        case "java.lang.Boolean"                        => Json.obj("type" -> "boolean").some
        case "scala.Boolean"                            => Json.obj("type" -> "boolean").some
        case "java.lang.String"                         => Json.obj("type" -> "string").some
        case "org.joda.time.DateTime"                   => Json.obj("type" -> "number").some
        case "scala.concurrent.duration.FiniteDuration" => Json.obj("type" -> "number").some
        case "org.joda.time.LocalTime"                  => Json.obj("type" -> "string").some
        case "play.api.libs.json.JsValue"               => Json.obj("type" -> "object").some
        case "play.api.libs.json.JsObject"              => Json.obj("type" -> "object").some
        case "play.api.libs.json.JsArray"               => Json.obj("type" -> "array").some
        case "akka.http.scaladsl.model.HttpProtocol"    => Json.obj("type" -> "string").some
        case _ if typ.toString.startsWith("scala.Option<") => {
          world.get(valueName).map(cl => visitEntity(cl, result, config))
          result.get(valueName) match {
            case None if valueName == "java.lang.Object" && name == "maxJwtLifespanSecs" => Json.obj("type" -> "integer", "format" -> "int64").some
            case Some(v) => Json.obj("oneOf" -> Json.arr(Json.obj("type" -> nullType), Json.obj("$ref" -> s"#/components/schemas/$valueName"))).some
            case _ =>
              println("fuuuuu opt", name, valueName)
              None
          }
        }
        case vn if valueName.startsWith("otoroshi")     => {
          world.get(valueName).map(cl => visitEntity(cl, result, config))
          Json.obj("$ref" -> s"#/components/schemas/$valueName").some
        }
        case _ =>
          println(s"${clazz.getName}.$name: $typ (unexpected 1)")
          None
      }
    }

    fields.foreach { field =>
      val name = field.getName
      val typ = field.getTypeSignatureOrTypeDescriptor
      typ match {
        case c: BaseTypeSignature => handleType(name, c.getTypeStr, typ).foreach { r =>
          properties = properties ++ Json.obj(
            name -> r.deepMerge(Json.obj(
              "description" -> getFieldDescription(clazz, name, typ, config)
            ))
          )
        }
        case c: ClassRefTypeSignature if c.getTypeArguments.size() > 0 && c.getBaseClassName == "scala.collection.immutable.Map" =>
          val valueName = c.getTypeArguments.asScala.tail.head.toString
          handleType(name, valueName, typ).foreach { r =>
            properties = properties ++ Json.obj(
              name -> Json.obj(
                "type" -> "object",
                "additionalProperties" -> r,
                "description" -> getFieldDescription(clazz, name, typ, config)
              )
            )
          }
        case c: ClassRefTypeSignature if c.getTypeArguments.size() > 0 && c.getBaseClassName == "scala.collection.Seq" =>
          val valueName = c.getTypeArguments.asScala.head.toString
          handleType(name, valueName, typ).foreach { r =>
            properties = properties ++ Json.obj(
              name -> Json.obj(
                "type" -> "array",
                "items" -> r,
                "description" -> getFieldDescription(clazz, name, typ, config)
              )
            )
          }
        case c: ClassRefTypeSignature if c.getTypeArguments.size() > 0 && c.getBaseClassName == "scala.Option" =>
          val valueName = c.getTypeArguments.asScala.head.toString
          handleType(name, valueName, typ).foreach { r =>
            properties = properties ++ Json.obj(
              name -> Json.obj(
                "oneOf" -> Json.arr(
                  Json.obj("type" -> nullType),
                  r
                ),
              "description" -> getFieldDescription(clazz, name, typ, config)
            ),
            )
          }
        case c: ClassRefTypeSignature =>
          val valueName = c.getBaseClassName
          handleType(name, valueName, typ).map { r =>
            properties = properties ++ Json.obj(
              name -> r.deepMerge(Json.obj(
                "description" -> getFieldDescription(clazz, name, typ, config))
              )
            )
          }
        // case c: TypeVariableSignature => println(s"  $name: $typ ${c.toStringWithTypeBound} (var)")
        // case c: ArrayTypeSignature    => println(s"  $name: $typ ${c.getTypeSignatureStr} ${c.getElementTypeSignature.toString} (arr)")
        // case c: ReferenceTypeSignature => println(s"  $name: $typ (ref)")
        case _ =>
          println(s"${clazz.getName}.$name: $typ (unexpected 2)")
      }
    }
    result.put(clazz.getName, Json.obj("type" -> "object", "properties" -> properties))
  }

  def getConfig(): OpenApiGeneratorConfig = {
    val f = new File(configFilePath)
    if (f.exists()) {
      OpenApiGeneratorConfig(configFilePath, Json.parse(Files.readString(f.toPath)))
    } else {
      OpenApiGeneratorConfig(configFilePath, Json.obj())
    }
  }

  def scanPathes(config: OpenApiGeneratorConfig): (JsValue, JsValue) = {
    val f = new File(routerPath)
    if (f.exists()) {
      var tags = Seq.empty[String]
      val lines = Files.readAllLines(f.toPath, StandardCharsets.UTF_8).asScala.toSeq.map(_.trim).filterNot(_.startsWith("#")).filterNot(_.isEmpty);
      val pathes: JsObject = lines.map { line =>
        val parts = line.split(" ").toSeq.map(_.trim).filterNot(_.isEmpty).toList
        parts match {
          case verb :: path :: rest if path.startsWith("/api") && !path.startsWith("/api/client-validators") && !path.startsWith("/api/swagger") => {
            val name = rest.mkString(" ").split("\\(").head
            val methodName = name.split("\\.").reverse.head
            val controllerName = name.split("\\.").reverse.tail.reverse.mkString(".")
            // val controller = world.get(controllerName).get
            // val method = controller.getMethodInfo(methodName)
            val pathParts = path.split("/").toList
            val tag = pathParts.tail.tail.head match {
              case "data-exporter-configs" => "data-exporters"
              case "tenants" => "organizations"
              case "verifier" => "jwt-verifiers"
              case "import" => "import / export"
              case "otoroshi.json" => "import-export"
              case ":entity" => "tempaltes"
              case v => v
            }
            val operationId = s"$controllerName.$methodName" match {
              case "otoroshi.controllers.adminapi.StatsController.serviceLiveStats" => s"$controllerName.${methodName}_${tag}"
              case "otoroshi.controllers.adminapi.TemplatesController.initiateTcpService" => s"$controllerName.${methodName}_${tag}"
              case "otoroshi.controllers.adminapi.TemplatesController.initiateApiKey" => s"$controllerName.${methodName}_${tag}"
              case "otoroshi.controllers.adminapi.TemplatesController.initiateService" => s"$controllerName.${methodName}_${tag}"
              case "otoroshi.controllers.adminapi.TemplatesController.initiateServiceGroup" => s"$controllerName.${methodName}_${tag}"
              case "otoroshi.controllers.adminapi.TemplatesController.createFromTemplate" if tag == "admins" => s"$controllerName.${methodName}_${pathParts.apply(3)}"
              case "otoroshi.controllers.adminapi.TemplatesController.createFromTemplate" => s"$controllerName.${methodName}_${tag}"
              case v => v
            }
            tags = tags :+ tag
            Json.obj(
              path -> Json.obj(
                verb.toLowerCase() -> Json.obj(
                  "tags" -> Json.arr(tag),
                  "summary" -> getOperationDescription(verb, path, operationId, tag, controllerName, methodName, config),
                  "operationId" -> operationId,
                  "parameters" -> Json.arr(), // TODO: extract parameters from path
                  "security" -> Json.arr(Json.obj("otoroshi_auth" -> Json.arr())),
                  "responses" -> Json.obj(
                    "401" -> Json.obj( // TODO: add example
                      "description" -> "You have to provide an Api Key. Api Key can be passed with 'Otoroshi-Client-Id' and 'Otoroshi-Client-Secret' headers, or use basic http authentication",
                      "content" -> Json.obj(
                        "application/json" -> Json.obj(
                          "schema" -> Json.obj(
                            "type" -> "object"
                          )
                        )
                      )
                    ),
                    "400" -> Json.obj( // TODO: add example
                      "description" -> "Bad resource format. Take another look to the swagger, or open an issue",
                      "content" -> Json.obj(
                        "application/json" -> Json.obj(
                          "schema" -> Json.obj(
                            "type" -> "object"
                          )
                        )
                      )
                    ),
                    "404" -> Json.obj( // TODO: add example
                      "description" -> "Resource not found or does not exist",
                      "content" -> Json.obj(
                        "application/json" -> Json.obj(
                          "schema" -> Json.obj(
                            "type" -> "object"
                          )
                        )
                      )
                    ),
                    "200" -> Json.obj(
                      "description" -> "Successful operation",
                      "content" -> Json.obj(
                        "application/json" -> Json.obj(
                          "schema" -> Json.obj(
                            "type" -> "object" // TODO: set result body
                          )
                        )
                      )
                    )
                  )
                ).applyOnIf(verb.toLowerCase() != "get" && verb.toLowerCase() != "delete" && verb.toLowerCase() != "options") { c =>
                  c ++ Json.obj("requestBody" -> Json.obj( // TODO: set request body
                    "description" -> "request body",
                    "required" -> true,
                    "content" -> Json.obj(
                      "application/json" -> Json.obj(
                        "schema" -> Json.obj(
                          "type" -> "object"
                          )
                        )
                      )
                  ))
                }
              )
            )
          }
          case _ =>
            // println(s"bad definition: $line")
            Json.obj()
        }
      }.foldLeft(Json.obj())((a, b) => a.deepMerge(b))
      (pathes, JsArray(tags.distinct.sortWith((a, b) => a.compareTo(b) < 0).map(t => Json.obj("name" -> t).applyOn { o =>
        o ++ Json.obj("description" -> getTagDescription(o.select("name").asString, config))
      })))
    } else {
      (Json.obj(), Json.obj())
    }
  }

  def run(): Unit = {
    val config = getConfig()
    val result = new TrieMap[String, JsValue]()
    result.put("otoroshi.auth.AuthModuleConfig", Json.obj(
      "oneOf" -> Json.arr(
        Json.obj("$ref" -> s"#/components/schemas/otoroshi.auth.BasicAuthModuleConfig"),
        Json.obj("$ref" -> s"#/components/schemas/otoroshi.auth.GenericOauth2ModuleConfig"),
        Json.obj("$ref" -> s"#/components/schemas/otoroshi.auth.LdapAuthModuleConfig"),
      )
    ))
    result.put("play.api.libs.ws.WSProxyServer", Json.obj(
      "type" -> "object",
      "required" -> Json.arr("host", "port"),
      "properties" -> Json.obj(
        "host"          -> Json.obj("type" -> "string"),
        "port"          -> Json.obj("type" -> "string"),
        "protocol"      -> Json.obj("type" -> "string"),
        "principal"     -> Json.obj("type" -> "string"),
        "password"      -> Json.obj("type" -> "string"),
        "ntlmDomain"    -> Json.obj("type" -> "string"),
        "encoding"      -> Json.obj("type" -> "string"),
        "nonProxyHosts" -> Json.obj("type" -> "array", "items" -> Json.obj("type" -> "string")),
    )))
    entities.foreach { clazz =>
      if (!config.banned.contains(clazz.getName)) {
        visitEntity(clazz, result, config)
      }
    }

    val (paths, tags) = scanPathes(config)

    println(s"found ${found.get()} descriptions, not found ${notFound.get()} descriptions")

    val spec = Json.obj(
      "openapi" -> openApiVersion,
      "info" -> Json.obj(
        "title" -> "otoroshi api",
        "description" -> "otoroshi api",
        "version" -> "1.5.0",
        "contact" -> Json.obj(
          "name" -> "Otoroshi Team",
          "email" -> "oss@maif.fr",
        ),
        "license" -> Json.obj(
         "name" -> "Apache 2.0",
          "url" -> "http://www.apache.org/licenses/LICENSE-2.0.html"
        ),
      ),
      "externalDocs" -> Json.obj("url" -> "https://www.otoroshi.io", "description" -> "everything about otoroshi"),
      "servers" -> Json.arr(
        Json.obj(
          "url" -> "http://otoroshi-api.oto.tools:8080",
          "description" -> "your local otoroshi server"
        )
      ),
      "tags" -> tags,
      "paths" -> paths,
      "components" -> Json.obj(
        "schemas" -> JsObject(result),
        "securitySchemes" -> Json.obj(
          "otoroshi_auth" -> Json.obj(
          "type" -> "http",
          "scheme" -> "basic"
          )
        )
      )
    )

    Files.writeString(new File(specFile).toPath, spec.prettify, StandardCharsets.UTF_8)
    OpenApiGeneratorConfig(config.filePath, config.raw.asObject ++ Json.obj(
      "descriptions" -> JsObject(foundDescriptions.mapValues(JsString.apply)),
    )).write()
  }

  def readOldSpec(): Unit = {
    val config = getConfig()
    val f = new File(oldSpecPath)
    if (f.exists()) {
      val oldSpec = Json.parse(Files.readString(f.toPath))
      val descriptions = new TrieMap[String, String]()
      val examples = new TrieMap[String, String]()
      oldSpec.select("components").select("schemas").asObject.value.map {
        case (key, value) => {
          val path = s"old.${key}"
          value.select("properties").asOpt[JsObject].map(_.value.map {
            case (field, component) => {
              val example = component.select("example").asOpt[String]
              val description = component.select("description").asOpt[String]
              example.foreach(v => examples.put(s"$path.$field", v))
              description.foreach(v => descriptions.put(s"$path.$field", v))
            }
          })
        }
      }
      OpenApiGeneratorConfig(config.filePath, config.raw.asObject ++ Json.obj(
        "old_descriptions" -> JsObject(descriptions.mapValues(JsString.apply)),
        "old_examples" -> JsObject(examples.mapValues(JsString.apply))
      )).write()
    } else {
      println("No old spec file found !!!!")
    }
  }
}

class OpenApiGeneratorRunner extends App {

  val generator = new OpenApiGenerator(
    "./conf/routes",
    "./app/openapi/openapi.cfg",
    "./app/openapi/openapi.json",
    "../release-1.5.0-alpha.8/swagger.json"
  )

  generator.run()
}
