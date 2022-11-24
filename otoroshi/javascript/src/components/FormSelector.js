import React, { useEffect, useState } from 'react';
import { Button } from './Button';

const FORM_SELECTOR_KEY = 'otoroshi-entities-form-selector';
export const ENTITIES = {
  ROUTES: 'routes',
  BACKENDS: 'backends',
  SERVICES: 'services',
  JWT_VERIFIERS: 'jwt-verifiers',
  AUTH_MODULES: 'auth-modules',
  API_KEYS: 'api-keys',
};

export function FormSelector({ onChange, entity, className = '' }) {

  const [showLegacyForm, toggleLegacyForm] = useState(false);

  useEffect(() => {
    loadEntities(entity);
  }, [entity]);

  const loadEntities = (entity) => {
    try {
      const values = JSON.parse(localStorage.getItem(FORM_SELECTOR_KEY) || '{}');
      const showLegacy = values[entity] === 'true'
      toggleLegacyForm(showLegacy);
      onChange(showLegacy);
    } catch (e) {
      console.log(e);
    }
  };

  const saveEntities = (entity, value) => {
    try {
      const values = JSON.parse(localStorage.getItem(FORM_SELECTOR_KEY) || '{}');
      localStorage.setItem(
        FORM_SELECTOR_KEY,
        JSON.stringify({
          ...values,
          [entity]: value,
        })
      );
    } catch (e) {
      console.log(e);
    }
  };

  return (
    <Button
      type="info"
      className={`btn-sm ${className}`}
      onClick={() => {
        toggleLegacyForm(!showLegacyForm);
        onChange(!showLegacyForm);
        saveEntities(entity, !showLegacyForm);
      }}
      text={showLegacyForm ? 'Simple view' : 'Advanced view'}
    />
  );
}
