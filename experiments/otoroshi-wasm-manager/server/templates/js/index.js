export function execute() {
    let context = JSON.parse(Host.inputString());
  
      if (context.request.headers["foo"] === "bar") {
          const out = {
              result: true
          };
          Host.outputString(JSON.stringify(out));
      } else {
          const error = {
              result: false,
              error: {
                  message: "you're not authorized",
                  status: 401
              }
          };
          Host.outputString(JSON.stringify(error));
      }
  
      return 0;
  }