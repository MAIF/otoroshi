import React from 'react';

export class PublishView extends React.Component {

  state = {
  }

  render() {
    if (!this.props.selectedPlugin)
      return null;

    const hasManifest = this.props.selectedPlugin.files.find(f => f.filename === 'wapm.toml');
    const hasREADME = this.props.selectedPlugin.files.find(f => f.filename === 'README.md');

    return (
      <div style={{ flex: 1, marginTop: 75 }} className="p-3 bg-light mx-auto w-75"
        onKeyDown={e => e.stopPropagation()}>

        <h1 className='mb-3'>Publish your package to WAPM</h1>

        <div className='mt-3 d-flex flex-column' style={{ maxWidth: 170 }}>
          <button className='d-flex justify-content-between align-items-center sidebar-header'
            onClick={() => hasManifest ? this.props.openTab('wapm.toml') : this.props.createManifest()}>
            <i className={`fas fa-${hasManifest ? 'check' : 'chevron-right'} me-2`} />
            <span>{hasManifest ? 'Edit Manifest' : 'Add Manifest'}</span>
          </button>
          <button className='d-flex justify-content-between align-items-center sidebar-header'
            onClick={() => hasREADME ? this.props.openTab('README.md') : this.props.createReadme()}>
            <i className={`fas fa-${hasREADME ? 'check' : 'chevron-right'} me-2`} />
            <span>{hasREADME ? 'Edit README' : 'Add README'}</span>
          </button>

          <button className='d-flex justify-content-between align-items-center sidebar-header mt-3'
            onClick={this.props.publish}>
            <i className='fas fa-upload me-2' />
            <span>Publish</span>
          </button>
        </div>
      </div>
    )
  }
}
