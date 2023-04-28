package main

import (
  "github.com/extism/go-pdk"
)

func StringBytePtr(msg string) uint64 {
	mem := pdk.AllocateString(msg)
    return mem.Offset()
}

func ByteArrPtr(arr []byte) uint64 {
	mem := pdk.AllocateBytes(arr)
    return mem.Offset()
}

func EmptyResult() int32 {
  mem := pdk.AllocateString("{}")
  pdk.OutputMemory(mem)
  return 0
}

func TestResult() int32 {
  mem := pdk.AllocateString(`{
    "result": true
  }`)
  pdk.OutputMemory(mem)
  return 0
}

func pointerToBytes(p uint64) []byte {
  responseMemory := pdk.FindMemory(p)

  buf := make([]byte, int(responseMemory.Length()))
  responseMemory.Load(buf)

  return buf
}

func pointerToString(p uint64) string {
  responseMemory := pdk.FindMemory(p)

  buf := make([]byte, int(responseMemory.Length()))
  responseMemory.Load(buf)

  return string(buf[:])
}

type LogLevel uint32

const (
	LogLevelTrace    LogLevel = iota
	LogLevelDebug
	LogLevelInfo
	LogLevelWarn
	LogLevelError
	LogLevelCritical
	LogLevelMax
)

func (bp LogLevel) String() string {
    return []string{
      "LogLevelTrace", 
      "LogLevelDebug", 
      "LogLevelInfo", 
      "LogLevelWarn",
      "LogLevelError", 
      "LogLevelCritical", 
      "LogLevelMax",
    }[bp]
}

type Status uint32

const (
	StatusOK              Status = iota
	StatusNotFound
	StatusBadArgument
	StatusEmpty
	StatusCasMismatch
	StatusInternalFailure
	StatusUnimplemented
)

func (bp Status) String() string {
    return []string{
      "StatusOK",
      "StatusNotFound",
      "StatusBadArgument",
      "StatusEmpty",
      "StatusCasMismatch",
      "StatusInternalFailure",
      "StatusUnimplemented",
    }[bp]
}


//export proxy_log
func ProxyLog(logLevel LogLevel, messageData uint64, messageSize uint64) Status
//export proxy_log_event
func ProxyLogEvent(context uint64, contextSize uint64) Status

//export proxy_http_call
func ProxyHttpCall(context uint64, contextSize uint64) uint64

//export proxy_state
func GetProxyState(context uint64) uint64
//export proxy_state_value
func GetProxyStateValue(context uint64, contextSize uint64) uint64

//export proxy_cluster_state
func GetClusterState(context uint64) uint64
//export proxy_cluster_state_value
func GetClusterStateValue(context uint64, contextSize uint64) uint64

//export proxy_datastore_keys
func _ProxyDatastoreKeys(context uint64, contextSize uint64) uint64
//export proxy_datastore_get
func _ProxyDataStoreGet(context uint64, contextSize uint64) uint64
//export proxy_datastore_exists
func _ProxyDataStoreExists(context uint64, contextSize uint64) uint64
//export proxy_datastore_pttl
func _ProxyDataStorePttl(context uint64, contextSize uint64) uint64
//export proxy_datastore_setnx
func _ProxyDataStoreSetnx(context uint64, contextSize uint64) uint64
//export proxy_datastore_del
func _ProxyDataStoreDel(context uint64, contextSize uint64) uint64
//export proxy_datastore_incrby
func _ProxyDataStoreIncrby(context uint64, contextSize uint64) uint64
//export proxy_datastore_pexpire
func _ProxyDataStorePexpire(context uint64, contextSize uint64) uint64
//export proxy_datastore_all_matching
func _ProxyDataStoreAllMatching(context uint64, contextSize uint64) uint64

//export proxy_plugin_datastore_keys
func _ProxyPluginDatastoreKeys(context uint64, contextSize uint64) uint64
//export proxy_plugin_datastore_get
func _ProxyPluginDataStoreGet(context uint64, contextSize uint64) uint64
//export proxy_plugin_datastore_exists
func _ProxyPluginDataStoreExists(context uint64, contextSize uint64) uint64
//export proxy_plugin_datastore_pttl
func _ProxyPluginDataStorePttl(context uint64, contextSize uint64) uint64
//export proxy_plugin_datastore_setnx
func _ProxyPluginDataStoreSetnx(context uint64, contextSize uint64) uint64
//export proxy_plugin_datastore_del
func _ProxyPluginDataStoreDel(context uint64, contextSize uint64) uint64
//export proxy_plugin_datastore_incrby
func _ProxyPluginDataStoreIncrby(context uint64, contextSize uint64) uint64
//export proxy_plugin_datastore_pexpire
func _ProxyPluginDataStorePexpire(context uint64, contextSize uint64) uint64
//export proxy_plugin_datastore_all_matching
func _ProxyPluginDataStoreAllMatching(context uint64, contextSize uint64) uint64

//export proxy_plugin_map_set
func _ProxyPluginMapSet(context uint64, contextSize uint64) uint64
//export proxy_plugin_map_get
func _ProxyPluginMapGet(context uint64, contextSize uint64) uint64
//export proxy_plugin_map
func _ProxyPluginMap(_requiredUInt64 uint64) uint64
//export proxy_global_map_set
func _ProxyGlobalMapSet(context uint64, contextSize uint64) uint64
//export proxy_global_map_get
func _ProxyGlobalMapGet(context uint64, contextSize uint64) uint64
//export proxy_global_map
func _ProxyGlobalMap(_requiredUInt64 uint64) uint64