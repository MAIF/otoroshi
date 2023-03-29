package main

import (
  "strconv"
  "github.com/buger/jsonparser"
   b64 "encoding/base64"
)

/* PLUGIN MAP */
func ProxyPluginMapSet(key string, value string) {
  context := []byte(`{
    "key": "` + key + `",
    "value": "` + value + `"
  }`)
  
  _ProxyPluginMapSet(ByteArrPtr(context), uint64(len(context)))
}

func ProxyPluginMapGet(key string) []byte {
  return pointerToBytes(_ProxyPluginMapGet(StringBytePtr(key), uint64(len(key))))
}

func ProxyPluginMap() map[string][]byte {
  p := pointerToBytes(_ProxyPluginMap(uint64(0)))

  out := make(map[string][]byte)
  jsonparser.ObjectEach(p, func(key []byte, value []byte, dataType jsonparser.ValueType, offset int) error {
    val, _ := b64.StdEncoding.DecodeString( string(value[:]))
    out[string(key)] = val
  	return nil
  })
  
  return out
}
/* PLUGIN MAP END */

/* GLOBAL PLUGIN MAP */
func ProxyGlobalMapSet(key string, value string) {
  context := []byte(`{
    "key": "` + key + `",
    "value": "` + value + `"
  }`)
  
  _ProxyGlobalMapSet(ByteArrPtr(context), uint64(len(context)))
}

func ProxyGlobalMapGet(key string) []byte {
  return pointerToBytes(_ProxyGlobalMapGet(StringBytePtr(key), uint64(len(key))))
}

func ProxyGlobalMap() map[string][]byte {
  p := pointerToBytes(_ProxyGlobalMap(uint64(0)))

  out := make(map[string][]byte)
  jsonparser.ObjectEach(p, func(key []byte, value []byte, dataType jsonparser.ValueType, offset int) error {
    val, _ := b64.StdEncoding.DecodeString( string(value[:]))
    out[string(key)] = val
  	return nil
  })
  
  return out
}
/* GLOBAL PLUGIN MAP END */

/* PLUGIN DATASTORE */

func ProxyPluginDatastoreKeys(key string) []byte {  
  return pointerToBytes(_ProxyPluginDatastoreKeys(StringBytePtr(key), uint64(len(key))))
}

func ProxyPluginDataStoreGet(key string) []byte {
  return pointerToBytes(_ProxyPluginDataStoreGet(StringBytePtr(key), uint64(len(key))))
}

func ProxyPluginDataStoreExists(key string) bool {
  res := _ProxyPluginDataStoreGet(StringBytePtr(key), uint64(len(key)))
  str := pointerToBytes(res)
  
  r, _ := strconv.ParseBool(string(str))

  return r
}

func ProxyPluginDataStorePttl(key string) uint64 {
  return _ProxyPluginDataStorePttl(StringBytePtr(key), uint64(len(key)))
}

func ProxyPluginDataStoreSetnx(value []byte) {
  _ProxyPluginDataStoreSetnx(ByteArrPtr(value), uint64(len(value)))
}

func ProxyPluginDataStoreDel(keys ...string) {
  var ret string
  for i, str := range keys {
    ret += "\"" + str + "\""

    if i != (len(keys)-1) {
      ret += ","
    }
  }
  
  str := []byte(`{ "keys": [` +  ret + `] }`)
  _ProxyPluginDataStoreDel(ByteArrPtr(str), uint64(len(str)))
}


func ProxyPluginDataStoreIncrby(key string, incr int) {
  context := []byte(`{
    "key": "` + key + `",
    "incr": "` + strconv.Itoa(incr) + `"
  }`)
  
  _ProxyPluginDataStoreIncrby(ByteArrPtr(context), uint64(len(context)))
}


func ProxyPluginDataStorePexpire(key string, pttl int64) {
  context := []byte(`{
    "key": "` + key + `",
    "pttl": "` + strconv.FormatInt(pttl, 10) + `"
  }`)
  
  _ProxyPluginDataStorePexpire(ByteArrPtr(context), uint64(len(context)))
}


func ProxyPluginDataStoreAllMatching(key string) []byte {
  return pointerToBytes(_ProxyPluginDataStoreAllMatching(StringBytePtr(key), uint64(len(key))))
}

/* PROXY DATASTORE */

func ProxyDatastoreKeys(key string) []byte {  
  return pointerToBytes(_ProxyDatastoreKeys(StringBytePtr(key), uint64(len(key))))
}

func ProxyDataStoreGet(key string) []byte {
  return pointerToBytes(_ProxyDataStoreGet(StringBytePtr(key), uint64(len(key))))
}

func ProxyDataStoreExists(key string) bool {
  res := _ProxyDataStoreGet(StringBytePtr(key), uint64(len(key)))
  str := pointerToBytes(res)
  
  r, _ := strconv.ParseBool(string(str))

  return r
}

func ProxyDataStorePttl(key string) uint64 {
  return _ProxyDataStorePttl(StringBytePtr(key), uint64(len(key)))
}

func ProxyDataStoreSetnx(value []byte) {
  _ProxyDataStoreSetnx(ByteArrPtr(value), uint64(len(value)))
}

func ProxyDataStoreDel(keys ...string) {
  var ret string
  for i, str := range keys {
    ret += "\"" + str + "\""

    if i != (len(keys)-1) {
      ret += ","
    }
  }
  
  str := []byte(`{ "keys": [` +  ret + `] }`)
  _ProxyDataStoreDel(ByteArrPtr(str), uint64(len(str)))
}


func ProxyDataStoreIncrby(key string, incr int) {
  context := []byte(`{
    "key": "` + key + `",
    "incr": "` + strconv.Itoa(incr) + `"
  }`)
  
  _ProxyDataStoreIncrby(ByteArrPtr(context), uint64(len(context)))
}


func ProxyDataStorePexpire(key string, pttl int64) {
  context := []byte(`{
    "key": "` + key + `",
    "pttl": "` + strconv.FormatInt(pttl, 10) + `"
  }`)
  
  _ProxyDataStorePexpire(ByteArrPtr(context), uint64(len(context)))
}


func ProxyDataStoreAllMatching(key string) []byte {
  return pointerToBytes(_ProxyDataStoreAllMatching(StringBytePtr(key), uint64(len(key))))
}

/* PROXY DATASTORE END */

func ClusterState() []byte {
  return pointerToBytes(GetClusterState(uint64(0)))
}

func ClusterStateField(field string) []byte {
  return pointerToBytes(GetClusterStateValue(StringBytePtr(field), uint64(len(field))))
}

func ProxyState() []byte {
  return pointerToBytes(GetProxyState(uint64(0)))
}

func ProxyStateEntities(entity string) []byte {
  context := []byte(`{
    "entity": "` + entity + `"
  }`)
  
  return pointerToBytes(GetProxyStateValue(ByteArrPtr(context), uint64(len(context))))
}

func ProxyStateEntity(entity string, id string) []byte {
  context := []byte(`{
    "entity": "` + entity + `",
    "id": "` + id + `"
  }`)
  
  return pointerToBytes(GetProxyStateValue(ByteArrPtr(context), uint64(len(context))))
}


func Log(logLevel LogLevel, message string) Status {
  return ProxyLog(logLevel, StringBytePtr(message), uint64(len(message)))
}

func LogBool(logLevel LogLevel, message bool) Status {
  str := strconv.FormatBool(message)
  return ProxyLog(logLevel, StringBytePtr(str), uint64(len(str)))
}

func LogLong(logLevel LogLevel, message uint64) Status {
  str := strconv.FormatUint(message, 10)
  return ProxyLog(logLevel, StringBytePtr(str), uint64(len(str)))
}

func LogBytes(logLevel LogLevel, message []byte) Status {
  return ProxyLog(logLevel, ByteArrPtr(message), uint64(len(message)))
}

func LogEvent(function string, message string, level LogLevel) Status {
  event := []byte(`{
    "function": "` + function + `",
    "message": "` + message + `",
    "level": "` + level.String() + `"}`)

  return ProxyLogEvent(ByteArrPtr(event), uint64(len(event)))
}