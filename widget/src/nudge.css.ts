// where the growing composer stops and scrolls instead, about five lines
export const COMPOSER_MAX_HEIGHT_PX = 120;

// how long the expand morph runs, matched by the timer that unhooks the transition after it
export const MORPH_MS = 300;

export const NUDGE_CSS = `
:host {
  all: initial;

  --background: #fff;
  --accent-background: #3b3bd1;
  --user-message-background: #ecebff;
  --agent-message-background: #f4f4f8;
  --face-background: #1a1a2e;
  --face-foreground: #fff;
  --notify-background: #ef4444;
  --send-background: #8a8a9a;
  --send-ready-background: #4a4a5a;
  --foreground: #1a1a2e;
  --accent-foreground: #fff;
  --muted-foreground: #8a8a9a;
  --link-foreground: #3b3bd1;
  --border: 1px solid #e2e2ea;
  --box-shadow: 0 8px 24px rgba(20, 20, 40, 0.16);
  --outline: 1px auto var(--accent-background);
  --easing: cubic-bezier(0.4, 0, 0.2, 1);
  --interactive-filter: brightness(95%);
  --font-family: system-ui, "Helvetica Neue", Arial, sans-serif;
  --font-size: 14px;
  --z-index: 2147483647;
}
@media (prefers-color-scheme: dark) {
  :host {
    color-scheme: only dark;

    --background: #23232f;
    --user-message-background: #35355c;
    --agent-message-background: #2e2e3c;
    --face-background: #fff;
    --face-foreground: #16161d;
    --send-background: #4a4a5a;
    --send-ready-background: #6b6b7c;
    --foreground: #e9e9f2;
    --muted-foreground: #a2a2b5;
    --link-foreground: #a5a5ff;
    --border: 1px solid #3a3a4a;
    --box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4);
    --interactive-filter: brightness(130%);
  }
}
.fr-root {
  position: fixed;
  right: 16px;
  bottom: 16px;
  z-index: var(--z-index);
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 12px;
  font-family: var(--font-family);
  font-size: var(--font-size);
  line-height: 1.45;
  color: var(--foreground);
}
.fr-shell {
  position: relative;
  box-sizing: border-box;
  width: 64px;
  height: 64px;
  overflow: hidden;
  container-type: size;
  background: var(--background);
  border: var(--border);
  border-radius: 24px;
  box-shadow: var(--box-shadow);
}
.fr-expanded {
  width: min(360px, calc(100vw - 32px));
  height: min(520px, calc(100dvh - 32px));
}
.fr-launcher {
  position: absolute;
  bottom: 19px;
  right: 13px;
  display: block;
  padding: 0;
  border: 0;
  border-radius: 12px;
  background: none;
  cursor: pointer;
}
.fr-launcher::after {
  content: "";
  position: absolute;
  inset: -19px -13px;
}
.fr-expanded .fr-launcher::after {
  content: none;
}
.fr-dot {
  position: absolute;
  top: 10px;
  right: 10px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--notify-background);
  transform: scale(0);
}
.fr-unread .fr-dot {
  transform: scale(1);
}
.fr-panel {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px 16px 12px;
  opacity: 0;
  visibility: hidden;
}
.fr-header {
  margin: -16px -16px 0;
  padding: 16px 16px 12px;
  background: var(--agent-message-background);
  border-bottom: var(--border);
  font-size: 15px;
  font-weight: 600;
}
@container (max-height: 200px) {
  .fr-panel {
    gap: 4px;
  }
}
.fr-expanded .fr-panel {
  opacity: 1;
  visibility: visible;
}
.fr-bubble {
  box-sizing: border-box;
  display: flex;
  align-items: flex-start;
  gap: 8px;
  max-width: 300px;
  padding: 12px 14px;
  background: var(--background);
  border: var(--border);
  border-radius: 16px;
  box-shadow: var(--box-shadow);
}
.fr-bubble-text {
  flex: 1;
  padding: 0;
  border: 0;
  background: none;
  font: inherit;
  color: inherit;
  text-align: left;
  cursor: pointer;
  white-space: pre-wrap;
  overflow-wrap: break-word;
}
.fr-card {
  box-sizing: border-box;
  width: 100%;
  background: var(--background);
  border: var(--border);
  border-radius: 12px;
  box-shadow: var(--box-shadow);
  padding: 12px 14px;
}
.fr-text {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: break-word;
}
.fr-btn {
  font: inherit;
  color: inherit;
  border: var(--border);
  border-radius: 8px;
  background: var(--background);
  padding: 5px 10px;
  cursor: pointer;
}
.fr-btn:hover:not(:disabled) {
  filter: var(--interactive-filter);
}
.fr-btn:disabled {
  opacity: 0.5;
  cursor: default;
}
.fr-btn:focus-visible,
.fr-bubble-text:focus-visible,
.fr-close:focus-visible,
.fr-launcher:focus-visible,
.fr-send:focus-visible {
  outline: var(--outline);
}
.fr-btn-primary {
  background: var(--accent-background);
  border-color: var(--accent-background);
  color: var(--accent-foreground);
}
.fr-close {
  border: 0;
  background: none;
  padding: 0 2px;
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
  color: var(--muted-foreground);
}
.fr-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 10px;
}
.fr-messages {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 8px;
  overflow-y: auto;
  padding-right: 8px;
  scrollbar-width: thin;
}
.fr-message {
  border-radius: 12px;
  padding: 8px 12px;
  white-space: pre-wrap;
  overflow-wrap: break-word;
}
.fr-message-user {
  align-self: flex-end;
  background: var(--user-message-background);
}
.fr-message-agent {
  align-self: flex-start;
  background: var(--agent-message-background);
}
.fr-citations {
  margin: 0;
  padding-left: 18px;
  font-size: 12px;
}
.fr-citations a {
  color: var(--link-foreground);
}
.fr-composer {
  display: flex;
  align-items: flex-end;
  order: 1;
  margin-right: 41px;
  background: var(--agent-message-background);
  border-radius: 19px;
}
.fr-input {
  flex: 1;
  box-sizing: border-box;
  font: inherit;
  line-height: 20px;
  color: inherit;
  background: none;
  border: 0;
  outline: none;
  padding: 9px 8px 9px 14px;
  min-height: 38px;
  max-height: ${COMPOSER_MAX_HEIGHT_PX}px;
  resize: none;
  overflow-y: hidden;
  scrollbar-width: thin;
}
.fr-input::placeholder {
  color: var(--muted-foreground);
}
.fr-send {
  flex: none;
  align-self: flex-end;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  margin: 5px 5px 5px 0;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: var(--send-background);
  color: var(--accent-foreground);
  cursor: pointer;
}
.fr-input:not(:placeholder-shown) + .fr-send {
  background: var(--send-ready-background);
}
.fr-send:hover {
  filter: var(--interactive-filter);
}
.fr-send svg {
  width: 14px;
  height: 14px;
  fill: none;
  stroke: currentColor;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
}
@media (prefers-reduced-motion: no-preference) {
  .fr-morph {
    transition: width 250ms var(--easing), height 250ms var(--easing);
  }
  .fr-morph.fr-expanded {
    transition-duration: ${MORPH_MS}ms;
  }
  .fr-panel {
    transition: opacity 150ms, visibility 0s 150ms;
  }
  .fr-expanded .fr-panel {
    transition: opacity 200ms 100ms, visibility 0s;
  }
  .fr-dot {
    transition: transform 150ms var(--easing);
  }
  .fr-send {
    transition: background 150ms;
  }
  .fr-bubble {
    animation: fr-rise 200ms var(--easing);
  }
  @keyframes fr-rise {
    from {
      opacity: 0;
      transform: translateY(4px);
    }
  }
}
`;
