import React from 'react';
import { Button } from '../../components/Button';
import { OffSwitch, OnSwitch } from '../../components/inputs';

const StopButton = ({ onClick, running }) => {
  return (
    <Button
      type="primaryColor"
      className="react-flow__controls-button"
      disabled={!running}
      onClick={onClick}
    >
      <i className="fas fa-square" />
    </Button>
  );
};

export function DesignerActions({ run, action, debug, running, handleFlowStop, next, resume }) {
  const isDebug = action === 'debug';

  const [step_by_step, setStep_by_step] = React.useState(false);

  return (
    <div className="designer-actions">
      {isDebug && (
        <>
          <DebuggerActions running={running} next={next} resume={resume} />
        </>
      )}
      {action && <StopButton onClick={handleFlowStop} running={running} />}

      {!action && (
        <>
          <Button
            type="primaryColor"
            className="react-flow__controls-button"
            disabled={running && running !== 'play'}
            onClick={(e) => debug(step_by_step)}
          >
            <i className={running === 'play' ? 'fas fa-square' : 'fas fa-play'} />
          </Button>
          <div
            style={{
              display: 'none',
              flexDirection: 'row',
              justifyContent: 'center',
              alignItems: 'center',
            }}
          >
            {step_by_step && <OnSwitch onChange={(e) => setStep_by_step(!step_by_step)} />}
            {!step_by_step && <OffSwitch onChange={(e) => setStep_by_step(!step_by_step)} />}
            <span style={{ marginLeft: 8, height: 17 }}>step by step</span>
          </div>
          <Button type="primaryColor" className="hide react-flow__controls-button" onClick={debug}>
            <i className="fas fa-bug" />
          </Button>
        </>
      )}
    </div>
  );
}

const DebuggerActions = ({ running, next, resume }) => {
  return (
    <div className="d-flex" style={{ gap: '.75rem' }}>
      <Button
        type="primaryColor"
        className="react-flow__controls-button"
        disabled={!running}
        onClick={next}
        style={{
          width: 'initial',
          gap: '.5rem',
          padding: '.75rem',
        }}
      >
        <i className="fas fa-step-forward" /> Next step
      </Button>
      <Button
        type="primaryColor"
        className="react-flow__controls-button"
        disabled={!running}
        onClick={resume}
        style={{
          width: 'initial',
          gap: '.5rem',
          padding: '.75rem',
        }}
      >
        <i className="fas fa-play" /> Continue
      </Button>
    </div>
  );
};
