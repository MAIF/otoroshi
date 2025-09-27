import React, { useState, useRef, useContext } from 'react';
import CodeInput from '../../components/inputs/CodeInput';
import { nodesCatalogSignal } from './models/Functions';
import { DesignerActions } from './DesignerActions';
import ReportInformation from './ReportInformation';
import moment from 'moment'
import { NgAnyRenderer } from '../../components/nginputs';


const Tab = ({ onClick, name, selected, isError }) => {
    return <div style={{
        borderBottom: '2px solid',
        borderColor: selected ? isError ? 'var(--color-red)' : '#f9b000' : 'transparent',
        textTransform: 'uppercase',
        userSelect: 'none',
        cursor: 'pointer'
    }} className='me-2 p-1' onClick={onClick}>
        <span>{name}</span>
    </div>
}

function Terminal({
    terminalSize,
    toggleResizingTerminal,
    changeTerminalSize,
    flowOperators,
    getTestPayload,
    saveTerminalTab,
    initialTerminalTab,
}) {
    const [tab, _setTab] = useState(initialTerminalTab || 'input')

    const [action, setAction] = useState()
    const [running, setRunning] = useState(false)

    const [input, setInput] = useState(nodesCatalogSignal.value.rawWorkflow.test_payload)
    const [log, setLog] = useState([])
    const [memory, setMemory] = useState()
    const [report, setReport] = useState()

    const wsRef = useRef()

    const setTab = newTab => {
        _setTab(newTab)
        saveTerminalTab(newTab)
    }

    const handleTabChange = newTab => {
        if (terminalSize === 0)
            changeTerminalSize(0.3)
        setTab(newTab)
    }

    function eventCallback(event, resolve) {
        if (event.kind === 'progress') {
            setLog(log => [event, ...log])

            const event_id = event?.data?.node?.id;
            // console.log(`[${event.data.node.kind}]`, event_id);

            if ((event?.data?.message || '').toLowerCase().startsWith("starting")) {
                if (event_id) {
                    flowOperators.highlightNode(event_id)
                    flowOperators.highlightEdge(event_id)
                }
            } else if ((event?.data?.message || '').toLowerCase().startsWith("ending")) {
                if (event_id) {
                    flowOperators.unhighlighNode(event_id)
                }
            }
        } else if (event.kind === 'result') {
            if (!event.data?.error) {
                flowOperators.highlightEdge("returned-node")
                flowOperators.unhighlighNode("returned-node")
            } else {
                const errorNodeId = event.data.error.nodeId
                flowOperators.setErrorNode(errorNodeId)
            }

            if (resolve) {
                resolve(event.data);
            }
            handleReportChange(event.data);
        } else if (event.kind === 'debugger-state') {
            setMemory(event.data.memory)
        } else {
            console.warn('Unknown kind:', event.kind);
        }
    }

    const handleReportChange = newReport => {
        setReport(newReport)
        setTab('report')
    }

    const runWsAction = (action, data = {}) => {
        if (action === "start" || action === "step_by_step") {
            wsRef.current.send(JSON.stringify({
                kind: 'start',
                data: { ...getTestPayload(input), ...data }
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
        if (action === 'start') {
            flowOperators.resetFlow()
            setTab('memory')
            setReport(undefined)
            setLog([])
            if (terminalSize === 0)
                changeTerminalSize(.3)
        }

        if (!wsRef.current) {
            const location = window.location;
            const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
            wsRef.current = new WebSocket(`${scheme}://${location.host}/extensions/workflows/_debugger`);
            wsRef.current.onmessage = (message) => {
                const json = JSON.parse(message.data);
                // console.log('received message 1', json);

                eventCallback(json);
                if (json.kind === 'result') {
                    setRunning(false);
                    setAction();
                    handleReportChange(json.data);
                    wsRef.current = null;
                }
            }
            setTimeout(() => {
                // minimize()
                runWsAction(action, data);
            }, 1000)
        } else {
            runWsAction(action, data);
        }
    }

    const run = () => {
        setAction('play')
        runWs('start')
    }

    const debug = (step_by_step = false) => {
        setAction('debug')
        runWs('start', { step_by_step })
    }

    const clearLog = () => {
        setLog([])
    }

    const tabContentVisible = terminalSize !== 0

    return <div className='terminal' style={{
        flex: terminalSize,
        maxHeight: 100 * terminalSize > 0 ? `${100 * terminalSize}%` : 'initial',
        // background: 'var(--bg-color_level2)'
    }}>
        <DesignerActions
            run={run}
            debug={debug}
            next={() => runWs('next')}
            resume={() => runWs('resume')}
            handleFlowStop={() => {
                runWs('stop')
                setRunning(false)
                setAction()
                flowOperators.resetFlow()
            }}
            action={action}
            running={running} />
        <div style={{
            minHeight: 12,
            maxHeight: 12,
            background: report?.error ? 'var(--color-red)' : 'var(--bg-color_level3)'
        }}
            className="d-flex align-items-center justify-content-center"
            onMouseDown={e => {
                e.stopPropagation();
                toggleResizingTerminal(true)
            }}
            onMouseMove={e => e.stopPropagation()}
            onMouseUp={e => {
                e.stopPropagation();
                toggleResizingTerminal(false)
            }}>
            <div style={{
                cursor: 'pointer',
                background: '#fff',
                width: 36,
                height: 6,
                borderRadius: 12
            }}></div>
        </div>

        <div className='d-flex justify-content-between align-items-center mx-2 me-3'>
            <div className='d-flex'>
                <Tab
                    name="Input"
                    onClick={() => handleTabChange('input')}
                    selected={tab === 'input'}
                    isError={report?.error} />
                <Tab
                    name="Log"
                    onClick={() => handleTabChange('log')}
                    selected={tab === 'log'}
                    isError={report?.error} />
                <Tab
                    name="Memory"
                    onClick={() => handleTabChange('memory')}
                    selected={tab === 'memory'}
                    isError={report?.error} />
                <Tab
                    name="Report"
                    onClick={() => handleTabChange('report')}
                    selected={tab === 'report'}
                    isError={report?.error} />
            </div>

            <div className='d-flex align-items-center'>
                {tab === 'log' && <span className='me-3' onClick={clearLog} >
                    <i className='fas fa-ban fa-sm' style={{ cursor: 'pointer' }} />
                </span>}
                <span onClick={() => changeTerminalSize(0)} >
                    <i className='fas fa-times fa-sm' style={{ cursor: 'pointer' }} />
                </span>
            </div>
        </div>

        {tabContentVisible && <>
            {tab === 'input' && <InputTab
                input={input}
                setInput={newInput => {
                    try {
                        nodesCatalogSignal.value.updateWorkflow({
                            test_payload: JSON.parse(newInput)
                        })
                        setInput(JSON.parse(newInput))
                    } catch (err) {

                    }
                }}
                terminalSize={terminalSize} />}

            {tab === 'report' && <ReportTab
                report={report}
                log={log}
                running={running}
                zoomIn={nodeId => flowOperators.scrollToNode(nodeId)} />}

            {tab === 'log' && <LogTab log={log} />}

            {tab === 'memory' && <MemoryTab memory={memory} setMemory={setMemory} />}
        </>}
    </div>
}

const LogTab = ({ log }) => {

    const [opened, setOpened] = useState({})

    return <div className='d-flex flex-column' style={{
        overflowY: 'auto',
    }}>
        <div className='terminal-log-header'>
            <div>Time</div>
            <div>Action</div>
            <div>ID</div>
            <div>Message</div>
        </div>
        {log.map((item, i) => {
            const itemOpened = opened[item.data.node.id]

            return <div key={`debug${i}`}
                className='terminal-log-header terminal-log-item'
                onClick={() => {
                    setOpened({
                        ...Object.fromEntries(Object.entries(opened).map(([key, _]) => [key, false])),
                        [item.data.node.id]: !!!itemOpened
                    })
                }}>
                <div>{moment(item.data.timestamp).format('hh:mm:ss')}</div>
                <div>{item.data.message.split(' ')[0]}</div>
                <div>{item.data.node.kind}</div>
                {
                    itemOpened ? <div onClick={e => e.stopPropagation()}>
                        <CodeInput
                            ace_config={{
                                readOnly: true,
                            }}
                            key={`debug${i}`}
                            value={item.data}
                            editorOnly={true}
                            label={null}
                        />
                    </div> :
                        <div className='terminal-log-item-data'>{JSON.stringify(item.data, null, 4)}</div>
                }
            </div>

        })}
    </div >
}

const ReportTab = ({ report, log, running, zoomIn }) => {
    const handleStep = step => {
        if (step === -1) { // global view
            zoomIn({ id: 'start' })
        } else {
            zoomIn({ id: step.ctx.node.id })
        }
    }

    if (!running && !report)
        return <div className='terminal-memory-tab'>Click Play or Debug to view the final report.</div>

    if (running) {
        return <ReportInformation
            handleStep={handleStep}
            report={{
                run: {
                    log: log
                        .map(item => item.data)
                        .slice()
                        .reverse()
                }
            }} />
    }

    return <ReportInformation report={report} handleStep={handleStep} />
}

const MemoryTab = ({ memory, setMemory }) => {
    if (!memory)
        return <div className='terminal-memory-tab'>Click Play or Debug to view workflow memory.</div>

    return <CodeInput
        ace_config={{
            readOnly: true,
            fontSize: 14,
            minLines: 1
        }}
        value={memory}
        onChange={setMemory}
        editorOnly={true}
        label={null}
    />
}

const InputTab = ({ input, setInput, terminalSize }) => {
    return <NgAnyRenderer
        ngOptions={{
            spread: true
        }}
        language='json'
        height={`calc(${(terminalSize) * 100}vh)`}
        value={input}
        onChange={setInput}
        editorOnly={true}
        label={null}
    />
}

export default Terminal;