package utils

import play.api.libs.typedmap.{TypedEntry, TypedKey}

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
}

object TypedMap {
  val empty: TypedMap = new ConcurrentMutableTypedMap(new TrieMap[TypedKey[_], Any])
  def apply(entries: TypedEntry[_]*): TypedMap = {
    TypedMap.empty.put(entries: _*)
  }
}

final class ConcurrentMutableTypedMap(m: TrieMap[TypedKey[_], Any]) extends TypedMap {

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

  override def toString: String = m.mkString("{", ", ", "}")

  override def remove(entries: TypedEntry[_]*): TypedMap = {
    entries.foreach { e =>
      m.remove(e.key)
    }
    this
  }

  override def toMap: Map[TypedKey[_], Any] = m.toMap

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