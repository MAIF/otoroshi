import React, { useContext, useEffect, useState } from 'react'
import ReportInformation from './ReportInformation'

import { NgForm } from '../../components/nginputs'
import { Button } from '../../components/Button'
import { SidebarContext } from '../../apps/BackOfficeApp'

export function Tester({ isOpen, report, handleClose, run }) {

    const sidebar = useContext(SidebarContext)

    const [state, setState] = useState({
        input: {
            workflow_input: {},
        }
    })

    const schema = {
        input: {
            type: 'code',
            label: 'Input',
            props: {
                editorOnly: true,
                height: '10rem',
            }
        },
        run: {
            renderer: () => {
                return <Button type="primaryColor" className="btn-xl ms-auto d-flex items-center m-2" onClick={() => run(state.input)}>
                    <i className="fas fa-flask me-1" />Run Test
                </Button>
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

    return <div className="report-explorer p-3">
        <h3>Tester</h3>
        <NgForm
            schema={schema}
            flow={flow}
            value={state}
            onChange={setState}
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