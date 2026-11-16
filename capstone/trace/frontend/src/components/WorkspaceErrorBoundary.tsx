import { Component, type ReactNode } from "react";
export class WorkspaceErrorBoundary extends Component<
  { children: ReactNode },
  { failed: boolean }
> {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  render() {
    return this.state.failed ? (
      <section className="panel" role="alert">
        <h2>This workspace could not be displayed</h2>
        <p>
          Reload the workspace to try again, or open another resource from the
          catalog.
        </p>
        <div className="action-row">
          <button onClick={() => window.location.reload()}>
            Reload workspace
          </button>
          <a className="button" href="#catalog">
            Open catalog
          </a>
        </div>
      </section>
    ) : (
      this.props.children
    );
  }
}
