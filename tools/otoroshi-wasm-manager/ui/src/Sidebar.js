import React from "react";
import './Sidebar.css'

const SidebarContext = React.createContext();

const MIN_SIDEBAR_WIDTH = 140;

class Sidebar extends React.Component {
  constructor(props) {
    super(props);
    this.state = {
      width: 250,
      isResizing: false,
      lastDownX: 0
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

  componentDidMount() {
    document.addEventListener("mousemove", this.onMouseMove);
    document.addEventListener("mouseup", this.onMouseUp);
  }

  componentWillUnmount() {
    document.removeEventListener("mousemove", this.onMouseMove);
    document.removeEventListener("mouseup", this.onMouseUp);
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

  onMouseDown = e => {
    e.stopPropagation()
    this.setState({
      isResizing: true,
      lastDownX: e.clientX
    });
  };

  onMouseUp = e => {
    e.stopPropagation()
    if (this.state.isResizing) {
      this.setState({ isResizing: false });
    }
  };

  onMouseMove = e => {
    e.stopPropagation()
    // update the width of the sidebar if the user is resizing it
    if (this.state.isResizing) {
      const delta = e.clientX - this.state.lastDownX;
      const newWidth = this.state.width + delta;

      if (newWidth < MIN_SIDEBAR_WIDTH) {
        this.onWidthChange(52)
        this.setState({
          lastDownX: e.clientX
        });
      }
      else if (newWidth >= 52 && newWidth <= 250) {
        this.onWidthChange(newWidth)
        this.setState({
          lastDownX: e.clientX
        });
      }
    }
  };

  toggle = () => {
    this.onWidthChange(this.state.width > 52 ? 52 : 250)
  }

  render() {
    return <div className="sidebar sidebar-resize-handle"
      style={{ width: this.state.width, zIndex: 100 }}
      onMouseDown={this.onMouseDown}>
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