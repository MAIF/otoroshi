import React, { Component } from 'react';
import ReactTooltip from 'react-tooltip';
import faker from 'faker';


export class Help extends Component {
  render() {
    const shouldRender = this.props.text && this.props.text !== '' && this.props.text !== '...';
    const randomId=faker.random.uuid();
    if (shouldRender) {
      return (
          <span>
            <i
              className="far fa-question-circle cursor-pointer"
                data-tip data-for={`registerTip-${randomId}`}
            />
            <ReactTooltip id={`registerTip-${randomId}`} place="top" effect="solid" multiline={true} className="tooltip">
                {this.props.text}
            </ReactTooltip>
        </span>
      );
    }
    return null;
  }
}
