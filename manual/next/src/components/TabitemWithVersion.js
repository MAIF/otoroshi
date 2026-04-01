import React from 'react';

import TabItem from '@theme/TabItem';

export function TabItemWithVersion({ children, ...props }) {
  return (
    <TabItem {...props}>
      {children}
    </TabItem>
  );
}