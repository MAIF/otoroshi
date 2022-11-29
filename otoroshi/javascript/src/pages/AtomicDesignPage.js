import React, { Component } from "react";
import { Button } from "../components/Button";
import { SimpleBooleanInput } from "../components/inputs";
import { PillButton } from "../components/PillButton";
import { SquareButton } from "../components/SquareButton";
import { FeedbackButton } from "./RouteDesigner/FeedbackButton";

export class AtomicDesignPage extends Component {
  state = {
    pillButton: false,
    booleanInput: false
  }

  feedbackCallback = () => {
    return new Promise(resolve => {
      setTimeout(resolve, 1500);
    })
  }

  failedFeedbackCallback = () => {
    return new Promise((_, reject) => {
      setTimeout(() => {
        reject(new Error('an error occured'))
      }, 1500);
    })
  }

  render() {
    const {
      pillButton,
      booleanInput
    } = this.state;

    return (
      <div className="mt-5">
        <p>Basic HTML elements</p>
        <h1>h1</h1>
        <h2>h2</h2>
        <h3>h3</h3>
        <hr />
        <p style={{ color: "var(--color-primary)" }}>Buttons</p>
        <Button type="primary" className="btn-sm" text="primary sm" style={{ margin: 10 }} />
        <Button type="primary" text="primary" style={{ margin: 10 }} />
        <Button type="primary" text="primary & disabled" disabled="true" style={{ margin: 10 }} />
        <br />
        <Button type="info" text="info" style={{ margin: 10 }} />
        <br />
        {/* danger */}
        <Button type="danger" className="btn-sm" text="danger sm" style={{ margin: 10 }} />
        <Button type="danger" text="danger" style={{ margin: 10 }} />
        <Button type="danger" text="danger & disabled" disabled="true" style={{ margin: 10 }} />
        <br />
        <Button type="success" className="btn-sm" text="success" style={{ margin: 10 }} />
        <Button type="success" text="success" style={{ margin: 10 }} />
        <Button type="success" text="success & disabled" disabled="true" style={{ margin: 10 }} />
        <br />
        <Button type="save" className="btn-sm" text="save" style={{ margin: 10 }} />
        <Button type="save" text="save" style={{ margin: 10 }} />
        <Button type="save" text="save & disabled" disabled="true" style={{ margin: 10 }} />
        <br />
        <Button type="default" className="btn-sm" text="default" style={{ margin: 10 }} />
        <Button type="default" text="default" style={{ margin: 10 }} />
        <Button type="default" text="default & disabled" disabled="true" style={{ margin: 10 }} />
        <br />
        <div>
          <SquareButton
            level="info"
            className="btn-sm"
            onClick={() => { }}
            text="Generate password"
            icon="fa-cog" />
          <SquareButton
            level="danger"
            className="btn-sm"
            onClick={() => { }}
            text="Generate password"
            icon="fa-cog" />
        </div>
        <br />
        <FeedbackButton
          className="me-3"
          onPress={this.feedbackCallback}
          text="Save"
          icon={() => <i className="fas fa-paper-plane" />} />
        <FeedbackButton
          onPress={this.failedFeedbackCallback}
          text="Failed save"
          icon={() => <i className="fas fa-paper-plane" />} />
        <br />
        <p style={{ color: "var(--color-primary)" }}>Pill button</p>
        <PillButton
          rightEnabled={pillButton}
          onChange={() => this.setState({ pillButton: !pillButton })}
          leftText="Design"
          rightText="Content"
        />
        <p style={{ color: "var(--color-primary)" }}>Boolean</p>
        <SimpleBooleanInput value={booleanInput} onChange={v => this.setState({ booleanInput: v })} />
        <hr />
        <p style={{ color: "var(--color-primary)" }}>Badges</p>
        <span className="badge bg-danger">badge bg-danger</span>{" "}
        <span className="badge bg-success">badge bg-success</span>
        <hr />
        <p style={{ color: "var(--color-primary)" }}>Colors global</p>
        <span style={{ backgroundColor: "var(--color-green)", }} >  --color-green </span>
        <span style={{ backgroundColor: "var(--color-blue)", }} > --color-blue </span>
        <span style={{ backgroundColor: "var(--color-red)", }} > --color-red </span>
        <span style={{ backgroundColor: "var(--color-primary)", }} > --color-primary </span>
        <span style={{ backgroundColor: "var(--color-primary-lighter)", }} > --color-primary-lighter </span>

        <p style={{ color: "var(--color-primary)" }}>Colors & bgcolors levels for Dark/White Mode</p>
        <div style={{ width: 400, height: 400, backgroundColor: "var(--bg-color_level1)", color: "var(--color_level1)" }}>
          Level 1
          <div style={{ width: 200, height: 200, margin: '0 auto', backgroundColor: "var(--bg-color_level2)", color: "var(--color_level2)" }}>
            Level 2
          </div>

        </div>
        <span style={{ backgroundColor: "var(--bg-color_level1)", color: "var(--color_level1)" }} > --bg-color_level1 & --color_level1 </span>
        <span style={{ backgroundColor: "var(--bg-color_level2)", color: "var(--color_level2)" }} > --bg-color_level2 & --color_level2 </span>

      </div>
    );
  }
}
