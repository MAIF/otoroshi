package main 

import (
    "github.com/extism/go-pdk"
    "github.com/tidwall/gjson"
)

type LogLevel uint32

const (
	LogLevelTrace    LogLevel = 0
	LogLevelDebug    LogLevel = 1
	LogLevelInfo     LogLevel = 2
	LogLevelWarn     LogLevel = 3
	LogLevelError    LogLevel = 4
	LogLevelCritical LogLevel = 5
	LogLevelMax      LogLevel = 6
)

type Status uint32

const (
	StatusOK              Status = 0
	StatusNotFound        Status = 1
	StatusBadArgument     Status = 2
	StatusEmpty           Status = 7
	StatusCasMismatch     Status = 8
	StatusInternalFailure Status = 10
	StatusUnimplemented   Status = 12
)

func StringBytePtr(msg string) uint64 {
	mem := pdk.AllocateString(msg)
    return mem.Offset()
}

func ByteArrPtr(arr []byte) uint64 {
	mem := pdk.AllocateBytes(arr)
    return mem.Offset()
}

func OtoroshiLogInfo(message string) {
  ProxyLog(LogLevelInfo, StringBytePtr(message), len(message))
}

func OtoroshiHttpCall(req string) string {
  var res_bytes = OtoroshiHttpCallBytes(req)
  return string(res_bytes[:])
}

func OtoroshiHttpCallBytes(req string) []byte {
  var res_ptr = ProxyHttpCall(StringBytePtr(req), len(req))
  var res_mem = pdk.FindMemory(res_ptr)
  var res_bytes = make([]byte, int(res_mem.Length()))
  res_mem.Load(res_bytes)
  return res_bytes
}

func OtoroshiClusterConfig() string {
  var res_bytes = OtoroshiClusterConfigBytes()
  return string(res_bytes[:])
}

func OtoroshiClusterConfigBytes() []byte {
  var res_ptr = GetClusterState(0)
  var res_mem = pdk.FindMemory(res_ptr)
  var res_bytes = make([]byte, int(res_mem.Length()))
  res_mem.Load(res_bytes)
  return res_bytes
}

func OtoroshiDatastoreKeys(pattern string) []string {
  var res_ptr = ProxyDatastoreKeys(StringBytePtr(pattern), len(pattern))
  var res_mem = pdk.FindMemory(res_ptr)
  var res_bytes = make([]byte, int(res_mem.Length()))
  res_mem.Load(res_bytes)
  var arr []string
  var keys = gjson.ParseBytes(res_bytes)
  for _, key := range keys.Array() {
    arr = append(arr, key.String())
  }
  return arr
}

//export proxy_log
func ProxyLog(logLevel LogLevel, messageData uint64, messageSize int) Status
//export proxy_log_event
func ProxyLogEvent(context uint64, contextSize int) Status

//export proxy_http_call
func ProxyHttpCall(context uint64, contextSize int) uint64

//export get_proxy_state
func GetProxyState(context uint64) uint64
//export get_proxy_state_value
func GetProxyStateValue(context uint64, contextSize int) uint64

//export get_cluster_state
func GetClusterState(context uint64) uint64
//export get_cluster_state_value
func GetClusterStateValue(context uint64, contextSize int) uint64

//export proxy_datastore_keys
func ProxyDatastoreKeys(context uint64, contextSize int) uint64
//export proxy_datastore_get
func ProxyDataStoreGet(context uint64, contextSize int) uint64
//export proxy_datastore_exists
func ProxyDataStoreExists(context uint64, contextSize int) uint64
//export proxy_datastore_pttl
func ProxyDataStorePttl(context uint64, contextSize int) uint64
//export proxy_datastore_setnx
func ProxyDataStoreSetnx(context uint64, contextSize int) uint64
//export proxy_datastore_del
func ProxyDataStoreDel(context uint64, contextSize int) uint64
//export proxy_datastore_incrby
func ProxyDataStoreIncrby(context uint64, contextSize int) uint64
//export proxy_datastore_pexpire
func ProxyDataStorePexpire(context uint64, contextSize int) uint64
//export proxy_datastore_all_matching
func ProxyDataStoreAllMatching(context uint64, contextSize int) uint64

//export proxy_plugin_datastore_keys
func ProxyPluginDatastoreKeys(context uint64, contextSize int) uint64
//export proxy_plugin_datastore_get
func ProxyPluginDataStoreGet(context uint64, contextSize int) uint64
//export proxy_plugin_datastore_exists
func ProxyPluginDataStoreExists(context uint64, contextSize int) uint64
//export proxy_plugin_datastore_pttl
func ProxyPluginDataStorePttl(context uint64, contextSize int) uint64
//export proxy_plugin_datastore_setnx
func ProxyPluginDataStoreSetnx(context uint64, contextSize int) uint64
//export proxy_plugin_datastore_del
func ProxyPluginDataStoreDel(context uint64, contextSize int) uint64
//export proxy_plugin_datastore_incrby
func ProxyPluginDataStoreIncrby(context uint64, contextSize int) uint64
//export proxy_plugin_datastore_pexpire
func ProxyPluginDataStorePexpire(context uint64, contextSize int) uint64
//export proxy_plugin_datastore_all_matching
func ProxyPluginDataStoreAllMatching(context uint64, contextSize int) uint64

//export proxy_plugin_map_set
func ProxyPluginMapSet(context uint64, contextSize int) uint64
//export proxy_plugin_map_get
func ProxyPluginMapGet(context uint64, contextSize int) uint64
//export proxy_plugin_map
func ProxyPluginMap(_requiredUInt64 uint64) uint64
//export proxy_global_map_set
func ProxyGlobalMapSet(context uint64, contextSize int) uint64
//export proxy_global_map_get
func ProxyGlobalMapGet(context uint64, contextSize int) uint64
//export proxy_global_map
func ProxyGlobalMap(_requiredUInt64 uint64) uint64
