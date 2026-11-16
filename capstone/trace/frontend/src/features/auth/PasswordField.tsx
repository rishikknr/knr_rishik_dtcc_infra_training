import { useId, useState } from "react";

interface PasswordFieldProps {
  label: string;
  name: string;
  autoComplete: "current-password" | "new-password";
  value?: string;
  onChange?: (value: string) => void;
  minimumLength?: number;
  describedBy?: string;
  invalid?: boolean;
}
export function PasswordField({
  label,
  name,
  autoComplete,
  value,
  onChange,
  minimumLength,
  describedBy,
  invalid,
}: PasswordFieldProps) {
  const id = useId();
  const [visible, setVisible] = useState(false);
  return (
    <div className="password-field">
      <label htmlFor={id}>{label}</label>
      <div className="password-control">
        <input
          id={id}
          name={name}
          type={visible ? "text" : "password"}
          autoComplete={autoComplete}
          required
          minLength={minimumLength}
          maxLength={72}
          value={value}
          onChange={onChange ? (e) => onChange(e.target.value) : undefined}
          aria-describedby={describedBy}
          aria-invalid={invalid || undefined}
        />
        <button
          type="button"
          className="password-toggle"
          aria-label={`${visible ? "Hide" : "Show"} ${label.toLowerCase()}`}
          aria-pressed={visible}
          onClick={() => setVisible((v) => !v)}
        >
          {visible ? "Hide" : "Show"}
        </button>
      </div>
    </div>
  );
}
