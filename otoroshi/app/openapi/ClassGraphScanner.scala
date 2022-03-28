package otoroshi.openapi

import io.github.classgraph.{ClassGraph, ScanResult}
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits.BetterJsValue
import play.api.Logger
import play.api.libs.json.{JsObject, JsValue, Json}

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.collection.concurrent.TrieMap

case class Form(schema: JsObject, flow: Set[String]) {
  def json: JsValue = Json.obj(
    "schema" -> schema,
    "flow" -> flow
  )
}

object Form {
  def fromJson(value: JsValue): Form = {
    Form(
      schema = value.select("schema").as[JsObject],
      flow = value.select("flow").as[Set[String]],
    )
  }
}

case class OpenApiSchema(
    scanResult: ScanResult,
    raw: JsValue,
    flattened: TrieMap[String, JsValue],
    asForms: Map[String, Form])

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
    val flattenedOpenapiSchema = new OpenapiToJson(openApiSchema).run()
    val asForms = new FormsGenerator(flattenedOpenapiSchema).run()
    val flatFile = new File("./conf/schemas/openapi-flat.json")
    val formFile = new File("./conf/schemas/openapi-forms.json")
    if (hasWritten || !flatFile.exists()) {
      val flat = new JsObject(flattenedOpenapiSchema)
      Files.writeString(flatFile.toPath, flat.prettify)
    }
    if (hasWritten || !formFile.exists()) {
      val forms = new JsObject(asForms.mapValues(_.json))
      Files.writeString(formFile.toPath, forms.prettify)
    }
    if (hasWritten) {
      new CrdsGenerator(openApiSchema).run()
    }
    OpenApiSchema(
      scanResult  = scanResult,
      raw         = openApiSchema,
      flattened   = flattenedOpenapiSchema,
      asForms     = asForms
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
        val obj = Json.parse(jsonRaw).as[JsObject]
        val map = new TrieMap[String, JsValue]()
        map.++=(obj.value)
      }
      val asForms = {
        val jsonRaw = new String(openapiformres.readAllBytes(), StandardCharsets.UTF_8)
        val obj = Json.parse(jsonRaw).as[JsObject]
        val map = new TrieMap[String, Form]()
        map.++=(obj.value.mapValues(Form.fromJson)).toMap
      }
      OpenApiSchema(
        scanResult  = scanResult,
        raw         = openApiSchema,
        flattened   = flattenedOpenapiSchema,
        asForms     = asForms
      )
    }) match {
      case Some(oas) => Right(oas)
      case None =>
        val openapiexists = env.environment.resourceAsStream("/schemas/openapi.json").isDefined
        val openapiflatexists = env.environment.resourceAsStream("/schemas/openapi-flat.json").isDefined
        val openapiformexists = env.environment.resourceAsStream("/schemas/openapi-forms.json").isDefined
        val message = s"""One or more schemas files resources not found !
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
    val dev = env.env == "dev"
    if (dev) {
      scanAndGenerateSchema(scanResult)
    } else {
      readSchemaFromFiles(scanResult, env) match {
        case Left(err) => throw new RuntimeException(err)
        case Right(oas) => oas
      }
    }
  }
}
