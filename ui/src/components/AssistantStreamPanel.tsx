type Props = {
  textStream: string;
  finalText: string;
};

export function AssistantStreamPanel({ textStream, finalText }: Props) {
  return (
    <section className="panel wide-panel">
      <div className="panel-header">
        <h2>Assistant Stream</h2>
        <span>Live deltas and final text</span>
      </div>
      <div className="stream-columns">
        <div>
          <label>Live Stream</label>
          <pre className="stream-box">{textStream || "Waiting for assistant.text.delta..."}</pre>
        </div>
        <div>
          <label>Final Text</label>
          <pre className="stream-box final">{finalText || "Waiting for assistant.text.done..."}</pre>
        </div>
      </div>
    </section>
  );
}