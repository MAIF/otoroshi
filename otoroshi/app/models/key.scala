package models

import play.api.libs.json.JsPath

case class Key(key: String) {

  private def pattern(str: String) = s"^${str.replaceAll("\\*", ".*")}$$"

  def matchPattern(str: String): Boolean = {
    val regex = pattern(str)
    key.matches(regex)
  }

  def /(path: String): Key = key match {
    case "" => Key(s"$path")
    case _  => Key(s"$key:$path")
  }

  def /(path: Key): Key = key match {
    case "" => path
    case _  => Key(s"$key:${path.key}")
  }

  val segments: Seq[String] = key.split(":")

  val jsPath: JsPath = segments.foldLeft[JsPath](JsPath) { (p, s) =>
    p \ s
  }
}

object Key {

  import play.api.libs.json.Reads._
  import play.api.libs.json._

  val Empty: Key = Key("")

  def apply(path: Seq[String]): Key = new Key(path.mkString(":"))

  val reads: Reads[Key] = Reads[Key] { k =>
    k.asOpt[String] match {
      case Some(k) => JsSuccess(Key(k))
      case None    => JsError("Not a string")
    }
  }
  val writes: Writes[Key] = Writes[Key] { k =>
    JsString(k.key)
  }

  implicit val format: Format[Key] = Format(reads, writes)

  implicit class EnhancedString(val str: String) extends AnyVal {
    def asKey: Key = Key(str)
  }
}
