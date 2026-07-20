// A5 to D6, a rising fourth: short and quiet enough to read as a tap.
const NUDGE_NOTES_HZ = [880, 1174.7] as const;
const NUDGE_DURATION_S = 0.18;
const NUDGE_PEAK_GAIN = 0.08;

// D6 to A5, the chime's fourth falling: an outbound send, quieter still.
const SEND_NOTES_HZ = [1174.7, 880] as const;
const SEND_DURATION_S = 0.1;
const SEND_PEAK_GAIN = 0.05;

// An exponential ramp never reaches zero, so the tail decays to inaudible.
const FLOOR_GAIN = 0.001;

/** The widget's two sounds: an inbound nudge chime and an outbound send tick. */
export interface Chime {
  nudge(): void;
  send(): void;
}

/**
 * Builds the widget's sounds over one shared AudioContext. The context is
 * created on the page's first user gesture, the only moment the autoplay
 * policy guarantees it runs, and playback stays silent whenever the policy kept
 * it suspended.
 */
export function createChime(): Chime {
  let context: AudioContext | undefined;

  const primeAudioContext = () => {
    try {
      context = new AudioContext();
    } catch {
      // No Web Audio, so the sounds stay silent.
    }
    removeEventListener("pointerdown", primeAudioContext);
    removeEventListener("keydown", primeAudioContext);
  };
  addEventListener("pointerdown", primeAudioContext);
  addEventListener("keydown", primeAudioContext);

  const playNotes = (notesHz: readonly [number, number], durationS: number, peakGain: number) => {
    if (context?.state !== "running") return;

    const start = context.currentTime;
    const oscillator = context.createOscillator();
    const gain = context.createGain();

    oscillator.frequency.setValueAtTime(notesHz[0], start);
    oscillator.frequency.setValueAtTime(notesHz[1], start + durationS / 2);

    gain.gain.setValueAtTime(peakGain, start);
    gain.gain.exponentialRampToValueAtTime(FLOOR_GAIN, start + durationS);

    oscillator.connect(gain).connect(context.destination);
    oscillator.start(start);
    oscillator.stop(start + durationS);
  };

  return {
    nudge: () => playNotes(NUDGE_NOTES_HZ, NUDGE_DURATION_S, NUDGE_PEAK_GAIN),
    send: () => playNotes(SEND_NOTES_HZ, SEND_DURATION_S, SEND_PEAK_GAIN),
  };
}
