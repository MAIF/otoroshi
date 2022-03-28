package otoroshi.openapi

import io.github.classgraph.{ClassGraph, ScanResult}
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits.BetterJsValue
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

  def scanAndGenerateSchema(scanResult: ScanResult): OpenApiSchema = {
    val (openApiSchema, hasWritten) = new OpenApiGenerator(
      "./conf/routes",
      "./app/openapi/openapi-cfg.json",
      Seq(
        "./public/openapi.json",
        "./conf/schemas/openapi.json",
        "../manual/src/main/paradox/code/openapi.json"
      ),
      scanResult = scanResult,
      write = true
    ).runAndMaybeWrite()
    val flattenedOpenapiSchema = new OpenapiToJson(openApiSchema).run()
    val asForms = new FormsGenerator(flattenedOpenapiSchema).run()
    val asCrds = new CrdsGenerator(openApiSchema).run()
    val flatFile = new File("./conf/schemas/openapi-flat.json")
    val formFile = new File("./conf/schemas/openapi-forms.json")
    if (hasWritten || !flatFile.exists() || !formFile.exists()) {
      val flat = new JsObject(flattenedOpenapiSchema)
      Files.writeString(flatFile.toPath, flat.prettify)
      val forms = new JsObject(asForms.mapValues(_.json))
      Files.writeString(formFile.toPath, forms.prettify)
    }
    OpenApiSchema(
      scanResult  = scanResult,
      raw         = openApiSchema,
      flattened   = flattenedOpenapiSchema,
      asForms     = asForms
    )
  }

  def readSchemaFromFiles(scanResult: ScanResult, env: Env): OpenApiSchema = {
    val openApiSchema = env.environment.resourceAsStream("/schemas/openapi.json").map { res =>
      val jsonRaw = new String(res.readAllBytes(), StandardCharsets.UTF_8)
      Json.parse(jsonRaw)
    }.getOrElse(Json.obj())
    val flattenedOpenapiSchema = env.environment.resourceAsStream("/schemas/openapi-flat.json").map { res =>
      val jsonRaw = new String(res.readAllBytes(), StandardCharsets.UTF_8)
      val obj = Json.parse(jsonRaw).as[JsObject]
      val map = new TrieMap[String, JsValue]()
      map.++=(obj.value)
    }.getOrElse(new TrieMap[String, JsValue]())
    val asForms = env.environment.resourceAsStream("/schemas/openapi-forms.json").map { res =>
      val jsonRaw = new String(res.readAllBytes(), StandardCharsets.UTF_8)
      val obj = Json.parse(jsonRaw).as[JsObject]
      val map = new TrieMap[String, Form]()
      map.++=(obj.value.mapValues(Form.fromJson)).toMap
    }.getOrElse(Map.empty[String, Form])
    OpenApiSchema(
      scanResult  = scanResult,
      raw         = openApiSchema,
      flattened   = flattenedOpenapiSchema,
      asForms     = asForms
    )
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
      readSchemaFromFiles(scanResult, env)
    }
  }
}
