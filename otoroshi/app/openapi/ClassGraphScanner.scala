package otoroshi.openapi

import com.google.common.io.CharStreams
import io.github.classgraph.{ClassGraph, ScanResult}
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.libs.json.{JsObject, JsValue, Json}

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.collection.concurrent.TrieMap

case class Form(schema: JsObject, flow: Set[String])

case class OpenApiSchema(
    scanResult: ScanResult,
    raw: JsValue,
    flattened: TrieMap[String, JsValue],
    asForms: Map[String, Form])

class ClassGraphScanner {

  def run(configurationPackages: Seq[String], env: Env): OpenApiSchema = {
    val scanResult = new ClassGraph()
      .addClassLoader(this.getClass.getClassLoader)
      .enableAllInfo()
      .acceptPackages(Seq("otoroshi", "otoroshi_plugins", "play.api.libs.ws") ++ configurationPackages: _*)
      .scan()

    val dev = env.env == "dev"
    val openApiSchema    = if (dev) {
        new OpenApiGenerator(
        "./conf/routes",
        "./app/openapi/openapi-cfg.json",
        Seq(
          "./public/openapi.json",
          "./conf/otoroshi-openapi.json",
          "../manual/src/main/paradox/code/openapi.json"
        ),
        scanResult = scanResult,
        write = dev
      ).run()
    } else {
      env.environment.resourceAsStream("/otoroshi-openapi.json").map { res =>
        val jsonRaw = new String(res.readAllBytes(), StandardCharsets.UTF_8)
        Json.parse(jsonRaw)
      }.getOrElse(Json.obj())
    }

    val flattenedOpenapiSchema = new OpenapiToJson(openApiSchema).run()

    OpenApiSchema(
      scanResult  = scanResult,
      raw         = openApiSchema,
      flattened   = flattenedOpenapiSchema,
      asForms     = new FormsGenerator(flattenedOpenapiSchema).run()
    )
  }
}
