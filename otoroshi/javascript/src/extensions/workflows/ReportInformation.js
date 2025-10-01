import React, { useState } from 'react';
import { ReportView } from '../../components/ReportView';
import { getNodeFromKind } from './models/Functions';

export default function ReportInformation(props) {
  const [unit, setUnit] = useState('ms');

  // console.log(props.report)
  let report = props.report;

  const { starting, ending } = report.run.log.reduce(
    (acc, log) => {
      if (log.message.includes('ending')) {
        return { ...acc, ending: [...acc.ending, log] };
      }
      return { ...acc, starting: [...acc.starting, log] };
    },
    { starting: [], ending: [] }
  );

  const steps = starting.reduce((acc, log, idx) => {
    const matches = log.message.match(/^starting '([a-zA-Z0-9-]+)'/);

    if (matches) {
      const id = matches[1];

      const stop = ending.find((l) => l.node.id === id)?.timestamp;

      let functionName = log.node?.function
      const kind = log.node?.kind

      if (functionName) {
        functionName = getNodeFromKind(functionName)?.display_name
      }

      return [
        ...acc,
        {
          task: `${idx + 1}-${functionName ? functionName : kind ? kind : log.message}`,
          start: log.timestamp,
          stop,
          duration_ns: Math.abs((stop ? stop - log.timestamp : 0) * 1_000_000),
          ctx: {
            error: log.error,
            node: log.node,
            memory: log.memory,
          },
        },
      ];
    }
    return acc;
  }, []);

  let stepsByCategory = steps.reduce((acc, step) => {
    const existingStep = acc[step.task];

    if (existingStep) {
      return {
        ...acc,
        [step.task]: {
          ...existingStep,
          ctx: {
            ...step.ctx,
            plugins: [
              ...existingStep.ctx.plugins,
              {
                ...step,
                name: `[${existingStep.ctx.plugins.length}]`,
              },
            ],
          },
        },
      };
    } else {
      return {
        ...acc,
        [step.task]: {
          ...step,
          ctx: {
            ...step.ctx,
            plugins: [],
          },
        },
      };
    }
  }, {});

  stepsByCategory = Object.fromEntries(
    Object.entries(stepsByCategory).map(([key, value]) => [key, {
      ...value,
      task: value.task.split('-')[1]
    }])
  )


  const start = report.run.log[0]?.timestamp;
  const end = report.run.log[report.run.log.length - 1]?.timestamp;

  return (
    <>
      <div style={{ position: 'relative', flex: 1 }} className="d-flex flex-column mt-1">
        <div className="tryIt">
          <ReportView
            error={report.error}
            report={{
              steps: Object.values(stepsByCategory),
              duration_ns: (end - start) * 1_000_000,
              returned: report.returned,
              memory: report.run.memory,
            }}
            isWorkflowView
            unit={unit}
            setUnit={setUnit}
            onClick={props.handleStep}
          />
        </div>
      </div>
    </>
  );
}
