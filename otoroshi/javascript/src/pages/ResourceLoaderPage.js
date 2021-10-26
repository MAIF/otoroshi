import React, { useEffect, useState } from 'react';
import AceEditor from 'react-ace';
import * as BackOfficeServices from '../services/BackOfficeServices';
import 'brace/mode/json';
import 'brace/mode/yaml';
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
`

const UPDATE_ENTITIES = {
    "ApiKey": (content, id) => BackOfficeServices.updateApiKey(id, content),
    "ServiceDescriptor": (content, id) => BackOfficeServices.updateService(id, content),
    "DataExporter": content => BackOfficeServices.updateDataExporterConfig(content),
    "ServiceGroup": content => BackOfficeServices.updateGroup(content),
    "Certificate": content => BackOfficeServices.updateCertificate(content),
    "Tenant": content => BackOfficeServices.updateTenant(content),
    "GlobalConfig": content => BackOfficeServices.updateGlobalConfig(content),
    "Team": content => BackOfficeServices.updateTeam(content),
    "TcpService": content => BackOfficeServices.updateTcpService(content),
    "AuthModule": content => BackOfficeServices.updateAuthConfig(content),
    "JwtVerifier": content => BackOfficeServices.updateJwtVerifier(content),
    "ClientValidator": content => BackOfficeServices.updateClientValidator(content),
    "Script": content => BackOfficeServices.updateScript(content),
    "ErrorTemplate": content => BackOfficeServices.updateTemplate(content)
}

export function ResourceLoaderPage({ setTitle }) {
    const [exportAll, setExportAll] = useState(true);
    const [format, setFormat] = useState("json");
    const [rawResources, setRawResources] = useState("");

    const [loadedResources, setLoadedResources] = useState([]);

    useEffect(() => {
        setTitle('Resources loader');
    }, []);

    const loadResources = () => {
        BackOfficeServices.createResources(rawResources)
            .then(res => {
                if (Array.isArray(res.created))
                    setLoadedResources(res.created.map(r => {
                        r.enabled = true
                        return r;
                    }));
                else
                    setLoadedResources([res.created || res].map(r => {
                        r.enabled = true
                        return r;
                    }));
            });
    }

    if (loadedResources.length > 0) {
        return (
            <div>
                <table>
                    <thead>
                        <tr>
                            <th scope="col" className="col-sm-5">Resource name</th>
                            <th scope="col" className="col-sm-3">Resource type</th>
                            <th scope="col" className="col-sm-2">To import</th>
                            <th scope="col" className="col-sm-2">Imported</th>
                        </tr>
                    </thead>
                    <tbody>
                        <tr>
                            <td className="col-sm-5"></td>
                            <td className="col-sm-3"></td>
                            <td className="col-sm-2">
                                {loadedResources.find(f => !f.error) && loadedResources.length > 1 && <SimpleBooleanInput
                                    value={exportAll}
                                    onChange={v => {
                                        setLoadedResources(loadedResources.map(r => {
                                            r.enabled = v;
                                            return r
                                        }))
                                        setExportAll(!exportAll)
                                    }}
                                />}
                            </td>
                            <td className="col-sm-2"></td>
                        </tr>
                        {loadedResources
                            .map((resource, i) => (
                                <tr key={`resource${i}`}>
                                    <td className="col-md-5">{resource.name || resource.resource?.name || "Unknown"}</td>
                                    <td className="col-md-3">
                                        <span className="badge" style={{
                                            padding: "0 12 0 12",
                                            backgroundColor: resource.error ? "red" : "#eee",
                                            color: "#000"
                                        }}>
                                            {resource.error || resource.kind}
                                        </span>
                                    </td>
                                    <td className="col-md-2">
                                        {!resource.error && <SimpleBooleanInput
                                            value={resource.enabled}
                                            onChange={v => setLoadedResources(loadedResources.map((r, j) => {
                                                if (i === j)
                                                    r.enabled = v;
                                                return r
                                            }))}
                                        />}
                                    </td>
                                    <td className="col-sm-2">
                                        <span className="badge" style={{ backgroundColor: resource.status ? '#f9b000' : '' }}>{resource.status}</span>
                                    </td>
                                </tr>
                            ))}
                    </tbody>
                </table>
                <button className="btn btn-info"
                    style={{ marginTop: 12 }}
                    onClick={() => setLoadedResources([])}>Restart</button>
                {loadedResources.find(f => !f.error) && <button type="button"
                    className="btn btn-success"
                    onClick={() => {
                        Promise.all(loadedResources
                            .filter(r => r.enabled)
                            .map(resource => {
                                const content = resource.resource;
                                const id = resource.id;
                                const k = resource.kind;

                                return UPDATE_ENTITIES[k](content, id)
                            }))
                            .then(() => {
                                setLoadedResources(loadedResources
                                    .filter(r => r.enabled)
                                    .map(r => {
                                        if (!r.error)
                                            return { ...r, status: "done" }
                                        return r;
                                    }))
                            })
                    }}>Import selected resources</button>}
            </div>
        )
    }

    return (
        <div>
            <div className="form-group">
                <button type="button"
                    onClick={() => setFormat("json")}
                    className={`btn btn-sm btn-${format === "json" ? 'success' : 'secondary'}`}>JSON</button>
                <button type="button"
                    onClick={() => setFormat("yaml")}
                    className={`btn btn-sm btn-${format === "yaml" ? 'success' : 'secondary'}`}>YAML</button>
            </div>
            <div className="form-group">
                <div className="row">
                    <div className="col-sm-8" style={{ paddingRight: 0 }}>
                        <AceEditor
                            name="resources-loader"
                            mode={format}
                            theme="monokai"
                            onChange={setRawResources}
                            value={rawResources}
                            editorProps={{ $blockScrolling: true }}
                            width="100%"
                            height="440px"
                            showGutter={true}
                            highlightActiveLine={true}
                            tabSize={2}
                            enableBasicAutocompletion={true}
                        // enableLiveAutocompletion={true}
                        />
                    </div>
                    <div className="col-sm-4" style={{ paddingLeft: 1 }}>
                        <div style={{
                            height: "30px",
                            textAlign: "center",
                            display: "flex",
                            alignItems: "center",
                            justifyContent: "center",
                            backgroundColor: "#f9b000",
                            color: "#fff",
                            fontStyle: "italic"
                        }}>Example</div>
                        <AceEditor
                            mode='json'
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
            <button type="button" className="btn btn-success"
                style={{ marginTop: 12 }}
                onClick={loadResources}>Load resources</button>
        </div>
    );
}
