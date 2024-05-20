package otoroshi.wasm.httpwasm.api

import org.extism.sdk.HostUserData
import otoroshi.next.plugins.api.{NgPluginHttpRequest, NgPluginHttpResponse}
import otoroshi.utils.syntax.implicits._

sealed trait HeaderKind {
  def value: Int
}

object HeaderKind {
  case object HeaderKindRequest extends HeaderKind {
    def value: Int = 0
  }
  case object HeaderKindResponse extends HeaderKind {
    def value: Int = 1
  }
  case object HeaderKindRequestTrailers extends HeaderKind {
    def value: Int = 2
  }
  case object HeaderKindResponseTrailers extends HeaderKind {
    def value: Int = 3
  }

  def fromValue(value: Int): HeaderKind = {
    value match {
      case 0 => HeaderKindRequest
      case 1 => HeaderKindResponse
      case 2 => HeaderKindRequestTrailers
      case 3 => HeaderKindResponseTrailers
    }
  }
}

sealed trait BodyKind {
  def value: Int
}

object BodyKind {
  case object BodyKindRequest extends BodyKind {
    def value: Int = 0
  }

  case object BodyKindResponse extends BodyKind {
    def value: Int = 1
  }

  def fromValue(value: Int): BodyKind = {
    value match {
      case 0 => BodyKindRequest
      case 1 => BodyKindResponse
    }
  }
}

sealed trait LogLevel {
  def value: Int
}

object LogLevel {
  case object LogLevelDebug extends LogLevel {
    def value: Int = -1
  }

  case object LogLevelInfo extends LogLevel {
    def value: Int = 0
  }

  case object LogLevelWarn extends LogLevel {
    def value: Int = 1
  }

  case object LogLevelError extends LogLevel {
    def value: Int = 2
  }

  case object LogLevelNone extends LogLevel {
    def value: Int = 3
  }

  def fromValue(value: Int): LogLevel = {
    value match {
      case -1 => LogLevelDebug
      case 0 => LogLevelInfo
      case 1 => LogLevelWarn
      case 2 => LogLevelError
      case 3 => LogLevelNone
    }
  }
}


sealed trait Feature {
  def value: Int
}

object Feature {
  case object FeatureBufferRequest extends Feature {
    def value: Int = 1 << 0
  }

  case object FeatureBufferResponse extends Feature {
    def value: Int = 1 << 1
  }

  case object FeatureTrailers extends Feature {
    def value: Int = 1 << 2
  }
}

case class Features(features: Int) {
  def has(feature: Feature): Boolean = {
    (features & feature.value) == feature.value
  }
}