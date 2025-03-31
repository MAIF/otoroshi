import React from 'react';
import { SquareButton } from '../SquareButton';

export function YAMLExportButton({ value, entityKind }) {
  return (
    <SquareButton
      onClick={() => {
        const itemName = entityKind ? entityKind.toLowerCase() : 'route';
        const kind = entityKind || 'Route';
        const name = value.id
          .replace(/ /g, '-')
          .replace(/\(/g, '')
          .replace(/\)/g, '')
          .toLowerCase();

        fetch('/bo/api/json_to_yaml', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({
            apiVersion: 'proxy.otoroshi.io/v1',
            kind,
            metadata: {
              name,
            },
            spec: value,
          }),
        })
          .then((r) => r.text())
          .then((yaml) => {
            const blob = new Blob([yaml], { type: 'application/yaml' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.id = String(Date.now());
            a.style.display = 'none';
            a.download = `${itemName}-${name}-${Date.now()}.yaml`;
            a.href = url;
            document.body.appendChild(a);
            a.click();
            setTimeout(() => document.body.removeChild(a), 300);
          });
      }}
      icon="fa-file-export"
      text="Export YAML"
    />
  );
}
