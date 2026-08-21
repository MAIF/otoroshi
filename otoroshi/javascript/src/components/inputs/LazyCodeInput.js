import React from 'react';

const CodeInput = React.lazy(() => import('./CodeInput'));

export function LazyCodeInput(props) {
  return <CodeInput {...props} />;
}
