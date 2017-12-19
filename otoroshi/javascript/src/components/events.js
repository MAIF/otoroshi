export class Events {
  listeners = [];

  dispatch = what => {
    setTimeout(() => {
      this.listeners.forEach(listener => listener(what));
    });
  };

  subscribe = listener => {
    this.listeners.push(listener);
    return () => {
      const index = this.listeners.indexOf(listener);
      this.listeners.splice(index, 1);
    };
  };
}
