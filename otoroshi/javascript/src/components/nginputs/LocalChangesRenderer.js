import React from "react";
import { Button } from "../Button";

export class LocalChangesRenderer extends React.Component {

    state = {
        folded: true
    }

    render() {
        const { validation, itemProps, onChange } = this.props
        const Renderer = this.props.renderer


        const { schema, flow } = itemProps;
        
        const v2Props = itemProps.schema.props?.v2 || {};

        const { folded } = this.state;

        return <div style={{
            position: 'relative',
            minHeight: 120
        }}>
            <Renderer
                validation={validation}
                {...itemProps}
                embedded
                readOnly={folded}
                onChange={onChange}
                schema={schema.schema || schema}
                flow={folded ? v2Props.folded : v2Props.flow}
                rawSchema={schema}
                rawFlow={flow}
            />
            {folded && <Button type="info" className="btn-sm" style={{
                position: 'absolute',
                top: 12,
                // bottom: 0,
                margin: 'auto',
                right: 12,
                height: 32
            }} onClick={() => {
                this.setState({ folded: false })
            }}>
                Change
            </Button>}
        </div>
    }
}