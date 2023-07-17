import React from 'react';

function Button({ onClick, icon, text }) {
  return <button className='d-flex justify-content-between align-items-center sidebar-header'
    onClick={onClick}>
    <i className={`fas fa-${icon} me-2`} />
    <span>{text}</span>
  </button>
}

export function PublishView({ selectedPlugin, openTab, createManifest, createReadme, publish }) {

  if (!selectedPlugin)
    return null;

  const hasManifest = selectedPlugin.files.find(f => f.filename === 'wapm.toml');
  const hasREADME = selectedPlugin.files.find(f => f.filename === 'README.md');

  return <div style={{ flex: 1, marginTop: 75 }} className="p-3 bg-light mx-auto w-75"
    onKeyDown={e => e.stopPropagation()}>

    <h1 className='mb-3'>Publish your package to WAPM</h1>

    <div className='mt-3 d-flex flex-column' style={{ maxWidth: 170 }}>
      <Button
        onClick={() => hasManifest ? openTab('wapm.toml') : createManifest()}
        icon={hasManifest ? 'check' : 'chevron-right'}
        text={hasManifest ? 'Edit Manifest' : 'Add Manifest'} />
      <Button
        onClick={() => hasREADME ? openTab('README.md') : createReadme()}
        icon={hasREADME ? 'check' : 'chevron-right'}
        text={hasREADME ? 'Edit README' : 'Add README'} />

      {/* <Button
        onClick={publish}
        icon="upload"
        text="Publish" /> */}
    </div>
  </div>
}
