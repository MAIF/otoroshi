import React, { useState, useEffect } from 'react';
import { mergeData, isArray, needFormat, getIndent } from './utils.js';

function NormalTree(props) {
    const {
        name,
        value,
        line,
        showIndex,
        type,
        lineType,
        needComma,
        level = 1
    } = props;

    return (
        <p className={`json-compare-p json-compare-line-${lineType}`} style={getIndent(level)}>
            <span className="json-compare-mark">{line}</span>
            <span className={`json-compare-of-${lineType}`}></span>
            <span className="json-compare-content">
                {showIndex && <span className="json-compare-key">{name}: </span>}
                <span className={`json-compare-${type}`}>{value}</span>
                <span className="json-compare-comma">{needComma ? ',' : ''}</span>
            </span>
        </p>
    );
}

function ComplexTree(props) {
    const {
        name,
        value,
        type,
        line,
        showIndex,
        needComma,
        level = 1,
        lineType,
        lastLineType,
        lastLine = null
    } = props;

    const [visiable, setVisiable] = useState(true);

    return (
        <div className="json-compare-line">
            <p
                className={`json-compare-p json-compare-line-${lineType}`}
                onClick={() => setVisiable(!visiable)}
                style={getIndent(level)}
            >
                <span className="json-compare-mark">{line}</span>
                <span className={`json-compare-of-${lineType}`}></span>
                <span className="json-compare-content">
                    {showIndex && <span className="json-compare-key">{name}: </span>}
                    <span className="json-compare-pt">{isArray(type) ? '[' : '{'}</span>
                </span>
                {!visiable && (
                    <span className="json-compare-pt">
                        {isArray(type) ? '...]' : '...}'}
                        {needComma ? ',' : ''}
                    </span>
                )}
            </p>
            <div style={{ display: visiable ? 'block' : 'none' }}>
                {value.map((item, index) => (
                    <Tree key={index} level={level + 1} {...item} />
                ))}
                <p
                    className={`json-compare-feet json-compare-p json-compare-line-${lineType}`}
                    style={getIndent(level)}
                >
                    {lastLine && <span className="json-compare-mark">{lastLine}</span>}
                    {lastLineType && <span className={`json-compare-of-${lastLineType}`}></span>}
                    <span className="json-compare-pt">
                        {isArray(type) ? ']' : '}'}
                        {needComma ? ',' : ''}
                    </span>
                </p>
            </div>
        </div>
    );
}

function Tree(props) {
    const { type } = props;
    if (needFormat(type)) {
        return <ComplexTree {...props} />;
    }
    return <NormalTree {...props} />;
}

export default function JsonViewCompare({ oldData, newData }) {
    const [data, setMergeData] = useState([]);

    useEffect(() => {
        setMergeData(mergeData(oldData, newData).result);
    }, [oldData, newData]);

    return (
        <pre className="json-compare-view hidden-scrollbar">
            <p className="json-compare-outter">{isArray(newData) ? '[' : '{'}</p>
            {data.map((item, index) => (
                <Tree key={index} {...item} />
            ))}
            <p className="json-compare-outter">{isArray(newData) ? ']' : '}'}</p>
        </pre>
    );
}