import { useRef, useState, type ReactNode } from "react";
import { Icon } from "./Design";

export function ContextNote({
  title,
  children,
}: {
  title: string;
  children: ReactNode;
}) {
  return (
    <div className="context-note">
      <strong>{title}</strong>
      {children}
    </div>
  );
}
export function Steps({
  labels,
  current,
}: {
  labels: string[];
  current: number;
}) {
  return (
    <ol className="workflow-steps" aria-label="Workflow progress">
      {labels.map((label, i) => (
        <li key={label} aria-current={i === current ? "step" : undefined}>
          <b>{i < current ? <Icon name="check" size={12} /> : i + 1}</b>
          {label}
        </li>
      ))}
    </ol>
  );
}
/** Keeps earlier inputs mounted so the final native FormData includes every step. */
export function GuidedFields({
  steps,
  busy = false,
  submitLabel,
}: {
  steps: { title: string; description: string; fields: ReactNode }[];
  busy?: boolean;
  submitLabel: string;
}) {
  const [step, setStep] = useState(0);
  const content = useRef<HTMLDivElement>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  function next() {
    const fields = content.current?.querySelectorAll<
      HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement
    >(
      '[data-current="true"] input, [data-current="true"] select, [data-current="true"] textarea',
    );
    if (fields && !Array.from(fields).every((field) => field.reportValidity()))
      return;
    setStep((n) => Math.min(n + 1, steps.length - 1));
    requestAnimationFrame(() => heading.current?.focus());
  }
  return (
    <div className="guide-content">
      <Steps labels={steps.map((s) => s.title)} current={step} />
      <h2 ref={heading} tabIndex={-1}>
        {steps[step].title}
      </h2>
      <p className="muted">{steps[step].description}</p>
      <div ref={content}>
        {steps.map((s, i) => (
          <div key={s.title} hidden={step !== i} data-current={step === i}>
            {s.fields}
          </div>
        ))}
      </div>
      <div className="guide-actions">
        <button
          type="button"
          disabled={busy || step === 0}
          onClick={() => setStep((n) => n - 1)}
        >
          Back
        </button>
        {step < steps.length - 1 ? (
          <button
            type="button"
            className="primary"
            disabled={busy}
            onClick={next}
          >
            Continue →
          </button>
        ) : (
          <button className="primary" disabled={busy}>
            {busy ? "Saving…" : submitLabel}
          </button>
        )}
      </div>
    </div>
  );
}
