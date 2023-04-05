package otoroshi.openapi

import otoroshi.utils.cache.types.LegitTrieMap
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.yaml.Yaml.write
import play.api.libs.json._

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.collection.concurrent.TrieMap

class CrdsGenerator(spec: JsValue = Json.obj()) {

  val openAPIV3SchemaPath = "openAPIV3Schema/properties/spec/properties"
  val nullType            = "#/components/schemas/Null"
  val otoroshiSchemaType  = "#/components/schemas/otoroshi."

  val crdsEntities = Json.obj(
    "ServiceGroup"      -> Json
      .obj("plural" -> "service-groups", "singular" -> "service-group", "entity" -> "otoroshi.models.ServiceGroup"),
    "Organization"      -> Json
      .obj("plural" -> "organizations", "singular" -> "organization", "entity" -> "otoroshi.models.Tenant"),
    "Team"              -> Json.obj("plural" -> "teams", "singular" -> "team", "entity" -> "otoroshi.models.Team"),
    "ServiceDescriptor" -> Json.obj(
      "plural"   -> "service-descriptors",
      "singular" -> "service-descriptor",
      "entity"   -> "otoroshi.models.ServiceDescriptor",
      "rawSpec"  -> Json.obj(
        "targets"                -> Json.obj(
          "x-kubernetes-preserve-unknown-fields" -> true
        ),
        "enabledAdditionalHosts" -> Json.obj(
          "type"        -> "boolean",
          "description" -> "if enabled, the additional hosts will be add to hosts array"
        )
      )
    ),
    "ApiKey"            -> Json.obj(
      "plural"   -> "apikeys",
      "singular" -> "apikey",
      "entity"   -> "otoroshi.models.ApiKey",
      "rawSpec"  -> Json.obj(
        "daikokuToken" -> Json.obj("type" -> "string", "description" -> "Integration token for Daikoku"),
        "exportSecret" -> Json.obj("type" -> "boolean", "description" -> "export api key as a kubernetes secret"),
        "secretName"   -> Json.obj("type" -> "string", "description" -> "name of the kubernetes secret")
      )
    ),
    "Certificate"       -> Json.obj(
      "plural"   -> "certificates",
      "singular" -> "certificate",
      "entity"   -> "otoroshi.ssl.Cert",
      "rawSpec"  -> Json.obj(
        "certType"     -> Json.obj("type" -> "string", "description" -> "the kind of certificate"),
        "exportSecret" -> Json.obj("type" -> "boolean", "description" -> "export certificate as a kubernetes secret"),
        "secretName"   -> Json.obj("type" -> "string", "description" -> "name of the kubernetes secret"),
        "csr"          -> Json.obj(
          "entity"    -> "otoroshi.ssl.pki.models.GenCsrQuery",
          "mergeWith" -> Json.obj(
            "properties" -> Json.obj(
              "issuer" -> Json.obj(
                "type"        -> "string",
                "description" -> "the issuer of the csr query"
              )
            )
          )
        )
      )
    ),
    "GlobalConfig"      -> Json
      .obj("plural" -> "global-configs", "singular" -> "global-config", "entity" -> "otoroshi.models.GlobalConfig"),
    "JwtVerifier"       -> Json.obj(
      "plural"   -> "jwt-verifiers",
      "singular" -> "jwt-verifier",
      "entity"   -> "otoroshi.models.GlobalJwtVerifier",
      "rawSpec"  -> Json.obj(
        "type" -> Json.obj(
          "type"        -> "string",
          "description" -> "the kind of jwt verifier"
        )
      )
    ),
    "AuthModule"        -> Json
      .obj("plural" -> "auth-modules", "singular" -> "auth-module", "entity" -> "otoroshi.auth.AuthModuleConfig"),
    "Script"            -> Json.obj("plural" -> "scripts", "singular" -> "script", "entity" -> "otoroshi.script.Script"),
    "TcpService"        -> Json
      .obj("plural" -> "tcp-services", "singular" -> "tcp-service", "entity" -> "otoroshi.tcp.TcpService"),
    "DataExporter"      -> Json.obj(
      "plural"   -> "data-exporters",
      "singular" -> "data-exporter",
      "entity"   -> "otoroshi.models.DataExporterConfig"
    ),
    "Admin"             -> Json.obj("plural" -> "admins", "singular" -> "admin", "entity" -> "otoroshi.models.SimpleOtoroshiAdmin"),
    "Route"             -> Json.obj("plural" -> "routes", "singular" -> "route", "entity" -> "otoroshi.next.models.NgRoute"),
    "RouteComposition"  -> Json
      .obj(
        "plural"   -> "route-compositions",
        "singular" -> "route-composition",
        "entity"   -> "otoroshi.next.models.NgService"
      ),
    "Backend"           -> Json
      .obj("plural" -> "backends", "singular" -> "backend", "entity" -> "otoroshi.next.models.NgBackend"),
    "WasmPlugin"        -> Json
      .obj("plural" -> "wasm-plugins", "singular" -> "wasm-plugin", "entity" -> "otoroshi.models.WasmPlugin")
  )

  def run(): Unit = {
    val data               = new OpenapiToJson(spec).run()
    val entitiesWithSchema = restrictResultAtCrdsEntities(data)
    writeFiles(entitiesWithSchema, data)
  }

  def reads(path: String): JsPath = {
    if (path.isEmpty)
      JsPath()
    else
      (if (path.startsWith("/")) path.substring(1) else path).split("/").foldLeft(JsPath()) { (acc, num) =>
        acc \ num
      }
  }

  def containsOnlyRef(values: IndexedSeq[JsValue]): Boolean =
    values.forall(p => (p \ "$ref").as[String] != nullType)

  def containsNullAndRef(values: IndexedSeq[JsValue]): Boolean =
    values.exists(p => (p \ "$ref").as[String] == nullType) &&
    values.exists(p => (p \ "$ref").as[String] != nullType)

  def contentToOpenAPIV3Schema(description: String, openAPIData: JsObject) = {
    Json.obj(
      "openAPIV3Schema" -> Json.obj(
        "type"        -> "object",
        "description" -> description,
        "properties"  -> Json.obj(
          "apiVersion" -> Json.obj(
            "description" -> "APIVersion defines the versioned schema of this representation of an object. Servers should convert recognized schemas to the latest internal value, and may reject unrecognized values. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#resources",
            "type"        -> "string"
          ),
          "kind"       -> Json.obj(
            "description" -> "Kind is a string value representing the REST resource this object represents. Servers may infer this from the endpoint the client submits requests to. Cannot be updated. In CamelCase. More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#types-kinds",
            "type"        -> "string"
          ),
          "metadata"   -> Json.obj(
            "type" -> "object"
          ),
          "spec"       -> Json.obj(
            "type"        -> "object",
            "description" -> description,
            "properties"  -> (openAPIData \ "properties").as[JsObject]
          )
        )
      )
    )
  }

  def restrictResultAtCrdsEntities(data: TrieMap[String, JsValue]): TrieMap[String, JsValue] = {
    val out     = new LegitTrieMap[String, JsValue]()
    val schemas = (spec \ "components" \ "schemas").as[JsObject]

    crdsEntities.fields.foreach(curr => {
      val entity = (curr._2 \ "entity").as[String]
      data.get(entity) match {
        case Some(content: JsObject) =>
          out.put(
            curr._1,
            contentToOpenAPIV3Schema((schemas \ entity \ "description").asOpt[String].getOrElse("???"), content)
          )
        case None                    =>
          println(s"Warning : crd entity not found in open api schema : ${curr._1} - $entity")
      }
    })
    out
  }

  def writeFiles(entitiesWithSchema: TrieMap[String, JsValue], data: TrieMap[String, JsValue]) = {
    val folderPath = "../kubernetes/helm/otoroshi/crds"

    val file            = new File(s"$folderPath/../crds-with-schema.yaml")
    val schemasAsString = crds(entitiesWithSchema, data).foldLeft("")((acc, curr) => s"$acc${write(curr)}")
    println(s"write crds-with-schema.yaml file: '${file.getAbsolutePath}'")
    Files.write(file.toPath, schemasAsString.getBytes(StandardCharsets.UTF_8))

    val defaultFile = new File(s"$folderPath/crds.yaml")
    println(s"write crds.yaml file: '${defaultFile.getAbsolutePath}'")
    Files.write(
      defaultFile.toPath,
      crds(entitiesWithSchema, data, withoutSchema = true)
        .foldLeft("")((acc, curr) => s"$acc${write(curr)}")
        .getBytes(StandardCharsets.UTF_8)
    )
    Files.write(
      new File("../kubernetes/kustomize/base/crds.yaml").toPath,
      crds(entitiesWithSchema, data, withoutSchema = true)
        .foldLeft("")((acc, curr) => s"$acc${write(curr)}")
        .getBytes(StandardCharsets.UTF_8)
    )
  }

  def patchSchema(data: TrieMap[String, JsValue], kind: String, schema: JsValue): JsValue = {
    val crdEntity = crdsEntities(kind)

    (crdEntity \ "rawSpec").asOpt[JsObject] match {
      case Some(rawSpec) =>
        rawSpec.fields.foldLeft(schema)((acc, curr) => {
          (curr._2 \ "entity").asOpt[String] match {
            case Some(entity) =>
              val missingData = data.getOrElse(entity, Json.obj()).as[JsObject]
              acc
                .transform(
                  reads(openAPIV3SchemaPath).json.update(
                    __.read[JsObject]
                      .map(o => o ++ Json.obj(curr._1 -> missingData.deepMerge((curr._2 \ "mergeWith").as[JsObject])))
                  )
                )
                .get
            case None         =>
              (curr._2 \ "x-kubernetes-preserve-unknown-fields").asOpt[Boolean] match {
                case Some(true) =>
                  acc.atPointer(s"$openAPIV3SchemaPath/${curr._1}").asOpt[JsObject] match {
                    case Some(_) =>
                      acc
                        .transform(reads(s"$openAPIV3SchemaPath/${curr._1}").json.prune)
                        .get
                        .transform(
                          reads(openAPIV3SchemaPath).json.update(
                            __.read[JsObject]
                              .map(_ =>
                                Json.obj(
                                  curr._1 -> Json.obj(
                                    "x-kubernetes-preserve-unknown-fields" -> true,
                                    "type"                                 -> "object"
                                  )
                                )
                              )
                          )
                        )
                        .get
                    case None    => acc
                  }
                case _          =>
                  acc.atPointer(openAPIV3SchemaPath).asOpt[JsObject] match {
                    case Some(_) =>
                      acc
                        .transform(
                          reads(openAPIV3SchemaPath).json
                            .update(__.read[JsObject].map(o => o ++ Json.obj(curr._1 -> curr._2.as[JsObject])))
                        )
                        .get
                    case None    => acc
                  }
              }
          }
        })
      case _             => schema
    }
  }

  def crdTemplate(
      name: String,
      kind: String,
      plural: String,
      singular: String,
      versions: Map[String, (Boolean, Boolean, JsValue)]
  ) =
    Json.obj(
      "apiVersion" -> "apiextensions.k8s.io/v1",
      "kind"       -> "CustomResourceDefinition",
      "metadata"   -> Json.obj(
        "name"              -> s"$name.proxy.otoroshi.io",
        "creationTimestamp" -> null
      ),
      "spec"       -> Json.obj(
        "group"    -> "proxy.otoroshi.io",
        "names"    -> Json.obj(
          "kind"     -> s"$kind",
          "plural"   -> s"$plural",
          "singular" -> s"$singular"
        ),
        "scope"    -> "Namespaced",
        "versions" -> JsArray(versions.map { case (version, (served, deprecated, content)) =>
          Json.obj(
            "name"       -> version,
            "served"     -> served,
            "storage"    -> !deprecated,
            "deprecated" -> deprecated,
            "schema"     -> overrideGeneratedOpenapiV3Schema(content)
          )
        }.toSeq)
      )
    )

  def preserveUnknownFieldsSchema: JsValue = Json.obj(
    "openAPIV3Schema" -> Json.obj(
      "x-kubernetes-preserve-unknown-fields" -> true,
      "type"                                 -> "object"
    )
  )

  def crds(out: TrieMap[String, JsValue], allData: TrieMap[String, JsValue], withoutSchema: Boolean = false) = out.map {
    data =>
      val crdEntity = crdsEntities(data._1)
      crdTemplate(
        name = (crdEntity \ "plural").as[String],
        kind = data._1,
        plural = (crdEntity \ "plural").as[String],
        singular = (crdEntity \ "singular").as[String],
        versions = Map(
          "v1alpha1" -> (false, true, preserveUnknownFieldsSchema),
          "v1"       -> (true, false, if (withoutSchema) preserveUnknownFieldsSchema else patchSchema(allData, data._1, data._2))
        )
      )
  }

  def overrideGeneratedOpenapiV3Schema(res: JsValue): JsValue = {
    def t(o: JsValue) =
      o.asOpt[JsObject] match {
        case None    => o
        case Some(v) => overrideGeneratedOpenapiV3Schema(v)
      }
    res
      .as[JsObject]
      .fields
      .filter(f => f._1 != "enum" && f._1 != "oneOfConstraints")
      .map { case (key, value) =>
        val updatedValue = t(value)

        val newValue = (updatedValue \ "properties").asOpt[JsObject] match {
          case Some(o) if o.fields.isEmpty && key == "interval" =>
            Json.obj(
              "type"                                 -> "string",
              "x-kubernetes-preserve-unknown-fields" -> true,
              "description"                          -> (updatedValue \ "description").as[String]
            )
          case Some(o) if o.fields.isEmpty                      =>
            Json.obj(
              "type"                                 -> "object",
              "x-kubernetes-preserve-unknown-fields" -> true,
              "description"                          -> (updatedValue \ "description").getOrElse(JsString("no-description")).as[String]
            )
          case _                                                => updatedValue
        }

        if (key == "oneOfConstraints")
          ("anyOf", newValue)
        else if (key == "typ")
          ("type", newValue)
        else
          (key, newValue)
      }
      .foldLeft(Json.obj())((acc, curr) => acc ++ Json.obj(curr._1 -> curr._2))
  }

}
