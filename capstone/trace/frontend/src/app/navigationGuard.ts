export const beforeLeave = "trace:before-leave";

export function allowNavigation(resume: () => void) {
  return window.dispatchEvent(
    new CustomEvent(beforeLeave, { cancelable: true, detail: resume }),
  );
}
