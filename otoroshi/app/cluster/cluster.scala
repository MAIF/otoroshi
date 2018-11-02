package cluster

trait ClusterMode {
  def name: String
  def clusterActive: Boolean
  def isWorker: Boolean
  def isLeader: Boolean
}

object ClusterMode {
  case object Off extends ClusterMode {
    def name: String = "Off"
    def clusterActive: Boolean = false
    def isWorker: Boolean = false
    def isLeader: Boolean = false
  }
  case object Leader extends ClusterMode {
    def name: String = "Leader"
    def clusterActive: Boolean = true
    def isWorker: Boolean = false
    def isLeader: Boolean = true
  }
  case object Worker extends ClusterMode {
    def name: String = "Worker"
    def clusterActive: Boolean = true
    def isWorker: Boolean = true
    def isLeader: Boolean = false
  }
  val values: Seq[ClusterMode] =
    Seq(Off, Leader, Worker)
  def apply(name: String): Option[ClusterMode] = name match {
    case "Off"             => Some(Off)
    case "Leader"          => Some(Leader)
    case "Worker"          => Some(Worker)
    case "off"             => Some(Off)
    case "leader"          => Some(Leader)
    case "worker"          => Some(Worker)
    case _                 => None
  }
}

class cluster {

}
