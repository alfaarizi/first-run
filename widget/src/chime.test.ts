// @vitest-environment jsdom
import { afterEach, expect, test, vi } from "vitest";

import { createChime } from "./chime";

function fakeContext(state: AudioContextState) {
  const gain = {
    gain: { setValueAtTime: vi.fn(), exponentialRampToValueAtTime: vi.fn() },
    connect: vi.fn(),
  };
  const oscillator = {
    frequency: { setValueAtTime: vi.fn() },
    connect: vi.fn(() => gain),
    start: vi.fn(),
    stop: vi.fn(),
  };
  return {
    state,
    currentTime: 0,
    destination: {},
    createOscillator: vi.fn(() => oscillator),
    createGain: vi.fn(() => gain),
    oscillator,
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

// `new`-able, unlike an arrow mock, so the chime's construction succeeds
function stubAudioContext(context: ReturnType<typeof fakeContext>) {
  const AudioContext = vi.fn(function () {
    return context;
  });
  vi.stubGlobal("AudioContext", AudioContext);
  return AudioContext;
}

test("stays silent before the first user gesture", () => {
  const AudioContext = stubAudioContext(fakeContext("running"));

  createChime().nudge();

  expect(AudioContext).not.toHaveBeenCalled();
});

test("primes on the first gesture and plays a rising two-tone nudge chime", () => {
  const context = fakeContext("running");
  stubAudioContext(context);
  const chime = createChime();

  dispatchEvent(new Event("pointerdown"));
  chime.nudge();

  expect(context.oscillator.frequency.setValueAtTime).toHaveBeenCalledTimes(2);
  expect(context.oscillator.start).toHaveBeenCalled();
  expect(context.oscillator.stop).toHaveBeenCalled();
});

test("plays a send tick over the same primed context", () => {
  const context = fakeContext("running");
  stubAudioContext(context);
  const chime = createChime();

  dispatchEvent(new Event("pointerdown"));
  chime.send();

  expect(context.createOscillator).toHaveBeenCalledTimes(1);
  expect(context.oscillator.start).toHaveBeenCalled();
});

test("skips playback while the autoplay policy keeps the context suspended", () => {
  const context = fakeContext("suspended");
  stubAudioContext(context);
  const chime = createChime();

  dispatchEvent(new Event("pointerdown"));
  chime.nudge();
  chime.send();

  expect(context.createOscillator).not.toHaveBeenCalled();
});

test("creates one context however many gestures follow", () => {
  const AudioContext = stubAudioContext(fakeContext("running"));
  createChime();

  dispatchEvent(new Event("pointerdown"));
  dispatchEvent(new Event("keydown"));
  dispatchEvent(new Event("pointerdown"));

  expect(AudioContext).toHaveBeenCalledTimes(1);
});
