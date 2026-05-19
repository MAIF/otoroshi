import React from 'react';

// Read-only renderer for an nginputs schema + flow. Walks the flow, maps each
// field to a label → value line by its `type`, recurses into nested `form`
// fields and `array` fields, and falls back to a JSON dump for custom
// `renderer:` fields or any type it doesn't know how to summarise.
//
// Used for the production (non-draft) view of config tabs, so the user sees a
// synthesis of the published config instead of a form full of inputs that
// can't be saved.

// Scalar types we format directly. `form` / `group` recurse; anything else
// (or a custom renderer) hits the JSON fallback.
const SCALAR_TYPES = new Set([
  'string',
  'number',
  'bool',
  'box-bool',
  'select',
  'dots',
  'array-select',
  'array',
  'object',
  'code',
  'date',
]);

function fieldLabel(name, field) {
  return field?.label || field?.props?.label || name;
}

// select / dots options come as ['a', 'b'] or [{ label, value }].
function optionLabel(field, value) {
  const options = field?.props?.options || field?.options;
  if (Array.isArray(options)) {
    const match = options.find((o) => (o && typeof o === 'object' ? o.value : o) === value);
    if (match) return typeof match === 'object' ? (match.label ?? match.value) : match;
  }
  return value;
}

function formatScalar(field, value) {
  if (value === undefined || value === null || value === '') return '—';
  switch (field.type) {
    case 'bool':
    case 'box-bool':
      return value ? 'Yes' : 'No';
    case 'select':
    case 'dots':
      return String(optionLabel(field, value));
    case 'object': {
      const entries = Object.entries(value || {});
      return entries.length ? entries.map(([k, v]) => `${k}: ${v}`).join('  ·  ') : '—';
    }
    case 'code':
      return typeof value === 'string' ? value : JSON.stringify(value, null, 2);
    default:
      return String(value);
  }
}

function resolveFlow(flow, value) {
  const resolved = typeof flow === 'function' ? flow(value) : flow;
  return Array.isArray(resolved) ? resolved : [];
}

function FieldRow({ label, children }) {
  return (
    <div className="schema-ro-row">
      <div className="schema-ro-label">{label}</div>
      <div className="schema-ro-value">{children}</div>
    </div>
  );
}

function JsonValue({ value }) {
  return <pre className="schema-ro-json">{JSON.stringify(value ?? null, null, 2)}</pre>;
}

function SchemaField({ name, field, value }) {
  if (!field) return null;
  const label = fieldLabel(name, field);

  // Custom renderer — we can't introspect arbitrary JSX. Skip pure-display
  // renderers (no backing value), dump the value otherwise.
  if (field.renderer) {
    if (value === undefined || value === null) return null;
    const isPrimitive = typeof value !== 'object';
    return <FieldRow label={label}>{isPrimitive ? String(value) : <JsonValue value={value} />}</FieldRow>;
  }

  // Array — of sub-forms when a schema is attached, of primitives otherwise.
  // `array: true` takes precedence over the declared `type`.
  if (field.array || field.type === 'array' || field.type === 'array-select') {
    const arr = Array.isArray(value) ? value : [];
    if (field.schema) {
      return (
        <div className="schema-ro-section">
          <div className="schema-ro-section-title">{label}</div>
          {arr.length === 0 && <div className="schema-ro-empty">— empty —</div>}
          {arr.map((item, i) => (
            <div className="schema-ro-item" key={i}>
              <div className="schema-ro-item-index">#{i + 1}</div>
              <SchemaBody schema={field.schema} flow={field.flow} value={item || {}} />
            </div>
          ))}
        </div>
      );
    }
    return <FieldRow label={label}>{arr.length ? arr.join(', ') : '—'}</FieldRow>;
  }

  // Nested form / group with its own schema.
  if ((field.type === 'form' || field.type === 'group') && field.schema) {
    return (
      <div className="schema-ro-section">
        <div className="schema-ro-section-title">{label}</div>
        <SchemaBody schema={field.schema} flow={field.flow} value={value || {}} />
      </div>
    );
  }

  // Known scalar — or an unknown type, dumped as JSON.
  if (field.type && !SCALAR_TYPES.has(field.type)) {
    return (
      <FieldRow label={label}>
        <JsonValue value={value} />
      </FieldRow>
    );
  }
  return <FieldRow label={label}>{formatScalar(field, value)}</FieldRow>;
}

function SchemaBody({ schema, flow, value }) {
  // No flow → every schema key in declaration order.
  const entries = flow ? resolveFlow(flow, value) : Object.keys(schema || {});

  return entries.map((entry, i) => {
    if (typeof entry === 'string') {
      return (
        <SchemaField key={entry} name={entry} field={schema?.[entry]} value={value?.[entry]} />
      );
    }
    // Group / grid flow entry — an object carrying a `fields` array.
    if (entry && Array.isArray(entry.fields)) {
      const groupName = typeof entry.name === 'string' ? entry.name : null;
      return (
        <div className="schema-ro-group" key={i}>
          {groupName && <div className="schema-ro-group-title">{groupName}</div>}
          {entry.fields.map((fn) => (
            <SchemaField key={fn} name={fn} field={schema?.[fn]} value={value?.[fn]} />
          ))}
        </div>
      );
    }
    return null;
  });
}

export function SchemaReadOnlyView({ schema, flow, value }) {
  return (
    <div className="schema-ro">
      <SchemaBody schema={schema} flow={flow} value={value || {}} />
    </div>
  );
}
