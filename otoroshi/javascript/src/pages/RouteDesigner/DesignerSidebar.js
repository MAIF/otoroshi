import React from 'react';
import { Link } from 'react-router-dom';

export default ({ route }) => (
  <ul className="nav flex-column nav-sidebar">
    <li className="nav-item">
      <h3>
        <span>
          <span className="fas fa-server" /> {route.name}
        </span>
      </h3>
    </li>
    <li className="nav-item">
      <Link to={`/routes/${route.id}?tab=informations`} className="nav-link">
        Route informations
      </Link>
    </li>
    <li className="nav-item">
      <Link to={`/routes/${route.id}?tab=flow`} className="nav-link">
        Route designer
      </Link>
    </li>
    <li className="nav-item">
      <Link to={`/routes/${route.id}?tab=try-it`} className="nav-link">
        Route tester
      </Link>
    </li>
  </ul>
);
