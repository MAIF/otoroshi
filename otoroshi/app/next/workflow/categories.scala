package otoroshi.next.workflow

import play.api.libs.json.{JsObject, Json}

object CategoriesInitializer {
  def initDefaults(): Unit = {
    Node.registerCategory("flow", FlowCategory())
    Node.registerCategory("core", CoreCategory())
    Node.registerCategory("transformations", TransformationsCategory())
    Node.registerCategory("functions", FunctionsCategory())
    Node.registerCategory("others", OthersCategory())
  }
}

trait NodeCategory {
  def name: String
  def description: String
  def nodes: Seq[String]
  def json: JsObject = Json.obj(
    "name"        -> name,
    "description" -> description,
    "nodes" -> nodes
  )
}

case class OthersCategory(
  name: String = "Others",
  description: String = "Bunch of nodes, operators and functions",
  nodes: Seq[String] = Seq()
) extends NodeCategory

case class CoreCategory(
  name: String = "Core",
  description: String = "Run code, make HTTP requests, etc",
  nodes: Seq[String] = Seq(
    "workflow",
    "call",
    "assign",
    "parallel",
    "switch",
    "if",
    "foreach",
    "map",
    "filter",
    "flatmap",
    "wait",
    "error",
    "value",
    "predicate",
    "returned"
  )
) extends NodeCategory

case class TransformationsCategory(
  name: String = "JSON Transformations",
  description: String = "Operators are one-key JSON objects used to manipulate data",
  nodes: Seq[String] = Seq(
    "$mem_ref",
    "$array_append",
    "$array_prepend",
    "$array_at",
    "$array_del",
    "$array_page",
    "$projection",
    "$map_put",
    "$map_get",
    "$map_del",
    "$json_parse",
    "$is_truthy",
    "$is_falsy",
    "$contains",
    "$eq",
    "$neq",
    "$encode_base64",
    "$decode_base64",
    "$not",
    "$str_split"
  )
) extends NodeCategory

case class FunctionsCategory(
  name: String = "Functions",
  description: String = "Execute functions",
  nodes: Seq[String] = Seq(

  )
) extends NodeCategory

case class FlowCategory(
  name: String = "Flow",
  description: String = "Branch, merge or loop the flow, etc",
  nodes: Seq[String] = Seq(
    "assign",
    "parallel",
    "switch",
    "if",
    "foreach",
    "map",
    "filter",
    "flatmap"
  )
) extends NodeCategory
