import React, { useEffect, useRef, useState } from 'react';

import AceEditor from 'react-ace';
import { PillButton } from '../components/PillButton';

import 'ace-builds/src-noconflict/mode-json';
import 'ace-builds/src-noconflict/mode-yaml';

import * as BackOfficeServices from '../services/BackOfficeServices';
import { SimpleBooleanInput } from '../components/inputs';

const EXAMPLE = `// a JSON object
{
    "kind": "events.otoroshi.io/DataExporter",
    ...
}

// a JsArray
[
    {
        "kind": "events.otoroshi.io/DataExporter",
        ...
    },
    {
        "kind": "proxy.otoroshi.io/ServiceDescriptor",
        ...
    }
]

// YAML resources
kind: events.otoroshi.io/DataExporter
...

---
kind: proxy.otoroshi.io/ServiceDescriptor
...
`;

const UPDATE_ENTITIES = {
  ApiKey: (content) => BackOfficeServices.createRawApiKey(content),
  ServiceDescriptor: (content) => BackOfficeServices.saveService(content),
  DataExporter: (content) => BackOfficeServices.createDataExporterConfig(content),
  ServiceGroup: (content) => BackOfficeServices.createGroup(content),
  Certificate: (content) => BackOfficeServices.createCertificate(content),
  Tenant: (content) => BackOfficeServices.createTenant(content),
  GlobalConfig: (content) => BackOfficeServices.updateGlobalConfig(content),
  Team: (content) => BackOfficeServices.createTeam(content),
  TcpService: (content) => BackOfficeServices.createTcpService(content),
  AuthModule: (content) => BackOfficeServices.createAuthConfig(content),
  JwtVerifier: (content) => BackOfficeServices.createJwtVerifier(content),
  ClientValidator: (content) => BackOfficeServices.createClientValidator(content),
  Script: (content) => BackOfficeServices.createScript(content),
  ErrorTemplate: (content) => BackOfficeServices.createTemplate(content),
  Route: (content) =>
    BackOfficeServices.nextClient.create(BackOfficeServices.nextClient.ENTITIES.ROUTES, content),
  Backend: (content) =>
    BackOfficeServices.nextClient.create(BackOfficeServices.nextClient.ENTITIES.BACKENDS, content),
  WasmPlugin: (content) =>
    BackOfficeServices.nextClient.forEntityNext('wasm-plugins').create(content),
  Generic: (content, group, kind) => {
    BackOfficeServices.nextClient.forEntityNextWithGroup(group, kind).create(content)
  }
};

export function ResourceLoaderPage({ setTitle }) {
  const [exportAll, setExportAll] = useState(true);
  const [format, setFormat] = useState('json');
  const [rawResources, setRawResources] = useState('');

  const [loadedResources, setLoadedResources] = useState([]);

  const aceRef = useRef();

  useEffect(() => {
    setTitle('Resources loader');
  }, []);

  const loadResources = () => {
    BackOfficeServices.createResources(rawResources).then((res) => {
      if (Array.isArray(res.created))
        setLoadedResources(
          res.created.map((r) => {
            r.enabled = true;
            return r;
          })
        );
      else
        setLoadedResources(
          [res.created || res].map((r) => {
            r.enabled = true;
            return r;
          })
        );
    });
  };

  const setResources = (resources) => {
    // if (resources) {
    //   if (resources.trim().startsWith('[') || resources.trim().startsWith('{')) {
    //     if (format !== 'json') {
    //       setFormat('json');
    //     }
    //   } else {
    //     if (format !== 'yaml') {
    //       setFormat('yaml');
    //     }
    //   }
    // }
    setRawResources(resources);
  };

  const onDrop = (ev) => {
    ev.preventDefault();
    onDragLeave();
    if (ev.dataTransfer.items) {
      for (let i = 0; i < ev.dataTransfer.items.length; i++) {
        if (ev.dataTransfer.items[i].kind === 'file') {
          const file = ev.dataTransfer.items[i].getAsFile();
          file.text().then(setRawResources);
        }
      }
    } else {
      for (let i = 0; i < ev.dataTransfer.files.length; i++) {
        const file = ev.dataTransfer.files[i];
        file.text().then(setRawResources);
      }
    }
  };

  const onDragOver = (ev) => {
    ev.preventDefault();
    const editor = document.getElementById('resources-loader');
    editor.classList.add('dragEffect');
  };
  const onDragLeave = (ev) => {
    if (ev) ev.preventDefault();
    const editor = document.getElementById('resources-loader');
    editor.classList.remove('dragEffect');
  };

  if (loadedResources.length > 0) {
    return (
      <div style={{ width: '100%' }}>
        <table className="table table-striped table-hover">
          <thead style={{ backgroundColor: 'inherit' }}>
            <tr>
              <th scope="col">Resource name</th>
              <th scope="col">Resource type</th>
              <th scope="col">To import</th>
              <th scope="col">Status</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td></td>
              <td></td>
              <td>
                {loadedResources.find((f) => !f.error) && loadedResources.length > 1 && (
                  <SimpleBooleanInput
                    value={exportAll}
                    onChange={(v) => {
                      setLoadedResources(
                        loadedResources.map((r) => {
                          r.enabled = v;
                          return r;
                        })
                      );
                      setExportAll(!exportAll);
                    }}
                  />
                )}
              </td>
              <td></td>
            </tr>
            {loadedResources.map((resource, i) => (
              <tr key={`resource${i}`}>
                <td>
                  {resource.name ||
                    resource.clientName ||
                    (resource.resource ? resource.resource.name : null) ||
                    (resource.resource ? resource.resource.clientName : null) ||
                    'Unknown'}
                </td>
                <td>
                  <span
                    className="badge"
                    style={{
                      padding: '0 12 0 12',
                      backgroundColor: resource.error ? 'var(--color-red)' : '#eee',
                      color: '#000',
                    }}
                  >
                    {resource.error || resource.kind}
                  </span>
                </td>
                <td>
                  {!resource.error && (
                    <SimpleBooleanInput
                      value={resource.enabled}
                      onChange={(v) =>
                        setLoadedResources(
                          loadedResources.map((r, j) => {
                            if (i === j) r.enabled = v;
                            return r;
                          })
                        )
                      }
                    />
                  )}
                </td>
                <td>
                  <span
                    className="badge"
                    style={{ backgroundColor: resource.status ? 'var(--color-primary)' : '' }}
                  >
                    {resource.status}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="d-flex justify-content-end">
          <button className="btn btn-danger" onClick={() => setLoadedResources([])}>
            Cancel
          </button>
          {loadedResources.find((f) => !f.error) && (
            <button
              type="button"
              className="btn btn-success ms-2"
              onClick={() => {
                Promise.all(
                  loadedResources
                    .filter((r) => r.enabled)
                    .map((resource) => {
                      const content = resource.resource;
                      const k = resource.kind || '';
                      if (k.indexOf('/') > -1) {
                        const parts = k.split("/");
                        const group = parts[0];
                        const kind = parts[1]; // TODO: find in meta stuff
                        return UPDATE_ENTITIES.Generic(content, group, kind);
                      } else {
                        return UPDATE_ENTITIES[k](content);
                      }
                    })
                ).then(() => {
                  setLoadedResources(
                    loadedResources
                      .filter((r) => r.enabled)
                      .map((r) => {
                        if (!r.error) return { ...r, status: 'done' };
                        return r;
                      })
                  );
                });
              }}
            >
              Import selected resources
            </button>
          )}
        </div>
      </div>
    );
  }

  return (
    <div class="container">
      <div className="mb-3 d-flex justify-content-between">
        <div>
          <PillButton
            rightEnabled={format === 'yaml' ? false : true}
            leftText="JSON"
            rightText="YAML"
            onChange={() => (format === 'yaml' ? setFormat('json') : setFormat('yaml'))}
          />
          {/* <button
            type="button"
            onClick={() => setFormat('json')}
            className={`btn btn-sm btn-${format === 'json' ? 'success' : 'secondary'}`}>
            JSON
          </button>
          <button
            type="button"
            onClick={() => setFormat('yaml')}
            className={`ms-2 btn btn-sm btn-${format === 'yaml' ? 'success' : 'secondary'}`}>
            YAML
          </button> */}
        </div>
        <button
          type="button"
          disabled={rawResources.length <= 0}
          className="btn btn-success"
          onClick={loadResources}
        >
          Load resources
        </button>
      </div>
      <div className="mb-3">
        <div className="row">
          <div
            className="col-sm-8"
            style={{ paddingRight: 0 }}
            onDrop={onDrop}
            onDragOver={onDragOver}
            onDragLeave={onDragLeave}
          >
            <AceEditor
              ref={aceRef}
              name="resources-loader"
              mode={format}
              theme="monokai"
              onChange={setResources}
              value={rawResources}
              editorProps={{ $blockScrolling: true }}
              width="100%"
              height="440px"
              showGutter={true}
              highlightActiveLine={true}
              tabSize={2}
              enableBasicAutocompletion={true}
              placeholder="Write, paste or drag your text here"
            />
          </div>
          <div className="col-sm-4" style={{ paddingLeft: 1 }}>
            <div
              style={{
                height: '30px',
                textAlign: 'center',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                backgroundColor: 'var(--color-primary)',
                color: '#fff',
                fontStyle: 'italic',
              }}
            >
              Example
            </div>
            <AceEditor
              mode="json"
              theme="monokai"
              readOnly={true}
              showGutter={false}
              value={EXAMPLE}
              name="example"
              height="410px"
              width="100%"
              tabSize={2}
            />
          </div>
        </div>
      </div>
    </div>
  );
}
