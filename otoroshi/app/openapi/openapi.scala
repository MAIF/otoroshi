package otoroshi.openapi

import io.github.classgraph._
import otoroshi.models.Entity
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.yaml.Yaml.write
import play.api.libs.json._

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap

case class OpenApiGeneratorConfig(filePath: String, raw: JsValue) {

  lazy val add_schemas   = raw.select("add_schemas").asOpt[JsObject].getOrElse(Json.obj())
  lazy val merge_schemas = raw.select("merge_schemas").asOpt[JsObject].getOrElse(Json.obj())
  lazy val fields_rename = raw.select("fields_rename").asOpt[JsObject].getOrElse(Json.obj())
  lazy val add_fields = raw.select("add_fields").asOpt[JsObject].getOrElse(Json.obj())

  lazy val bulkControllerMethods             = raw
    .select("bulkControllerMethods")
    .asOpt[Seq[String]]
    .getOrElse(
      Seq(
        "bulkUpdateAction",
        "bulkCreateAction",
        "bulkPatchAction",
        "bulkDeleteAction"
      )
    )
  lazy val crudControllerMethods             = raw
    .select("crudControllerMethods")
    .asOpt[Seq[String]]
    .getOrElse(
      Seq(
        "createAction",
        "findAllEntitiesAction",
        "findEntityByIdAction",
        "updateEntityAction",
        "patchEntityAction",
        "deleteEntityAction",
        "deleteEntitiesAction"
      )
    )
  lazy val banned: Seq[String]               = raw
    .select("banned")
    .asOpt[Seq[String]]
    .getOrElse(
      Seq(
        "otoroshi.controllers.BackOfficeController$SearchedService",
        "otoroshi.models.EntityLocationSupport",
        "otoroshi.auth.AuthModuleConfig",
        "otoroshi.auth.OAuth2ModuleConfig",
        "otoroshi.ssl.ClientCertificateValidator"
      )
    )
  lazy val descriptions: Map[String, String] =
    raw.select("descriptions").asOpt[Map[String, String]].getOrElse(Map.empty)
  // lazy val old_descriptions: Map[String, String] = raw.select("old_descriptions").asOpt[Map[String, String]].getOrElse(Map.empty)
  // lazy val old_examples: Map[String, String] = raw.select("old_examples").asOpt[Map[String, String]].getOrElse(Map.empty)
  def write(): Unit = {
    val config = Json.obj(
      "banned"                -> JsArray(banned.map(JsString.apply)),
      "descriptions"          -> JsObject(descriptions.mapValues(JsString.apply)),
      // "old_descriptions" -> JsObject(old_descriptions.mapValues(JsString.apply)),
      // "old_examples" -> JsObject(old_examples.mapValues(JsString.apply)),
      "bulkControllerMethods" -> JsArray(bulkControllerMethods.map(JsString.apply)),
      "crudControllerMethods" -> JsArray(crudControllerMethods.map(JsString.apply)),
      "add_schemas"           -> add_schemas,
      "merge_schemas"         -> merge_schemas,
      "fields_rename"         -> fields_rename,
      "add_fields"            -> add_fields
    )
    val f      = new File(filePath)
    if (!f.exists()) {
      f.createNewFile()
    }

    val descs = descriptions
      .mapValues(JsString.apply)
      .toSeq
      .sortWith((a, b) => a._1.compareTo(b._1) < 0)
      .map(t => s"    ${JsString(t._1).stringify}: ${t._2.stringify}")
      .mkString(",\n")
    // val olddescs = old_descriptions.mapValues(JsString.apply).toSeq.sortWith((a, b) => a._1.compareTo(b._1) < 0).map(t => s"    ${JsString(t._1).stringify}: ${t._2.stringify}").mkString(",\n")

    val fileContent = s"""{
  "banned": ${JsArray(banned.map(JsString.apply)).prettify},
  "descriptions": {
$descs
  },
  "add_schemas": ${add_schemas.prettify.split("\n").map(v => "  " + v).mkString("\n")},
  "merge_schemas": ${merge_schemas.prettify.split("\n").map(v => "  " + v).mkString("\n")},
  "fields_rename": ${fields_rename.prettify.split("\n").map(v => "  " + v).mkString("\n")},
  "add_fields": ${add_fields.prettify.split("\n").map(v => "  " + v).mkString("\n")}
}"""
    println(s"write config file: '${f.getAbsolutePath}'")
    Files.write(f.toPath, fileContent.split("\n").toList.asJava, StandardCharsets.UTF_8)
  }
}

// TODO: validate weird generated stuff
// TODO: handle all Unknown data type
// TODO: handle all ???
// TODO: handle adt with type field
class OpenApiGenerator(routerPath: String, configFilePath: String, specFiles: Seq[String], write: Boolean) {

  val nullType       =
    Json.obj("$ref" -> s"#/components/schemas/Null") // Json.obj("type" -> "null") needs openapi 3.1.0 support :(
  val openApiVersion = JsString("3.0.3")
  val unknownValue   = "???"

  val scanResult: ScanResult = new ClassGraph()
    .addClassLoader(this.getClass.getClassLoader)
    .enableAllInfo()
    .whitelistPackages(Seq("otoroshi", "play.api.libs.ws"): _*)
    .scan

  val world = scanResult.getAllClassesAsMap.asScala

  val entities = (
    scanResult.getClassesImplementing(classOf[Entity].getName).asScala ++
      scanResult.getSubclasses(classOf[Entity].getName).asScala ++
      world.get("otoroshi.models.GlobalConfig") ++
      world.get("otoroshi.ssl.pki.models.GenKeyPairQuery") ++
      world.get("otoroshi.ssl.pki.models.GenCsrQuery") ++
      world.get("otoroshi.ssl.pki.models.GenCertResponse") ++
      world.get("otoroshi.ssl.pki.models.GenCsrResponse") ++
      world.get("otoroshi.ssl.pki.models.SignCertResponse") ++
      world.get("otoroshi.ssl.pki.models.GenKeyPairResponse") ++
      world.get("otoroshi.models.ErrorTemplate") ++
      world.get("otoroshi.models.Outage") ++
      world.get("otoroshi.models.RemainingQuotas") ++
      world.get("otoroshi.events.HealthCheckEvent")
  ).toSeq.distinct

  var adts              = Seq.empty[JsObject]
  val foundDescriptions = new TrieMap[String, String]()
  val found             = new AtomicLong(0L)
  val notFound          = new AtomicLong(0L)
  val resFound          = new AtomicLong(0L)
  val resNotFound       = new AtomicLong(0L)
  val inFound           = new AtomicLong(0L)
  val inNotFound        = new AtomicLong(0L)

  def getFieldDescription(
      clazz: ClassInfo,
      name: String,
      typ: TypeSignature,
      config: OpenApiGeneratorConfig
  ): JsValue = {
    val finalPath  = s"${clazz.getName}.$name"
    val simpleName = clazz.getSimpleName match {
      case "ServiceDescriptor" => "Service"
      case "ServiceGroup"      => "Service"
      case v                   => v
    }
    config.descriptions.get(s"${clazz.getName}.$name").filterNot(_ == unknownValue) match {
      case None        =>
        notFound.incrementAndGet()
        foundDescriptions.put(finalPath, unknownValue)
        unknownValue.json
      case Some(value) =>
        found.incrementAndGet()
        foundDescriptions.put(finalPath, value)
        value.json
    }
  }

  def entityDescription(clazz: String, config: OpenApiGeneratorConfig): JsValue = {
    val finalPath = s"entity_description.${clazz}"
    config.descriptions.get(finalPath).filterNot(_ == unknownValue) match {
      case None        =>
        notFound.incrementAndGet()
        foundDescriptions.put(finalPath, unknownValue)
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
      case None        =>
        notFound.incrementAndGet()
        foundDescriptions.put(finalPath, unknownValue)
        unknownValue.json
      case Some(value) =>
        found.incrementAndGet()
        foundDescriptions.put(finalPath, value)
        value.json
    }
  }

  def getOperationDescription(
      verb: String,
      path: String,
      operationId: String,
      tag: String,
      controllerName: String,
      controllerMethod: String,
      isCrud: Boolean,
      isBulk: Boolean,
      entity: Option[String],
      rawTag: String,
      config: OpenApiGeneratorConfig
  ): JsValue = {

    def foundDescription(finalPath: String, value: String): JsValue = {
      found.incrementAndGet()
      foundDescriptions.put(finalPath, value)
      value.json
    }

    def singular  = entity.map(_.split("\\.").last).get
    def plural    = s"${singular}s"
    val finalPath = s"operations.$controllerName.${controllerMethod}_$tag"
    config.descriptions.get(finalPath).filterNot(_ == unknownValue) match {
      case None if isBulk && controllerMethod == "bulkUpdateAction" =>
        foundDescription(finalPath, s"Update multiple $plural at the same time")
      case None if isBulk && controllerMethod == "bulkCreateAction" =>
        foundDescription(finalPath, s"Create multiple $plural at the same time")
      case None if isBulk && controllerMethod == "bulkPatchAction"  =>
        foundDescription(finalPath, s"Update (using json-patch) multiple $plural at the same time")
      case None if isBulk && controllerMethod == "bulkDeleteAction" =>
        foundDescription(finalPath, s"Delete multiple $plural at the same time")

      case None if isCrud && controllerMethod == "createAction"          => foundDescription(finalPath, s"Creates a $singular")
      case None if isCrud && controllerMethod == "findAllEntitiesAction" =>
        foundDescription(finalPath, s"Find all possible $plural entities")
      case None if isCrud && controllerMethod == "findEntityByIdAction"  =>
        foundDescription(finalPath, s"Find a specific $singular using its id")
      case None if isCrud && controllerMethod == "updateEntityAction"    =>
        foundDescription(finalPath, s"Updates a specific $singular using its id")
      case None if isCrud && controllerMethod == "patchEntityAction"     =>
        foundDescription(finalPath, s"Updates (using json-patch) a specific $singular using its id")
      case None if isCrud && controllerMethod == "deleteEntityAction"    =>
        foundDescription(finalPath, s"Deletes a specific $singular using its id")
      case None if isCrud && controllerMethod == "deleteEntitiesAction"  =>
        foundDescription(finalPath, s"Deletes all $plural entities")

      case None if controllerName.endsWith("TemplatesController") && controllerMethod.startsWith("initiate")   =>
        foundDescription(
          finalPath,
          s"Creates a new ${controllerMethod.replace("initiate", "").split("_")(0)} from a template"
        )
      case None if controllerName.endsWith("TemplatesController") && controllerMethod.startsWith("createFrom") =>
        foundDescription(
          finalPath,
          s"Creates a new ${controllerMethod.replace("createFrom", "").split("_")(0)} from a template"
        )

      case None        =>
        notFound.incrementAndGet()
        foundDescriptions.put(finalPath, unknownValue)
        unknownValue.json
      case Some(value) =>
        found.incrementAndGet()
        foundDescriptions.put(finalPath, value)
        value.json
    }
  }

  def visitEntity(
      clazz: ClassInfo,
      parent: Option[ClassInfo],
      result: TrieMap[String, JsValue],
      config: OpenApiGeneratorConfig
  ): Unit = {

    if (clazz.getName.contains("$")) {
      return ()
    }

    if (clazz.getName.startsWith("otoroshi.")) {
      if (clazz.isInterface) {
        val children = scanResult.getClassesImplementing(clazz.getName).asScala.map(_.getName)
        children.flatMap(cl => world.get(cl)).map(cl => visitEntity(cl, clazz.some, result, config))
        adts = adts :+ Json.obj(
          clazz.getName -> Json.obj(
            "oneOf" -> JsArray(children.map(c => Json.obj("$ref" -> s"#/components/schemas/$c"))
            )
          )
        )
      }
    }

    if (!result.contains(clazz.getName)) {

      val ctrInfo    = clazz.getDeclaredConstructorInfo.asScala.headOption
      val params     = ctrInfo.map(_.getParameterInfo.toSeq).getOrElse(Seq.empty)
      val paramNames = params.map { param =>
        param.getName
      }

      val fields     = (clazz.getFieldInfo.asScala ++ clazz.getDeclaredFieldInfo.asScala).toSet
        .filter(_.isFinal)
        .filter(i => paramNames.contains(i.getName))
      var properties = Json.obj()
      var required   = Json.arr()

      def handleType(name: String, valueName: String, typ: TypeSignature): Option[JsObject] = {
        valueName match {
          case "java.security.KeyPair"                                     => None
          case "play.api.Logger"                                           => None
          case "byte"                                                      => None
          case "java.security.cert.X509Certificate[]"                      => None
          case _ if typ.toString == "byte"                                 => None
          case _ if typ.toString == "java.security.cert.X509Certificate[]" => None
          case "int"                                                       => Json.obj("type" -> "integer", "format" -> "int32").some
          case "long"                                                      => Json.obj("type" -> "integer", "format" -> "int64").some
          case "double"                                                    => Json.obj("type" -> "number", "format" -> "double").some
          case "float"                                                     => Json.obj("type" -> "number", "format" -> "float").some

          case "java.math.BigInteger" => Json.obj("type" -> "integer", "format" -> "int64").some
          case "java.math.BigDecimal" => Json.obj("type" -> "number", "format" -> "double").some
          case "java.lang.Integer"    => Json.obj("type" -> "integer", "format" -> "int32").some
          case "java.lang.Long"       => Json.obj("type" -> "integer", "format" -> "int64").some
          case "java.lang.Double"     => Json.obj("type" -> "number", "format" -> "double").some
          case "java.lang.Float"      => Json.obj("type" -> "number", "format" -> "float").some

          case "scala.math.BigInt"     => Json.obj("type" -> "integer", "format" -> "int64").some
          case "scala.math.BigDecimal" => Json.obj("type" -> "number", "format" -> "double").some
          case "scala.Int"             => Json.obj("type" -> "integer", "format" -> "int32").some
          case "scala.Long"            => Json.obj("type" -> "integer", "format" -> "int64").some
          case "scala.Double"          => Json.obj("type" -> "number", "format" -> "double").some
          case "scala.Float"           => Json.obj("type" -> "number", "format" -> "float").some

          case "boolean"                                          => Json.obj("type" -> "boolean").some
          case "java.lang.Boolean"                                => Json.obj("type" -> "boolean").some
          case "scala.Boolean"                                    => Json.obj("type" -> "boolean").some
          case "java.lang.String"                                 => Json.obj("type" -> "string").some
          case "org.joda.time.DateTime"                           => Json.obj("type" -> "number").some
          case "scala.concurrent.duration.FiniteDuration"         => Json.obj("type" -> "number").some
          case "org.joda.time.LocalTime"                          => Json.obj("type" -> "string").some
          case "play.api.libs.json.JsValue"                       => Json.obj("type" -> "object").some
          case "play.api.libs.json.JsObject"                      => Json.obj("type" -> "object").some
          case "play.api.libs.json.JsArray"                       => Json.obj("type" -> "array").some
          case "akka.http.scaladsl.model.HttpProtocol"            => Json.obj("type" -> "string").some
          case "java.security.cert.X509Certificate"               =>
            Json.obj("type" -> "string", "description" -> "pem encoded X509 certificate").some
          case "java.security.PrivateKey"                         =>
            Json.obj("type" -> "string", "description" -> "pem encoded private key").some
          case "java.security.PublicKey"                          =>
            Json.obj("type" -> "string", "description" -> "pem encoded private key").some
          case "org.bouncycastle.pkcs.PKCS10CertificationRequest" =>
            Json.obj("type" -> "string", "description" -> "pem encoded csr").some
          case "com.nimbusds.jose.jwk.KeyType"                    => Json.obj("type" -> "string", "description" -> "key type").some
          case _ if typ.toString.startsWith("scala.Option<")      => {
            world.get(valueName).map(cl => visitEntity(cl, None, result, config))
            result.get(valueName) match {
              case None
                  if valueName == "java.lang.Object" && (name == "maxJwtLifespanSecs" || name == "existingSerialNumber") =>
                Json.obj("type" -> "integer", "format" -> "int64").some
              case Some(v) => Json.obj("$ref" -> s"#/components/schemas/$valueName").some
              case _       =>
                println("fuuuuu opt", name, valueName)
                None
            }
          }
          case vn if valueName.startsWith("otoroshi")             => {
            world.get(valueName).map(cl => visitEntity(cl, None, result, config))
            Json.obj("$ref" -> s"#/components/schemas/$valueName").some
          }
          case _                                                  =>
            println(s"${clazz.getName}.$name: $typ (unexpected 1)")
            None
        }
      }

      fields.foreach { field =>
        val name = field.getName
        val typ  = field.getTypeSignatureOrTypeDescriptor
        typ match {
          case c: BaseTypeSignature                                                                              =>
            val valueName = c.getTypeStr
            val fieldName = config.fields_rename.select(s"$name:$valueName").asOpt[String]
              .orElse(config.fields_rename.select(s"${clazz.getName}.$name:$valueName").asOpt[String])
              .orElse(config.fields_rename.select(s"${clazz.getName}.$name").asOpt[String])
              .getOrElse(name)
            handleType(name, c.getTypeStr, typ).foreach { r =>
              properties = properties ++ Json.obj(
                fieldName -> r.deepMerge(
                  Json.obj(
                    "description" -> getFieldDescription(clazz, name, typ, config)
                  )
                )
              )
            }
          case c: ClassRefTypeSignature
              if c.getTypeArguments.size() > 0 && c.getBaseClassName == "scala.collection.immutable.Map" =>
            val valueName = c.getTypeArguments.asScala.tail.head.toString
            val fieldName = config.fields_rename.select(s"$name:$valueName").asOpt[String]
              .orElse(config.fields_rename.select(s"${clazz.getName}.$name:$valueName").asOpt[String])
              .orElse(config.fields_rename.select(s"${clazz.getName}.$name").asOpt[String])
              .getOrElse(name)
            handleType(name, valueName, typ).foreach { r =>
              properties = properties ++ Json.obj(
                fieldName -> Json.obj(
                  "type"                 -> "object",
                  "additionalProperties" -> r,
                  "description"          -> getFieldDescription(clazz, name, typ, config)
                )
              )
            }
          case c: ClassRefTypeSignature
              if c.getTypeArguments.size() > 0 && c.getBaseClassName == "scala.collection.Seq" =>
            val valueName = c.getTypeArguments.asScala.head.toString
            val fieldName = config.fields_rename.select(s"$name:$valueName").asOpt[String]
              .orElse(config.fields_rename.select(s"${clazz.getName}.$name:$valueName").asOpt[String])
              .orElse(config.fields_rename.select(s"${clazz.getName}.$name").asOpt[String])
              .getOrElse(name)
            handleType(name, valueName, typ).foreach { r =>
              properties = properties ++ Json.obj(
                fieldName -> Json.obj(
                  "type"        -> "array",
                  "items"       -> r,
                  "description" -> getFieldDescription(clazz, name, typ, config)
                )
              )
            }
          case c: ClassRefTypeSignature
              if c.getTypeArguments.size() > 0 && c.getBaseClassName == "scala.collection.immutable.List" =>
            val valueName = c.getTypeArguments.asScala.head.toString
            val fieldName = config.fields_rename.select(s"$name:$valueName").asOpt[String]
              .orElse(config.fields_rename.select(s"${clazz.getName}.$name:$valueName").asOpt[String])
              .orElse(config.fields_rename.select(s"${clazz.getName}.$name").asOpt[String])
              .getOrElse(name)
            handleType(name, valueName, typ).foreach { r =>
              properties = properties ++ Json.obj(
                fieldName -> Json.obj(
                  "type"        -> "array",
                  "items"       -> r,
                  "description" -> getFieldDescription(clazz, name, typ, config)
                )
              )
            }
          case c: ClassRefTypeSignature if c.getTypeArguments.size() > 0 && c.getBaseClassName == "scala.Option" =>
            val valueName = c.getTypeArguments.asScala.head.toString
            val fieldName = config.fields_rename.select(s"$name:$valueName").asOpt[String]
              .orElse(config.fields_rename.select(s"${clazz.getName}.$name:$valueName").asOpt[String])
              .orElse(config.fields_rename.select(s"${clazz.getName}.$name").asOpt[String])
              .getOrElse(name)
            handleType(name, valueName, typ).foreach { r =>
              properties = properties ++ Json.obj(
                fieldName -> Json.obj(
                  "oneOf"       -> Json.arr(
                    nullType,
                    r
                  ),
                  "description" -> getFieldDescription(clazz, name, typ, config)
                )
              )
            }
          case c: ClassRefTypeSignature                                                                          =>
            val valueName = c.getBaseClassName
            val fieldName = config.fields_rename.select(s"$name:$valueName").asOpt[String]
              .orElse(config.fields_rename.select(s"${clazz.getName}.$name:$valueName").asOpt[String])
              .orElse(config.fields_rename.select(s"${clazz.getName}.$name").asOpt[String])
              .getOrElse(name)
            handleType(name, valueName, typ).map { r =>
              properties = properties ++ Json.obj(
                fieldName -> r.deepMerge(Json.obj("description" -> getFieldDescription(clazz, name, typ, config)))
              )
            }
          // case c: TypeVariableSignature => println(s"  $name: $typ ${c.toStringWithTypeBound} (var)")
          // case c: ArrayTypeSignature    => println(s"  $name: $typ ${c.getTypeSignatureStr} ${c.getElementTypeSignature.toString} (arr)")
          // case c: ReferenceTypeSignature => println(s"  $name: $typ (ref)")
          case _                                                                                                 =>
            println(s"${clazz.getName}.$name: $typ (unexpected 2)")
        }
      }

      val toMergeSelf    = config.merge_schemas.select(clazz.getName).asOpt[JsObject].getOrElse(Json.obj())
      val toMergeParent  =
        parent.flatMap(p => config.merge_schemas.select(p.getName).asOpt[JsObject]).getOrElse(Json.obj())
      val toMergeParents = clazz.getInterfaces.asScala
        .filter(_.getName.startsWith("otoroshi."))
        .flatMap(c => config.merge_schemas.select(c.getName).asOpt[JsObject])
        .foldLeft(Json.obj())((a, b) => a.deepMerge(b))
      val toMerge        = toMergeParent.deepMerge(toMergeParents.deepMerge(toMergeSelf))
      if (clazz.getName.startsWith("otoroshi.auth")) {
        // println(clazz.getName + " - " + parent.map(p => p.getName).getOrElse("") + " - " + clazz.getInterfaces.asScala.map(_.getName).mkString(", "))
      }
      if (toMerge != Json.obj()) {
        //println(s"found some stuff to merge for ${clazz.getName} - ${parent.map(p => p.getName).getOrElse("")}")//- ${toMerge.prettify}")
      }
      result.put(
        clazz.getName,
        toMerge.deepMerge(
          Json.obj(
             "type"        -> "object",
            "description" -> entityDescription(clazz.getName, config),
            "properties"  -> properties,
            "openAPIV3Schema" -> Json.obj(
              "type" -> "object",
              "description" -> entityDescription(clazz.getName, config),
              "properties" -> Json.obj(
                "apiVersion" -> Json.obj(
                  "description" -> "APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources",
                  "type" -> "string"
                ),
                "kind" -> Json.obj(
                  "description" -> "Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds",
                  "type" -> "string"
                ),
                "metadata" -> Json.obj(
                  "type" -> "object"
                ),
                "spec" -> Json.obj(
                  "type" -> "object",
                  "description" -> entityDescription(clazz.getName, config),
                  "properties" -> properties
                )
              )
            )
          )
        )
      )
    }
  }

  def getConfig(): OpenApiGeneratorConfig = {
    val f = new File(configFilePath)
    if (f.exists()) {
      OpenApiGeneratorConfig(configFilePath, Json.parse(Files.readAllLines(f.toPath).asScala.mkString("\n")))
    } else {
      OpenApiGeneratorConfig(configFilePath, Json.obj())
    }
  }

  def extractParameters(path: String, entity: Option[String], isBulk: Boolean, isCrud: Boolean): JsValue = {
    JsArray(
      path
        .split("/")
        .toSeq
        .filter(_.contains(":"))
        .map { param =>
          val paramName = param.replace(":", "")
          if (isBulk || isCrud) {
            Json.obj(
              "name"        -> paramName,
              "in"          -> "path",
              "schema"      -> Json.obj(
                "type" -> "string"
              ),
              "required"    -> true,
              "description" -> s"The ${paramName} param of the target entity"
            )
          } else {
            Json.obj(
              "name"        -> paramName,
              "in"          -> "path",
              "schema"      -> Json.obj(
                "type" -> "string"
              ),
              "required"    -> true,
              "description" -> s"the ${paramName} parameter"
            )
          }
        }
    )
  }

  def extractResBody(
      verb: String,
      path: String,
      operationId: String,
      tag: String,
      controllerName: String,
      controllerMethod: String,
      isCrud: Boolean,
      isBulk: Boolean,
      entity: Option[String],
      rawTag: String,
      config: OpenApiGeneratorConfig
  ): (String, JsValue) = {
    def foundDescription(finalPath: String, value: String): String = {
      resFound.incrementAndGet()
      foundDescriptions.put(finalPath, value)
      value
    }
    var resStatus = "200"
    if (isCrud && controllerMethod == "createAction") {
      resStatus = "201"
    }
    var multiple  = false
    if (isCrud && controllerMethod == "findAllEntitiesAction") {
      multiple = true
    }
    val finalPath = s"operations_response_entity.$controllerName.${controllerMethod}_$tag"
    val resBody   = (config.descriptions.get(finalPath).filterNot(_ == unknownValue) match {
      case None if isBulk && controllerMethod == "bulkUpdateAction" => foundDescription(finalPath, "BulkResponseBody")
      case None if isBulk && controllerMethod == "bulkCreateAction" => foundDescription(finalPath, "BulkResponseBody")
      case None if isBulk && controllerMethod == "bulkPatchAction"  => foundDescription(finalPath, "BulkResponseBody")
      case None if isBulk && controllerMethod == "bulkDeleteAction" => foundDescription(finalPath, "BulkResponseBody")

      case None if isCrud && controllerMethod == "createAction"          =>
        resStatus = "201"
        foundDescription(finalPath, entity.get)
      case None if isCrud && controllerMethod == "findAllEntitiesAction" =>
        multiple = true
        foundDescription(finalPath, entity.get)
      case None if isCrud && controllerMethod == "findEntityByIdAction"  => foundDescription(finalPath, entity.get)
      case None if isCrud && controllerMethod == "updateEntityAction"    => foundDescription(finalPath, entity.get)
      case None if isCrud && controllerMethod == "patchEntityAction"     => foundDescription(finalPath, entity.get)
      case None if isCrud && controllerMethod == "deleteEntityAction"    => foundDescription(finalPath, entity.get)
      case None if isCrud && controllerMethod == "deleteEntitiesAction"  => foundDescription(finalPath, entity.get)

      case None        =>
        resNotFound.incrementAndGet()
        foundDescriptions.put(finalPath, unknownValue)
        unknownValue
      case Some(value) =>
        resFound.incrementAndGet()
        foundDescriptions.put(finalPath, value)
        value
    }) match {
      case v if v == unknownValue => Json.obj("$ref" -> "#/components/schemas/Unknown")
      case v if multiple          => Json.obj("type" -> "array", "items" -> Json.obj("$ref" -> s"#/components/schemas/$v"))
      case v                      => Json.obj("$ref" -> s"#/components/schemas/$v")
    }

    (resStatus, resBody)
  }

  def extractReqBody(
      verb: String,
      path: String,
      operationId: String,
      tag: String,
      controllerName: String,
      controllerMethod: String,
      isCrud: Boolean,
      isBulk: Boolean,
      entity: Option[String],
      rawTag: String,
      config: OpenApiGeneratorConfig
  ): Option[JsValue] = {
    val shouldHaveBody =
      verb.toLowerCase() != "get" && verb.toLowerCase() != "delete" && verb.toLowerCase() != "options"
    if (shouldHaveBody) {
      def foundDescription(finalPath: String, value: String): String = {
        inFound.incrementAndGet()
        foundDescriptions.put(finalPath, value)
        value
      }

      val finalPath = s"operations_input_entity.$controllerName.${controllerMethod}_$tag"
      val reqBody   = (config.descriptions.get(finalPath).filterNot(_ == unknownValue) match {
        case None if isBulk && controllerMethod == "bulkUpdateAction" => foundDescription(finalPath, "BulkBody")
        case None if isBulk && controllerMethod == "bulkCreateAction" => foundDescription(finalPath, "BulkBody")
        case None if isBulk && controllerMethod == "bulkPatchAction"  => foundDescription(finalPath, "BulkBody")
        case None if isBulk && controllerMethod == "bulkDeleteAction" => foundDescription(finalPath, "BulkBody")

        case None if isCrud && controllerMethod == "createAction"          => foundDescription(finalPath, entity.get)
        case None if isCrud && controllerMethod == "findAllEntitiesAction" => foundDescription(finalPath, entity.get)
        case None if isCrud && controllerMethod == "findEntityByIdAction"  => foundDescription(finalPath, entity.get)
        case None if isCrud && controllerMethod == "updateEntityAction"    => foundDescription(finalPath, entity.get)
        case None if isCrud && controllerMethod == "patchEntityAction"     => foundDescription(finalPath, entity.get)
        case None if isCrud && controllerMethod == "deleteEntityAction"    => foundDescription(finalPath, entity.get)
        case None if isCrud && controllerMethod == "deleteEntitiesAction"  => foundDescription(finalPath, entity.get)

        case None        =>
          inNotFound.incrementAndGet()
          foundDescriptions.put(finalPath, unknownValue)
          unknownValue
        case Some(value) =>
          inFound.incrementAndGet()
          foundDescriptions.put(finalPath, value)
          value
      }) match {
        case v if v == unknownValue => Json.obj("$ref" -> "#/components/schemas/Unknown")
        case v                      => Json.obj("$ref" -> s"#/components/schemas/$v")
      }
      reqBody.some
    } else {
      None
    }
  }

  def scanPaths(config: OpenApiGeneratorConfig): (JsValue, JsValue) = {
    val f = new File(routerPath)
    if (f.exists()) {
      var tags             = Seq.empty[String]
      val lines            = Files
        .readAllLines(f.toPath, StandardCharsets.UTF_8)
        .asScala
        .toSeq
        .map(_.trim)
        .filterNot(_.startsWith("#"))
        .filterNot(_.isEmpty);
      val pathes: JsObject = lines
        .map { line =>
          val parts = line.split(" ").toSeq.map(_.trim).filterNot(_.isEmpty).toList
          parts match {
            case verb :: path :: rest
                if path.startsWith("/api") && !path.startsWith("/api/client-validators") && !path.startsWith(
                  "/api/swagger"
                ) && !path.startsWith("/api/openapi") && !path.startsWith("/api/services/:serviceId/apikeys") && !path
                  .startsWith("/api/groups/:groupId/apikeys") => {
              val name               = rest.mkString(" ").split("\\(").head
              val methodName         = name.split("\\.").reverse.head
              val controllerName     = name.split("\\.").reverse.tail.reverse.mkString(".")
              val controller         = world.get(controllerName).get
              val method             = controller.getMethodInfo(methodName)
              val isCrud             = controller.implementsInterface("otoroshi.utils.controllers.CrudControllerHelper")
              val isBulk             = controller.implementsInterface("otoroshi.utils.controllers.BulkControllerHelper")
              val entity             = if (isCrud || isBulk) {
                controller
                  .getMethodInfo("extractId")
                  .asScala
                  .head
                  .getParameterInfo
                  .toSeq
                  .head
                  .getTypeDescriptor
                  .toString
                  .some
              } else {
                None
              }
              val pathParts          = path.split("/").toList
              val rawTag             = pathParts.tail.tail.head
              // TODO: from config
              val tag                = rawTag match {
                case "data-exporter-configs" => "data-exporters"
                case "tenants"               => "organizations"
                case "verifiers"             => "jwt-verifiers"
                case "import"                => "import-export"
                case "otoroshi.json"         => "import-export"
                case ":entity"               => "templates"
                case "new"                   => "templates"
                case "auths"                 => "auth-modules"
                case "stats"                 => "analytics"
                case "events"                => "analytics"
                case "status"                => "analytics"
                case "audit"                 => "events"
                case "alert"                 => "events"
                case v                       => v
              }
              // TODO: from config
              val operationId        = s"$controllerName.$methodName" match {
                case "otoroshi.controllers.adminapi.StatsController.serviceLiveStats"                          =>
                  s"$controllerName.${methodName}_${tag}"
                case "otoroshi.controllers.adminapi.TemplatesController.initiateTcpService"                    =>
                  s"$controllerName.${methodName}_${tag}"
                case "otoroshi.controllers.adminapi.TemplatesController.initiateApiKey"                        =>
                  s"$controllerName.${methodName}_${tag}"
                case "otoroshi.controllers.adminapi.TemplatesController.initiateService"                       =>
                  s"$controllerName.${methodName}_${tag}"
                case "otoroshi.controllers.adminapi.TemplatesController.initiateServiceGroup"                  =>
                  s"$controllerName.${methodName}_${tag}"
                case "otoroshi.controllers.adminapi.TemplatesController.createFromTemplate" if tag == "admins" =>
                  s"$controllerName.${methodName}_${pathParts.apply(3)}"
                case "otoroshi.controllers.adminapi.TemplatesController.createFromTemplate"                    =>
                  s"$controllerName.${methodName}_${tag}"
                case v                                                                                         => v
              }
              tags = tags :+ tag
              val (resCode, resBody) = extractResBody(
                verb,
                path,
                operationId,
                tag,
                controllerName,
                methodName,
                isCrud,
                isBulk,
                entity,
                rawTag,
                config
              )
              val reqBodyOpt         = extractReqBody(
                verb,
                path,
                operationId,
                tag,
                controllerName,
                methodName,
                isCrud,
                isBulk,
                entity,
                rawTag,
                config
              )
              val customizedPath     = path
                .split("/")
                .map {
                  case part if part.startsWith(":") => s"{${part.substring(1)}}"
                  case part                         => part
                }
                .mkString("/")
              Json.obj(
                customizedPath -> Json.obj(
                  verb.toLowerCase() -> Json
                    .obj(
                      "tags"        -> Json.arr(tag),
                      "summary"     -> getOperationDescription(
                        verb,
                        path,
                        operationId,
                        tag,
                        controllerName,
                        methodName,
                        isCrud,
                        isBulk,
                        entity,
                        rawTag,
                        config
                      ),
                      "operationId" -> operationId,
                      "parameters"  -> extractParameters(path, entity, isBulk, isCrud),
                      "security"    -> Json.arr(Json.obj("otoroshi_auth" -> Json.arr())),
                      "responses"   -> Json.obj(
                        "401"   -> Json.obj(
                          "description" -> "You have to provide an Api Key. Api Key can be passed with 'Otoroshi-Client-Id' and 'Otoroshi-Client-Secret' headers, or use basic http authentication",
                          "content"     -> Json.obj(
                            "application/json" -> Json.obj(
                              "schema" -> Json.obj(
                                "$ref" -> "#/components/schemas/ErrorResponse"
                              )
                            )
                          )
                        ),
                        "400"   -> Json.obj(
                          "description" -> "Bad resource format. Take another look to the swagger, or open an issue",
                          "content"     -> Json.obj(
                            "application/json" -> Json.obj(
                              "schema" -> Json.obj(
                                "$ref" -> "#/components/schemas/ErrorResponse"
                              )
                            )
                          )
                        ),
                        "404"   -> Json.obj(
                          "description" -> "Resource not found or does not exist",
                          "content"     -> Json.obj(
                            "application/json" -> Json.obj(
                              "schema" -> Json.obj(
                                "$ref" -> "#/components/schemas/ErrorResponse"
                              )
                            )
                          )
                        ),
                        resCode -> Json.obj(
                          "description" -> "Successful operation",
                          "content"     -> Json.obj(
                            "application/json" -> Json.obj(
                              "schema" -> resBody
                            )
                          )
                        )
                      )
                    )
                    .applyOnIf(
                      verb.toLowerCase() != "get" && verb.toLowerCase() != "delete" && verb.toLowerCase() != "options"
                    ) { c =>
                      c ++ Json.obj(
                        "requestBody" -> Json.obj(
                          "description" -> "request body",
                          "required"    -> true,
                          "content"     -> Json.obj(
                            "application/json" -> Json.obj(
                              "schema" -> reqBodyOpt.get
                            )
                          )
                        )
                      )
                    }
                )
              )
            }
            case _ =>
              // println(s"bad definition: $line")
              Json.obj()
          }
        }
        .foldLeft(Json.obj())((a, b) => a.deepMerge(b))
      (
        pathes,
        JsArray(
          tags.distinct
            .sortWith((a, b) => a.compareTo(b) < 0)
            .map(t =>
              Json.obj("name" -> t).applyOn { o =>
                o ++ Json.obj("description" -> getTagDescription(o.select("name").asString, config))
              }
            )
        )
      )
    } else {
      (Json.obj(), Json.obj())
    }
  }

  def run(): (JsValue, JsObject) = {
    val config = getConfig()
    val result = new TrieMap[String, JsValue]()

    config.add_schemas.value.map { case (key, value) =>
      result.put(key, value)
    }

    entities.foreach { clazz =>
      if (!config.banned.contains(clazz.getName)) {
        visitEntity(clazz, None, result, config)
      }
    }

    def reads(path: String): JsPath =
      path.split("/").foldLeft(JsPath()) { (acc, num) =>
          acc \ num
      }

    def replaceSubOneOf(key: String, path: String): Boolean = {
      val currentObj = result(key).as[JsObject].atPointer(path).asOpt[JsObject] match {
        case Some(p) => p
        case _ => Json.obj()
      }

      ((currentObj \ "type").asOpt[String], (currentObj \ "items").asOpt[JsObject]) match {
        case (Some(t), Some(items)) if t == "array" && (items \ "$ref").asOpt[String].isDefined =>
          result.put(key, result(key).transform(
            reads(path + "/items/$ref").json.prune
          ).get)

          replaceRef(key, path + "/items", (items \ "$ref").as[String], true)

          true
        case _ =>
          currentObj.fields.map {
            case ("oneOf", oneOfArray) =>
              val fields = oneOfArray.as[Seq[JsValue]]

              result.put(key, result(key).transform(
                reads(path + "/oneOf").json.prune
              ).get)

              fields.find(field => (field \ "type").asOpt[String].isDefined) match {
                case Some(field) =>
                  result.put(key, result(key).transform(
                    reads(path).json.update(__.read[JsObject].map(o => o ++ field.as[JsObject]))
                  ).get)
                case None =>
                  result.put(key, result(key).transform(
                    reads(path).json.update(__.read[JsObject].map(o => o ++ Json.obj("type" -> "object")))
                  ).get)

                  val refs = fields.flatMap { rawField =>
                    val field = (rawField \ "$ref").as[String]
                    if (field != "#/components/schemas/Null") {
                      val obj = getRef(field)
                      (obj \ "oneOf").asOpt[Seq[JsValue]] match {
                        case Some(arr) => arr.map { f =>
                          val r = (f \ "$ref").as[String]
                          if (r != "#/components/schemas/Null")
                            getRef(r)
                          else
                            Json.obj()
                        }
                        case _ => Seq(obj)
                      }
                    }
                    else
                      Seq(Json.obj())
                  }
                    .filter(f => f.fields.nonEmpty)

                  if (refs.length == 1)
                    (refs.head \ "oneOf").asOpt[JsArray] match {
                      case Some(ob) if ob.value.map(_.as[JsObject]).forall(p => (p \ "$ref").isDefined) =>
                        result.put(key, result(key).transform(
                          reads(path).json.update(__.read[JsObject].map(o => o ++ refs.head.as[JsObject])
                          )
                        ).get)
                      case _ =>
                        result.put(key, result(key).transform(
                          reads(path).json.update(__.read[JsObject].map(o => o ++ Json.obj("properties" -> refs.head)))
                        ).get)
                    }
                  else
                    result.put(key, result(key).transform(
                      reads(path).json.update(__.read[JsObject].map(o => o ++ Json.obj(
                        "oneOfConstraints" -> refs.map(ref => Json.obj("required" -> ref.keys)),
                        "properties" -> refs.foldLeft(Json.obj())((acc, curr) => acc ++ curr)
                      )
                      ))
                    ).get)
              }
              true
            case ("$ref", fields) if fields.isInstanceOf[JsString] =>
              result.put(key, result(key).transform(
                reads(path + "/$ref").json.prune
              ).get)

              replaceRef(key, path, fields.as[String])

              true

            case (fieldName, fields) if fields.isInstanceOf[JsObject] => replaceSubOneOf(key, s"$path/$fieldName")
            case _ => false
          }.foldLeft(false)(_ || _)

      }
    }

    def getRef(ref: String): JsObject = {
      if (ref.startsWith("#/components/schemas/otoroshi.")) {
        val reference = ref.replace("#/components/schemas/", "")

        (result(reference) \ "properties").asOpt[JsObject] match {
          case Some(prop) => prop
          case _ => result(reference).as[JsObject]
        }
      } else
          Json.obj()
    }

    def replaceRef(key: String, path: String, ref: String, itemsField: Boolean = false) = {
      if (ref.startsWith("#/components/schemas/otoroshi.")) {
        val out = getRef(ref)

        (out \ "type").asOpt[String] match {
          case Some(t) if t == "string" && (out \ "enum").asOpt[JsArray].isEmpty =>
            result.put(key, result(key).transform(
              reads(path).json.update(__.read[JsObject].map(o => o ++ Json.obj("type" -> "string")))
            ).get)
          case _ =>
            result.put(key, result(key).transform(
              reads(path).json.update(__.read[JsObject].map(o => o ++
                ((out \ "oneOf").asOpt[JsArray] match {
                  case Some(arr) if arr.value.length > 2 || containsNullAndRef(arr.value) => out
                  case Some(arr) if containsOnlyRef(arr.value) =>
                    Json.obj("type" -> (getRef((arr.value.head \ "$ref").as[String]) \ "type").as[String])
                  case None if (out \ "enum").isDefined =>
                    out
                  case _ => Json.obj("properties" -> out, "type" -> "object")
                })))
            ).get)
        }
      }
    }

    def containsOnlyRef(values: IndexedSeq[JsValue]): Boolean =
      values.forall(p => (p \ "$ref").as[String] != "#/components/schemas/Null")

    def containsNullAndRef(values: IndexedSeq[JsValue]): Boolean =
      values.exists(p => (p \ "$ref").as[String] == "#/components/schemas/Null") &&
        values.exists(p => (p\ "$ref").as[String] != "#/components/schemas/Null")

    def replaceOneOf(): Boolean =
      JsObject(result).fields.map { case (key, value) =>
        (value \ "openAPIV3Schema" \ "properties" \ "spec" \ "properties").asOpt[JsObject] match {
          case Some(_) => replaceSubOneOf(key, "openAPIV3Schema/properties/spec/properties")
          case _ => false // if here, not important to treat it cauz openAPIV3Schema is not declared
        }
      }.foldLeft(false)(_ || _)

    var changed = true
    do {
      changed = replaceOneOf()
    } while(changed)

    val (paths, tags) = scanPaths(config)

    println("")
    println(s"found ${found.get()} descriptions, not found ${notFound.get()} descriptions")
    println(s"found ${resFound.get()} response types, not found ${resNotFound.get()} response types")
    println(s"found ${inFound.get()} input types, not found ${inNotFound.get()} input types")
    println("")
    println(s"total found ${found.get() + resFound.get() + inFound
      .get()}, not found ${notFound.get() + resNotFound.get() + inNotFound.get()}")

    val specWithOpenAPIV3Schema = JsObject(result).fields
        .filter(p => (p._2 \ "openAPIV3Schema").asOpt[JsObject].isDefined)
        .map(f => (
          f._1,
          Json.obj("openAPIV3Schema" -> f._2.transform(reads("openAPIV3Schema").json.pick).get)
        ))

    /*result.foreach { case (key, value) =>
      (result(key) \ "openAPIV3Schema").asOpt[JsObject] match {
        case Some(_) => result.put(key, result(key).transform(
          reads("openAPIV3Schema").json.prune
        ).get)
        case None => ()
      }
    }*/

    val spec = Json.obj(
      "openapi"      -> openApiVersion,
      "info"         -> Json.obj(
        "title"       -> "Otoroshi Admin API",
        "description" -> "Admin API of the Otoroshi reverse proxy",
        "version"     -> "1.5.0-dev",
        "contact"     -> Json.obj(
          "name"  -> "Otoroshi Team",
          "email" -> "oss@maif.fr"
        ),
        "license"     -> Json.obj(
          "name" -> "Apache 2.0",
          "url"  -> "http://www.apache.org/licenses/LICENSE-2.0.html"
        )
      ),
      "externalDocs" -> Json.obj("url" -> "https://www.otoroshi.io", "description" -> "everything about otoroshi"),
      "servers"      -> Json.arr(
        Json.obj(
          "url"         -> "http://otoroshi-api.oto.tools:8080",
          "description" -> "your local otoroshi server"
        )
      ),
      "tags"         -> tags,
      "paths"        -> paths,
      "components"   -> Json.obj(
        "schemas"         -> JsObject(result),
        "securitySchemes" -> Json.obj(
          "otoroshi_auth" -> Json.obj(
            "type"   -> "http",
            "scheme" -> "basic"
          )
        )
      )
    )

    if (write) {
      println("")
      specFiles.foreach { specFile =>
        val file = new File(specFile)
        println(s"writing spec to: '${file.getAbsolutePath}'")
        Files.write(file.toPath, spec.prettify.split("\n").toList.asJava, StandardCharsets.UTF_8)
      }
      OpenApiGeneratorConfig(
        config.filePath,
        config.raw.asObject ++ Json.obj(
          "descriptions" -> JsObject(foundDescriptions.mapValues(JsString.apply))
          // "add_schemas" -> (config.add_schemas ++ adts.foldLeft(Json.obj())(_ ++ _))
        )
      ).write()
      println("")
    }

    (spec, JsObject(specWithOpenAPIV3Schema))
  }

  def readOldSpec(oldSpecPath: String): Unit = {
    val config = getConfig()
    val f      = new File(oldSpecPath)
    if (f.exists()) {
      val oldSpec      = Json.parse(Files.readAllLines(f.toPath).asScala.mkString("\n"))
      val descriptions = new TrieMap[String, String]()
      val examples     = new TrieMap[String, String]()
      oldSpec.select("components").select("schemas").asObject.value.map {
        case (key, value) => {
          val path = s"old.${key}"
          value
            .select("properties")
            .asOpt[JsObject]
            .map(_.value.map {
              case (field, component) => {
                val example     = component.select("example").asOpt[String]
                val description = component.select("description").asOpt[String]
                example.foreach(v => examples.put(s"$path.$field", v))
                description.foreach(v => descriptions.put(s"$path.$field", v))
              }
            })
        }
      }
      OpenApiGeneratorConfig(
        config.filePath,
        config.raw.asObject ++ Json.obj(
          "old_descriptions" -> JsObject(descriptions.mapValues(JsString.apply)),
          "old_examples"     -> JsObject(examples.mapValues(JsString.apply))
        )
      ).write()
    } else {
      println("No old spec file found !!!!")
    }
  }
}

class OpenApiGeneratorRunner extends App {

  def generate() = {
    val generator = new OpenApiGenerator(
      "./conf/routes",
      "./app/openapi/openapi-cfg.json",
      Seq("./public/openapi.json", "../manual/src/main/paradox/code/openapi.json"),
      write = true
    )

    val spec = generator.run()

    def getRef(reference: String): JsObject = (spec._2(reference) \ "openAPIV3Schema" \ "properties" \ "spec").as[JsObject] ++ Json.obj(
      "type" -> "object"
    )

    val crdsEntities = Json.obj(
      "ServiceGroup"-> Json.obj("plural" -> "service-groups", "singular" -> "service-group", "entity" -> "otoroshi.models.ServiceGroup"),
      "Organization" -> Json.obj("plural" -> "organizations", "singular" -> "organization", "entity" -> "otoroshi.models.Tenant"),
      "Team" -> Json.obj("plural" -> "teams", "singular" -> "team", "entity" -> "otoroshi.models.Team"),
      "ServiceDescriptor" -> Json.obj(
        "plural" -> "service-descriptors",
        "singular" -> "service-descriptor",
        "entity" -> "otoroshi.models.ServiceDescriptor",
        "rawSpec" -> Json.obj(
          "targets" -> Json.obj(
            "x-kubernetes-preserve-unknown-fields" -> true
          ),
          "enabledAdditionalHosts" -> Json.obj(
            "type" -> "boolean",
            "description" -> "???"
          )
        )
      ),
      "ApiKey" -> Json.obj(
        "plural" -> "apikeys",
        "singular" -> "apikey",
        "entity" -> "otoroshi.models.ApiKey",
        "rawSpec" -> Json.obj(
          "daikokuToken" -> Json.obj("type" -> "string", "description" -> "???"),
          "exportSecret" -> Json.obj("type" -> "boolean", "description" -> "???"),
          "secretName" -> Json.obj("type" -> "string", "description" -> "???")
        )
      ),
      "Certificate" -> Json.obj(
        "plural" -> "certificates",
        "singular" -> "certificate",
        "entity" -> "otoroshi.ssl.Cert",
        "rawSpec" -> Json.obj(
          "certType" -> Json.obj("type" -> "string", "description" -> "the kind of certificate"),
          "exportSecret" -> Json.obj("type" -> "boolean", "description" -> "???"),
          "secretName" -> Json.obj("type" -> "string", "description" -> "???"),
          "csr" -> (getRef("otoroshi.ssl.pki.models.GenCsrQuery").deepMerge(Json.obj("properties" -> Json.obj(
            "issuer" -> Json.obj(
              "type" -> "string",
              "description" -> "???"
            )))
          ))
        )
      ),
      "GlobalConfig" -> Json.obj("plural" -> "global-configs", "singular" -> "global-config", "entity" -> "otoroshi.models.GlobalConfig"),
      "JwtVerifier" -> Json.obj("plural" -> "jwt-verifiers", "singular" -> "jwt-verifier", "entity" -> "otoroshi.models.GlobalJwtVerifier",
      "rawSpec" -> Json.obj(
        "type" -> Json.obj(
          "type" -> "string",
          "description" -> "???"
        )
      )),
      "AuthModule" -> Json.obj("plural" -> "auth-modules", "singular" -> "auth-module", "entity" -> "otoroshi.auth.AuthModuleConfig"),
      "Script" -> Json.obj("plural" -> "scripts", "singular" -> "script", "entity" -> "otoroshi.script.Script"),
      "TcpService" -> Json.obj("plural" -> "tcp-services", "singular" -> "tcp-service", "entity" -> "otoroshi.tcp.TcpService"),
      "DataExporter" -> Json.obj("plural" -> "data-exporters", "singular" -> "data-exporter", "entity" -> "otoroshi.models.DataExporterConfig"),
      "Admin" -> Json.obj("plural" -> "admins", "singular" -> "admin", "entity" -> "otoroshi.models.SimpleOtoroshiAdmin")
    )

    def reads(path: String): JsPath = path.split("/").foldLeft(JsPath())((acc, num) => acc \ num)

    def patchSchema(kind: String, schema: JsObject): JsObject = {
      val crdEntity = crdsEntities(kind)

      (crdEntity \ "rawSpec").asOpt[JsObject] match {
        case Some(rawSpec) =>
          rawSpec.fields.foldLeft(schema)((acc, curr) => {
            (curr._2 \ "x-kubernetes-preserve-unknown-fields").asOpt[Boolean] match {
              case Some(true) =>
                acc.atPointer(s"openAPIV3Schema/properties/spec/properties/${curr._1}").asOpt[JsObject] match {
                  case Some(_) =>
                    acc.transform(reads(s"openAPIV3Schema/properties/spec/properties/${curr._1}").json.prune)
                      .get
                      .transform(reads(s"openAPIV3Schema/properties/spec/properties")
                      .json.update(__.read[JsObject].map(_ => Json.obj(curr._1 -> Json.obj(
                      "x-kubernetes-preserve-unknown-fields" -> true,
                      "type" -> "object"
                    ))))
                    ).get
                  case None => acc
                }
              case _ =>
                acc.atPointer(s"openAPIV3Schema/properties/spec/properties").asOpt[JsObject] match {
                  case Some(_) =>
                    acc.transform(reads(s"openAPIV3Schema/properties/spec/properties")
                      .json.update(__.read[JsObject].map(o => o ++ Json.obj(curr._1 -> curr._2.as[JsObject])))
                    ).get
                  case None => acc
                }
            }
          })
        case _ => schema
      }
    }

    def crdTemplate(name: String, kind: String, plural: String, singular: String, versions: Map[String, (Boolean, JsObject)]) =
      Json.obj(
        "apiVersion" -> "apiextensions.k8s.io/v1",
          "kind" -> "CustomResourceDefinition",
          "metadata" -> Json.obj(
          "name" -> s"$name.proxy.otoroshi.io",
            "creationTimestamp" -> null
          ),
        "spec" -> Json.obj(
              "group" -> "proxy.otoroshi.io",
               "names" -> Json.obj(
                  "kind" -> s"$kind",
                  "plural" -> s"$plural",
                  "singular" -> s"$singular"
               ),
          "scope" -> "Namespaced",
          "versions" -> JsArray(versions.map {
            case (version, (deprecated, content)) => Json.obj(
              "name" -> version,
              "served" -> true,
              "storage" -> !deprecated,
              "deprecated" -> deprecated,
              "schema" -> overrideGeneratedOpenapiV3Schema(content)
            )
          }.toSeq)
      )
    )

    val schemas = spec._2

    val openAPIV3Schemas = crdsEntities.fields.map { case (key, value) =>
      Json.obj("key" -> key) ++ value.as[JsObject] ++ Json.obj("data" ->
        (schemas \ (value \ "entity").as[String]).asOpt[JsObject]
      ).as[JsObject]
    }

    def defaultSchema = Json.obj(
      "openAPIV3Schema" -> Json.obj(
        "x-kubernetes-preserve-unknown-fields" -> true,
        "type" -> "object",
        "properties" -> Json.obj(
          "spec" -> Json.obj(
            "type" -> "object"
          )
        )
      )
    )

    def crds(withoutSchema: Boolean = false) = openAPIV3Schemas.map { schema =>
      crdTemplate(
        name = (schema \ "plural").as[String],
        kind = (schema \ "key").as[String],
        plural = (schema \ "plural").as[String],
        singular = (schema \ "singular").as[String],
        versions = Map(
          "v1alpha1" -> (true, Json.obj(
            "openAPIV3Schema" -> Json.obj(
              "x-kubernetes-preserve-unknown-fields" -> true,
              "type" -> "object",
              "properties" -> Json.obj(
                "spec" -> Json.obj("type" -> "object")
              )
            )
          )),
          "v1" -> (false, if(withoutSchema) defaultSchema else (schema \ "data").asOpt[JsObject].map(s => patchSchema((schema \ "key").as[String], s)).getOrElse(defaultSchema))
        )
      )
    }

    val res = crds().foldLeft("")((acc, curr) => s"$acc${write(curr)}")

    val file = new File("../kubernetes/helm/otoroshi/crds/crds-with-schema.yaml")
    println(s"write crds-with-schema.yaml file: '${file.getAbsolutePath}'")
    Files.write(file.toPath, res.getBytes(StandardCharsets.UTF_8))

    val defaultFile = new File("../kubernetes/helm/otoroshi/crds/crds.yaml")
    println(s"write crds.yaml file: '${defaultFile.getAbsolutePath}'")
    Files.write(defaultFile.toPath, crds(true).foldLeft("")((acc, curr) => s"$acc${write(curr)}").getBytes(StandardCharsets.UTF_8))
  }

  def overrideGeneratedOpenapiV3Schema(res: JsObject): JsObject = {
    def t(o: JsValue) =
      o.asOpt[JsObject] match {
        case None => o
        case Some(v) => overrideGeneratedOpenapiV3Schema(v)
      }
    res.fields
      .filter(f => f._1 != "enum" && f._1 != "oneOfConstraints")
      .map { case (key, value) =>
        val updatedValue = t(value)

        val newValue = (updatedValue \ "properties").asOpt[JsObject] match {
          case Some(o) if o.fields.isEmpty && key == "interval" => Json.obj(
            "type" -> "string",
            "x-kubernetes-preserve-unknown-fields" -> true,
            "description" -> (updatedValue \ "description").as[String]
          )
          case Some(o) if o.fields.isEmpty => Json.obj(
            "type" -> "object",
            "x-kubernetes-preserve-unknown-fields" -> true,
            "description" -> (updatedValue \ "description").as[String]
          )
          case _ => updatedValue
        }

        if(key == "oneOfConstraints")
          ("anyOf", newValue)
        else if(key == "typ")
          ("type", newValue)
        else
          (key, newValue)
      }
      .foldLeft(Json.obj())((acc, curr) => acc ++ Json.obj(curr._1-> curr._2))
  }
}















