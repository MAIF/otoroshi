package otoroshi.next.workflow

import otoroshi.env.Env
import otoroshi.next.workflow.WorkflowOperator.registerOperator
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsArray, JsNull, JsObject, JsString, JsValue, Json}

object WorkflowOperatorsInitializer {
  def initDefaults(): Unit = {
    registerOperator("$mem_ref", new MemRefOperator())
    registerOperator("$array_append", new ArrayAppendOperator())
    registerOperator("$array_prepend", new ArrayPrependOperator())
    registerOperator("$array_at", new ArrayAtOperator())
    registerOperator("$array_del", new ArrayDelOperator())
    registerOperator("$array_page", new ArrayPageOperator())
    registerOperator("$projection", new ProjectionOperator())

    registerOperator("$map_put", new MapPutOperator())
    registerOperator("$map_get", new MapGetOperator())
    registerOperator("$map_del", new MapDelOperator())

    registerOperator("$json_parse", new JsonParseOperator())
    registerOperator("$str_concat", new StrConcatOperator())
  }
}

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

class JsonParseOperator extends WorkflowOperator {
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val value: JsValue = opts.select("value").asOptString match {
      case Some(v) => v.json
      case None => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None => JsNull
          case Some(value) if path.isEmpty => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    value match {
      case JsString(str) => str.parseJson
      case _ => JsNull
    }
  }
}

class StrConcatOperator extends WorkflowOperator {
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val values = opts.select("values").asOpt[Seq[String]].getOrElse(Seq.empty)
    val separator = opts.select("separator").asOptString.getOrElse(" ")
    values.mkString(separator).json
  }
}

class MapGetOperator extends WorkflowOperator {

  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val key = opts.select("key").asString
    val value: JsValue = opts.select("map").asOpt[JsObject]  match {
      case Some(v) => v
      case None => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None => JsNull
          case Some(value) if path.isEmpty => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    value match {
      case JsObject(underlying) => underlying.get(key).getOrElse(JsNull)
      case _ => JsNull
    }
  }
}

class MapDelOperator extends WorkflowOperator {

  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val key = opts.select("key").asString
    val value: JsValue = opts.select("map").asOpt[JsObject] match {
      case Some(v) => v
      case None => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None => JsNull
          case Some(value) if path.isEmpty => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    value match {
      case obj @ JsObject(_) => obj - key
      case _ => JsNull
    }
  }
}

class MapPutOperator extends WorkflowOperator {

  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val key = opts.select("key").asString
    val v = opts.select("value").asValue
    val value: JsValue = opts.select("map").asOpt[JsObject] match {
      case Some(v) => v
      case None => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None => JsNull
          case Some(value) if path.isEmpty => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    value match {
      case obj @ JsObject(_) => obj ++ Json.obj(key -> v)
      case _ => JsNull
    }
  }
}

class ArrayAppendOperator extends WorkflowOperator {

  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val v = opts.select("value").asValue
    val value: JsValue = opts.select("array").asOpt[JsArray] match {
      case Some(v) => v
      case None => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None => JsNull
          case Some(value) if path.isEmpty => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    value match {
      case arr @ JsArray(_) => arr.append(v)
      case _ => JsNull
    }
  }
}

class ArrayPrependOperator extends WorkflowOperator {

  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val v = opts.select("value").asValue
    val value: JsValue = opts.select("array").asOpt[JsArray] match {
      case Some(v) => v
      case None => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None => JsNull
          case Some(value) if path.isEmpty => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    value match {
      case arr @ JsArray(_) => arr.prepend(v)
      case _ => JsNull
    }
  }
}

class ArrayDelOperator extends WorkflowOperator {

  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val idx = opts.select("idx").asInt
    val value: JsValue = opts.select("array").asOpt[JsArray] match {
      case Some(v) => v
      case None => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None => JsNull
          case Some(value) if path.isEmpty => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    value match {
      case arr @ JsArray(_) => JsArray(arr.value.zipWithIndex.filterNot(_._2 == idx).map(_._1))
      case _ => JsNull
    }
  }
}

class ArrayAtOperator extends WorkflowOperator {

  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val idx = opts.select("idx").asInt
    val value: JsValue = opts.select("array").asOpt[JsArray] match {
      case Some(v) => v
      case None => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None => JsNull
          case Some(value) if path.isEmpty => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    value match {
      case JsArray(arr) => arr.apply(idx)
      case _ => JsNull
    }
  }
}

class ArrayPageOperator extends WorkflowOperator {

  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val page = opts.select("page").asInt
    val pageSize = opts.select("page_size").asInt
    val value: JsValue = opts.select("array").asOpt[JsArray] match {
      case Some(v) => v
      case None => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None => JsNull
          case Some(value) if path.isEmpty => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    value match {
      case JsArray(arr) => {
        val paginationPosition      = page * pageSize
        val content = arr.slice(paginationPosition, paginationPosition + pageSize)
        JsArray(content)
      }
      case _ => JsNull
    }
  }
}

class ProjectionOperator extends WorkflowOperator {

  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val blueprint = opts.select("projection").asObject
    val value: JsValue = opts.select("value").asOpt[JsObject] match {
      case Some(v) => v
      case None => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None => JsNull
          case Some(value) if path.isEmpty => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    otoroshi.utils.Projection.project(value, blueprint, identity)
  }
}


