package cluster.polling

object PollingCluster {
  /**
   * TODO
   *
   * [ ] add an instance type in the configuration (autonomous, master, worker)
   * [ ] if in worker mode, then exposeAdminApi and exposeAdminDashboard should be false, or set to false
   * [ ] if in worker mode, store every call to a service in a local counter
   * [ ] start a job that will run every `env.cluster.polling.every` ms if in worker mode
   * [ ]  - fetch full export from master (from config) for each job run and apply it
   * [ ]  - send counter diffs for service calls, duration, etc ...
   * [ ] create a new admin api endpoint exposed only if in master mode that allow to create private app session remotely
   * [ ] create a new admin api endpoint exposed only if in master mode that allow to fetch private app session remotely
   * [ ] for any private app access, if session not available and in worker mode, try to fetch it from master
   * [ ] for any private app login, if in worker mode, create it on the master
   */ 
}