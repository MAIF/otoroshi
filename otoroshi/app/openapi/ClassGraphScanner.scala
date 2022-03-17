package otoroshi.openapi

import io.github.classgraph.{ClassGraph, ScanResult}
import play.api.libs.json.{JsObject, JsValue}

import scala.collection.concurrent.TrieMap

case class Form(schema: JsObject, flow: Set[String])

case class OpenApiSchema(
    scanResult: ScanResult,
    raw: JsValue,
    flattened: TrieMap[String, JsValue],
    asForms: Map[String, Form])

class ClassGraphScanner {

  def run(configurationPackages: Seq[String]): OpenApiSchema = {
    val scanResult = new ClassGraph()
      .addClassLoader(this.getClass.getClassLoader)
      .enableAllInfo()
      .acceptPackages(Seq("otoroshi", "otoroshi_plugins", "play.api.libs.ws") ++ configurationPackages: _*)
      .scan()

    val openApiSchema    = new OpenApiGenerator(
      "./conf/routes",
      "./app/openapi/openapi-cfg.json",
      scanResult = scanResult
    ).run()

    val flattenedOpenapiSchema = new OpenapiToJson(openApiSchema).run()

    OpenApiSchema(
      scanResult  = scanResult,
      raw         = openApiSchema,
      flattened   = flattenedOpenapiSchema,
      asForms     = new FormsGenerator(flattenedOpenapiSchema).run()
    )
  }
}
