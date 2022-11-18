package otoroshi.utils

import akka.http.scaladsl.model.DateTime
import otoroshi.gateway.GwError
import otoroshi.models.{ApiKey, ApiKeyRotationInfo, ApikeyTuple, JwtInjection, PrivateAppsUser, RemainingQuotas, Target}
import otoroshi.next.models.{NgBackend, NgContextualPlugins, NgMatchedRoute, NgRoute, NgTarget}
import otoroshi.utils.cache.types.LegitTrieMap
import play.api.libs.json.{JsArray, JsBoolean, JsNumber, JsObject, JsString, JsValue, Json}
import play.api.libs.typedmap.{TypedEntry, TypedKey}
import otoroshi.utils.json._

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong}
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
  def empty: TypedMap = new ConcurrentMutableTypedMap(new LegitTrieMap[TypedKey[_], Any])
  def apply(entries: TypedEntry[_]*): TypedMap = {
    TypedMap.empty.put(entries: _*)
  }
}

final class ConcurrentMutableTypedMap(m: TrieMap[TypedKey[_], Any]) extends TypedMap {

  override def json: JsValue = {
    JsObject(m.toSeq.zipWithIndex.map {
      case ((key, value: String), idx)                                                             => (key.displayName.getOrElse(s"key-${idx}"), JsString(value))
      case ((key, value: Boolean), idx)                                                            => (key.displayName.getOrElse(s"key-${idx}"), JsBoolean(value))
      case ((key, value: Int), idx)                                                                => (key.displayName.getOrElse(s"key-${idx}"), JsNumber(value))
      case ((key, value: Double), idx)                                                             => (key.displayName.getOrElse(s"key-${idx}"), JsNumber(value))
      case ((key, value: Long), idx)                                                               => (key.displayName.getOrElse(s"key-${idx}"), JsNumber(value))
      case ((key, value: DateTime), idx)                                                           => (key.displayName.getOrElse(s"key-${idx}"), JsString(value.toString()))
      case ((key, value: AtomicLong), idx)                                                         => (key.displayName.getOrElse(s"key-${idx}"), JsNumber(value.get()))
      case ((key, value: AtomicInteger), idx)                                                      => (key.displayName.getOrElse(s"key-${idx}"), JsNumber(value.get()))
      case ((key, value: AtomicBoolean), idx)                                                      => (key.displayName.getOrElse(s"key-${idx}"), JsBoolean(value.get()))
      case ((key, value: Target), idx)                                                             => (key.displayName.getOrElse(s"key-${idx}"), value.json)
      case ((key, value: GwError), idx)                                                            => (key.displayName.getOrElse(s"key-${idx}"), value.json)
      case ((key, value: JsValue), idx)                                                            => (key.displayName.getOrElse(s"key-${idx}"), value)
      case ((key, value: Jsonable), idx)                                                           => (key.displayName.getOrElse(s"key-${idx}"), value.json)
      case ((key, value: Map[_, _]), idx)                                                          =>
        (
          key.displayName.getOrElse(s"key-${idx}"),
          JsObject(value.map { case (k, v) => (k.toString, JsString(v.toString)) })
        )
      case ((key, v: NgRoute), idx)                                                                => (key.displayName.getOrElse(s"key-${idx}"), v.json)
      case ((key, v: NgTarget), idx)                                                               => (key.displayName.getOrElse(s"key-${idx}"), v.json)
      case ((key, v: NgBackend), idx)                                                              => (key.displayName.getOrElse(s"key-${idx}"), v.json)
      case ((key, v: ApikeyTuple), idx)                                                            => (key.displayName.getOrElse(s"key-${idx}"), v.json)
      case ((key, v: JwtInjection), idx)                                                           => (key.displayName.getOrElse(s"key-${idx}"), v.json)
      case ((key, v: ApiKey), idx)                                                                 => (key.displayName.getOrElse(s"key-${idx}"), v.json)
      case ((key, v: ApiKeyRotationInfo), idx)                                                     => (key.displayName.getOrElse(s"key-${idx}"), v.json)
      case ((key, v: RemainingQuotas), idx)                                                        => (key.displayName.getOrElse(s"key-${idx}"), v.toJson)
      case ((key, v: PrivateAppsUser), idx)                                                        => (key.displayName.getOrElse(s"key-${idx}"), v.json)
      case ((key, v: otoroshi.next.proxy.NgExecutionReport), idx)                                  =>
        (key.displayName.getOrElse(s"key-${idx}"), JsString(v.id))
      case ((key, v: otoroshi.next.models.NgMatchedRoute), idx)                                    => (key.displayName.getOrElse(s"key-${idx}"), v.json)
      case ((key, v: otoroshi.next.models.NgContextualPlugins), idx)                               =>
        (
          key.displayName.getOrElse(s"key-${idx}"),
          Json.obj(
            "disabled_plugins" -> v.disabledPlugins.map(p => JsString(p.plugin)),
            "excluded_plugins" -> v.filteredPlugins.map(p => JsString(p.plugin)),
            "included_plugins" -> v.allPlugins.map(p => JsString(p.plugin))
          )
        )
      case ((key, v: Seq[_]), idx) if key.displayName.contains("otoroshi.next.core.MatchedRoutes") =>
        (key.displayName.getOrElse(s"key-${idx}"), JsArray(v.asInstanceOf[Seq[String]].map(JsString.apply)))
      case ((key, value), idx)                                                                     => (key.displayName.getOrElse(s"key-${idx}"), JsString(value.toString))
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
