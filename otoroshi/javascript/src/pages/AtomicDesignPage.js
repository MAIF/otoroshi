import React, { Component } from 'react';
import { Button } from '../components/Button';
import { PillButton } from '../components/PillButton';
import { SquareButton } from '../components/SquareButton';
import { FeedbackButton } from './RouteDesigner/FeedbackButton';
import {
  TextInput,
  SelectInput,
  SimpleBooleanInput,
  ArrayInput,
  NumberInput,
} from '../components/inputs';

import { NgSelectRenderer, LabelAndInput, NgForm } from '../components/nginputs';
export class AtomicDesignPage extends Component {
  state = {
    pillButton: false,
    booleanInput: false,
  };

  feedbackCallback = () => {
    return new Promise((resolve) => {
      setTimeout(resolve, 1500);
    });
  };

  failedFeedbackCallback = () => {
    return new Promise((_, reject) => {
      setTimeout(() => {
        reject(new Error('an error occured'));
      }, 1500);
    });
  };

  render() {
    const { pillButton, booleanInput } = this.state;

    return (
      <div className="mt-5">
        <p style={{ color: 'var(--color-primary)' }}>Basic HTML elements</p>
        <h1>h1</h1>
        <h2>h2</h2>
        <h3>h3</h3>
        <h4>h4</h4>
        <hr />
        <p style={{ color: 'var(--color-primary)' }}>Buttons (personalization : text)</p>
        <Button type="primary" className="btn-sm" text="primary sm" style={{ margin: 10 }} />
        <Button type="primary" text="primary" style={{ margin: 10 }} />
        <Button type="primary" text="primary & disabled" disabled={true} style={{ margin: 10 }} />
        <br />
        <Button type="danger" className="btn-sm" text="danger sm" style={{ margin: 10 }} />
        <Button type="danger" text="danger" style={{ margin: 10 }} />
        <Button type="danger" text="danger & disabled" disabled={true} style={{ margin: 10 }} />
        <br />
        <Button type="success" className="btn-sm" text="success sm" style={{ margin: 10 }} />
        <Button type="success" text="success" style={{ margin: 10 }} />
        <Button type="success" text="success & disabled" disabled={true} style={{ margin: 10 }} />
        <br />
        <Button type="save" className="btn-sm" text="save sm" style={{ margin: 10 }} />
        <Button type="save" text="save" style={{ margin: 10 }} />
        <Button type="save" text="save & disabled" disabled={true} style={{ margin: 10 }} />
        <br />
        <Button type="quiet" className="btn-sm" text="quiet sm" style={{ margin: 10 }} />
        <Button type="quiet" text="quiet" style={{ margin: 10 }} />
        <Button type="quiet" text="quiet & disabled" disabled={true} style={{ margin: 10 }} />
        <br />
        <Button
          type="primaryColor"
          className="btn-sm"
          text="primaryColor sm"
          style={{ margin: 10 }}
        />
        <Button type="primaryColor" text="primaryColor" style={{ margin: 10 }} />
        <Button
          type="primaryColor"
          text="primaryColor & disabled"
          disabled={true}
          style={{ margin: 10 }}
        />
        <br />
        <Button type="dark" className="btn-sm" text="dark sm" style={{ margin: 10 }} />
        <Button type="dark" text="dark" style={{ margin: 10 }} />
        <Button type="dark" text="dark & disabled" disabled={true} style={{ margin: 10 }} />
        <br />
        <Button type="default" className="btn-sm" text="default sm" style={{ margin: 10 }} />
        <Button type="default" text="default" style={{ margin: 10 }} />
        <Button type="default" text="default & disabled" disabled={true} style={{ margin: 10 }} />
        <br />
        <p style={{ color: 'var(--color-primary)' }}>
          Button + icon (personalization : button type, text, icon)
        </p>
        <Button type="primary" className="btn-sm">
          <i className="fas fa-key me-1" />
          Button type - icon - text
        </Button>
        <br />
        <br />
        <p style={{ color: 'var(--color-primary)' }}>
          Square button (personalization : button type, text, icon)
        </p>
        <div className="d-flex">
          <SquareButton
            level="primary"
            className="me-3"
            onClick={() => {}}
            text="primary - cog"
            icon="fa-cog"
          />
          <SquareButton level="danger" onClick={() => {}} text="danger - cog" icon="fa-cog" />
        </div>
        <br />
        <p style={{ color: 'var(--color-primary)' }}>
          Feedback button (personalization : text, icon)
        </p>
        <FeedbackButton
          className="me-3"
          onPress={this.feedbackCallback}
          text="Save"
          icon={() => <i className="fas fa-paper-plane" />}
        />
        <FeedbackButton
          onPress={this.failedFeedbackCallback}
          text="Failed save"
          icon={() => <i className="fas fa-paper-plane" />}
        />
        <br />
        <br />
        <p style={{ color: 'var(--color-primary)' }}>Pill button (personalization : texts)</p>
        <PillButton
          rightEnabled={pillButton}
          onChange={() => this.setState({ pillButton: !pillButton })}
          leftText="Design"
          rightText="Content"
        />
        <p style={{ color: 'var(--color-primary)' }}>Boolean</p>
        <SimpleBooleanInput
          value={booleanInput}
          onChange={(v) => this.setState({ booleanInput: v })}
        />
        <hr />
        <p style={{ color: 'var(--color-primary)' }}>Badges</p>
        <span className="badge bg-danger">badge bg-danger</span>{' '}
        <span className="badge bg-success">badge bg-success</span>
        <hr />
        <p style={{ color: 'var(--color-primary)' }}>Colors global</p>
        <table>
          <tbody>
            <tr>
              <td style={{ paddingLeft: 10, paddingRight: 10 }}>--color-green</td>
              <td style={{ paddingLeft: 10, paddingRight: 10 }}>--color-blue</td>
              <td style={{ paddingLeft: 10, paddingRight: 10 }}>--color-red</td>
              <td style={{ paddingLeft: 10, paddingRight: 10 }}>--color-primary</td>
              <td style={{ paddingLeft: 10, paddingRight: 10 }}>--color-primary-lighter</td>
            </tr>
            <tr style={{ height: 30 }}>
              <td style={{ backgroundColor: 'var(--color-green)' }}></td>
              <td style={{ backgroundColor: 'var(--color-blue)' }}></td>
              <td style={{ backgroundColor: 'var(--color-red)' }}></td>
              <td style={{ backgroundColor: 'var(--color-primary)' }}></td>
              <td style={{ backgroundColor: 'var(--color-primary-lighter)' }}></td>
            </tr>
          </tbody>
        </table>
        <br />
        <br />
        <p style={{ color: 'var(--color-primary)' }}>
          Colors & bg-colors levels for Dark/White Mode
        </p>
        <div
          style={{
            width: 400,
            backgroundColor: 'var(--bg-color_level1)',
            color: 'var(--color_level1)',
            border: '1px solid',
            padding: 10,
          }}
        >
          --bg-color_level1 & --color_level1
          <div
            style={{
              width: 300,
              margin: '0 auto',
              backgroundColor: 'var(--bg-color_level2)',
              color: 'var(--color_level2)',
              padding: 10,
            }}
          >
            --bg-color_level2 & --color_level2
            <div
              style={{
                width: 200,
                margin: '0 auto',
                backgroundColor: 'var(--bg-color_level3)',
                color: 'var(--color_level3)',
                padding: 10,
              }}
            >
              --bg-color_level3 & --color_level3
            </div>
          </div>
        </div>
        <hr />
        <p style={{ color: 'var(--color-primary)' }}>Formulaires (old school)</p>
        TextInput (label, help, value)
        <TextInput label="My label" help="Here comes the label" value="TextInput" />
        TextInput with suffix (label, help, value)
        <TextInput label="My label" help="Here comes the label" value="TextInput" suffix="suffix" />
        TextInput with prefix (label, help, value)
        <TextInput label="My label" help="Here comes the label" value="TextInput" prefix="prefix" />
        NumberInput with suffix (label, help, value)
        <NumberInput label="My label" value="0" suffix="seconds" />
        SelectInput (label, help, values)
        <SelectInput
          label="My label"
          help="Here comes the help"
          value="plain"
          defaultValue="S256"
          possibleValues={[
            { value: 'S256', label: 'HMAC-SHA256' },
            { value: 'plain', label: 'PLAIN' },
          ]}
          onChange={(v) => {}}
        />
        ArrayInput (label, help, values)
        <ArrayInput
          label="My label"
          value={['value 2']}
          possibleValues={[
            { value: '1', label: 'Value 1' },
            { value: '2', label: 'value 2' },
          ]}
          onChange={(v) => {}}
          help="Here comes the help"
        />
        <p style={{ color: 'var(--color-primary)' }}>Formulaires (new way)</p>
        NgSelectRenderer
        <NgSelectRenderer
          id="rows-per-page"
          value="3"
          label={' '}
          options={[5, 15, 20, 50, 100]}
          onChange={(v) => {}}
        />
        LabelAndInput
        <LabelAndInput label="My label" help="Here comes the help">
          <NgForm
            schema={{
              header: {
                ngOptions: {
                  spread: true,
                },
                type: 'json',
                props: {
                  editorOnly: true,
                  height: '50px',
                  defaultValue: {
                    Authorization: 'Bearer XXX.XXX.XXX',
                  },
                },
              },
              result: {
                type: 'form',
                label: 'Form values',
                schema: {
                  headerName: {
                    type: 'string',
                    label: 'Name',
                    props: {
                      disabled: true,
                      defaultValue: 'Authorization',
                    },
                  },
                  remove: {
                    type: 'string',
                    label: 'Remove value',
                    props: {
                      disabled: true,
                      defaultValue: 'Bearer ',
                    },
                  },
                },
                flow: ['headerName', 'remove'],
              },
            }}
            flow={[
              {
                type: 'group',
                collapsable: false,
                name: 'A bearer token expected in Authorization header',
                fields: ['header', 'result'],
              },
            ]}
          />
        </LabelAndInput>
      </div>
    );
  }
}
