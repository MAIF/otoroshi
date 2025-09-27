package otoroshi.utils

object OS {

  private lazy val osName = System.getProperty("os.name").toLowerCase()

  def isWindows: Boolean = osName.indexOf("win") >= 0
  def isMac: Boolean     = osName.indexOf("mac") >= 0
  def isLinux: Boolean   = osName.indexOf("nux") >= 0
  def isSolaris: Boolean = osName.indexOf("sunos") >= 0
  def isBSD: Boolean     = osName.indexOf("bd") >= 0
  def isUnix: Boolean    = osName.indexOf("nix") >= 0 || isLinux || osName.indexOf("aix") > 0
}
