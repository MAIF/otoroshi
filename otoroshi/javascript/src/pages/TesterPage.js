import React, { Suspense, useEffect, useState } from 'react';
import { NgSelectRenderer } from '../components/nginputs';

const TryIt = React.lazy(() => import('./RouteDesigner/TryIt'));

export function TesterPage({ setTitle }) {

  const [item, setItem] = useState()

  useEffect(() => {
    setTitle('Tester');
  }, []);

  return <div>
    <NgSelectRenderer
      id="entities"
      ngOptions={{ spread: true }}
      value={item}
      options={this.state.tenants}
      onChange={this.onChange}
      style={{ width: '200px' }} />
    {route && <Suspense fallback={null}>
      <TryIt route={route} />
    </Suspense>}
  </div>
}
