import { signal } from 'signals-react-safe';

// Cross-component "spotlight" signal: stepper sets the target testid, the
// destination component (currently DashboardTitle) reads it and pulses the
// matching button. Cleared automatically after the highlight animation.
export const signalHighlight = signal(null);
