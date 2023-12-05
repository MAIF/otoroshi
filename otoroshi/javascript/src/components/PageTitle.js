import React, { useEffect } from "react";
import { Button } from "./Button";

export default function PageTitle({
  title,
  children,
  style = {},
  className = "ms-0 mb-3",
}) {
  return (
    <div
      className={`page-header_title d-flex align-item-center justify-content-between ${className}`}
      style={style}
    >
      <div className="d-flex">
        <h3 className="m-0 align-self-center">{title}</h3>
        <Button
          type="quiet"
          // disabled={shortcutDisabled}
          title="Add current page to sidebar shortcuts"
          // onClick={addShortcut}
          className="ms-3 btn-sm align-self-center"
        >
          <i className="fas fa-thumbtack"></i>
        </Button>
      </div>
      <div className="d-flex align-item-center justify-content-between">
        {children}
      </div>
    </div>
  );
}
