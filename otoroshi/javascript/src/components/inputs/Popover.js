import React, { Component } from 'react';
import $ from 'jquery';

export default class Popover extends Component {
  componentDidMount() {
    $(this._ref)
      .popover(this.props.options)
      .popover(this.props.state);
  }

  render() {
    return (
      <div id="test-5" type="button" className="btn btn-lg btn-danger" ref={r => (this._ref = r)}>
        {this.props.children}
      </div>
    );
  }
}

export function popover(options, state = 'hide', closeAfter) {
  return {
    ref: r => {
      setTimeout(() => {
        $(r)
          .popover(options)
          .on('shown.bs.popover', eventShown => {
            const $popup = $('#' + $(eventShown.target).attr('aria-describedby'));
            $popup.find('button.cancel').click(e => {
              $popup.popover('hide');
            });
          })
          .popover(state);
        if (closeAfter) {
          setTimeout(() => {
            $(r).popover('destroy');
          }, closeAfter);
        }
      }, 2000);
    },
  };
}

export class DefaultAdminPopover extends Component {
  state = {
    alreadyPopped: false,
  };

  componentDidMount() {
    setTimeout(() => {
      this.createPopover();
    }, 2000);
  }

  createPopover = () => {
    if (this.ref && !this.state.alreadyPopped) {
      this.setState({ alreadyPopped: true }, () => {
        $(this.ref)
          .popover({
            html: 'true',
            container: 'body',
            placement: 'bottom',
          })
          .popover('show');
        setTimeout(() => {
          $('.popovercancel').on('click', e => {
            $(this.ref).popover('hide');
          });
        }, 1000);
      });
    }
  };

  render() {
    return (
      <li>
        <a
          ref={r => (this.ref = r)}
          data-trigger="focus"
          tabIndex="0"
          role="button"
          style={{
            paddingLeft: 0,
            paddingRight: 0,
            marginLeft: 0,
            marginRight: 0,
            marginTop: 15,
            width: 0,
          }}
          data-toggle="popover"
          title={`<span><strong>Create an admin user</strong></span><button type="button" class="close cancel pull-right popovercancel" >&times;</button>`}
          data-content="You're using a temporary admin user with a default (and very unsecure) password, please <a href='/bo/dashboard/admins'>create a dedicated one here</a>"
        />
      </li>
    );
  }
}
