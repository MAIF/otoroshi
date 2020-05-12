package otoroshi.models

object TeamId {
  def apply(raw: String): TeamId = {
    if (raw.contains(":")) {
      val parts = raw.toLowerCase.split(":")
      val canRead = parts.last.contains("r")
      val canWrite = parts.last.contains("w")
      TeamId(parts.head, canRead, canRead && canWrite)
    } else {
      TeamId(raw, true, true)
    }
  }
}
case class TeamId(value: String, canRead: Boolean, canWrite: Boolean) {
  lazy val toRaw: String = {
    s"$value:${if (canRead) "r" else ""}${if (canRead && canWrite) "w" else ""}"
  }
}

object TenantId {
  def apply(raw: String): TenantId = {
    if (raw.contains(":")) {
      val parts = raw.toLowerCase.split(":")
      val canRead = parts.last.contains("r")
      val canWrite = parts.last.contains("w")
      TenantId(parts.head, canRead, canRead && canWrite)
    } else {
      TenantId(raw, true, true)
    }
  }
}
case class TenantId(value: String, canRead: Boolean, canWrite: Boolean) {
  lazy val toRaw: String = {
    s"$value:${if (canRead) "r" else ""}${if (canRead && canWrite) "w" else ""}"
  }
}