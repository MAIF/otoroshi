
extern "C" {
  fn proxy_log(logLevel: i32, message: u64, size: u64) -> i32;
  fn proxy_log_event(context: i64, size: u64) -> u64;
  fn proxy_http_call(context: i64, size: u64) -> u64;
  fn get_proxy_state(context: i64) -> u64;
  fn get_proxy_state_value(context: i64, size: u64) -> u64;
  fn get_cluster_state(context: i64) -> u64;
  fn get_cluster_state_value(context: i64, size: u64) -> u64;
  fn proxy_datastore_keys(context: u64, size: u64) -> u64;
  fn proxy_datastore_get(context: i64, size: u64) -> u64;
  fn proxy_datastore_exists(context: i64, size: u64) -> u64;
  fn proxy_datastore_pttl(context: i64, size: u64) -> u64;
  fn proxy_datastore_setnx(context: i64, size: u64) -> u64;
  fn proxy_datastore_del(context: i64, size: u64) -> u64;
  fn proxy_datastore_incrby(context: i64, size: u64) -> u64;
  fn proxy_datastore_pexpire(context: i64, size: u64) -> u64;
  fn proxy_datastore_all_matching(context: i64, size: u64) -> u64;
  fn proxy_plugin_datastore_keys(context: i64, size: u64) -> u64;
  fn proxy_plugin_datastore_get(context: i64, size: u64) -> u64;
  fn proxy_plugin_datastore_exists(context: i64, size: u64) -> u64;
  fn proxy_plugin_datastore_pttl(context: i64, size: u64) -> u64;
  fn proxy_plugin_datastore_setnx(context: i64, size: u64) -> u64;
  fn proxy_plugin_datastore_del(context: i64, size: u64) -> u64;
  fn proxy_plugin_datastore_incrby(context: i64, size: u64) -> u64;
  fn proxy_plugin_datastore_pexpire(context: i64, size: u64) -> u64;
  fn proxy_plugin_datastore_all_matching(context: i64, size: u64) -> u64;
  fn proxy_plugin_map_set(context: i64, size: u64) -> u64;
  fn proxy_plugin_map_get(context: i64, size: u64) -> u64;
  fn proxy_plugin_map(unused: u64) -> u64;
  fn proxy_global_map_set(context: i64, size: u64) -> u64;
  fn proxy_global_map_get(context: i64, size: u64) -> u64;
  fn proxy_global_map(unused: u64) -> u64;
}

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
  let mut mem_in = extism_pdk::Memory::new(pattern.len());
  mem_in.store(pattern);
  mem_in
}

fn get_string_from_ptr(ptr: u64) -> Option<&str> {
  match find_memory(res_ptr) {
    None => None,
    Some(mem_out) => {
      match  mem_out.to_string() {
        Err(_e) => None,
        Ok(json_str) => Some(json_str)
      }
    }
  }
}

fn get_json_from_ptr(ptr: u64) -> Option<serde_json::Value> {
  match get_string_from_ptr(ptr) {
    None => None,
    Some(string) => {
      let json: serde_json::Value = serde_json::from_str(&json_str).unwrap();
      json
    }
  }
}

fn get_json_array_from_ptr(ptr: u64) -> Option<serde_json::Value> {
  match get_json_from_ptr(ptr) {
    None => None,
    Some(json) => json.as_array()
  }
}

/////// api   ///////

pub fn otoroshi_proxy_log(level: i32, msg: String) -> i32 {
  let mut mem = extism_pdk::Memory::new(msg.len());
  mem.store(msg);
  unsafe { proxy_log(level, mem.offset, mem.length) }
}

pub fn otoroshi_proxy_datastore_keys(pattern: &str) -> Vec<String>  {
  let mem = allocate_string(pattern);
  let ptr = unsafe { proxy_datastore_keys(mem.offset, mem.length) };
  match get_json_array_from_ptr(ptr) {
    None => Vec::new(),
    Some(arr) => arr.into_iter().map(|x| x.as_str().unwrap().to_string()).collect()
  }
  // let mut mem_in = extism_pdk::Memory::new(pattern.len());
  // mem_in.store(pattern);
  // let res_ptr = unsafe { proxy_datastore_keys(mem_in.offset, mem_in.length) };
  // match find_memory(res_ptr) {
  //   None => Vec::new(),
  //   Some(mem_out) => {
  //     match  mem_out.to_string() {
  //       Err(_e) => Vec::new(),
  //       Ok(json_str) => {
  //         let json: serde_json::Value = serde_json::from_str(&json_str).unwrap();
  //         match json.as_array() {
  //           None => Vec::new(),
  //           Some(arr) => arr.into_iter().map(|x| x.as_str().unwrap().to_string()).collect()
  //         }  
  //       }
  //     }
  //   }
  // }
}
