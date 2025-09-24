import React from 'react';
import { Button } from '../../../components/Button';

export function NewTask({ onClick }) {
  return (
    <Button type="primaryColor" className="new-task" onClick={onClick}>
      <i className="fas fa-plus" />
    </Button>
  );
}
