import React, {useContext, useEffect, useRef, useState} from 'react'
import ReportInformation from './ReportInformation'

import { NgForm } from '../../components/nginputs'
import { Button } from '../../components/Button'
import { SidebarContext } from '../../apps/BackOfficeApp'
import { nodesCatalogSignal } from './models/Functions'
import {message} from "antd";

export function Tester({ isOpen, report, handleClose, run, runLive, getTestPayload, eventCallback, setReport, setReportStatus }) {

    const sidebar = useContext(SidebarContext)

    const wsRef = useRef(null)

    const [state, setState] = useState({
        input: nodesCatalogSignal.value.rawWorkflow.test_payload
    });

    const [running, setRunning] = useState(false);
    const [debug, setDebug] = useState(false);

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

    const runWsAction = (action, data = {}) => {
        if (action === "start" || action === "step_by_step") {
            wsRef.current.send(JSON.stringify({
                kind: 'start',
                data: { ...getTestPayload(state.input), ...data }
            }));
            setRunning(true)
        } else if (action === "stop") {
            wsRef.current.send(JSON.stringify({ kind: action, data: {} }));
            setRunning(false)
            wsRef.current = null;
        } else {
            wsRef.current.send(JSON.stringify({ kind: action, data: {} }));
        }
    }

    const runWs = (action, data = {}) => {
        if (!wsRef.current) {
            const location = window.location;
            const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
            wsRef.current = new WebSocket(`${scheme}://${location.host}/extensions/workflows/_debugger`);
            wsRef.current.onmessage = (message) => {
                const json = JSON.parse(message.data);
                console.log('received message 1', json);
                if (eventCallback) {
                    eventCallback(json);
                }
                if (json.kind === 'result') {
                    setRunning(false);
                    setDebug(false);
                    setReport(json.data);
                    setReportStatus(true)
                    wsRef.current = null;
                }
            }
            setTimeout(() => {
                setDebug(true);
                handleClose();
                runWsAction(action, data);
            }, 1000)
        } else {
            runWsAction(action, data);
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
                    <div className='d-flex gap-2 ms-auto justify-content-end m-2' style={{ flexDirection: 'row'}}>
                        {/*<Button type="primaryColor" className="d-flex items-center" disabled={running} onClick={runTest}>
                            {!running && <span><i className="fas fa-flask me-1" />Run Test</span>}
                            {running && <span><i className="fas fa-flask me-1" />Running ...</span>}
                        </Button>
                        <Button type="primaryColor" className="d-flex items-center" disabled={running} onClick={runLiveTest}>
                            {!running && <span><i className="fas fa-flask me-1" />Run Test</span>}
                            {running && <span><i className="fas fa-flask me-1" />Running ...</span>}
                        </Button>*/}
                        <Button type="primaryColor" className="d-flex items-center" disabled={running} onClick={() => runWs('start')}>
                            {!running && <span><i className="fas fa-flask me-1" />Run Test</span>}
                            {running && <span><i className="fas fa-flask me-1" />Running ...</span>}
                        </Button>
                        <Button type="primaryColor" className="d-flex items-center" disabled={running} onClick={() => runWs('start', { step_by_step: true })}>
                            {!running && <span><i className="fas fa-flask me-1" />Run Test Step by Step</span>}
                            {running && <span><i className="fas fa-flask me-1" />Running ...</span>}
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

    if (!isOpen && !debug) {
        return null;
    }

    if (!isOpen && debug) {
      return (
        <div style={{position: 'fixed', bottom: 20, right: 20, zIndex: 9999, display: 'flex', flexDirection: 'row', gap: 10 }}>
         {/*<Button type="primaryColor" className="d-flex items-center" disabled={running} onClick={() => runWs('start')}>Debug</Button>
          <Button type="primaryColor" className="d-flex items-center" disabled={running} onClick={() => runWs('start', { step_by_step: true })}>Step by step</Button>*/}
          <Button type="primaryColor" className="d-flex items-center" disabled={!running} onClick={() => runWs('next')}><i   style={{ marginRight: 5 }} className="fas fa-step-forward"/> Next step</Button>
          <Button type="primaryColor" className="d-flex items-center" disabled={!running} onClick={() => runWs('resume')}><i style={{ marginRight: 5 }} className="fas fa-play"/> Continue</Button>
          <Button type="primaryColor" className="d-flex items-center" disabled={!running} onClick={() => runWs('stop')}><i   style={{ marginRight: 5 }} className="fas fa-stop"/> Stop</Button>
        </div>
      )
    }

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