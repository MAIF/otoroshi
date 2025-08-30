import React, { useContext } from 'react';
import { Button } from '../../components/Button';
import { SidebarContext } from '../../apps/BackOfficeApp';

export function DesignerActions({ run }) {
  const sidebar = useContext(SidebarContext);

  return (
    <div
      className="designer-actions"
      style={{
        left: sidebar.openedSidebar ? 250 : 48,
      }}
    >
      <Button type="primaryColor" className="p-2 px-4" onClick={run}>
        <i className="fas fa-flask me-1" />
        Test Workflow
      </Button>
      {/* <Button type="primaryColor">
            <i className='fas fa-trash' />
        </Button> */}
    </div>
  );
}
