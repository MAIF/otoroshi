
import { ReactComponent as Js } from './assets/js.svg';
import { ReactComponent as Rust } from './assets/rust.svg';
import { ReactComponent as Json } from './assets/json.svg';
import { ReactComponent as Ts } from './assets/ts.svg';
import { ReactComponent as Go } from './assets/go.svg';
import { ReactComponent as OPA } from './assets/opa.svg';
import { ReactComponent as Github } from './assets/github.svg';

const LOGOS = {
  js: <Js style={{ height: 20, width: 20 }} />,
  json: <div className='d-flex justify-content-center'>
    <Json style={{ height: 18 }} />
  </div>,
  log: <i className='fas fa-file' style={{ fontSize: '.9em' }} />,
  rs: <Rust style={{ height: 30, width: 30, marginLeft: -4, transform: 'scale(.85)' }} />,
  toml: <i className='fas fa-file' />,
  ts: <Ts style={{ height: 22, width: 22, marginBottom: 2 }} />,
  go: <Go style={{ height: 22, width: 22 }} />,
  opa: <OPA style={{ height: 22, width: 22 }} />,
  github:  <Github style={{ height: 22, width: 22 }} />
};

LOGOS.rust = LOGOS.rs;

export { LOGOS };