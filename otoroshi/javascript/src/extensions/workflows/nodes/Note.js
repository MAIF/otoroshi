import { NodeResizer } from '@xyflow/react';
import React, { useLayoutEffect } from 'react'
import { CompactPicker } from 'react-color';
import { Row } from '../../../components/Row';


export const NoteNode = {
    kind: 'note',
    type: 'note',
    description: "Note",
    display_name: 'Note',
    icon: 'fas fa-sticky-note',
    flow: ['content', 'titleColor', 'color'],
    form_schema: {
        content: {
            type: 'string',
            label: "Content"
        },
        titleColor: {
            renderer: props => {
                return <Row title="Title color">
                    <CompactPicker
                        color={props.value}
                        onChangeComplete={(color) => props.onChange(color.hex)}
                    />
                </Row>
            }
        },
        color: {
            renderer: props => {
                return <Row title="Color">
                    <CompactPicker
                        color={props.value}
                        onChangeComplete={(color) => props.onChange(color.hex)}
                    />
                </Row>
            }
        }
    }
};

export const NoteRenderer = (props) => {
    const { data, selected } = props;

    useLayoutEffect(() => {
        const sourceEl = document.querySelector(`[data-id="${props.id}"]`);

        if (data.operators) sourceEl?.classList.add('note');
    }, []);

    return <button
        className='btn'
        onDoubleClick={(e) => {
            e.stopPropagation();
            data.functions.onDoubleClick(props);
        }} style={{
            color: data.content.titleColor ? data.content.titleColor : '#fff',
            backgroundColor: data.content.color ? data.content.color : 'var(--bg-color_level3)'
        }}>
        <NodeResizer
            color="#f9b000"
            minWidth={150}
            isVisible={selected}
            minHeight={100} />
        {data.content.content}
    </button>
}