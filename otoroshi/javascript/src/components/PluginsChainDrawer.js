import React, { useEffect, useRef, useState } from 'react';
import snakeCase from 'lodash/snakeCase';
import Designer from '../pages/RouteDesigner/Designer';
import { Help } from './inputs/Help';

const WIDTH_KEY = 'plugins_chain_drawer_width';
const MIN_WIDTH = 600;

function clampWidth(width) {
  return Math.min(Math.max(width, MIN_WIDTH), Math.round(window.innerWidth * 0.95));
}

function initialWidth() {
  try {
    const stored = parseInt(localStorage.getItem(WIDTH_KEY), 10);
    if (!isNaN(stored)) return clampWidth(stored);
  } catch (err) {}
  return Math.round(window.innerWidth * 0.75);
}

function shortName(pluginId) {
  return (pluginId || '')
    .split('.')
    .slice(-1)[0]
    .replace(/([a-z])([A-Z])/g, '$1 $2');
}

// the designer hands back its own nodeId and CamelCase plugin_index keys, both internal to it
function toInstance({ nodeId, ...plugin }) {
  if (!plugin.plugin_index) return plugin;

  return {
    ...plugin,
    plugin_index: Object.fromEntries(
      Object.entries(plugin.plugin_index).map(([key, v]) => [snakeCase(key), v])
    ),
  };
}

// Edits an array of NgPluginInstance with the route designer, in a resizable right drawer. Reusable
// wherever a plugin chain lives outside of a route: apikeys, api plans, ...
export function PluginsChainDrawer({
  value,
  onChange,
  entityId,
  rawValue,
  label = 'Plugins',
  help,
}) {
  const [open, setOpen] = useState(false);
  const [width, setWidth] = useState(initialWidth);

  const panelRef = useRef();
  const widthRef = useRef(width);

  // the legacy Form hands over {} when the value is still undefined
  const slots = Array.isArray(value) ? value : [];

  useEffect(() => {
    if (!open) return;

    const onKeyDown = (e) => {
      if (e.key === 'Escape') setOpen(false);
    };

    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [open]);

  const startResize = () => {
    document.body.style.userSelect = 'none';

    const onMouseMove = (e) => {
      widthRef.current = clampWidth(window.innerWidth - e.clientX);
      setWidth(widthRef.current);
    };

    const onMouseUp = () => {
      document.removeEventListener('mousemove', onMouseMove);
      document.removeEventListener('mouseup', onMouseUp);
      document.body.style.userSelect = '';

      try {
        localStorage.setItem(WIDTH_KEY, String(widthRef.current));
      } catch (err) {}
    };

    document.addEventListener('mousemove', onMouseMove);
    document.addEventListener('mouseup', onMouseUp);
  };

  // the designer throws on a plugin without config, and keys its collapsed steps on the entity id
  const designerValue = () => ({
    id: entityId || rawValue?.clientId || 'plugins-chain',
    plugins: slots.map((plugin) => ({ ...plugin, config: plugin.config || {} })),
  });

  return (
    <>
      <div className="row mb-3">
        <label className="col-xs-12 col-sm-2 col-form-label">
          {label} <Help text={help} />
        </label>
        <div className="col-sm-10">
          {slots.length === 0 ? (
            <p style={{ opacity: 0.6 }}>No plugin in this chain</p>
          ) : (
            <ol style={{ listStyle: 'none', paddingLeft: 0 }}>
              {slots.map((plugin, idx) => (
                <li key={idx}>
                  <span style={{ opacity: 0.5 }}>{idx + 1}.</span> {shortName(plugin.plugin)}
                  {plugin.plugin && !plugin.enabled && (
                    <span
                      className="badge bg-secondary"
                      style={{ marginLeft: 8, fontWeight: 'normal' }}
                    >
                      disabled
                    </span>
                  )}
                  {plugin.plugin && plugin.debug && (
                    <span
                      className="badge bg-warning"
                      style={{ marginLeft: 8, fontWeight: 'normal' }}
                    >
                      debug
                    </span>
                  )}
                </li>
              ))}
            </ol>
          )}
          <button type="button" className="btn btn-sm btn-primary" onClick={() => setOpen(true)}>
            <i className="fas fa-edit me-1" /> Edit plugins ({slots.length})
          </button>
        </div>
      </div>
      {open && (
        <div
          className="wizard plugins-chain-drawer"
          onMouseDown={(e) => {
            if (e.target === e.currentTarget) setOpen(false);
          }}
        >
          <div className="plugins-chain-drawer-handle" onMouseDown={startResize} />
          <div
            ref={panelRef}
            className="wizard-container"
            style={{ width }}
            onClick={(e) => e.stopPropagation()}
          >
            <label style={{ fontSize: '1.15rem', marginBottom: '1rem' }}>
              <i
                className="fas fa-times me-3"
                onClick={() => setOpen(false)}
                style={{ cursor: 'pointer' }}
              />
              <span>{label}</span>
            </label>
            <div className="designer">
              <Designer
                chainOnly
                getScrollContainer={() => panelRef.current}
                value={designerValue()}
                setValue={(route) => onChange((route.plugins || []).map(toInstance))}
                setSaveButton={() => {}}
              />
            </div>
          </div>
        </div>
      )}
    </>
  );
}

export default PluginsChainDrawer;
