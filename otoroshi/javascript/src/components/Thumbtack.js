import { useContext } from "react";
import { Button } from "./Button";
import { SidebarContext } from "../apps/BackOfficeApp";

export default function Thumbtack() {

  const context = useContext(SidebarContext);

  console.log(context)

  const addShortcut = () => {
    const pathname = window.location.pathname;
    let title = this.props.getTitle() || document.title;
    if (pathname === '/bo/dashboard' || pathname === '/bo/dashboard/') {
      title = 'Home';
    }
    let icon = 'fa-star';
    const link = (window.location.pathname + window.location.search + window.location.hash).replace(
      '/bo/dashboard',
      ''
    );
    const feats = graph(this.props.env).flatMap((l) => l.features);
    feats.find((f) => {
      if (pathname.startsWith('/bo/dashboard' + f.link)) {
        icon = f.icon();
        if (_.isObject(icon)) {
          icon = 'fa-snow-monkey';
        }
        if (pathname.includes(`/edit/`)) {
          title = 'Edit ' + f.title.toLowerCase();
        } else if (pathname.endsWith('/add')) {
          title = 'Create an ' + f.title.toLowerCase();
        } else {
          title = f.title;
        }
      }
    });
    window.newPrompt('Shortcut title ?', { value: title }).then((newTitle) => {
      if (newTitle) {
        fetch('/bo/api/me/preferences/backoffice_sidebar_shortcuts', {
          method: 'GET',
          credentials: 'include',
          headers: {
            Accept: 'application/json',
          },
        }).then((r) => {
          if (r.status === 200) {
            return r.json().then((shortcurts) => {
              const found = shortcurts.find((s) => {
                if (_.isObject(s)) {
                  return s.link === link;
                } else {
                  return false;
                }
              });
              let newShortCuts = [...shortcurts, { title: newTitle, link, icon }];
              if (found) {
                newShortCuts = [...shortcurts];
              }
              fetch('/bo/api/me/preferences/backoffice_sidebar_shortcuts', {
                method: 'POST',
                credentials: 'include',
                headers: {
                  Accept: 'application/json',
                  'Content-Type': 'application/json',
                },
                body: JSON.stringify(newShortCuts),
              }).then((r) => {
                this.props.reloadEnv();
              });
            });
          }
        });
      }
    });
  }

  let shortcutDisabled = !!shortcuts
    .filter((s) => _.isObject(s))
    .find((s) => '/bo/dashboard' + s.link === window.location.pathname);
  if (!shortcutDisabled) {
    const feats = graph(this.props.env).flatMap((l) => l.features);
    const found = feats.find((f) => '/bo/dashboard' + f.link === window.location.pathname);
    if (found) {
      shortcutDisabled = !!shortcuts.find((s) => s === found.title.toLowerCase());
    }
  }

  return <Button
    type="quiet"
    disabled={shortcutDisabled}
    title="Add current page to sidebar shortcuts"
    onClick={addShortcut}
    className="ms-3 btn-sm align-self-center"
  >
    <i className="fas fa-thumbtack"></i>
  </Button>
}