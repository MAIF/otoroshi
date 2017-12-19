import React, { Component } from 'react';

export class Loader extends Component {
  state = {
    bigDot: 0,
  };

  next = () => {
    if (!this.mounted) {
      return;
    }
    if (this.state.bigDot === 2) {
      this.setState({ bigDot: 0 }, () => {
        this.timeout = setTimeout(this.next, 200);
      });
    } else {
      this.setState({ bigDot: this.state.bigDot + 1 }, () => {
        this.timeout = setTimeout(this.next, 200);
      });
    }
  };

  componentDidMount() {
    this.mounted = true;
    this.timeout = setTimeout(this.next, 0);
  }

  componentWillUnmount() {
    this.mounted = false;
    clearTimeout(this.timeout);
  }

  render() {
    return (
      <div style={{ color: 'black' }}>
        <span
          style={{
            fontSize: this.state.bigDot === 0 ? 16 : 12,
            fontWeight: this.state.bigDot === 0 ? 'bold' : 'normal',
          }}>
          {'.'}
        </span>
        <span
          style={{
            fontSize: this.state.bigDot === 1 ? 16 : 12,
            fontWeight: this.state.bigDot === 1 ? 'bold' : 'normal',
          }}>
          {'.'}
        </span>
        <span
          style={{
            fontSize: this.state.bigDot === 2 ? 16 : 12,
            fontWeight: this.state.bigDot === 2 ? 'bold' : 'normal',
          }}>
          {'.'}
        </span>
      </div>
    );
  }
}
