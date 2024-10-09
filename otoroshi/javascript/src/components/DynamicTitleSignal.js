import React from 'react';
import Thumbtack from './Thumbtack';
import { signal, useSignalValue } from 'signals-react-safe';

export const dynamicTitleContent = signal();

export function DynamicTitleSignal(props) {

  const content = useSignalValue(dynamicTitleContent)

  if (!content) {
    return null;
  }

  if (React.isValidElement(content)) {
    return (
      <div style={{ position: 'relative' }}>
        {content}
      </div>
    );
  }

  return (
    <div style={{ position: 'relative' }}>
      <div className="page-header">
        <h3 className="page-header_title">
          {content}
          <Thumbtack {...props} getTitle={() => content} />
        </h3>
      </div>
    </div>
  );
}
