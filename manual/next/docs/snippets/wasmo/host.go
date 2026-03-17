// PLUGIN MAP 
func ProxyPluginMapSet(key string, value string)
func ProxyPluginMapGet(key string) []byte
func ProxyPluginMap() map[string][]byte
// GLOBAL PLUGIN MAP
func ProxyGlobalMapSet(key string, value string)
func ProxyGlobalMapGet(key string) []byte
func ProxyGlobalMap() map[string][]byte
// PLUGIN DATASTORE
func ProxyPluginDatastoreKeys(key string) []byte
func ProxyPluginDataStoreGet(key string) []byte
func ProxyPluginDataStoreExists(key string) bool
func ProxyPluginDataStorePttl(key string) uint64
func ProxyPluginDataStoreSetnx(value []byte)
func ProxyPluginDataStoreDel(keys ...string)
func ProxyPluginDataStoreIncrby(key string, incr int)
func ProxyPluginDataStorePexpire(key string, pttl int64)
func ProxyPluginDataStoreAllMatching(key string) []byte
// PROXY DATASTORE 
func ProxyDatastoreKeys(key string) []byte
func ProxyDataStoreGet(key string) []byte
func ProxyDataStoreExists(key string) bool
func ProxyDataStorePttl(key string) uint64
func ProxyDataStoreSetnx(value []byte)
func ProxyDataStoreDel(keys ...string)
func ProxyDataStoreIncrby(key string, incr int)
func ProxyDataStorePexpire(key string, pttl int64)
func ProxyDataStoreAllMatching(key string) []byte
// cluster config
func ClusterState() []byte
func ClusterStateField(field string) []byte
// proxy state
func ProxyState() []byte
func ProxyStateEntities(entity string) []byte
func ProxyStateEntity(entity string, id string) []byte
// logs
func Log(logLevel LogLevel, message string) Status
func LogBool(logLevel LogLevel, message bool) Status
func LogLong(logLevel LogLevel, message uint64) Status
func LogBytes(logLevel LogLevel, message []byte) Status
func LogEvent(function string, message string, level LogLevel) Status