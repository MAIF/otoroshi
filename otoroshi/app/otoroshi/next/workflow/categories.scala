package otoroshi.next.workflow

import play.api.libs.json.{JsObject, Json}

object CategoriesInitializer {
  def initDefaults(): Unit = {
    Node.registerCategory("flow", FlowCategory())
    Node.registerCategory("core", CoreCategory())
    Node.registerCategory("transformations", TransformationsCategory())
    Node.registerCategory("functions", FunctionsCategory())
    Node.registerCategory("udfs", UserDefinedFunctionsCategory())
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
    "nodes"       -> nodes
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
      "returned",
      "start",
      "pause"
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
      "$str_concat",
      "$is_truthy",
      "$is_falsy",
      "$contains",
      "$eq",
      "$neq",
      "$gt",
      "$lt",
      "$gte",
      "$lte",
      "$encode_base64",
      "$decode_base64",
      "$basic_auth",
      "$now",
      "$not",
      "$parse_datetime",
      "$parse_date",
      "$parse_time",
      "$add",
      "$subtract",
      "$multiply",
      "$divide",
      "$incr",
      "$decr",
      "$str_upper_case",
      "$str_lower_case",
      "$str_split",
      "$expression_language",
      "$stringify",
      "$prettify",
      "$str_replace",
      "$str_replace_all",
      "$jq"
    )
) extends NodeCategory

case class FunctionsCategory(
    name: String = "Functions",
    description: String = "Execute functions",
    nodes: Seq[String] = Seq()
) extends NodeCategory

case class UserDefinedFunctionsCategory(
    name: String = "User-defined functions",
    description: String = "Execute your own functions",
    nodes: Seq[String] = Seq()
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
