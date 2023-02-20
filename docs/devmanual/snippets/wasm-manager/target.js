// (2)
export function execute() {
  let str = Host.inputString();
  let context = JSON.parse(str);
  // (3)
  let headers = {};
  headers["foo"] = "bar";
  // (4)
  let response = {
    headers,
    body: "{\"foo\": \"bar\"}",
    status: 200
  };
  Host.outputString(JSON.stringify(response));
  return 0;
}