package otoroshi.openapi

import otoroshi.utils.syntax.implicits._
import otoroshi.utils.yaml.Yaml.write
import play.api.libs.json._

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.collection.concurrent.TrieMap

class CrdsGenerator(spec: JsValue)  {

  val openAPIV3SchemaPath = "openAPIV3Schema/properties/spec/properties"
  val nullType = "#/components/schemas/Null"
  val otoroshiSchemaType = "#/components/schemas/otoroshi."

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
          "description" -> "if enabled, the additional hosts will be add to hosts array"
        )
      )
    ),
    "ApiKey" -> Json.obj(
      "plural" -> "apikeys",
      "singular" -> "apikey",
      "entity" -> "otoroshi.models.ApiKey",
      "rawSpec" -> Json.obj(
        "daikokuToken" -> Json.obj("type" -> "string", "description" -> "Integration token for Daikoku"),
        "exportSecret" -> Json.obj("type" -> "boolean", "description" -> "export api key as a kubernetes secret"),
        "secretName" -> Json.obj("type" -> "string", "description" -> "name of the kubernetes secret")
      )
    ),
    "Certificate" -> Json.obj(
      "plural" -> "certificates",
      "singular" -> "certificate",
      "entity" -> "otoroshi.ssl.Cert",
      "rawSpec" -> Json.obj(
        "certType" -> Json.obj("type" -> "string", "description" -> "the kind of certificate"),
        "exportSecret" -> Json.obj("type" -> "boolean", "description" -> "export certificate as a kubernetes secret"),
        "secretName" -> Json.obj("type" -> "string", "description" -> "name of the kubernetes secret"),
        "csr" -> Json.obj(
          "entity" -> "otoroshi.ssl.pki.models.GenCsrQuery",
          "mergeWith" -> Json.obj("properties" -> Json.obj(
          "issuer" -> Json.obj(
            "type" -> "string",
            "description" -> "the issuer of the csr query"
          )))
        )
      )
    ),
    "GlobalConfig" -> Json.obj("plural" -> "global-configs", "singular" -> "global-config", "entity" -> "otoroshi.models.GlobalConfig"),
    "JwtVerifier" -> Json.obj("plural" -> "jwt-verifiers", "singular" -> "jwt-verifier", "entity" -> "otoroshi.models.GlobalJwtVerifier",
      "rawSpec" -> Json.obj(
        "type" -> Json.obj(
          "type" -> "string",
          "description" -> "the kind of jwt verifier"
        )
      )),
    "AuthModule" -> Json.obj("plural" -> "auth-modules", "singular" -> "auth-module", "entity" -> "otoroshi.auth.AuthModuleConfig"),
    "Script" -> Json.obj("plural" -> "scripts", "singular" -> "script", "entity" -> "otoroshi.script.Script"),
    "TcpService" -> Json.obj("plural" -> "tcp-services", "singular" -> "tcp-service", "entity" -> "otoroshi.tcp.TcpService"),
    "DataExporter" -> Json.obj("plural" -> "data-exporters", "singular" -> "data-exporter", "entity" -> "otoroshi.models.DataExporterConfig"),
    "Admin" -> Json.obj("plural" -> "admins", "singular" -> "admin", "entity" -> "otoroshi.models.SimpleOtoroshiAdmin")
  )

  def run(): Unit = {
    val data = openApiSchemaToOpenAPIV3Schema()
    process(data)
    val entitiesWithSchema = restrictResultAtCrdsEntities(data)
    writeFiles(entitiesWithSchema, data)
  }

  def openApiSchemaToOpenAPIV3Schema(): TrieMap[String, JsValue] = {
    val schemas = (spec \ "components" \ "schemas").as[JsObject]
    val data = new TrieMap[String, JsValue]()
    schemas.fields.foreach(curr => data.put(curr._1, curr._2))
    data
  }

  def process(data: TrieMap[String, JsValue])= {
    var changed = true
    do {
      changed = replaceOneOf(data)
    } while(changed)
  }

  def reads(path: String): JsPath = {
    if(path.isEmpty)
      JsPath()
    else
      (if(path.startsWith("/")) path.substring(1) else path).split("/").foldLeft(JsPath()) { (acc, num) =>
        acc \ num
      }
  }

  def containsOnlyRef(values: IndexedSeq[JsValue]): Boolean =
    values.forall(p => (p \ "$ref").as[String] != nullType)

  def containsNullAndRef(values: IndexedSeq[JsValue]): Boolean =
    values.exists(p => (p \ "$ref").as[String] == nullType) &&
      values.exists(p => (p\ "$ref").as[String] != nullType)

  def contentToOpenAPIV3Schema(description: String, openAPIData: JsObject) = {
    Json.obj(
      "openAPIV3Schema" -> Json.obj(
        "type" -> "object",
        "description" -> description,
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
            "description" -> description,
            "properties" -> (openAPIData \ "properties").as[JsObject]
          )
        )
      )
    )
  }
  
  def restrictResultAtCrdsEntities(data: TrieMap[String, JsValue]): TrieMap[String, JsValue] = {
    val out = new TrieMap[String, JsValue]()
    val schemas = (spec \ "components" \ "schemas").as[JsObject]

    crdsEntities.fields.foreach(curr => {
      val entity = (curr._2 \ "entity").as[String]
      data(entity).asOpt[JsObject] match {
        case Some(content) => out.put(curr._1, contentToOpenAPIV3Schema((schemas \ entity \ "description").asOpt[String].getOrElse("???"), content))
        case None =>
          println(s"Warning : crd entity not found in open api schema : ${curr._1} - $entity")
      }
    })
    out
  }

  def pruneField(data: TrieMap[String, JsValue], key: String, path: String) =
    data.put(key, data(key).transform(reads(path).json.prune).get)

  def updateField(data: TrieMap[String, JsValue], key: String, path: String, additionalObj: JsValue) =
    data.put(key, data(key).transform(reads(path).json.update(__.read[JsObject].map(o => o ++ additionalObj.as[JsObject]))).get)

  def replaceSubOneOf(data: TrieMap[String, JsValue], key: String, path: String): Boolean = {
    val currentObj = data(key).as[JsObject].atPointer(path).asOpt[JsObject] match {
      case Some(p) => p
      case _ => Json.obj()
    }

    ((currentObj \ "type").asOpt[String], (currentObj \ "items").asOpt[JsObject]) match {
      case (Some(t), Some(items)) if t == "array" && (items \ "$ref").asOpt[String].isDefined =>
        pruneField(data, key, path + "/items/$ref")

        replaceRef(data, key, path + "/items", (items \ "$ref").as[String])

        true
      case _ =>
        currentObj.fields.map {
          case ("oneOf", oneOfArray) =>
            val fields = oneOfArray.as[Seq[JsValue]]

            pruneField(data, key, path + "/oneOf")

            fields.find(field => (field \ "type").asOpt[String].isDefined) match {
              case Some(field) =>
                updateField(data, key, path, field)
              case None =>
                // corresponding when the object is composed of a list of references
                updateField(data, key, path, Json.obj("type" -> "object"))

                // get a flat object for each #ref and check if object is not only composed of an oneOf of refs
                val refs = fields.flatMap { rawField =>
                  val field = (rawField \ "$ref").as[String]
                  if (field != nullType) {
                    val obj = getRef(data, field)
                    (obj \ "oneOf").asOpt[Seq[JsValue]] match {
                      case Some(arr) => arr.map { f =>
                        val r = (f \ "$ref").as[String]
                        if (r != nullType)
                          getRef(data, r)
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
                      updateField(data, key, path, refs.head)
                    case _ =>
                      updateField(data, key, path, Json.obj("properties" -> refs.head))
                  }
                else
                  updateField(data, key, path, Json.obj(
                    "oneOfConstraints" -> refs.map(ref => Json.obj("required" -> ref.keys)),
                    "properties" -> refs.foldLeft(Json.obj())((acc, curr) => acc ++ curr)
                  ))
            }
            true
          case ("$ref", fields) if fields.isInstanceOf[JsString] =>
            pruneField(data, key, path + "/$ref")
            replaceRef(data, key, path, fields.as[String])
            true

          case (fieldName, fields) if fields.isInstanceOf[JsObject] => replaceSubOneOf(data, key, s"$path/$fieldName")
          case _ => false
        }.foldLeft(false)(_ || _)

    }
  }

  def getRef(data: TrieMap[String, JsValue], ref: String): JsObject = {
    if (ref.startsWith(otoroshiSchemaType)) {
      val reference = ref.replace("#/components/schemas/", "")

      (data(reference) \ "properties").asOpt[JsObject] match {
        case Some(prop) => prop
        case _ => data(reference).as[JsObject]
      }
    } else
      Json.obj()
  }

  def replaceRef(data: TrieMap[String, JsValue], key: String, path: String, ref: String) = {
    if (ref.startsWith(otoroshiSchemaType)) {
      val out = getRef(data, ref)

      (out \ "type").asOpt[String] match {
        case Some(t) if t == "string" && (out \ "enum").asOpt[JsArray].isEmpty =>
          updateField(data, key, path, Json.obj("type" -> "string"))
        case _ =>
          updateField(data, key, path,
              (out \ "oneOf").asOpt[JsArray] match {
                case Some(arr) if arr.value.length > 2 || containsNullAndRef(arr.value) => out
                case Some(arr) if containsOnlyRef(arr.value) =>
                  Json.obj("type" -> (getRef(data, (arr.value.head \ "$ref").as[String]) \ "type").as[String])
                case None if (out \ "enum").isDefined =>
                  out
                case _ => Json.obj("properties" -> out, "type" -> "object")
              }
          )
      }
    }
  }

  def replaceOneOf(data: TrieMap[String, JsValue]): Boolean =
    JsObject(data)
      .fields
      .map(field => replaceSubOneOf(data, field._1, ""))
      .foldLeft(false)(_ || _)

  def writeFiles(entitiesWithSchema: TrieMap[String, JsValue], data: TrieMap[String, JsValue]) = {
    val folderPath = "../kubernetes/helm/otoroshi/crds"

    val file = new File(s"$folderPath/../crds-with-schema.yaml")
    val schemasAsString = crds(entitiesWithSchema, data).foldLeft("")((acc, curr) => s"$acc${write(curr)}")
    println(s"write crds-with-schema.yaml file: '${file.getAbsolutePath}'")
    Files.write(file.toPath, schemasAsString.getBytes(StandardCharsets.UTF_8))

    val defaultFile = new File(s"$folderPath/crds.yaml")
    println(s"write crds.yaml file: '${defaultFile.getAbsolutePath}'")
    Files.write(defaultFile.toPath, crds(entitiesWithSchema, data, withoutSchema = true).foldLeft("")((acc, curr) => s"$acc${write(curr)}").getBytes(StandardCharsets.UTF_8))
  }

  def patchSchema(data: TrieMap[String, JsValue], kind: String, schema: JsValue): JsValue = {
    val crdEntity = crdsEntities(kind)

    (crdEntity \ "rawSpec").asOpt[JsObject] match {
      case Some(rawSpec) =>
        rawSpec.fields.foldLeft(schema)((acc, curr) => {
          (curr._2 \ "entity").asOpt[String] match {
            case Some(entity) =>
              val missingData = data(entity).as[JsObject]
              acc.transform(reads(openAPIV3SchemaPath)
                .json.update(__.read[JsObject].map(o => o ++ Json.obj(curr._1 -> missingData.deepMerge((curr._2 \ "mergeWith").as[JsObject]))))
              ).get
            case None =>
              (curr._2 \ "x-kubernetes-preserve-unknown-fields").asOpt[Boolean] match {
                case Some(true) =>
                  acc.atPointer(s"$openAPIV3SchemaPath/${curr._1}").asOpt[JsObject] match {
                    case Some(_) =>
                      acc.transform(reads(s"$openAPIV3SchemaPath/${curr._1}").json.prune)
                        .get
                        .transform(reads(openAPIV3SchemaPath)
                          .json.update(__.read[JsObject].map(_ => Json.obj(curr._1 -> Json.obj(
                          "x-kubernetes-preserve-unknown-fields" -> true,
                          "type" -> "object"
                        ))))
                        ).get
                    case None => acc
                  }
                case _ =>
                  acc.atPointer(openAPIV3SchemaPath).asOpt[JsObject] match {
                    case Some(_) =>
                      acc.transform(reads(openAPIV3SchemaPath)
                        .json.update(__.read[JsObject].map(o => o ++ Json.obj(curr._1 -> curr._2.as[JsObject])))
                      ).get
                    case None => acc
                  }
              }
          }
        })
      case _ => schema
    }
  }

  def crdTemplate(name: String, kind: String, plural: String, singular: String, versions: Map[String, (Boolean, JsValue)]) =
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

  def preserveUnknownFieldsSchema: JsValue = Json.obj(
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

  def crds(out: TrieMap[String, JsValue], allData: TrieMap[String, JsValue], withoutSchema: Boolean = false) = out.map { data =>
    val crdEntity = crdsEntities(data._1)
    crdTemplate(
      name = (crdEntity \ "plural").as[String],
      kind = data._1,
      plural = (crdEntity \ "plural").as[String],
      singular = (crdEntity \ "singular").as[String],
      versions = Map(
        "v1alpha1" -> (true, preserveUnknownFieldsSchema),
        "v1" -> (false, if(withoutSchema) preserveUnknownFieldsSchema else patchSchema(allData, data._1, data._2))
      )
    )
  }

  def overrideGeneratedOpenapiV3Schema(res: JsValue): JsValue = {
    def t(o: JsValue) =
      o.asOpt[JsObject] match {
        case None => o
        case Some(v) => overrideGeneratedOpenapiV3Schema(v)
      }
    res
      .as[JsObject]
      .fields
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















