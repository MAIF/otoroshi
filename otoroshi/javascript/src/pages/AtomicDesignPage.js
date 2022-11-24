import React, { Component } from "react";
import { Button } from "../components/Button";
import { SimpleBooleanInput } from "../components/inputs";

export class AtomicDesignPage extends Component {
  render() {
    return (
      <div className="mt-5">
        <p>Basic HTML elements</p>
        <h1>h1</h1>
        <h2>h2</h2>
        <h3>h3</h3>
        <hr />
        <p style={{color:"var(--color-primary)"}}>Buttons</p>
        <Button type="primary" className="btn-sm" text="primary sm" style={{margin:10}}/>
        <Button type="primary" text="primary" style={{margin:10}}/>
        <Button type="primary" text="primary & disabled" disabled="true" style={{margin:10}}/>
        <br />
        <Button type="info" text="info" style={{margin:10}}/>
        <br/>
        {/* danger */}
        <Button type="danger" className="btn-sm" text="danger sm" style={{margin:10}} />
        <Button type="danger" text="danger" style={{margin:10}}/>
        <Button type="danger" text="danger & disabled" disabled="true" style={{margin:10}}/>

        <br />
        <Button type="success" className="btn-sm" text="success" style={{margin:10}}/>
        <Button type="success" text="success" style={{margin:10}}/>
        <Button type="success" text="success & disabled" disabled="true" style={{margin:10}}/>
        <br />
        <SimpleBooleanInput value="disabled" onChange={(e) => e} />
        <hr />
        <p style={{color:"var(--color-primary)"}}>Badges</p>
        <span className="badge bg-danger">badge bg-danger</span>{" "}
        <span className="badge bg-success">badge bg-success</span>
        <hr />
        <p style={{color:"var(--color-primary)"}}>Colors global</p>
        <span style={{ backgroundColor: "var(--color-green)", }} >  --color-green </span>
        <span style={{ backgroundColor: "var(--color-blue)", }} > --color-blue </span>
        <span style={{ backgroundColor: "var(--color-red)", }} > --color-red </span>

        <p style={{color:"var(--color-primary)"}}>Colors Dark Mode</p>
        <span style={{ backgroundColor: "var(--color-primary)", }} > --color-primary </span>
        <span style={{ backgroundColor: "var(--color-primary-lighter)", }} > --color-primary-lighter </span>

        <span style={{ backgroundColor: "var(--bg-color_primary)", }} > --bg-color_primary </span>
        <span style={{ backgroundColor: "var(--bg-color_1)", }} > --bg-color_1 </span>
        <span style={{ backgroundColor: "var(--bg-color_2)", }} > --bg-color_2 </span>
        <span style={{ backgroundColor: "var(--color_2)", }} > --color_2 </span>
        <p style={{color:"var(--color-primary)"}}>Colors Light Mode</p>

      </div>
    );
  }
}
