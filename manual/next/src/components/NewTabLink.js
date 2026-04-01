import React from 'react';

export function NewTabLink({ children, ...props }) {
  return (
    <a {...props} target={"_blank"} >{children}</a>
  );
}