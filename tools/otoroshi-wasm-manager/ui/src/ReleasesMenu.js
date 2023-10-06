import React from 'react';
import { LOGOS } from './FilesLogo';
import * as Service from './services'

const isAString = variable => typeof variable === 'string' || variable instanceof String;

export default function ReleasesMenu({ files }) {

    const releasesFile = files.find(f => f.filename === 'config' && f.ext === 'json')

    if (!releasesFile)
        return <p style={{ marginLeft: 12, color: '#000', fontSize: '.9rem' }}>No releases yet</p>

    let versions = JSON.parse(releasesFile.content).versions;

    if (versions.length > 0 && isAString(versions[0])) {
        versions = versions.map(name => ({ name }))
    }

    return <div className='d-flex flex-column'>
        {versions
            .map(version => {
                const key = version.name.split(".wasm")[0];
                return <div
                    className='d-flex align-items-center mb-1'
                    style={{ marginLeft: 12, cursor: 'pointer' }}
                    key={key}
                    onClick={() => {
                        Service.getWasmRelease(key)
                            .then(res => res.blob())
                            .then(blob => {
                                const file = URL.createObjectURL(blob);
                                window.location.assign(file);
                            })
                    }}>
                    <div style={{ minWidth: 30 }}>
                        {LOGOS.release}
                    </div>
                    <span style={{ fontSize: '.9em', color: '#000' }}>{key}</span>
                </div>
            })}
    </div>
}