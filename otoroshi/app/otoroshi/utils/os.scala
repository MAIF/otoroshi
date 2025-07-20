package otoroshi.utils

object OS {

  private lazy val osName = System.getProperty("os.name").toLowerCase()

  def isWindows = osName.indexOf("win") >= 0
  def isMac     = osName.indexOf("mac") >= 0
  def isLinux   = osName.indexOf("nux") >= 0
  def isSolaris = osName.indexOf("sunos") >= 0
  def isBSD     = osName.indexOf("bd") >= 0
  def isUnix    = osName.indexOf("nix") >= 0 || isLinux || osName.indexOf("aix") > 0
}
