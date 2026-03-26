import type { TimelineEntry } from "../types";

type Props = {
  entries: TimelineEntry[];
};

export function EventTimeline({ entries }: Props) {
  return (
    <section className="panel wide-panel">
      <div className="panel-header">
        <h2>Timeline</h2>
        <span>{entries.length} events</span>
      </div>
      <div className="timeline-list">
        {entries.map((entry) => (
          <div className="timeline-item" key={entry.id}>
            <strong>{new Date(entry.at).toLocaleTimeString()}</strong>
            <span>{entry.label}</span>
            <small>{entry.detail ?? ""}</small>
          </div>
        ))}
      </div>
    </section>
  );
}