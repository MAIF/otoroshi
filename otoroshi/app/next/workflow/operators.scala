package otoroshi.next.workflow

import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

object WorkflowOperatorsInitializer {
  def initDefaults(): Unit = {
    WorkflowOperator.registerOperator("$mem_ref", new MemRefOperator())
    WorkflowOperator.registerOperator("$array_append", new ArrayAppendOperator())
    WorkflowOperator.registerOperator("$array_prepend", new ArrayPrependOperator())
    WorkflowOperator.registerOperator("$array_at", new ArrayAtOperator())
    WorkflowOperator.registerOperator("$array_del", new ArrayDelOperator())
    WorkflowOperator.registerOperator("$array_page", new ArrayPageOperator())
    WorkflowOperator.registerOperator("$projection", new ProjectionOperator())

    WorkflowOperator.registerOperator("$map_put", new MapPutOperator())
    WorkflowOperator.registerOperator("$map_get", new MapGetOperator())
    WorkflowOperator.registerOperator("$map_del", new MapDelOperator())

    WorkflowOperator.registerOperator("$json_parse", new JsonParseOperator())
    WorkflowOperator.registerOperator("$str_concat", new StrConcatOperator())

    WorkflowOperator.registerOperator("$is_truthy", new IsTruthyOperator())
    WorkflowOperator.registerOperator("$is_falsy", new IsFalsyOperator())
    WorkflowOperator.registerOperator("$contains", new ContainsOperator())
    WorkflowOperator.registerOperator("$eq", new EqOperator())
    WorkflowOperator.registerOperator("$neq", new NeqOperator())
    WorkflowOperator.registerOperator("$gt", new GtOperator())
    WorkflowOperator.registerOperator("$lt", new LtOperator())
    WorkflowOperator.registerOperator("$gte", new GteOperator())
    WorkflowOperator.registerOperator("$lte", new LteOperator())
    WorkflowOperator.registerOperator("$encode_base64", new EncodeBase64Operator())
    WorkflowOperator.registerOperator("$decode_base64", new DecodeBase64Operator())
    WorkflowOperator.registerOperator("$basic_auth", new BasicAuthOperator())
    WorkflowOperator.registerOperator("$now", new NowOperator())
    WorkflowOperator.registerOperator("$not", new NotOperator())
    // math operations
  }
}

class NotOperator extends WorkflowOperator {
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    opts.select("value").asOpt[JsValue] match {
      case Some(JsBoolean(b)) => JsBoolean(!b)
      case _                  => JsBoolean(false)
    }
  }
}

class NowOperator extends WorkflowOperator {
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    System.currentTimeMillis().json
  }
}

class BasicAuthOperator extends WorkflowOperator {
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val user     = opts.select("user").asString
    val password = opts.select("password").asString
    s"Basic ${s"${user}:${password}".base64}".json
  }
}

class EncodeBase64Operator extends WorkflowOperator {
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    opts.select("value").asString.base64.json
  }
}

class DecodeBase64Operator extends WorkflowOperator {
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    opts.select("value").asString.decodeBase64.json
  }
}

class GtOperator extends WorkflowOperator {
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val a = opts.select("a").as[JsNumber]
    val b = opts.select("b").as[JsNumber]
    (a.value > b.value).json
  }
}

class GteOperator extends WorkflowOperator {
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val a = opts.select("a").as[JsNumber]
    val b = opts.select("b").as[JsNumber]
    (a.value >= b.value).json
  }
}

class LtOperator extends WorkflowOperator {
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val a = opts.select("a").as[JsNumber]
    val b = opts.select("b").as[JsNumber]
    (a.value < b.value).json
  }
}

class LteOperator extends WorkflowOperator {
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val a = opts.select("a").as[JsNumber]
    val b = opts.select("b").as[JsNumber]
    (a.value <= b.value).json
  }
}

class EqOperator extends WorkflowOperator {
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val a = opts.select("a").asValue
    val b = opts.select("b").asValue
    (a == b).json
  }
}

class NeqOperator extends WorkflowOperator {
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val a = opts.select("a").asValue
    val b = opts.select("b").asValue
    (a != b).json
  }
}

class ContainsOperator extends WorkflowOperator {
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val value              = opts.select("value").asValue
    val container: JsValue = opts.select("container").asOpt[JsValue] match {
      case Some(v) => v
      case None    => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None                          => JsNull
          case Some(value) if path.isEmpty   => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    (container match {
      case JsObject(values) if value.isInstanceOf[JsString] => values.contains(value.asString)
      case JsArray(values)                                  => values.contains(value)
      case JsString(str) if value.isInstanceOf[JsString]    => str.contains(value.asString)
      case _                                                => false
    }).json
  }
}

class IsTruthyOperator extends WorkflowOperator {
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val value: JsValue = opts.select("value").asOpt[JsValue] match {
      case Some(v) => v
      case None    => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None                          => JsNull
          case Some(value) if path.isEmpty   => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    (value match {
      case JsNull                                                   => false
      case JsString(str) if str.isEmpty                             => false
      case JsBoolean(false)                                         => false
      case JsNumber(v) if v.bigDecimal == java.math.BigDecimal.ZERO => false
      case _                                                        => true
    }).json
  }
}

class IsFalsyOperator extends WorkflowOperator {
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val value: JsValue = opts.select("value").asOpt[JsValue] match {
      case Some(v) => v
      case None    => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None                          => JsNull
          case Some(value) if path.isEmpty   => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    val res            = value match {
      case JsNull                                                   => false
      case JsString(str) if str.isEmpty                             => false
      case JsBoolean(false)                                         => false
      case JsNumber(v) if v.bigDecimal == java.math.BigDecimal.ZERO => false
      case _                                                        => true
    }
    (!res).json
  }
}

class MemRefOperator extends WorkflowOperator {
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val name = opts.select("name").asString
    val path = opts.select("path").asOptString
    wfr.memory.get(name) match {
      case None                          => JsNull
      case Some(value) if path.isEmpty   => value
      case Some(value) if path.isDefined => value.at(path.get).asValue
    }
  }
}

class JsonParseOperator extends WorkflowOperator {
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val value: JsValue = opts.select("value").asOptString match {
      case Some(v) => v.json
      case None    => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None                          => JsNull
          case Some(value) if path.isEmpty   => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    value match {
      case JsString(str) => str.parseJson
      case _             => JsNull
    }
  }
}

class StrConcatOperator extends WorkflowOperator {
  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val values    = opts.select("values").asOpt[Seq[String]].getOrElse(Seq.empty)
    val separator = opts.select("separator").asOptString.getOrElse(" ")
    values.mkString(separator).json
  }
}

class MapGetOperator extends WorkflowOperator {

  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val key            = opts.select("key").asString
    val value: JsValue = opts.select("map").asOpt[JsObject] match {
      case Some(v) => v
      case None    => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None                          => JsNull
          case Some(value) if path.isEmpty   => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    value match {
      case JsObject(underlying) => underlying.get(key).getOrElse(JsNull)
      case _                    => JsNull
    }
  }
}

class MapDelOperator extends WorkflowOperator {

  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val key            = opts.select("key").asString
    val value: JsValue = opts.select("map").asOpt[JsObject] match {
      case Some(v) => v
      case None    => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None                          => JsNull
          case Some(value) if path.isEmpty   => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    value match {
      case obj @ JsObject(_) => obj - key
      case _                 => JsNull
    }
  }
}

class MapPutOperator extends WorkflowOperator {

  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val key            = opts.select("key").asString
    val v              = opts.select("value").asValue
    val value: JsValue = opts.select("map").asOpt[JsObject] match {
      case Some(v) => v
      case None    => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None                          => JsNull
          case Some(value) if path.isEmpty   => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    value match {
      case obj @ JsObject(_) => obj ++ Json.obj(key -> v)
      case _                 => JsNull
    }
  }
}

class ArrayAppendOperator extends WorkflowOperator {

  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val v              = opts.select("value").asValue
    val value: JsValue = opts.select("array").asOpt[JsArray] match {
      case Some(v) => v
      case None    => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None                          => JsNull
          case Some(value) if path.isEmpty   => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    value match {
      case arr @ JsArray(_) if v.isInstanceOf[JsArray] => arr ++ v.asArray
      case arr @ JsArray(_)                            => arr.append(v)
      case _                                           => JsNull
    }
  }
}

class ArrayPrependOperator extends WorkflowOperator {

  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val v              = opts.select("value").asValue
    val value: JsValue = opts.select("array").asOpt[JsArray] match {
      case Some(v) => v
      case None    => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None                          => JsNull
          case Some(value) if path.isEmpty   => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    value match {
      case arr @ JsArray(_) if v.isInstanceOf[JsArray] => v.asArray ++ arr
      case arr @ JsArray(_)                            => arr.prepend(v)
      case _                                           => JsNull
    }
  }
}

class ArrayDelOperator extends WorkflowOperator {

  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val idx            = opts.select("idx").asInt
    val value: JsValue = opts.select("array").asOpt[JsArray] match {
      case Some(v) => v
      case None    => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None                          => JsNull
          case Some(value) if path.isEmpty   => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    value match {
      case arr @ JsArray(_) => JsArray(arr.value.zipWithIndex.filterNot(_._2 == idx).map(_._1))
      case _                => JsNull
    }
  }
}

class ArrayAtOperator extends WorkflowOperator {

  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val idx            = opts.select("idx").asInt
    val value: JsValue = opts.select("array").asOpt[JsArray] match {
      case Some(v) => v
      case None    => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None                          => JsNull
          case Some(value) if path.isEmpty   => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    value match {
      case JsArray(arr) => arr.apply(idx)
      case _            => JsNull
    }
  }
}

class ArrayPageOperator extends WorkflowOperator {

  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val page           = opts.select("page").asInt
    val pageSize       = opts.select("page_size").asInt
    val value: JsValue = opts.select("array").asOpt[JsArray] match {
      case Some(v) => v
      case None    => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None                          => JsNull
          case Some(value) if path.isEmpty   => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    value match {
      case JsArray(arr) => {
        val paginationPosition = page * pageSize
        val content            = arr.slice(paginationPosition, paginationPosition + pageSize)
        JsArray(content)
      }
      case _            => JsNull
    }
  }
}

class ProjectionOperator extends WorkflowOperator {

  override def process(opts: JsValue, wfr: WorkflowRun, env: Env): JsValue = {
    val blueprint      = opts.select("projection").asObject
    val value: JsValue = opts.select("value").asOpt[JsObject] match {
      case Some(v) => v
      case None    => {
        val name = opts.select("name").asString
        val path = opts.select("path").asOptString
        wfr.memory.get(name) match {
          case None                          => JsNull
          case Some(value) if path.isEmpty   => value
          case Some(value) if path.isDefined => value.at(path.get).asValue
        }
      }
    }
    otoroshi.utils.Projection.project(value, blueprint, identity)
  }
}
