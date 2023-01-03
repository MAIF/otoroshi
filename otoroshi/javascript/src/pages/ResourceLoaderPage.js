import React, { useEffect, useRef, useState } from 'react';

import AceEditor from 'react-ace';

import 'ace-builds/src-noconflict/mode-json';
import 'ace-builds/src-noconflict/mode-yaml';

import * as BackOfficeServices from '../services/BackOfficeServices';
import { SimpleBooleanInput } from '../components/inputs';

const EXAMPLE = `// a JSON object
{
    "kind": "DataExporter",
    ...
}

// a JsArray
[
    {
        "kind": "DataExporter",
        ...
    },
    {
        "kind": "ServiceDescriptor",
        ...
    }
]

// YAML resources
kind: DataExporter
...

---
kind: ServiceDescriptor
...
`;

const UPDATE_ENTITIES = {
  ApiKey: (content) => BackOfficeServices.updateRawApiKey(content),
  ServiceDescriptor: (content) => BackOfficeServices.updateRawService(content),
  DataExporter: (content) => BackOfficeServices.updateDataExporterConfig(content),
  ServiceGroup: (content) => BackOfficeServices.updateGroup(content),
  Certificate: (content) => BackOfficeServices.updateCertificate(content),
  Tenant: (content) => BackOfficeServices.updateTenant(content),
  GlobalConfig: (content) => BackOfficeServices.updateGlobalConfig(content),
  Team: (content) => BackOfficeServices.updateTeam(content),
  TcpService: (content) => BackOfficeServices.updateTcpService(content),
  AuthModule: (content) => BackOfficeServices.updateAuthConfig(content),
  JwtVerifier: (content) => BackOfficeServices.updateJwtVerifier(content),
  ClientValidator: (content) => BackOfficeServices.updateClientValidator(content),
  Script: (content) => BackOfficeServices.updateScript(content),
  ErrorTemplate: (content) => BackOfficeServices.updateTemplate(content),
  Route: (content) =>
    BackOfficeServices.nextClient.update(BackOfficeServices.nextClient.ENTITIES.ROUTES, content),
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
    if (resources) {
      if (resources.trim().startsWith('[') || resources.trim().startsWith('{')) {
        if (format !== 'json') {
          setFormat('json');
        }
      } else {
        if (format !== 'yaml') {
          setFormat('yaml');
        }
      }
    }
    setRawResources(resources);
  };

  const onDrop = (ev) => {
    ev.preventDefault();
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
                      backgroundColor: resource.error ? 'red' : '#eee',
                      color: '#000',
                    }}>
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
                    style={{ backgroundColor: resource.status ? '#f9b000' : '' }}>
                    {resource.status}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <div style={{ width: '100%', display: 'flex', justifyContent: 'flex-end' }}>
          <div className="btn-group">
            <button className="btn btn-danger" onClick={() => setLoadedResources([])}>
              Cancel
            </button>
            {loadedResources.find((f) => !f.error) && (
              <button
                type="button"
                className="btn btn-success"
                onClick={() => {
                  Promise.all(
                    loadedResources
                      .filter((r) => r.enabled)
                      .map((resource) => {
                        const content = resource.resource;
                        const k = resource.kind;
                        return UPDATE_ENTITIES[k](content);
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
                }}>
                Import selected resources
              </button>
            )}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div>
      <div className="mb-3">
        <button
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
        </button>
      </div>
      <div className="mb-3">
        <div className="row">
          <div
            className="col-sm-8"
            style={{ paddingRight: 0 }}
            onDrop={onDrop}
            onDragOver={(e) => e.preventDefault()}>
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
                backgroundColor: '#f9b000',
                color: '#fff',
                fontStyle: 'italic',
              }}>
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
      <button
        type="button"
        className="btn btn-success"
        style={{ marginTop: 12 }}
        onClick={loadResources}>
        Load resources
      </button>
    </div>
  );
}
