// A5 to D6, a rising fourth, short and quiet enough to read as a tap
const NOTES_HZ = [880, 1174.7] as const;
const DURATION_S = 0.18;
const PEAK_GAIN = 0.08;
// an exponential ramp never reaches zero, so the tail decays to inaudible
const FLOOR_GAIN = 0.001;

/**
 * Builds the notification chime. The AudioContext is created on the page's
 * first user gesture, the only moment the autoplay policy guarantees it
 * runs, and play stays silent whenever the policy kept it suspended.
 */
export function createChime(): () => void {
  let context: AudioContext | undefined;

  const prime = () => {
    try {
      context = new AudioContext();
    } catch {
      // no Web Audio, so the chime stays silent
    }
    removeEventListener("pointerdown", prime);
    removeEventListener("keydown", prime);
  };
  addEventListener("pointerdown", prime);
  addEventListener("keydown", prime);

  return () => {
    if (context?.state !== "running") return;

    const start = context.currentTime;
    const oscillator = context.createOscillator();
    const gain = context.createGain();
    oscillator.frequency.setValueAtTime(NOTES_HZ[0], start);
    oscillator.frequency.setValueAtTime(NOTES_HZ[1], start + DURATION_S / 2);
    gain.gain.setValueAtTime(PEAK_GAIN, start);
    gain.gain.exponentialRampToValueAtTime(FLOOR_GAIN, start + DURATION_S);
    oscillator.connect(gain).connect(context.destination);
    oscillator.start(start);
    oscillator.stop(start + DURATION_S);
  };
}
