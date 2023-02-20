export function execute() {
  let str = Host.inputString();
  let context = JSON.parse(str);
  let fooHeader = context.request.headers['foo'];
  if (fooHeader) {
    if (fooHeader === "bar") {
      Host.outputString(JSON.stringify({ result: true, error: null }));
    } else {
      Host.outputString(JSON.stringify({ result: false, error: { status: 401, message: `${fooHeader} is not authorized` }}));
    }
  } else {
    Host.outputString(JSON.stringify({ result: false, error: { status: 401, message: `you're not authorized` }}));
  }
  return 0;
}