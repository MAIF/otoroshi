import React from "react";
import './Sidebar.css'

const SidebarContext = React.createContext();

const MIN_SIDEBAR_WIDTH = 140;

class Sidebar extends React.Component {
  constructor(props) {
    super(props);
    this.state = {
      width: 250
    };

    this.props.setContext({
      open: this.state.width > MIN_SIDEBAR_WIDTH ? true : false,
      ButtonWhenHidden: () => <ButtonWhenHidden onClick={() => this.onWidthChange(250)} />
    })
  }

  componentDidUpdate(nextProps) {
    if (this.props.context.open !== nextProps.context.open) {
      this.onWidthChange(this.props.context.open ? 250 : 52)
    }
  }

  onWidthChange = newValue => {
    this.props.setContext({
      open: newValue > MIN_SIDEBAR_WIDTH ? true : false,
      sidebarSize: newValue,
      ButtonWhenHidden: () => <ButtonWhenHidden onClick={() => this.onWidthChange(250)} />
    });
    this.setState({
      width: newValue
    })
  }

  toggle = () => {
    this.onWidthChange(this.state.width > 52 ? 52 : 250)
  }

  render() {
    return <div className="sidebar sidebar-resize-handle"
      style={{ width: this.state.width, zIndex: 100 }}>
      <div className='d-flex flex-column sidebar-container'
        style={{
          height: '100vh'
        }}
      >
        {this.props.children}
      </div>
    </div>
  }
}

function ButtonWhenHidden({ onClick }) {
  return <button type="button" className='d-flex align-items-center justify-content-center py-3' style={{
    background: '#ddd',
    border: 'none'
  }} onClick={onClick}>
    <i className="fa fa-bars" />
  </button>
}

export { Sidebar, SidebarContext };