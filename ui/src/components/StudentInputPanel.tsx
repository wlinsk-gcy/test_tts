type Props = {
  value: string;
  disabled: boolean;
  onChange: (value: string) => void;
  onSubmit: () => void;
};

export function StudentInputPanel({ value, disabled, onChange, onSubmit }: Props) {
  return (
    <section className="panel">
      <div className="panel-header">
        <h2>Student Turn</h2>
        <span>Simulated ASR final text</span>
      </div>
      <textarea
        className="student-input"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder="Type the final ASR text here..."
      />
      <button className="primary-button" type="button" disabled={disabled || !value.trim()} onClick={onSubmit}>
        Submit Next Turn
      </button>
    </section>
  );
}