/**
 * The bot face styles. Eye motion composes `--fr-gaze-x`/`--fr-gaze-y`, so
 * later expressions (winks, looking around) are keyframe additions, not
 * structural changes.
 */
export const FACE_CSS = `
.fr-face {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  width: 36px;
  height: 24px;
  border-radius: 12px;
  background: var(--face-background);
}
.fr-eye {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--face-foreground);
  transform: translate(var(--fr-gaze-x, 0), var(--fr-gaze-y, 0));
}
@media (prefers-reduced-motion: no-preference) {
  .fr-eye {
    animation: fr-blink 6s infinite;
  }
  @keyframes fr-blink {
    0%, 94%, 100% {
      transform: translate(var(--fr-gaze-x, 0), var(--fr-gaze-y, 0)) scaleY(1);
    }
    97% {
      transform: translate(var(--fr-gaze-x, 0), var(--fr-gaze-y, 0)) scaleY(0.15);
    }
  }
}
`;
