import React from 'react'
import { Button } from '../../components/Button'

const StopButton = ({ onClick, running }) => {
  return <Button type="primaryColor"
    className="react-flow__controls-button"
    disabled={!running}
    onClick={onClick}>
    <i className="fas fa-square" />
  </Button>
}

export function DesignerActions({ run, action, debug, running, handleFlowStop, next, resume }) {

  const isDebug = action === 'debug'

  return (
    <div className="designer-actions">

      {isDebug && <>
        <DebuggerActions
          running={running}
          next={next}
          resume={resume} />
      </>}
      {action && <StopButton onClick={handleFlowStop} running={running} />}

      {!action && <>
        <Button type="primaryColor"
          className="react-flow__controls-button"
          disabled={running && running !== 'play'}
          onClick={run}>
          <i className={running === 'play' ? 'fas fa-square' : 'fas fa-play'} />
        </Button>
        <Button type="primaryColor"
          className="react-flow__controls-button"
          onClick={debug}>
          <i className="fas fa-bug" />
        </Button>
      </>}
    </div>
  );
}

const DebuggerActions = ({ running, next, resume }) => {
  return <div className='d-flex' style={{ gap: '.75rem' }}>
    <Button type="primaryColor"
      className='react-flow__controls-button'
      disabled={!running}
      onClick={next}
      style={{
        width: 'initial',
        gap: '.5rem',
        padding: '.75rem'
      }}>
      <i className="fas fa-step-forward" /> Next step</Button>
    <Button type="primaryColor"
      className='react-flow__controls-button'
      disabled={!running}
      onClick={resume}
      style={{
        width: 'initial',
        gap: '.5rem',
        padding: '.75rem'
      }}>
      <i className="fas fa-play" /> Continue</Button>
  </div>
}