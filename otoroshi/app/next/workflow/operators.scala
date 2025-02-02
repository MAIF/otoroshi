package otoroshi.next.workflow

import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsNull, JsValue}

class MemRefOperator extends WorkflowOperator {

  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val name = opts.select("name").asString
    val path = opts.select("path").asOptString
    wfr.memory.get(name) match {
      case None => JsNull
      case Some(value) if path.isEmpty => value
      case Some(value) if path.isDefined => value.at(path.get).asValue
    }
  }
}
