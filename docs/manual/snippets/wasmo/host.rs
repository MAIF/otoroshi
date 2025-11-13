/// logs
pub fn log(level: i32, msg: String)
pub fn log_event(level: i32, function: String,  message: String)
/// static config access api
pub fn static_config() -> Option<serde_json::Map<String, serde_json::Value>>
pub fn static_config_at(path: &str) -> Option<serde_json::Value>
/// global config access api
pub fn global_config() -> Option<serde_json::Map<String, serde_json::Value>>
pub fn global_config_at(path: &str) -> Option<serde_json::Value>
/// cluster config access api
pub fn cluster_config() -> Option<serde_json::Map<String, serde_json::Value>>
pub fn cluster_config_value(path: &str) -> Option<serde_json::Map<String, serde_json::Value>> 
pub fn cluster_config_at(path: &str) -> Option<serde_json::Value> 
//// state access api
pub fn state() -> Option<serde_json::Map<String, serde_json::Value>>
pub fn state_value(entity: &str, id: &str) -> Option<serde_json::Map<String, serde_json::Value>>
pub fn state_at(path: &str) -> Option<serde_json::Value>
//// plugin memory api
pub fn plugin_mem() -> Option<HashMap<String, Vec<u8>>>
pub fn plugin_mem_get(key: &str) -> Option<Vec<u8>>
pub fn plugin_mem_del(key: &str)
pub fn plugin_mem_set(key: &str, value: Vec<u8>)
pub fn plugin_mem_set_string(key: &str, value: String)
pub fn plugin_mem_get_string(key: &str) -> Option<String> 
pub fn plugin_mem_set_json(key: &str, value: serde_json::Value)
pub fn plugin_mem_get_json(key: &str) -> Option<serde_json::Value>
pub fn plugin_mem_set_bool(key: &str, value: bool)
pub fn plugin_mem_get_bool(key: &str) -> Option<bool>
//// shared memory api
pub fn shared_mem() -> Option<HashMap<String, Vec<u8>>>
pub fn shared_mem_get(key: &str) -> Option<Vec<u8>>
pub fn shared_mem_del(key: &str)
pub fn shared_mem_set(key: &str, value: Vec<u8>)
pub fn shared_mem_set_string(key: &str, value: String)
pub fn shared_mem_get_string(key: &str) -> Option<String>
pub fn shared_mem_set_json(key: &str, value: serde_json::Value)
pub fn shared_mem_get_json(key: &str) -> Option<serde_json::Value>
pub fn shared_mem_set_bool(key: &str, value: bool)
pub fn shared_mem_get_bool(key: &str) -> Option<bool>
/// datastore access api
pub fn datastore_set(key: &str, value: Vec<u8>, ttl: Option<u64>)
pub fn datastore_setnx(key: &str, value: Vec<u8>, ttl: Option<u64>)
pub fn datastore_get(key: &str) -> Option<Vec<u8>>
pub fn datastore_exists(key: &str) -> bool
pub fn datastore_pttl(key: &str) -> u64
pub fn datastore_del(keys: Vec<&str>)
pub fn datastore_incrby(key: &str, incr: u64) -> u64
pub fn datastore_pexpire(key: &str, ttl: u64)
pub fn datastore_keys(pattern: &str) -> Vec<String>
pub fn datastore_all_matching(pattern: &str) -> Vec<Vec<u8>>
pub fn datastore_all_matching_string(pattern: &str) -> Vec<String>
pub fn datastore_all_matching_json(pattern: &str) -> Vec<serde_json::Value> 
/// plugin scoped datastore access api
pub fn plugin_datastore_set(key: &str, value: Vec<u8>, ttl: Option<u64>)
pub fn plugin_datastore_setnx(key: &str, value: Vec<u8>, ttl: Option<u64>)
pub fn plugin_datastore_get(key: &str) -> Option<Vec<u8>>
pub fn plugin_datastore_exists(key: &str) -> bool
pub fn plugin_datastore_pttl(key: &str) -> u64
pub fn plugin_datastore_del(keys: Vec<&str>)
pub fn plugin_datastore_incrby(key: &str, incr: u64) -> u64
pub fn plugin_datastore_pexpire(key: &str, ttl: u64)
pub fn plugin_datastore_keys(pattern: &str) -> Vec<String>
pub fn plugin_datastore_all_matching(pattern: &str) -> Vec<Vec<u8>>
pub fn plugin_datastore_all_matching_string(pattern: &str) -> Vec<String>
pub fn plugin_datastore_all_matching_json(pattern: &str) -> Vec<serde_json::Value>
/// http client api
pub fn http_call(req: OtoroshiHttpRequest) -> Option<OtoroshiHttpResponse>