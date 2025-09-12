import React, { useContext, useEffect, useState } from 'react'
import ReportInformation from './ReportInformation'

import { NgForm } from '../../components/nginputs'
import { Button } from '../../components/Button'
import { SidebarContext } from '../../apps/BackOfficeApp'
import { nodesCatalogSignal } from './models/Functions'

export function Tester({ isOpen, report, handleClose, run, runLive }) {

    const sidebar = useContext(SidebarContext)

    const [state, setState] = useState({
        input: nodesCatalogSignal.value.rawWorkflow.test_payload
    });

    const [running, setRunning] = useState(false);

    const runTest = () => {
        if (!running) {
            setRunning(true)
            const minDelay = new Promise(resolve => setTimeout(resolve, 250));
            const operation = run(state.input);

            Promise.all([minDelay, operation])
                .then(() => {
                    setRunning(false);
                })
                .catch(() => {
                    setRunning(false);
                });
        }
    }

    const runLiveTest = () => {
        if (!running) {
            setRunning(true)
            const minDelay = new Promise(resolve => setTimeout(resolve, 250));
            const operation = runLive(state.input);

            Promise.all([minDelay, operation])
                .then(() => {
                    setRunning(false);
                })
                .catch(() => {
                    setRunning(false);
                });
        }
    }

    const schema = {
        input: {
            type: 'json',
            label: 'Input',
            props: {
                editorOnly: true,
                height: '10rem',
            }
        },
        run: {
            renderer: () => {
                return (
                    <div>
                        <Button type="primaryColor" className="btn-xl ms-auto d-flex items-center m-2" disabled={running} onClick={runTest}>
                            {!running && <span><i className="fas fa-flask me-1" />Run Test</span>}
                            {running && <span><i className="fas fa-flask me-1" />Running ...</span>}
                        </Button>
                        <Button type="primaryColor" className="btn-xl ms-auto d-flex items-center m-2" disabled={running} onClick={runLiveTest}>
                            <i className="fas fa-play me-1" /> Run Live !
                        </Button>
                    </div>
                );
            }
        },
        report: {
            renderer: () => {
                return report ? <ReportInformation report={report} /> : null
            }
        }
    }

    const flow = [
        {
            type: 'group',
            name: 'Information',
            collasped: false,
            fields: ['input', 'run'],
        },
        {
            type: 'group',
            name: () => `Report ${!report ? '(will be displayed here)' : ''}`,
            collasped: false,
            collapsable: false,
            fields: ['report'],
        }
    ]

    if (!isOpen)
        return null

    // TODO: make the tester smaller ?
    return <div className="report-explorer p-3">
        <h3>Tester</h3>
        <NgForm
            schema={schema}
            flow={flow}
            value={state}
            onChange={newState => {
                try {
                    nodesCatalogSignal.value.updateWorkflow({
                        test_payload: newState.input
                    })
                    setState(newState)
                } catch (err) {

                }
            }}
        />
        <Button
            type="primaryColor"
            className="p-2 px-4 report-explorer-action"
            style={{ left: sidebar.openedSidebar ? 250 : 48 }}
            onClick={handleClose}
        >
            <i className="fas fa-check me-1" />
            Close
        </Button>
    </div>
}