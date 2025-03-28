import React, { Component, useEffect, useState } from 'react';
import SimpleLoader from './SimpleLoader';

function Metric({ time, value, ...props }) {
    const size = 30;

    const [values, setValues] = useState(new Array(size).fill(0))

    const restrict = (what) => {
        if (what.length > size) {
            what.shift();
        }
        return what;
    }

    useEffect(() => {
        let newValue = value
        if (newValue.replace) {
            newValue = newValue
                .replace(' ', '')
                .replace('in', '')
                .replace('out', '')
                .replace('/sec', '')
                .replace(/Mb|Gb|Tb|Pb|Kb/, '');
            newValue = parseFloat(newValue);
        }
        setValues(restrict([...values, newValue]))
    }, [time])

    return (
        <div
            className="metric"
            style={{
                width: props.width || 300,
            }}
        >
            <span className="metric-text-title">{props.legend}</span>
            <span className="metric-text-value">{value}<span>{props.unit}</span></span>
        </div>
    )
}

export class ApiStats extends Component {
    state = {
        firstDone: false,
        requests: 0,
        rate: 0.0,
        duration: 0.0,
        overhead: 0.0,
    };

    componentDidMount() {
        this.evtSource = new EventSource(this.props.url);
        this.evtSource.onmessage = (e) => this.onMessage(e);
    }

    componentWillUnmount() {
        if (this.evtSource) {
            this.evtSource.close();
            delete this.evtSource;
        }
    }

    onMessage = (e) => {
        const data = JSON.parse(e.data);
        data.rate = data.rate || 0.0;
        data.duration = data.duration || 0.0;

        this.setState({
            firstDone: true,
            requests: data.calls.prettify(),
            rate: parseFloat(data.rate.toFixed(2)).prettify(),
            duration: parseFloat(data.duration.toFixed(2)).prettify(),
            overhead: parseFloat(data.overhead.toFixed(2)).prettify(),
        });
    };

    render() {
        if (!this.state.firstDone) {
            return <SimpleLoader />
        }

        return <div className='d-flex gap-3'>
            <div className="row-metrics">
                <Metric time={Date.now()} value={this.state.rate} legend="Requests" unit="/sec" />
                <Metric time={Date.now()} value={this.state.requests} legend="Requests served" />
                <Metric time={Date.now()} value={this.state.duration} legend="Time per request" unit="ms" />
                <Metric time={Date.now()} value={this.state.overhead} legend="Overhead per request" unit="ms" />
            </div>
        </div>
    }
}