
use std::collections::HashMap;
use base64::{engine::general_purpose, Engine};
use extism_pdk::FromBytes;
use serde::{Deserialize, Serialize};

#[allow(dead_code)]
extern "C" {
  ///
  fn proxy_log(logLevel: i32, message: u64, size: u64) -> i32;
  fn proxy_log_event(context: u64, size: u64) -> u64;
  /// 
  fn proxy_http_call(context: u64, size: u64) -> u64;
  /// 
  fn proxy_state(unused: u64) -> u64;
  fn proxy_state_value(context: u64, size: u64) -> u64;
  /// 
  fn proxy_cluster_state(unused: u64) -> u64;
  fn proxy_cluster_state_value(context: u64, size: u64) -> u64;
  /// 
  fn proxy_global_config(unused: u64) -> u64;
  /// 
  fn proxy_config(unused: u64) -> u64;
  /// 
  fn proxy_datastore_keys(context: u64, size: u64) -> u64;
  fn proxy_datastore_get(context: u64, size: u64) -> u64;
  fn proxy_datastore_exists(context: u64, size: u64) -> u64;
  fn proxy_datastore_pttl(context: u64, size: u64) -> u64;
  fn proxy_datastore_set(context: u64, size: u64) -> u64;
  fn proxy_datastore_setnx(context: u64, size: u64) -> u64;
  fn proxy_datastore_del(context: u64, size: u64) -> u64;
  fn proxy_datastore_incrby(context: u64, size: u64) -> u64;
  fn proxy_datastore_pexpire(context: u64, size: u64) -> u64;
  fn proxy_datastore_all_matching(context: u64, size: u64) -> u64;
  /// 
  fn proxy_plugin_datastore_keys(context: u64, size: u64) -> u64;
  fn proxy_plugin_datastore_get(context: u64, size: u64) -> u64;
  fn proxy_plugin_datastore_exists(context: u64, size: u64) -> u64;
  fn proxy_plugin_datastore_pttl(context: u64, size: u64) -> u64;
  fn proxy_plugin_datastore_set(context: u64, size: u64) -> u64;
  fn proxy_plugin_datastore_setnx(context: u64, size: u64) -> u64;
  fn proxy_plugin_datastore_del(context: u64, size: u64) -> u64;
  fn proxy_plugin_datastore_incrby(context: u64, size: u64) -> u64;
  fn proxy_plugin_datastore_pexpire(context: u64, size: u64) -> u64;
  fn proxy_plugin_datastore_all_matching(context: u64, size: u64) -> u64;
  /// 
  fn proxy_plugin_map_del(context: u64, size: u64) -> u64;
  fn proxy_plugin_map_set(context: u64, size: u64) -> u64;
  fn proxy_plugin_map_get(context: u64, size: u64) -> u64;
  fn proxy_plugin_map(unused: u64) -> u64;
  ///
  fn proxy_global_map_del(context: u64, size: u64) -> u64;
  fn proxy_global_map_set(context: u64, size: u64) -> u64;
  fn proxy_global_map_get(context: u64, size: u64) -> u64;
  fn proxy_global_map(unused: u64) -> u64;
  ///
  fn proxy_get_attrs(unused: u64) -> u64;
  fn proxy_clear_attrs(unused: u64) -> u64;
  fn proxy_get_attr(context: u64, size: u64) -> u64;
  fn proxy_set_attr(context: u64, size: u64) -> u64;
  fn proxy_del_attr(context: u64, size: u64) -> u64;
}

pub struct Otoroshi {}

#[allow(dead_code)]
impl Otoroshi {

  /////// utils ///////

  fn find_memory(offset: u64) -> Option<extism_pdk::Memory> {
    let length = unsafe { extism_pdk::bindings::extism_length(offset) };
    if length == 0 {
      return None;
    }
    Some(extism_pdk::Memory {
      offset,
      length,
      free: false,
    })
  }

  fn allocate_string(s: &str) -> extism_pdk::Memory {
    let mut mem_in = extism_pdk::Memory::new(s.len());
    mem_in.store(s);
    mem_in
  }

  fn get_u64_from_ptr(ptr: u64) -> u64 {
    ptr
  }

  fn get_bool_from_ptr(ptr: u64) -> bool {
    if ptr > 0 {
      true
    } else {
      false
    }
  }

  fn get_string_from_ptr(ptr: u64) -> Option<String> {
    match Self::find_memory(ptr) {
      None => None,
      Some(mem_out) => {
        match  mem_out.to_string() {
          Err(_e) => None,
          Ok(json_str) => Some(json_str)
        }
      }
    }
  }

  fn get_bytes_from_ptr(ptr: u64) -> Option<Vec<u8>> {
    match Self::find_memory(ptr) {
      None => None,
      Some(mem_out) => Some(mem_out.to_vec())
    }
  }

  fn get_json_from_ptr(ptr: u64) -> Option<serde_json::Value> {
    match Self::get_string_from_ptr(ptr) {
      None => None,
      Some(json_str) => {
        let json: serde_json::Value = serde_json::from_str(&json_str).unwrap();
        Some(json)
      }
    }
  }

  fn get_json_array_from_ptr(ptr: u64) -> Option<Vec<serde_json::Value>> {
    match Self::get_json_from_ptr(ptr) {
      None => None,
      Some(json) => json.as_array().cloned()
    }
  }

  fn get_json_object_from_ptr(ptr: u64) -> Option<serde_json::Map<String, serde_json::Value>> {
    match Self::get_json_from_ptr(ptr) {
      None => None,
      Some(json) => json.as_object().cloned()
    }
  }

  fn encode_base64(value: Vec<u8>) -> String {
    let base64_eng: general_purpose::GeneralPurpose = general_purpose::STANDARD_NO_PAD;
    let mut buf = String::new();
    base64_eng.encode_string(value, &mut buf);
    buf
  }

  fn decode_base64(value: String) -> Vec<u8> {
    let base64_eng: general_purpose::GeneralPurpose = general_purpose::STANDARD_NO_PAD;
    let mut buffer = Vec::<u8>::new();
    match base64_eng.decode_vec(value, &mut buffer) {
      Err(e) => {
        Self::log(2, format!("error: {}", e));
        buffer
      },
      Ok(_) => buffer
    }
  }

  fn decode_base64_string(value: String) -> String {
    String::from_utf8(Self::decode_base64(value)).unwrap()
  }

  /////// apis ///////
  
  /// logs

  pub fn log(level: i32, msg: String) {
    let mem = Self::allocate_string(msg.as_str());
    unsafe { proxy_log(level, mem.offset, mem.length) };
  }

  pub fn log_event(level: i32, function: String,  message: String) {
    let obj = serde_json::json!({
      "function": function,
      "message": message,
      "level": level,
    });
    let mem = Self::allocate_string(serde_json::to_string(&obj).unwrap().as_str());
    unsafe { proxy_log_event(mem.offset, mem.length) };
  }
  
  /// static config access api
  
  pub fn static_config() -> Option<serde_json::Map<String, serde_json::Value>> {
    let ptr = unsafe { proxy_config(0) };
    Self::get_json_object_from_ptr(ptr)
  }

  pub fn static_config_at(path: &str) -> Option<serde_json::Value> {
    let ptr = unsafe { proxy_config(0) };
    Self::get_json_from_ptr(ptr).and_then(|c| c.pointer(path).cloned())
  }

  /// global config access api

  pub fn global_config() -> Option<serde_json::Map<String, serde_json::Value>> {
    let ptr = unsafe { proxy_global_config(0) };
    Self::get_json_object_from_ptr(ptr)
  }

  pub fn global_config_at(path: &str) -> Option<serde_json::Value> {
    let ptr = unsafe { proxy_global_config(0) };
    Self::get_json_from_ptr(ptr).and_then(|c| c.pointer(path).cloned())
  }

  /// cluster config access api

  pub fn cluster_config() -> Option<serde_json::Map<String, serde_json::Value>> {
    let ptr = unsafe { proxy_cluster_state(0) };
    Self::get_json_object_from_ptr(ptr)
  }

  pub fn cluster_config_value(path: &str) -> Option<serde_json::Map<String, serde_json::Value>> {
    let mem = Self::allocate_string(path);
    let ptr = unsafe { proxy_cluster_state_value(mem.offset, mem.length) };
    Self::get_json_object_from_ptr(ptr)
  }

  pub fn cluster_config_at(path: &str) -> Option<serde_json::Value> {
    let ptr = unsafe { proxy_cluster_state(0) };
    Self::get_json_from_ptr(ptr).and_then(|c| c.pointer(path).cloned())   
  }

  //// state access api
 
  pub fn state() -> Option<serde_json::Map<String, serde_json::Value>> {
    let ptr = unsafe { proxy_state(0) };
    Self::get_json_object_from_ptr(ptr)
  }

  pub fn state_value(entity: &str, id: &str) -> Option<serde_json::Map<String, serde_json::Value>> {
    let obj = serde_json::json!({
      "entity": entity,
      "id": id,
    });
    let mem = Self::allocate_string(serde_json::to_string(&obj).unwrap().as_str());
    let ptr = unsafe { proxy_state_value(mem.offset, mem.length) };
    Self::get_json_object_from_ptr(ptr)
  }

  pub fn state_at(path: &str) -> Option<serde_json::Value> {
    let ptr = unsafe { proxy_state(0) };
    Self::get_json_from_ptr(ptr).and_then(|c| c.pointer(path).cloned())   
  }

  //// plugin memory api
  
  pub fn plugin_mem() -> Option<HashMap<String, Vec<u8>>> {
    let ptr = unsafe { proxy_plugin_map(0) };
    match Self::get_json_object_from_ptr(ptr) {
      None => None,
      Some(mem) => {
        let mut res: HashMap<String, Vec<u8>> = HashMap::new();
        mem.iter().for_each(|entry| {
          res.insert(entry.0.to_owned(), Self::decode_base64(entry.1.as_str().unwrap().to_owned()));
        });
        Some(res)
      }
    }
  } 

  pub fn plugin_mem_get(key: &str) -> Option<Vec<u8>> {
    let mem = Self::allocate_string(key);
    let ptr = unsafe { proxy_plugin_map_get(mem.offset, mem.length) };
    Self::get_string_from_ptr(ptr).map(|s| Self::decode_base64(s))
  } 

  pub fn plugin_mem_del(key: &str) {
    let mem = Self::allocate_string(key);
    unsafe { proxy_plugin_map_del(mem.offset, mem.length) };
  } 

  pub fn plugin_mem_set(key: &str, value: Vec<u8>) {
    let obj = serde_json::json!({
      "key": key,
      "value": Self::encode_base64(value),
    });
    let mem = Self::allocate_string(serde_json::to_string(&obj).unwrap().as_str());
    unsafe { proxy_plugin_map_set(mem.offset, mem.length) };
  } 

  pub fn plugin_mem_set_string(key: &str, value: String) {
    Self::plugin_mem_set(key, value.into_bytes())
  } 

  pub fn plugin_mem_get_string(key: &str) -> Option<String> {
    Self::plugin_mem_get(key).map(|bytes| String::from_bytes(bytes).unwrap())
  }

  pub fn plugin_mem_set_json(key: &str, value: serde_json::Value) {
    Self::plugin_mem_set_string(key, serde_json::to_string(&value).unwrap())
  } 

  pub fn plugin_mem_get_json(key: &str) -> Option<serde_json::Value> {
    Self::plugin_mem_get_string(key).and_then(|s| {
      match serde_json::from_str(s.as_str()) {
        Err(_e) => None,
        Ok(v) => Some(v)
      }
    })
  }

  pub fn plugin_mem_set_bool(key: &str, value: bool) {
    let mut vec: Vec<u8> = Vec::new();
    if value {
      vec.push(1);
    } else {
      vec.push(0);
    }
    Self::plugin_mem_set(key, vec)
  } 

  pub fn plugin_mem_get_bool(key: &str) -> Option<bool> {
    Self::plugin_mem_get(key).map(|bytes| {
      match bytes.get(0) {
        Some(0) => false,
        Some(1) => true,
        _ => false
      }
    })
  } 

  //// shared memory api
  
  pub fn shared_mem() -> Option<HashMap<String, Vec<u8>>> {
    let ptr = unsafe { proxy_global_map(0) };
    match Self::get_json_object_from_ptr(ptr) {
      None => None,
      Some(mem) => {
        let mut res: HashMap<String, Vec<u8>> = HashMap::new();
        mem.iter().for_each(|entry| {
          res.insert(entry.0.to_owned(), Self::decode_base64(entry.1.as_str().unwrap().to_owned()));
        });
        Some(res)
      }
    }
  } 

  pub fn shared_mem_get(key: &str) -> Option<Vec<u8>> {
    let mem = Self::allocate_string(key);
    let ptr = unsafe { proxy_global_map_get(mem.offset, mem.length) };
    Self::get_string_from_ptr(ptr).map(|s| Self::decode_base64(s))
  } 

  pub fn shared_mem_del(key: &str) {
    let mem = Self::allocate_string(key);
    unsafe { proxy_global_map_del(mem.offset, mem.length) };
  } 

  pub fn shared_mem_set(key: &str, value: Vec<u8>) {
    let obj = serde_json::json!({
      "key": key,
      "value": Self::encode_base64(value),
    });
    let mem = Self::allocate_string(serde_json::to_string(&obj).unwrap().as_str());
    unsafe { proxy_global_map_set(mem.offset, mem.length) };
  } 

  pub fn shared_mem_set_string(key: &str, value: String) {
    Self::shared_mem_set(key, value.into_bytes())
  } 

  pub fn shared_mem_get_string(key: &str) -> Option<String> {
    Self::shared_mem_get(key).map(|bytes| String::from_bytes(bytes).unwrap())
  }

  pub fn shared_mem_set_json(key: &str, value: serde_json::Value) {
    Self::shared_mem_set_string(key, serde_json::to_string(&value).unwrap())
  } 

  pub fn shared_mem_get_json(key: &str) -> Option<serde_json::Value> {
    Self::shared_mem_get_string(key).and_then(|s| {
      match serde_json::from_str(s.as_str()) {
        Err(_e) => None,
        Ok(v) => Some(v)
      }
    })
  }

  pub fn shared_mem_set_bool(key: &str, value: bool) {
    let mut vec: Vec<u8> = Vec::new();
    if value {
      vec.push(1);
    } else {
      vec.push(0);
    }
    Self::shared_mem_set(key, vec)
  } 

  pub fn shared_mem_get_bool(key: &str) -> Option<bool> {
    Self::shared_mem_get(key).map(|bytes| {
      match bytes.get(0) {
        Some(0) => false,
        Some(1) => true,
        _ => false
      }
    })
  } 

  /// datastore access api

  pub fn datastore_set(key: &str, value: Vec<u8>, ttl: Option<u64>) {
    let ttl_str: serde_json::Value = match ttl {
      None => serde_json::Value::Null,
      Some(v) => serde_json::Value::Number(serde_json::Number::from(v))
    };
    let obj = serde_json::json!({
      "key": key,
      "value_base64": Self::encode_base64(value),
      "ttl": ttl_str,
    });
    let mem = Self::allocate_string(serde_json::to_string(&obj).unwrap().as_str());
    unsafe { proxy_datastore_set(mem.offset, mem.length) };
  }
    
  pub fn datastore_setnx(key: &str, value: Vec<u8>, ttl: Option<u64>) {
    let ttl_str: serde_json::Value = match ttl {
      None => serde_json::Value::Null,
      Some(v) => serde_json::Value::Number(serde_json::Number::from(v))
    };
    let obj = serde_json::json!({
      "key": key,
      "value_base64": Self::encode_base64(value),
      "ttl": ttl_str,
    });
    let mem = Self::allocate_string(serde_json::to_string(&obj).unwrap().as_str());
    unsafe { proxy_datastore_setnx(mem.offset, mem.length) };
  }
  
  pub fn datastore_get(key: &str) -> Option<Vec<u8>> {
    let mem = Self::allocate_string(key);
    let ptr = unsafe { proxy_datastore_get(mem.offset, mem.length) };
    Self::get_bytes_from_ptr(ptr)
  }
  
  pub fn datastore_exists(key: &str) -> bool {
    let mem = Self::allocate_string(key);
    let ptr = unsafe { proxy_datastore_exists(mem.offset, mem.length) };
    Self::get_bool_from_ptr(ptr)
  }
  
  pub fn datastore_pttl(key: &str) -> u64 {
    let mem = Self::allocate_string(key);
    let ptr = unsafe { proxy_datastore_pttl(mem.offset, mem.length) };
    Self::get_u64_from_ptr(ptr)
  }

  pub fn datastore_del(keys: Vec<&str>) {
    let obj = serde_json::json!({
      "keys": keys,
    });
    let mem = Self::allocate_string(serde_json::to_string(&obj).unwrap().as_str());
    unsafe { proxy_datastore_del(mem.offset, mem.length) };
  }
  
  pub fn datastore_incrby(key: &str, incr: u64) -> u64 {
    let obj = serde_json::json!({
      "key": key,
      "incr": incr,
    });
    let mem = Self::allocate_string(serde_json::to_string(&obj).unwrap().as_str());
    let ptr = unsafe { proxy_datastore_incrby(mem.offset, mem.length) };
    Self::get_u64_from_ptr(ptr)
  }
  
  pub fn datastore_pexpire(key: &str, ttl: u64) {
    let obj = serde_json::json!({
      "key": key,
      "pttl": ttl,
    });
    let mem = Self::allocate_string(serde_json::to_string(&obj).unwrap().as_str());
    unsafe { proxy_datastore_pexpire(mem.offset, mem.length) };
  }

  pub fn datastore_keys(pattern: &str) -> Vec<String>  {
    let mem = Self::allocate_string(pattern);
    let ptr = unsafe { proxy_datastore_keys(mem.offset, mem.length) };
    match Self::get_json_array_from_ptr(ptr) {
      None => Vec::new(),
      Some(arr) => arr.into_iter().map(|x| x.as_str().unwrap().to_string()).collect()
    }
  }

  pub fn datastore_all_matching(pattern: &str) -> Vec<Vec<u8>> {
    let mem = Self::allocate_string(pattern);
    let ptr = unsafe { proxy_datastore_all_matching(mem.offset, mem.length) };
    match Self::get_json_array_from_ptr(ptr) {
      None => Vec::new(),
      Some(arr) => arr.into_iter().map(|x| Self::decode_base64(x.as_str().unwrap().to_string())).collect()
    }
  }

  pub fn datastore_all_matching_string(pattern: &str) -> Vec<String> {
    Self::datastore_all_matching(pattern).iter().map(|b| String::from_utf8(b.to_vec()).unwrap()).collect()
  }

  pub fn datastore_all_matching_json(pattern: &str) -> Vec<serde_json::Value> {
    Self::datastore_all_matching_string(pattern).iter().map(|s| serde_json::from_str(s).unwrap()).collect()
  }

  /// plugin scoped datastore access api

  pub fn plugin_datastore_set(key: &str, value: Vec<u8>, ttl: Option<u64>) {
    let ttl_str: serde_json::Value = match ttl {
      None => serde_json::Value::Null,
      Some(v) => serde_json::Value::Number(serde_json::Number::from(v))
    };
    let obj = serde_json::json!({
      "key": key,
      "value_base64": Self::encode_base64(value),
      "ttl": ttl_str,
    });
    let mem = Self::allocate_string(serde_json::to_string(&obj).unwrap().as_str());
    unsafe { proxy_plugin_datastore_set(mem.offset, mem.length) };
  }
    
  pub fn plugin_datastore_setnx(key: &str, value: Vec<u8>, ttl: Option<u64>) {
    let ttl_str: serde_json::Value = match ttl {
      None => serde_json::Value::Null,
      Some(v) => serde_json::Value::Number(serde_json::Number::from(v))
    };
    let obj = serde_json::json!({
      "key": key,
      "value_base64": Self::encode_base64(value),
      "ttl": ttl_str,
    });
    let mem = Self::allocate_string(serde_json::to_string(&obj).unwrap().as_str());
    unsafe { proxy_plugin_datastore_setnx(mem.offset, mem.length) };
  }
  
  pub fn plugin_datastore_get(key: &str) -> Option<Vec<u8>> {
    let mem = Self::allocate_string(key);
    let ptr = unsafe { proxy_plugin_datastore_get(mem.offset, mem.length) };
    Self::get_bytes_from_ptr(ptr)
  }
  
  pub fn plugin_datastore_exists(key: &str) -> bool {
    let mem = Self::allocate_string(key);
    let ptr = unsafe { proxy_plugin_datastore_exists(mem.offset, mem.length) };
    Self::get_bool_from_ptr(ptr)
  }
  
  pub fn plugin_datastore_pttl(key: &str) -> u64 {
    let mem = Self::allocate_string(key);
    let ptr = unsafe { proxy_plugin_datastore_pttl(mem.offset, mem.length) };
    Self::get_u64_from_ptr(ptr)
  }

  pub fn plugin_datastore_del(keys: Vec<&str>) {
    let obj = serde_json::json!({
      "keys": keys,
    });
    let mem = Self::allocate_string(serde_json::to_string(&obj).unwrap().as_str());
    unsafe { proxy_plugin_datastore_del(mem.offset, mem.length) };
  }
  
  pub fn plugin_datastore_incrby(key: &str, incr: u64) -> u64 {
    let obj = serde_json::json!({
      "key": key,
      "incr": incr,
    });
    let mem = Self::allocate_string(serde_json::to_string(&obj).unwrap().as_str());
    let ptr = unsafe { proxy_plugin_datastore_incrby(mem.offset, mem.length) };
    Self::get_u64_from_ptr(ptr)
  }
  
  pub fn plugin_datastore_pexpire(key: &str, ttl: u64) {
    let obj = serde_json::json!({
      "key": key,
      "pttl": ttl,
    });
    let mem = Self::allocate_string(serde_json::to_string(&obj).unwrap().as_str());
    unsafe { proxy_plugin_datastore_pexpire(mem.offset, mem.length) };
  }

  pub fn plugin_datastore_keys(pattern: &str) -> Vec<String>  {
    let mem = Self::allocate_string(pattern);
    let ptr = unsafe { proxy_plugin_datastore_keys(mem.offset, mem.length) };
    match Self::get_json_array_from_ptr(ptr) {
      None => Vec::new(),
      Some(arr) => arr.into_iter().map(|x| x.as_str().unwrap().to_string()).collect()
    }
  }

  pub fn plugin_datastore_all_matching(pattern: &str) -> Vec<Vec<u8>> {
    let mem = Self::allocate_string(pattern);
    let ptr = unsafe { proxy_plugin_datastore_all_matching(mem.offset, mem.length) };
    match Self::get_json_array_from_ptr(ptr) {
      None => Vec::new(),
      Some(arr) => arr.into_iter().map(|x| Self::decode_base64(x.as_str().unwrap().to_string())).collect()
    }
  }

  pub fn plugin_datastore_all_matching_string(pattern: &str) -> Vec<String> {
    Self::datastore_all_matching(pattern).iter().map(|b| String::from_utf8(b.to_vec()).unwrap()).collect()
  }

  pub fn plugin_datastore_all_matching_json(pattern: &str) -> Vec<serde_json::Value> {
    Self::datastore_all_matching_string(pattern).iter().map(|s| serde_json::from_str(s).unwrap()).collect()
  }

  /// attrs api
  
  pub fn attrs() -> Option<serde_json::Map<String, serde_json::Value>> {
    let ptr = unsafe { proxy_get_attrs(0) };
    Self::get_json_object_from_ptr(ptr)
  }

  pub fn attrs_get(key: String) -> Option<serde_json::Value> {
    let mem = Self::allocate_string(&key);
    let ptr = unsafe { proxy_get_attr(mem.offset, mem.length) };
    Self::get_json_from_ptr(ptr)
  }

  pub fn attrs_set(key: String, value: serde_json::Value) {
    let obj = serde_json::json!({
      "key": key,
      "value": value,
    });
    let mem = Self::allocate_string(serde_json::to_string(&obj).unwrap().as_str());
    unsafe { proxy_set_attr(mem.offset, mem.length) };
  }

  pub fn attrs_del(key: String) {
    let mem = Self::allocate_string(&key);
    unsafe { proxy_del_attr(mem.offset, mem.length) };
  }

  pub fn attrs_clear() {
    unsafe { proxy_clear_attrs(0) };
  }

  /// http client api

  pub fn http_call(req: OtoroshiHttpRequest) -> Option<OtoroshiHttpResponse> {
    let mem = Self::allocate_string(req.to_json().as_str());
    let ptr = unsafe { proxy_http_call(mem.offset, mem.length) };
    match Self::get_json_from_ptr(ptr) {
      None => None,
      Some(value) => Some(OtoroshiHttpResponse::from_json(value))
    }
  }

}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct OtoroshiHttpRequest {
  pub method: Option<String>,
  pub url: String,
  pub headers: Option<HashMap<String, String>>,
  pub request_timeout: Option<i64>,
  pub follow_redirects: Option<bool>,
  pub query: Option<HashMap<String, String>>,
  pub body_bytes: Option<Vec<u8>>,
  pub body_base64: Option<String>,
  pub body_str: Option<String>,
  pub body_json: Option<serde_json::Value>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct OtoroshiHttpResponse {
  pub status: u32,
  pub headers: Option<HashMap<String, String>>,
  pub body_base64: Option<String>,
}

#[allow(dead_code)]
impl OtoroshiHttpRequest {
  pub fn default() -> OtoroshiHttpRequest {
    OtoroshiHttpRequest {
      url: "https://mirror.otoroshi.io".into(),
      method: Some("GET".into()),
      headers: None,
      request_timeout: Some(5000),
      follow_redirects: Some(true),
      query: None,
      body_bytes: None,
      body_base64: None,
      body_str: None,
      body_json: None,
    }
  }
  pub fn to_json(&self) -> String {
    serde_json::to_string(self).unwrap()
  }
}

#[allow(dead_code)]
impl OtoroshiHttpResponse {
  pub fn from_json(value: serde_json::Value) -> OtoroshiHttpResponse {
    serde_json::from_value(value).unwrap()
  }
  pub fn body_bytes(&self) -> Option<Vec<u8>> {
    match &self.body_base64 {
      None => None,
      Some(body) => Some(Otoroshi::decode_base64(body.to_string()))
    }
  }
  pub fn body_str(&self) -> Option<String> {
    match &self.body_base64 {
      None => None,
      Some(body) => Some(Otoroshi::decode_base64_string(body.to_string()))
    }
  }
  pub fn body_json(&self) -> Option<serde_json::Value> {
    match &self.body_base64 {
      None => None,
      Some(body) => {
        match serde_json::from_slice(&Otoroshi::decode_base64(body.to_string())) {
          Err(_e) => None,
          Ok(v) => Some(v)
        }
      }
    }
  }
}