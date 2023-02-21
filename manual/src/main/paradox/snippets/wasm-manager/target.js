// (2)
export function execute() {
    const context = JSON.parse(Host.inputString());

    // (3)
    const headers = {
      "foo": "bar",
      ...(context.request.headers || {})
    }

    // (4)
    const response = {
        headers,
        status: 200,
        body: "{\"foo\": \"bar\"}"
    };
    Host.outputString(JSON.stringify(response));

    return 0;
}