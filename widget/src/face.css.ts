/**
 * The bot face styles: dot eyes that alternate a blink and a happy squint at
 * rest, and glance around while the panel is open. The squint is a
 * face-colored lid (the eye's `::after`) rising over the dot, which leaves a
 * crescent. Eye motion composes `--fr-gaze-x`/`--fr-gaze-y`, so new
 * expressions are keyframe additions, not structural changes.
 */
export const FACE_CSS = `
.fr-face {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  width: 36px;
  height: 24px;
  border-radius: 12px 12px 2px 12px;
  background: var(--face-background);
}
.fr-face::after {
  content: "";
  position: absolute;
  right: 0;
  bottom: -4px;
  width: 5px;
  height: 5px;
  background: inherit;
  clip-path: polygon(0 0, 100% 0, 100% 100%);
}
.fr-eye {
  position: relative;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--face-foreground);
  transform: translate(var(--fr-gaze-x, 0), var(--fr-gaze-y, 0));
}
.fr-eye::after {
  content: "";
  position: absolute;
  inset: 0;
  border-radius: 50%;
  background: var(--face-background);
  transform: translateY(100%);
}
@media (prefers-reduced-motion: no-preference) {
  .fr-expanded .fr-face {
    animation: fr-glance 26s infinite;
  }
  .fr-eye {
    animation: fr-blink 24s infinite;
  }
  .fr-eye::after {
    animation: fr-squint 24s infinite;
  }
  @keyframes fr-blink {
    0%, 22.25%, 23.75%, 46.25%, 47.75%, 70.25%, 71.75%, 100% {
      transform: translate(var(--fr-gaze-x, 0), var(--fr-gaze-y, 0)) scaleY(1);
    }
    23%, 47%, 71% {
      transform: translate(var(--fr-gaze-x, 0), var(--fr-gaze-y, 0)) scaleY(0.15);
    }
  }
  @keyframes fr-squint {
    0%, 93%, 99.5%, 100% {
      transform: translateY(100%);
    }
    94%, 98.75% {
      transform: translateY(40%);
    }
  }
  @keyframes fr-glance {
    0%, 78%, 92%, 100% {
      --fr-gaze-x: 0px;
      --fr-gaze-y: 0px;
    }
    79%, 84% {
      --fr-gaze-x: -2px;
      --fr-gaze-y: -2px;
    }
    85%, 91% {
      --fr-gaze-x: -2px;
      --fr-gaze-y: 2px;
    }
  }
}
`;
