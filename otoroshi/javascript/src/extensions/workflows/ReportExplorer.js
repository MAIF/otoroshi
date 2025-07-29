import React, { useState } from 'react'
import { ReportView } from '../../components/ReportView'
import { Button } from '../../components/Button'

export default function ReportExplorer({ report, handleClose, isOpen }) {

    if (!report || !isOpen)
        return null

    if(report.done === false) {
        return <div>{report.error}</div>
    }

    const steps = report.run.log.reduce((acc, log) => {

        if (log.message.includes('ending'))
            return acc

        const matches = log.message.match(/^starting '([a-zA-Z0-9-]+)'/)

        if (matches) {
            const id = matches[1]
            const re = new RegExp(`^ending '${id}'$`);

            const stop = report.run.log.find(l => re.test(l.message))?.timestamp
            return [...acc, {
                task: log.node?.kind || log.message,
                start: log.timestamp,
                stop,
                duration_ns: (stop ? stop - log.timestamp : 0) * 1_000_000,
                ctx: {
                    error: log.error,
                    node: log.node,
                    memory: log.memory
                }
            }]
        }
        return acc
    }, [])

    const stepsByCategory = steps.reduce((acc, step) => {
        const existingStep = acc[step.task]

        if (existingStep) {
            // return acc
            return {
                ...acc,
                [step.task]: {
                    ...existingStep,
                    ctx: {
                        ...step.ctx,
                        plugins: [...existingStep.ctx.plugins, {
                            ...step,
                            name: `[${existingStep.ctx.plugins.length}]`
                        }]
                    }
                }
            }
        } else {
            return {
                ...acc,
                [step.task]: {
                    ...step,
                    ctx: {
                        ...step.ctx,
                        plugins: []
                    }
                }
            }
        }
    }, {})
    const [unit, setUnit] = useState('ms');

    const start = report.run.log[0]?.timestamp
    const end = report.run.log[report.run.log.length - 1]?.timestamp

    return <div className="report-explorer">
        <h3 className='pt-3 ps-2'>Report</h3>
        <div style={{ position: 'relative', flex: 1 }} className='d-flex flex-column mt-1'>
            <div className='tryIt'>
                <ReportView
                    report={{
                        steps: Object.values(stepsByCategory),
                        duration_ns: (end - start) * 1_000_000,
                        returned: report.returned
                    }}
                    isWorkflowView
                    unit={unit}
                    setUnit={setUnit}
                />
            </div>
            <Button type="primaryColor" className='p-2 px-4 report-explorer-action' onClick={handleClose}>
                <i className='fas fa-check me-1' />Close
            </Button>
        </div>
    </div>
}