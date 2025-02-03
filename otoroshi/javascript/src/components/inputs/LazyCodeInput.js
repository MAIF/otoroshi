const React = require("react");

const CodeInput = React.lazy(() => Promise.resolve(require('./CodeInput')));

export function LazyCodeInput(props) {
  return <CodeInput {...props} />;
}
