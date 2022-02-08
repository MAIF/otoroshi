package otoroshi.utils

import play.api.libs.json.{JsBoolean, JsNumber, JsObject, JsString, JsValue}
import play.api.libs.typedmap.{TypedEntry, TypedKey}
import otoroshi.utils.json._

import scala.collection.concurrent.TrieMap

trait TypedMap {
  def get[A](key: TypedKey[A]): Option[A]
  def contains(key: TypedKey[_]): Boolean
  def put(entries: TypedEntry[_]*): TypedMap
  def putIfAbsent(entries: TypedEntry[_]*): TypedMap
  def remove(entries: TypedEntry[_]*): TypedMap
  def replace(entries: TypedEntry[_]*): TypedMap
  def update(entries: TypedEntry[_]*): TypedMap
  def clear(): TypedMap
  def getOrElseUpdate[A](key: TypedKey[A], op: => Any): TypedMap
  def toMap: Map[TypedKey[_], Any]
  def json: JsValue
}

object TypedMap {
  def empty: TypedMap = new ConcurrentMutableTypedMap(new TrieMap[TypedKey[_], Any])
  def apply(entries: TypedEntry[_]*): TypedMap = {
    TypedMap.empty.put(entries: _*)
  }
}

final class ConcurrentMutableTypedMap(m: TrieMap[TypedKey[_], Any]) extends TypedMap {

  override def json: JsValue = {
    JsObject(m.toSeq.zipWithIndex.map {
      case ((key, value: String), idx) => (key.displayName.getOrElse(s"key-${idx}"), JsString(value))
      case ((key, value: Boolean), idx) => (key.displayName.getOrElse(s"key-${idx}"), JsBoolean(value))
      case ((key, value: Int), idx) => (key.displayName.getOrElse(s"key-${idx}"), JsNumber(value))
      case ((key, value: Double), idx) => (key.displayName.getOrElse(s"key-${idx}"), JsNumber(value))
      case ((key, value: Long), idx) => (key.displayName.getOrElse(s"key-${idx}"), JsNumber(value))
      case ((key, value: JsValue), idx) => (key.displayName.getOrElse(s"key-${idx}"), value)
      case ((key, value: Jsonable), idx) => (key.displayName.getOrElse(s"key-${idx}"), value.json)
      case ((key, value: Map[_, _]), idx) => (key.displayName.getOrElse(s"key-${idx}"), JsObject(value.map { case (k, v) => (k.toString, JsString(v.toString)) } ))
      case ((key, value), idx) => (key.displayName.getOrElse(s"key-${idx}"), JsString(value.toString))
    })
  }

  override def toString: String = m.mkString("{", ", ", "}")

  override def toMap: Map[TypedKey[_], Any] = m.toMap

  override def get[A](key: TypedKey[A]): Option[A] = m.get(key).asInstanceOf[Option[A]]

  override def contains(key: TypedKey[_]): Boolean = m.contains(key)

  override def putIfAbsent(entries: TypedEntry[_]*): TypedMap = {
    entries.foreach { e =>
      m.putIfAbsent(e.key, e.value)
    }
    this
  }

  override def put(entries: TypedEntry[_]*): TypedMap = {
    entries.foreach { e =>
      m.put(e.key, e.value)
    }
    this
  }

  override def remove(entries: TypedEntry[_]*): TypedMap = {
    entries.foreach { e =>
      m.remove(e.key)
    }
    this
  }

  override def replace(entries: TypedEntry[_]*): TypedMap = {
    entries.foreach { e =>
      m.replace(e.key, e.value)
    }
    this
  }

  override def update(entries: TypedEntry[_]*): TypedMap = {
    entries.foreach { e =>
      m.update(e.key, e.value)
    }
    this
  }

  override def clear(): TypedMap = {
    m.clear()
    this
  }

  override def getOrElseUpdate[A](key: TypedKey[A], op: => Any): TypedMap = {
    m.getOrElseUpdate(key, op)
    this
  }
}
