import React from 'react';

export default function SimpleLoader() {
  return (
    <div className="d-flex justify-content-center">
      <i className="fas fa-cog fa-spin" style={{ fontSize: '30px' }} />
    </div>
  );
}
