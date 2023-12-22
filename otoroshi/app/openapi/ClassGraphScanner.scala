package otoroshi.openapi

import io.github.classgraph.{ClassGraph, ScanResult}
import otoroshi.env.Env
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits.BetterJsValue
import play.api.Logger
import play.api.libs.json.{JsObject, JsValue, Json}
import otoroshi.utils.syntax.implicits._

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.collection.concurrent.TrieMap

case class Form(schema: JsObject, flow: Seq[String] = Seq.empty) {
  def json: JsValue = Json.obj(
    "schema" -> schema,
    "flow"   -> flow
  )
}

object Form {
  def fromJson(value: JsValue): Form = {
    Form(
      schema = value.select("schema").as[JsObject],
      flow = value.select("flow").as[Seq[String]]
    )
  }
}

case class OpenApiSchema(
    scanResult: ScanResult,
    raw: JsValue,
    flattened: Map[String, JsValue],
    asForms: Map[String, Form]
)

class ClassGraphScanner {

  private val logger = Logger("otoroshi-classpath-scanner")

  def scanAndGenerateSchema(scanResult: ScanResult): OpenApiSchema = {
    val (openApiSchema, hasWritten) = new OpenApiGenerator(
      "./conf/routes",
      "./conf/schemas/openapi-cfg.json",
      Seq(
        "./conf/schemas/openapi.json",
        "./public/openapi.json",
        "../manual/src/main/paradox/code/openapi.json"
      ),
      scanResult = scanResult,
      write = true
    ).runAndMaybeWrite()
    val flattenedOpenapiSchema      = new OpenapiToJson(openApiSchema).run()
    val asForms                     = new FormsGenerator(flattenedOpenapiSchema).run()
    val flatFile                    = new File("./conf/schemas/openapi-flat.json")
    val formFile                    = new File("./conf/schemas/openapi-forms.json")
    if (hasWritten || !flatFile.exists()) {
      val flat = new JsObject(flattenedOpenapiSchema)
      Files.writeString(flatFile.toPath, flat.prettify)
      new CrdsGenerator(openApiSchema).run()
    }
    if (hasWritten || !formFile.exists()) {
      val forms = new JsObject(asForms.mapValues(_.json))
      Files.writeString(formFile.toPath, forms.prettify)
      new CrdsGenerator(openApiSchema).run()
    }
    OpenApiSchema(
      scanResult = scanResult,
      raw = openApiSchema,
      flattened = flattenedOpenapiSchema.toMap,
      asForms = asForms /*{
        val jsonRaw = Files.readString(formFile.toPath)
        val obj     = Json.parse(jsonRaw).as[JsObject]
        val map     = new LegitTrieMap[String, Form]()
        map.++=(obj.value.mapValues(Form.fromJson)).toMap
      }*/
    )
  }

  def readSchemaFromFiles(scanResult: ScanResult, env: Env): Either[String, OpenApiSchema] = {
    (for {
      openapires     <- env.environment.resourceAsStream("/schemas/openapi.json")
      openapiflatres <- env.environment.resourceAsStream("/schemas/openapi-flat.json")
      openapiformres <- env.environment.resourceAsStream("/schemas/openapi-forms.json")
    } yield {
      val openApiSchema = {
        val jsonRaw = new String(openapires.readAllBytes(), StandardCharsets.UTF_8)
        Json.parse(jsonRaw)
      }
      val flattenedOpenapiSchema = {
        val jsonRaw = new String(openapiflatres.readAllBytes(), StandardCharsets.UTF_8)
        val obj     = Json.parse(jsonRaw).as[JsObject]
        val map     = new UnboundedTrieMap[String, JsValue]()
        map.++=(obj.value)
      }
      val asForms = {
        val jsonRaw = new String(openapiformres.readAllBytes(), StandardCharsets.UTF_8)
        val obj     = Json.parse(jsonRaw).as[JsObject]
        val map     = new UnboundedTrieMap[String, Form]()
        map.++=(obj.value.mapValues(Form.fromJson)).toMap
      }
      OpenApiSchema(
        scanResult = scanResult,
        raw = openApiSchema,
        flattened = flattenedOpenapiSchema.toMap,
        asForms = asForms
      )
    }) match {
      case Some(oas) => Right(oas)
      case None      =>
        val openapiexists     = env.environment.resourceAsStream("/schemas/openapi.json").isDefined
        val openapiflatexists = env.environment.resourceAsStream("/schemas/openapi-flat.json").isDefined
        val openapiformexists = env.environment.resourceAsStream("/schemas/openapi-forms.json").isDefined
        val message           = s"""One or more schemas files resources not found !
          |
          |- /schemas/openapi.json ${if (openapiexists) "exists" else "does not exist"}
          |- /schemas/openapi-flat.json ${if (openapiflatexists) "exists" else "does not exist"}
          |- /schemas/openapi-forms.json ${if (openapiformexists) "exists" else "does not exist"}
          |""".stripMargin
        logger.error(message)
        Left(message)
    }
  }

  def run(configurationPackages: Seq[String], env: Env): OpenApiSchema = {
    val scanResult = new ClassGraph()
      .addClassLoader(this.getClass.getClassLoader)
      .enableAllInfo()
      .acceptPackages(Seq("otoroshi", "otoroshi_plugins", "play.api.libs.ws") ++ configurationPackages: _*)
      .scan()
    //val dev        = env.isDev
    //if (dev) {
    //  scanAndGenerateSchema(scanResult)
    //} else {
    readSchemaFromFiles(scanResult, env) match {
      case Left(err)  => throw new RuntimeException(err)
      case Right(oas) => oas
    }
    //}
  }
}
