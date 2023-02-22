import React from 'react';
import { SquareButton } from '../SquareButton';

export function JsonExportButton({ value, entityKind }) {
  return (
    <SquareButton
      onClick={() => {
        const what = window.location.pathname.split('/')[3];
        const itemName = entityKind ? entityKind.toLowerCase() : (what === 'routes' ? 'route' : 'route-composition');
        const kind = entityKind || (what === 'routes' ? 'Route' : 'RouteComposition');
        const name = value.id
          .replace(/ /g, '-')
          .replace(/\(/g, '')
          .replace(/\)/g, '')
          .toLowerCase();
        const json = JSON.stringify({ ...value, kind }, null, 2);
        const blob = new Blob([json], { type: 'application/json' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.id = String(Date.now());
        a.style.display = 'none';
        a.download = `${itemName}-${name}-${Date.now()}.json`;
        a.href = url;
        document.body.appendChild(a);
        a.click();
        setTimeout(() => document.body.removeChild(a), 300);
      }}
      icon="fas fa-file-export"
      text="Export JSON"
    />
  );
}
