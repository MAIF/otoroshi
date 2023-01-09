import React, { useState } from 'react';

function Terminal({ toggleTerminalVisibility, showTerminal }) {

  if (!showTerminal)
    return <div style={{
      height: 30
    }} className="bg-light">
      <button type="button" style={{ border: 'none' }} onClick={() => toggleTerminalVisibility(true)}>
        <span>TERMINAL</span>
      </button>
    </div>

  if (showTerminal)
    return <div style={{
      height: 300,
      flex: .3
    }} className="bg-light">
      <div className='d-flex justify-content-between align-items-center mx-2 me-3'>
        <div style={{ borderBottom: '2px solid #f9b000', textTransform: 'uppercase', width: 'fit-content' }} className='p-1'>
          <span>Terminal</span>
        </div>
        <i className='fas fa-times fa-sm' style={{ cursor: 'pointer' }} onClick={() => toggleTerminalVisibility(false)} />
      </div>
    </div>
}

export default Terminal;